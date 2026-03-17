package social.mycelium.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProfileDao {
    @Query("SELECT * FROM cached_profiles WHERE pubkey = :pubkey")
    suspend fun getProfile(pubkey: String): CachedProfileEntity?

    @Query("SELECT * FROM cached_profiles WHERE pubkey IN (:pubkeys)")
    suspend fun getProfiles(pubkeys: List<String>): List<CachedProfileEntity>

    @Query("SELECT * FROM cached_profiles")
    suspend fun getAllProfiles(): List<CachedProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: CachedProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<CachedProfileEntity>)

    @Query("DELETE FROM cached_profiles WHERE updatedAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("SELECT COUNT(*) FROM cached_profiles")
    suspend fun count(): Int
}
