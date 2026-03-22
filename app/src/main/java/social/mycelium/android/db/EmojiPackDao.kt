package social.mycelium.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmojiPackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(packs: List<CachedEmojiPackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pack: CachedEmojiPackEntity)

    /** Get all cached emoji packs. */
    @Query("SELECT * FROM cached_emoji_packs")
    suspend fun getAll(): List<CachedEmojiPackEntity>

    /** Get packs by their address coordinates. */
    @Query("SELECT * FROM cached_emoji_packs WHERE address IN (:addresses)")
    suspend fun getByAddresses(addresses: List<String>): List<CachedEmojiPackEntity>

    /** Delete all cached emoji packs (account switch). */
    @Query("DELETE FROM cached_emoji_packs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM cached_emoji_packs")
    suspend fun count(): Int
}
