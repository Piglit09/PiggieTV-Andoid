package org.jellyfin.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.UserEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class LegacyTokenResidueRemediatorInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var databaseName: String
    private lateinit var database: JellyfinDatabase
    private lateinit var completionStore: RecordingCompletionStore
    private var journalMode = RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING

    @Before
    fun setUp() {
        databaseName = "$DATABASE_PREFIX-${UUID.randomUUID()}.db"
        assertTrue(context.deleteDatabase(databaseName) || !context.getDatabasePath(databaseName).exists())
        journalMode = RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING
        database = openDatabase()
        completionStore = RecordingCompletionStore()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized && database.isOpen) database.close()
        if (::databaseName.isInitialized) context.deleteDatabase(databaseName)
    }

    @Test
    fun directLegacyClearSanitizesEverySqliteArtifactAndPreservesUnrelatedRows() {
        val canary = newCanary()
        val fixture = seedLegacyDatabase(canary)
        val remediator = remediator()

        assertEquals(LegacyTokenPresence.PRESENT, remediator.legacyTokenPresence())
        assertTrue(remediator.clearLogicalTokensAfterSecureVerification())
        assertEquals(LegacyTokenPresence.ABSENT, remediator.legacyTokenPresence())
        assertRawCanaryPresent(canary)

        assertTrue(remediator.sanitizeHistoricalResidue(canary))

        assertSanitized(remediator, canary, fixture)
        assertOneTimeAcrossReopen(remediator, canary, fixture)
    }

    @Test
    fun alreadyMigratedDatabaseReceivesHistoricalResidueSanitation() {
        val canary = newCanary()
        val fixture = seedLegacyDatabase(canary)

        assertTrue(database.userDao.clearAllLegacyAccessTokensVerified())
        assertEquals(0, database.userDao.countLegacyAccessTokens())
        assertRawCanaryPresent(canary)

        val remediator = remediator()
        assertFalse(remediator.isComplete())
        assertEquals(LegacyTokenPresence.ABSENT, remediator.legacyTokenPresence())
        assertTrue(remediator.sanitizeHistoricalResidue(canary))

        assertSanitized(remediator, canary, fixture)
        assertOneTimeAcrossReopen(remediator, canary, fixture)
    }

    @Test
    fun truncateJournalModeCompletesVerifiedMaintenanceAndPreservesUnrelatedRows() {
        database.close()
        assertTrue(context.deleteDatabase(databaseName) || !context.getDatabasePath(databaseName).exists())
        journalMode = RoomDatabase.JournalMode.TRUNCATE
        database = openDatabase()

        assertEquals("truncate", scalarString(database.openHelper.writableDatabase, "PRAGMA journal_mode"))
        val canary = newCanary()
        val fixture = seedLegacyDatabase(canary)
        val remediator = remediator()

        assertTrue(remediator.clearLogicalTokensAfterSecureVerification())
        assertTrue(remediator.sanitizeHistoricalResidue(canary))

        assertSanitized(remediator, canary, fixture)
        assertOneTimeAcrossReopen(remediator, canary, fixture)
    }

    private fun seedLegacyDatabase(canary: String): DatabaseFixture {
        val primaryServer = ServerEntity(
            id = 0,
            hostname = PRIMARY_SERVER_HOSTNAME,
            lastUsedTimestamp = PRIMARY_SERVER_TIMESTAMP,
        )
        val unrelatedServer = ServerEntity(
            id = 0,
            hostname = UNRELATED_SERVER_HOSTNAME,
            lastUsedTimestamp = UNRELATED_SERVER_TIMESTAMP,
        )
        val primaryServerId = database.serverDao.insert(primaryServer)
        val unrelatedServerId = database.serverDao.insert(unrelatedServer)
        val primaryUserUuid = UUID.randomUUID()
        val unrelatedUserUuid = UUID.randomUUID()
        val primaryUserId = database.userDao.insert(
            UserEntity(
                id = 0,
                serverId = primaryServerId,
                userId = primaryUserUuid,
                legacyAccessToken = canary,
                lastLoginTimestamp = PRIMARY_USER_TIMESTAMP,
            ),
        )
        val unrelatedUserId = database.userDao.insert(
            UserEntity(
                id = 0,
                serverId = unrelatedServerId,
                userId = unrelatedUserUuid,
                legacyAccessToken = null,
                lastLoginTimestamp = UNRELATED_USER_TIMESTAMP,
            ),
        )

        disableSecureDelete()
        assertCheckpointSucceeded(database.openHelper.writableDatabase)
        assertRawCanaryPresent(canary)

        return DatabaseFixture(
            primaryServerId = primaryServerId,
            unrelatedServerId = unrelatedServerId,
            primaryUserId = primaryUserId,
            unrelatedUserId = unrelatedUserId,
            primaryUserUuid = primaryUserUuid,
            unrelatedUserUuid = unrelatedUserUuid,
        )
    }

    private fun assertSanitized(
        remediator: LegacyTokenResidueRemediator,
        canary: String,
        fixture: DatabaseFixture,
    ) {
        assertTrue(remediator.isComplete())
        assertEquals(1, completionStore.markCompleteCalls)
        assertEquals(0, database.userDao.countLegacyAccessTokens())
        assertDatabaseHealthy()
        assertFixturePreserved(fixture)
        assertRawCanaryAbsent(canary)
    }

    private fun assertOneTimeAcrossReopen(
        remediator: LegacyTokenResidueRemediator,
        canary: String,
        fixture: DatabaseFixture,
    ) {
        assertTrue(remediator.sanitizeHistoricalResidue(canary))
        assertEquals(1, completionStore.markCompleteCalls)

        database.close()
        assertRawCanaryAbsent(canary)
        database = openDatabase()

        val restarted = remediator()
        assertTrue(restarted.isComplete())
        assertTrue(restarted.sanitizeHistoricalResidue(canary))
        assertEquals(1, completionStore.markCompleteCalls)
        assertDatabaseHealthy()
        assertFixturePreserved(fixture)
        assertRawCanaryAbsent(canary)
    }

    private fun assertDatabaseHealthy() {
        val sqliteDatabase = database.openHelper.writableDatabase
        assertEquals(EXPECTED_SCHEMA_VERSION, sqliteDatabase.version)
        assertEquals("ok", scalarString(sqliteDatabase, "PRAGMA integrity_check"))
        assertEquals("ok", scalarString(sqliteDatabase, "PRAGMA quick_check(1)"))
        assertEquals(0L, scalarLong(sqliteDatabase, "PRAGMA freelist_count"))
    }

    private fun assertFixturePreserved(fixture: DatabaseFixture) {
        val primaryServer = database.serverDao.getServer(fixture.primaryServerId)
        assertNotNull(primaryServer)
        assertEquals(PRIMARY_SERVER_HOSTNAME, primaryServer?.hostname)
        assertEquals(PRIMARY_SERVER_TIMESTAMP, primaryServer?.lastUsedTimestamp)

        val unrelatedServer = database.serverDao.getServer(fixture.unrelatedServerId)
        assertNotNull(unrelatedServer)
        assertEquals(UNRELATED_SERVER_HOSTNAME, unrelatedServer?.hostname)
        assertEquals(UNRELATED_SERVER_TIMESTAMP, unrelatedServer?.lastUsedTimestamp)

        val primaryUser = database.userDao.getByUserId(fixture.primaryServerId, fixture.primaryUserUuid)
        assertNotNull(primaryUser)
        assertEquals(fixture.primaryUserId, primaryUser?.id)
        assertEquals(PRIMARY_USER_TIMESTAMP, primaryUser?.lastLoginTimestamp)
        assertNull(primaryUser?.legacyAccessToken)

        val unrelatedUser = database.userDao.getByUserId(fixture.unrelatedServerId, fixture.unrelatedUserUuid)
        assertNotNull(unrelatedUser)
        assertEquals(fixture.unrelatedUserId, unrelatedUser?.id)
        assertEquals(UNRELATED_USER_TIMESTAMP, unrelatedUser?.lastLoginTimestamp)
        assertNull(unrelatedUser?.legacyAccessToken)
    }

    private fun assertRawCanaryPresent(canary: String) {
        val canaryBytes = canary.toByteArray(Charsets.UTF_8)
        try {
            assertTrue(
                "The test fixture did not reproduce recoverable SQLite residue.",
                sqliteArtifacts().any { artifact -> artifact.contains(canaryBytes) },
            )
        } finally {
            canaryBytes.fill(0)
        }
    }

    private fun assertRawCanaryAbsent(canary: String) {
        val canaryBytes = canary.toByteArray(Charsets.UTF_8)
        try {
            sqliteArtifacts().forEach { artifact ->
                assertFalse(
                    "A SQLite persistence artifact retained the legacy credential canary.",
                    artifact.contains(canaryBytes),
                )
            }
        } finally {
            canaryBytes.fill(0)
        }
    }

    private fun sqliteArtifacts(): List<File> {
        val databaseFile = context.getDatabasePath(databaseName)
        return databaseFile.parentFile
            ?.listFiles()
            ?.filter { artifact -> artifact.isFile && artifact.name.startsWith(databaseFile.name) }
            .orEmpty()
    }

    private fun File.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty() || length() < needle.size) return false
        val bytes = readBytes()
        return try {
            bytes.contains(needle)
        } finally {
            bytes.fill(0)
        }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        for (start in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun disableSecureDelete() {
        val sqliteDatabase = database.openHelper.writableDatabase
        sqliteDatabase.query("PRAGMA secure_delete=OFF").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }
    }

    private fun assertCheckpointSucceeded(sqliteDatabase: SupportSQLiteDatabase) {
        sqliteDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    private fun scalarString(sqliteDatabase: SupportSQLiteDatabase, query: String): String =
        sqliteDatabase.query(query).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun scalarLong(sqliteDatabase: SupportSQLiteDatabase, query: String): Long =
        sqliteDatabase.query(query).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun remediator(): LegacyTokenResidueRemediator = LegacyTokenResidueRemediator(
        database = database,
        userDao = database.userDao,
        completionStore = completionStore,
    )

    private fun openDatabase(): JellyfinDatabase =
        Room.databaseBuilder(
            context,
            JellyfinDatabase::class.java,
            databaseName,
        ).setJournalMode(journalMode)
            .build()

    private fun newCanary(): String = "PTV_SQLITE_RESIDUE_${UUID.randomUUID()}_${UUID.randomUUID()}"

    private data class DatabaseFixture(
        val primaryServerId: Long,
        val unrelatedServerId: Long,
        val primaryUserId: Long,
        val unrelatedUserId: Long,
        val primaryUserUuid: UUID,
        val unrelatedUserUuid: UUID,
    )

    private class RecordingCompletionStore : LegacyTokenResidueCompletionStore {
        var markCompleteCalls: Int = 0
            private set
        private var complete: Boolean = false

        override fun isComplete(): Boolean = complete

        override fun markComplete(): Boolean {
            markCompleteCalls += 1
            complete = true
            return true
        }
    }

    private companion object {
        const val DATABASE_PREFIX = "${BuildConfig.APPLICATION_ID}.instrumentation.sqlite_residue"
        const val EXPECTED_SCHEMA_VERSION = 5
        const val PRIMARY_SERVER_HOSTNAME = "https://primary.instrumentation.invalid"
        const val UNRELATED_SERVER_HOSTNAME = "https://unrelated.instrumentation.invalid"
        const val PRIMARY_SERVER_TIMESTAMP = 1_700_000_000_001L
        const val UNRELATED_SERVER_TIMESTAMP = 1_700_000_000_002L
        const val PRIMARY_USER_TIMESTAMP = 1_700_000_000_003L
        const val UNRELATED_USER_TIMESTAMP = 1_700_000_000_004L
    }
}
