package org.jellyfin.mobile.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import org.jellyfin.mobile.data.dao.UserDao
import java.io.File
import java.io.FileInputStream

internal enum class LegacyTokenPresence {
    PRESENT,
    ABSENT,
    UNKNOWN,
}

/**
 * One-time physical cleanup for credentials formerly stored in Room's plaintext user column.
 *
 * The caller owns the security ordering: a legacy credential must already be durably persisted
 * and verified in secure storage before [clearLogicalTokensAfterSecureVerification] is called.
 */
internal interface LegacyTokenResidueRemediation {
    fun isComplete(): Boolean

    fun legacyTokenPresence(): LegacyTokenPresence

    fun clearLogicalTokensAfterSecureVerification(): Boolean

    fun sanitizeHistoricalResidue(knownLegacyToken: String?): Boolean

    fun markFreshInstallPristine(): Boolean
}

internal interface LegacyTokenResidueCompletionStore {
    fun isComplete(): Boolean

    fun markComplete(): Boolean
}

internal class SharedPreferencesLegacyTokenResidueCompletionStore(context: Context) :
    LegacyTokenResidueCompletionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isComplete(): Boolean = runCatching {
        preferences.getBoolean(KEY_COMPLETED, false)
    }.getOrDefault(false)

    override fun markComplete(): Boolean = runCatching {
        preferences.edit()
            .putBoolean(KEY_COMPLETED, true)
            .commit() && isComplete()
    }.getOrDefault(false)

    private companion object {
        // This dedicated preference file is deliberately outside the exact backup allowlist.
        const val PREFERENCES_NAME = "piggietv_legacy_token_residue_remediation"
        const val KEY_COMPLETED = "sqlite_legacy_token_residue_v1_complete"
    }
}

@Suppress("TooGenericExceptionCaught")
internal class LegacyTokenResidueRemediator internal constructor(
    private val database: JellyfinDatabase,
    private val userDao: UserDao,
    private val completionStore: LegacyTokenResidueCompletionStore,
) : LegacyTokenResidueRemediation {
    constructor(
        context: Context,
        database: JellyfinDatabase,
        userDao: UserDao,
    ) : this(
        database = database,
        userDao = userDao,
        completionStore = SharedPreferencesLegacyTokenResidueCompletionStore(context),
    )

    private val maintenanceLock = Any()

    override fun isComplete(): Boolean = completionStore.isComplete()

    override fun legacyTokenPresence(): LegacyTokenPresence = try {
        if (userDao.countLegacyAccessTokens() == 0) {
            LegacyTokenPresence.ABSENT
        } else {
            LegacyTokenPresence.PRESENT
        }
    } catch (_: Exception) {
        LegacyTokenPresence.UNKNOWN
    }

    override fun clearLogicalTokensAfterSecureVerification(): Boolean = runCatching {
        userDao.clearAllLegacyAccessTokensVerified()
    }.getOrDefault(false)

    /**
     * Rebuilds the database only after the logical credential has been removed. A failed or
     * interrupted pass leaves the completion marker absent, making the next restore retry safely.
     */
    @Suppress("CyclomaticComplexMethod")
    override fun sanitizeHistoricalResidue(knownLegacyToken: String?): Boolean = synchronized(maintenanceLock) {
        if (isComplete()) return@synchronized true
        if (legacyTokenPresence() != LegacyTokenPresence.ABSENT) return@synchronized false

        val sqliteDatabase = runCatching { database.openHelper.writableDatabase }.getOrNull()
            ?: return@synchronized false
        if (!sqliteDatabase.isOpen || sqliteDatabase.inTransaction()) return@synchronized false

        val sanitized = runCatching {
            checkpointAndTruncate(sqliteDatabase) &&
                vacuum(sqliteDatabase) &&
                checkpointAndTruncate(sqliteDatabase) &&
                legacyTokenPresence() == LegacyTokenPresence.ABSENT &&
                sqliteDatabase.isDatabaseIntegrityOk &&
                quickCheckIsOk(sqliteDatabase) &&
                freelistIsEmpty(sqliteDatabase) &&
                rawTokenIsAbsent(sqliteDatabase, knownLegacyToken)
        }.getOrDefault(false)

        sanitized && completionStore.markComplete() && isComplete()
    }

    /**
     * A verified fresh install with no logical legacy token has no historical database to rebuild.
     * The caller must establish fresh-install origin before using this no-VACUUM path.
     */
    override fun markFreshInstallPristine(): Boolean = synchronized(maintenanceLock) {
        if (isComplete()) return@synchronized true
        if (legacyTokenPresence() != LegacyTokenPresence.ABSENT) return@synchronized false

        val sqliteDatabase = runCatching { database.openHelper.writableDatabase }.getOrNull()
            ?: return@synchronized false
        val pristine = sqliteDatabase.isOpen &&
            !sqliteDatabase.inTransaction() &&
            runCatching {
                sqliteDatabase.isDatabaseIntegrityOk && quickCheckIsOk(sqliteDatabase)
            }.getOrDefault(false)

        pristine && completionStore.markComplete() && isComplete()
    }

    private fun checkpointAndTruncate(sqliteDatabase: SupportSQLiteDatabase): Boolean {
        val journalMode = sqliteDatabase.query("PRAGMA journal_mode").use { cursor ->
            cursor.takeIf { it.moveToFirst() }
                ?.getString(0)
                ?.trim()
                ?.lowercase()
        } ?: return false

        return sqliteDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            if (!cursor.moveToFirst() || cursor.columnCount < CHECKPOINT_RESULT_COLUMNS) return@use false

            val busy = cursor.getInt(CHECKPOINT_BUSY_COLUMN)
            val logFrames = cursor.getInt(CHECKPOINT_LOG_COLUMN)
            val checkpointedFrames = cursor.getInt(CHECKPOINTED_COLUMN)
            when (journalMode) {
                JOURNAL_MODE_WAL ->
                    busy == CHECKPOINT_NOT_BUSY &&
                        logFrames == CHECKPOINT_EMPTY_WAL &&
                        checkpointedFrames == CHECKPOINT_EMPTY_WAL

                in SAFE_ROLLBACK_JOURNAL_MODES ->
                    busy == CHECKPOINT_NOT_BUSY &&
                        logFrames == CHECKPOINT_NOT_APPLICABLE &&
                        checkpointedFrames == CHECKPOINT_NOT_APPLICABLE

                else -> false
            }
        }
    }

    private fun vacuum(sqliteDatabase: SupportSQLiteDatabase): Boolean {
        if (sqliteDatabase.inTransaction()) return false
        sqliteDatabase.execSQL("VACUUM")
        return true
    }

    private fun quickCheckIsOk(sqliteDatabase: SupportSQLiteDatabase): Boolean =
        sqliteDatabase.query("PRAGMA quick_check(1)").use { cursor ->
            cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
        }

    private fun freelistIsEmpty(sqliteDatabase: SupportSQLiteDatabase): Boolean =
        sqliteDatabase.query("PRAGMA freelist_count").use { cursor ->
            cursor.moveToFirst() && cursor.getLong(0) == 0L
        }

    private fun rawTokenIsAbsent(sqliteDatabase: SupportSQLiteDatabase, knownLegacyToken: String?): Boolean {
        val tokenBytes = knownLegacyToken
            ?.takeIf(String::isNotBlank)
            ?.toByteArray(Charsets.UTF_8)
            ?: return true
        return try {
            sqliteDatabase.path
                ?.let(::File)
                ?.let { databaseFile ->
                    databaseFile.parentFile
                        ?.listFiles()
                        ?.filter { file -> file.isFile && file.name.startsWith(databaseFile.name) }
                        ?.none { artifact -> fileContains(artifact, tokenBytes) }
                } == true
        } catch (_: Exception) {
            false
        } finally {
            tokenBytes.fill(0)
        }
    }

    @Suppress("NestedBlockDepth")
    private fun fileContains(file: File, needle: ByteArray): Boolean {
        if (needle.isEmpty() || file.length() < needle.size) return false
        val prefixTable = prefixTable(needle)
        val buffer = ByteArray(RAW_SCAN_BUFFER_BYTES)
        var matched = 0
        return try {
            FileInputStream(file).use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    for (index in 0 until count) {
                        while (matched > 0 && buffer[index] != needle[matched]) {
                            matched = prefixTable[matched - 1]
                        }
                        if (buffer[index] == needle[matched]) matched += 1
                        if (matched == needle.size) return true
                    }
                }
            }
            false
        } finally {
            buffer.fill(0)
        }
    }

    private fun prefixTable(needle: ByteArray): IntArray {
        val table = IntArray(needle.size)
        var prefixLength = 0
        for (index in 1 until needle.size) {
            while (prefixLength > 0 && needle[index] != needle[prefixLength]) {
                prefixLength = table[prefixLength - 1]
            }
            if (needle[index] == needle[prefixLength]) prefixLength += 1
            table[index] = prefixLength
        }
        return table
    }

    private companion object {
        const val CHECKPOINT_RESULT_COLUMNS = 3
        const val CHECKPOINT_BUSY_COLUMN = 0
        const val CHECKPOINT_LOG_COLUMN = 1
        const val CHECKPOINTED_COLUMN = 2
        const val CHECKPOINT_NOT_BUSY = 0
        const val CHECKPOINT_EMPTY_WAL = 0
        const val CHECKPOINT_NOT_APPLICABLE = -1
        const val JOURNAL_MODE_WAL = "wal"
        val SAFE_ROLLBACK_JOURNAL_MODES = setOf("delete", "truncate")
        const val RAW_SCAN_BUFFER_BYTES = 16 * 1024
    }
}
