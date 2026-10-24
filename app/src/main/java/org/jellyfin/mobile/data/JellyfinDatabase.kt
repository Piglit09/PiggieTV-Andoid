package org.jellyfin.mobile.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RenameTable
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID

@Database(
    entities = [
        ServerEntity::class,
        UserEntity::class,
        DownloadEntity::class,
    ],
    version = 5,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = JellyfinDatabase.MigrateV4::class),
        AutoMigration(from = 4, to = 5, spec = JellyfinDatabase.SanitizeDownloadCredentialsV5::class),
    ],
)
@TypeConverters(JellyfinDatabase.Converters::class)
abstract class JellyfinDatabase : RoomDatabase() {
    abstract val serverDao: ServerDao
    abstract val userDao: UserDao
    abstract val downloadDao: DownloadDao

    // Converters

    class Converters {
        @TypeConverter
        fun fromUuid(uuid: UUID?): String? = uuid?.toString()

        @TypeConverter
        fun toUuid(value: String?): UUID? = value?.toUUIDOrNull()
    }

    // Migrations

    @RenameTable(fromTableName = "Server", toTableName = "server")
    @RenameTable(fromTableName = "User", toTableName = "user")
    class MigrateV4 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            val cursor = db.query("SELECT user_id FROM user")
            while (cursor.moveToNext()) {
                val oldValue = cursor.getString(0)
                val newValue = oldValue.toUUIDOrNull()?.toString()

                if (newValue == null) {
                    Timber.i("Deleting a user record due to an invalid user ID")
                    db.execSQL("DELETE FROM user WHERE user_id = ?", arrayOf(oldValue))
                } else {
                    Timber.i("Normalizing a persisted user ID")
                    db.execSQL("UPDATE user SET user_id = ? WHERE user_id = ?", arrayOf(newValue, oldValue))
                }
            }

            cursor.close()
        }
    }

    /** Removes URL query/header-token residues written by the legacy WebView download bridge. */
    class SanitizeDownloadCredentialsV5 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            val cursor = db.query("SELECT item_id, media_source FROM Download")
            while (cursor.moveToNext()) {
                val itemId = cursor.getString(0)
                val original = cursor.getString(1)
                val sanitized = DownloadCredentialSanitizer.sanitizeSerializedMediaSource(original)
                when {
                    sanitized == null -> db.execSQL(
                        "DELETE FROM Download WHERE item_id = ?",
                        arrayOf(itemId),
                    )

                    sanitized != original -> db.execSQL(
                        "UPDATE Download SET media_source = ? WHERE item_id = ?",
                        arrayOf(sanitized, itemId),
                    )
                }
            }
            cursor.close()
        }
    }
}
