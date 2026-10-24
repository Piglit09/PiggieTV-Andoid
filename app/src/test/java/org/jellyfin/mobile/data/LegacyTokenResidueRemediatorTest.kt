package org.jellyfin.mobile.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.jellyfin.mobile.data.dao.UserDao
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LegacyTokenResidueRemediatorTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `successful sanitation marks complete only after every database verification`() {
        val events = mutableListOf<String>()
        val fixture = fixture(events = events)

        fixture.remediator.sanitizeHistoricalResidue(null).shouldBeTrue()

        fixture.completionStore.complete.shouldBeTrue()
        fixture.completionStore.markCount shouldBe 1
        events.shouldContainExactly(
            "marker-read:false",
            "legacy-count",
            "database-open",
            "database-transaction-check",
            "journal-mode",
            "checkpoint",
            "database-transaction-check",
            "vacuum",
            "journal-mode",
            "checkpoint",
            "legacy-count",
            "integrity-check",
            "quick-check",
            "freelist-check",
            "marker-write",
            "marker-read:true",
        )
    }

    @Test
    fun `raw residue failure remains unmarked and a later invocation retries`() {
        val databaseFile = temporaryDirectory.resolve("jellyfin").toFile().apply {
            writeText("database-prefix-$KNOWN_TOKEN-database-suffix")
        }
        temporaryDirectory.resolve("jellyfin-wal").toFile().writeText("wal-without-credential")
        val fixture = fixture(databasePath = databaseFile.absolutePath)

        fixture.remediator.sanitizeHistoricalResidue(KNOWN_TOKEN).shouldBeFalse()

        fixture.completionStore.complete.shouldBeFalse()
        fixture.completionStore.markCount shouldBe 0
        databaseFile.writeText("sanitized-database")

        fixture.remediator.sanitizeHistoricalResidue(KNOWN_TOKEN).shouldBeTrue()

        fixture.completionStore.complete.shouldBeTrue()
        fixture.completionStore.markCount shouldBe 1
        verify(exactly = 4) { fixture.sqliteDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)") }
        verify(exactly = 2) { fixture.sqliteDatabase.execSQL("VACUUM") }
    }

    @Test
    fun `failed completion marker commit leaves successful maintenance retryable`() {
        val fixture = fixture()
        fixture.completionStore.failNextMark = true

        fixture.remediator.sanitizeHistoricalResidue(null).shouldBeFalse()

        fixture.completionStore.complete.shouldBeFalse()
        fixture.completionStore.markCount shouldBe 1

        fixture.remediator.sanitizeHistoricalResidue(null).shouldBeTrue()

        fixture.completionStore.complete.shouldBeTrue()
        fixture.completionStore.markCount shouldBe 2
        verify(exactly = 4) { fixture.sqliteDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)") }
        verify(exactly = 2) { fixture.sqliteDatabase.execSQL("VACUUM") }
    }

    @Test
    fun `logical legacy token or unknown presence prevents destructive maintenance`() {
        listOf(
            LegacyTokenPresence.PRESENT to { 1 },
            LegacyTokenPresence.UNKNOWN to { throw IllegalStateException("database unavailable") },
        ).forEach { (expectedPresence, countAnswer) ->
            val fixture = fixture(legacyCount = countAnswer)

            fixture.remediator.legacyTokenPresence() shouldBe expectedPresence
            fixture.remediator.sanitizeHistoricalResidue(KNOWN_TOKEN).shouldBeFalse()

            fixture.completionStore.complete.shouldBeFalse()
            fixture.completionStore.markCount shouldBe 0
            verify(exactly = 0) { fixture.database.openHelper }
            verify(exactly = 0) { fixture.sqliteDatabase.execSQL(any()) }
        }
    }

    @Test
    fun `fresh install marks a verified pristine database without vacuum and becomes a no op`() {
        val fixture = fixture()

        fixture.remediator.markFreshInstallPristine().shouldBeTrue()
        fixture.remediator.markFreshInstallPristine().shouldBeTrue()

        fixture.completionStore.complete.shouldBeTrue()
        fixture.completionStore.markCount shouldBe 1
        verify(exactly = 1) { fixture.userDao.countLegacyAccessTokens() }
        verify(exactly = 1) { fixture.database.openHelper }
        verify(exactly = 1) { fixture.sqliteDatabase.query("PRAGMA quick_check(1)") }
        verify(exactly = 0) { fixture.sqliteDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)") }
        verify(exactly = 0) { fixture.sqliteDatabase.execSQL("VACUUM") }
    }

    @Test
    fun `canonical rollback journal checkpoint is accepted only for safe confirmed modes`() {
        listOf("delete", "truncate").forEach { journalMode ->
            val fixture = fixture(
                journalMode = journalMode,
                checkpointResult = intArrayOf(0, -1, -1),
            )

            fixture.remediator.sanitizeHistoricalResidue(null).shouldBeTrue()

            fixture.completionStore.complete.shouldBeTrue()
            verify(exactly = 2) { fixture.sqliteDatabase.query("PRAGMA journal_mode") }
            verify(exactly = 2) { fixture.sqliteDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)") }
            verify(exactly = 1) { fixture.sqliteDatabase.execSQL("VACUUM") }
        }
    }

    @Test
    fun `checkpoint result must exactly match the confirmed journal mode`() {
        listOf(
            CheckpointCase(journalMode = "wal", result = intArrayOf(0, -1, -1)),
            CheckpointCase(journalMode = "truncate", result = intArrayOf(0, 0, 0)),
            CheckpointCase(journalMode = "delete", result = intArrayOf(1, -1, -1)),
            CheckpointCase(journalMode = "persist", result = intArrayOf(0, -1, -1)),
        ).forEach { checkpointCase ->
            val fixture = fixture(
                journalMode = checkpointCase.journalMode,
                checkpointResult = checkpointCase.result,
            )

            fixture.remediator.sanitizeHistoricalResidue(null).shouldBeFalse()

            fixture.completionStore.complete.shouldBeFalse()
            fixture.completionStore.markCount shouldBe 0
            verify(exactly = 0) { fixture.sqliteDatabase.execSQL("VACUUM") }
        }
    }

    private fun fixture(
        events: MutableList<String> = mutableListOf(),
        legacyCount: () -> Int = { 0 },
        databasePath: String = Files.createTempFile(temporaryDirectory, "jellyfin-empty-", ".db").toString(),
        journalMode: String = "wal",
        checkpointResult: IntArray = intArrayOf(0, 0, 0),
    ): Fixture {
        val database = mockk<JellyfinDatabase>()
        val userDao = mockk<UserDao>()
        val openHelper = mockk<SupportSQLiteOpenHelper>()
        val sqliteDatabase = mockk<SupportSQLiteDatabase>()
        val completionStore = RecordingCompletionStore(events)

        every { userDao.countLegacyAccessTokens() } answers {
            events += "legacy-count"
            legacyCount()
        }
        every { database.openHelper } answers {
            events += "database-open"
            openHelper
        }
        every { openHelper.writableDatabase } returns sqliteDatabase
        every { sqliteDatabase.isOpen } returns true
        every { sqliteDatabase.inTransaction() } answers {
            events += "database-transaction-check"
            false
        }
        every { sqliteDatabase.path } returns databasePath
        every { sqliteDatabase.execSQL("VACUUM") } answers {
            events += "vacuum"
        }
        every { sqliteDatabase.isDatabaseIntegrityOk } answers {
            events += "integrity-check"
            true
        }
        every { sqliteDatabase.query(any<String>()) } answers {
            when (val sql = firstArg<String>()) {
                "PRAGMA journal_mode" -> {
                    events += "journal-mode"
                    stringCursor(journalMode)
                }

                "PRAGMA wal_checkpoint(TRUNCATE)" -> {
                    events += "checkpoint"
                    integerCursor(checkpointResult)
                }

                "PRAGMA quick_check(1)" -> {
                    events += "quick-check"
                    stringCursor("ok")
                }

                "PRAGMA freelist_count" -> {
                    events += "freelist-check"
                    longCursor(0L)
                }

                else -> error("Unexpected query: $sql")
            }
        }

        return Fixture(
            database = database,
            userDao = userDao,
            sqliteDatabase = sqliteDatabase,
            completionStore = completionStore,
            remediator = LegacyTokenResidueRemediator(database, userDao, completionStore),
        )
    }

    private fun integerCursor(values: IntArray): Cursor = mockk {
        every { moveToFirst() } returns true
        every { this@mockk.columnCount } returns values.size
        every { getInt(any()) } answers { values[firstArg()] }
        every { close() } just runs
    }

    private fun longCursor(value: Long): Cursor = mockk {
        every { moveToFirst() } returns true
        every { getLong(0) } returns value
        every { close() } just runs
    }

    private fun stringCursor(value: String): Cursor = mockk {
        every { moveToFirst() } returns true
        every { getString(0) } returns value
        every { close() } just runs
    }

    private data class Fixture(
        val database: JellyfinDatabase,
        val userDao: UserDao,
        val sqliteDatabase: SupportSQLiteDatabase,
        val completionStore: RecordingCompletionStore,
        val remediator: LegacyTokenResidueRemediator,
    )

    private data class CheckpointCase(val journalMode: String, val result: IntArray)

    private class RecordingCompletionStore(private val events: MutableList<String>) :
        LegacyTokenResidueCompletionStore {
        var complete = false
        var markCount = 0
        var failNextMark = false

        override fun isComplete(): Boolean {
            events += "marker-read:$complete"
            return complete
        }

        override fun markComplete(): Boolean {
            events += "marker-write"
            markCount += 1
            if (failNextMark) {
                failNextMark = false
                return false
            }
            complete = true
            return true
        }
    }

    private companion object {
        const val KNOWN_TOKEN = "PTV_SQLITE_TEST_TOKEN_DO_NOT_PERSIST_57214"
    }
}
