package social.mycelium.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(notifications: List<CachedNotificationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notification: CachedNotificationEntity)

    /** Load notifications for a given account, newest first, with a limit. */
    @Query("SELECT * FROM cached_notifications WHERE ownerPubkey = :pubkey ORDER BY sortTimestamp DESC LIMIT :limit")
    suspend fun getForOwner(pubkey: String, limit: Int = 5000): List<CachedNotificationEntity>

    /** Load ALL notifications for a given account, newest first. No cap — supports years of history. */
    @Query("SELECT * FROM cached_notifications WHERE ownerPubkey = :pubkey ORDER BY sortTimestamp DESC")
    suspend fun getAllForOwner(pubkey: String): List<CachedNotificationEntity>

    /** Delete all notifications for an account (account switch / logout). */
    @Query("DELETE FROM cached_notifications WHERE ownerPubkey = :pubkey")
    suspend fun deleteForOwner(pubkey: String)

    /** Delete specific notifications by ID. */
    @Query("DELETE FROM cached_notifications WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM cached_notifications WHERE ownerPubkey = :pubkey")
    suspend fun countForOwner(pubkey: String): Int

    /** Size-based pruning: keep only the newest [keepCount] notifications for an account. */
    @Query("DELETE FROM cached_notifications WHERE ownerPubkey = :pubkey AND id NOT IN (SELECT id FROM cached_notifications WHERE ownerPubkey = :pubkey ORDER BY sortTimestamp DESC LIMIT :keepCount)")
    suspend fun trimToNewest(pubkey: String, keepCount: Int)
}
