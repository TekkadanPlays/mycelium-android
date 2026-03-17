package social.mycelium.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface Nip65Dao {
    @Query("SELECT * FROM cached_nip65 WHERE pubkey = :pubkey")
    suspend fun get(pubkey: String): CachedNip65Entity?

    @Query("SELECT * FROM cached_nip65 WHERE pubkey IN (:pubkeys)")
    suspend fun getMany(pubkeys: List<String>): List<CachedNip65Entity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedNip65Entity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CachedNip65Entity>)

    @Query("SELECT * FROM cached_nip65")
    suspend fun getAll(): List<CachedNip65Entity>

    @Query("DELETE FROM cached_nip65 WHERE updatedAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("SELECT COUNT(*) FROM cached_nip65")
    suspend fun count(): Int
}
