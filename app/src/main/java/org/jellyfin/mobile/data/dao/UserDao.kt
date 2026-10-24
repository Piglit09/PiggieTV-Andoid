package org.jellyfin.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.jellyfin.mobile.data.entity.ServerUser
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.mobile.data.entity.UserEntity.Key.ACCESS_TOKEN
import org.jellyfin.mobile.data.entity.UserEntity.Key.ID
import org.jellyfin.mobile.data.entity.UserEntity.Key.LAST_LOGIN_TIMESTAMP
import org.jellyfin.mobile.data.entity.UserEntity.Key.SERVER_ID
import org.jellyfin.mobile.data.entity.UserEntity.Key.TABLE_NAME
import org.jellyfin.mobile.data.entity.UserEntity.Key.USER_ID
import java.util.UUID

@Dao
@Suppress("TooManyFunctions")
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: UserEntity): Long

    fun insert(serverId: Long, userId: UUID) = insert(UserEntity(serverId, userId))

    @Transaction
    fun upsert(serverId: Long, userId: UUID): Long = when (val user = getByUserId(serverId, userId)) {
        null -> insert(serverId, userId)

        else -> {
            markLoggedInAt(user.id, System.currentTimeMillis())
            user.id
        }
    }

    @Transaction
    @Query("SELECT * FROM $TABLE_NAME WHERE $SERVER_ID = :serverId AND $ID = :id")
    fun getServerUser(serverId: Long, id: Long): ServerUser?

    @Transaction
    @Query("SELECT * FROM $TABLE_NAME WHERE $SERVER_ID = :serverId AND $USER_ID = :userId")
    fun getServerUser(serverId: Long, userId: UUID): ServerUser?

    @Query("SELECT * FROM $TABLE_NAME WHERE $SERVER_ID = :serverId AND $USER_ID = :userId")
    fun getByUserId(serverId: Long, userId: UUID): UserEntity?

    @Query("SELECT * FROM $TABLE_NAME WHERE $SERVER_ID = :serverId")
    fun getAllForServer(serverId: Long): List<UserEntity>

    @Query("UPDATE $TABLE_NAME SET $LAST_LOGIN_TIMESTAMP = :timestamp WHERE $ID = :id")
    fun markLoggedInAt(id: Long, timestamp: Long): Int

    @Query("UPDATE $TABLE_NAME SET $ACCESS_TOKEN = NULL WHERE $ID = :id")
    fun clearLegacyAccessToken(id: Long): Int

    @Query("UPDATE $TABLE_NAME SET $ACCESS_TOKEN = NULL WHERE $ACCESS_TOKEN IS NOT NULL")
    fun clearAllLegacyAccessTokens(): Int

    @Query("SELECT COUNT(*) FROM $TABLE_NAME WHERE $ACCESS_TOKEN IS NOT NULL")
    fun countLegacyAccessTokens(): Int

    /**
     * Removes every legacy plaintext token as one verified Room transaction. Throwing from the
     * verification rolls the update back, so secure-store activation can safely retain the
     * legacy session when cleanup cannot be proven complete.
     */
    @Transaction
    fun clearAllLegacyAccessTokensVerified(): Boolean {
        clearAllLegacyAccessTokens()
        check(countLegacyAccessTokens() == 0) { "Legacy authentication cleanup was incomplete." }
        return true
    }
}
