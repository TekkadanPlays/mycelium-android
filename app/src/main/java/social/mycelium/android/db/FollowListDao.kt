package social.mycelium.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FollowListDao {
    @Query("SELECT * FROM cached_follow_list WHERE pubkey = :pubkey")
    suspend fun get(pubkey: String): CachedFollowListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedFollowListEntity)

    @Query("DELETE FROM cached_follow_list WHERE pubkey = :pubkey")
    suspend fun delete(pubkey: String)
}
