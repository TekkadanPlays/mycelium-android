package social.mycelium.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface Nip11Dao {
    @Query("SELECT * FROM cached_nip11 WHERE relayUrl = :relayUrl")
    suspend fun get(relayUrl: String): CachedNip11Entity?

    @Query("SELECT * FROM cached_nip11 WHERE relayUrl IN (:relayUrls)")
    suspend fun getMany(relayUrls: List<String>): List<CachedNip11Entity>

    @Query("SELECT * FROM cached_nip11")
    suspend fun getAll(): List<CachedNip11Entity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedNip11Entity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CachedNip11Entity>)

    @Query("SELECT relayUrl FROM cached_nip11")
    suspend fun getAllUrls(): List<String>

    @Query("SELECT relayUrl FROM cached_nip11 WHERE fetchedAt < :cutoffMs")
    suspend fun getStaleUrls(cutoffMs: Long): List<String>

    @Query("DELETE FROM cached_nip11 WHERE fetchedAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("DELETE FROM cached_nip11")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM cached_nip11")
    suspend fun count(): Int
}
