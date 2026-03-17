package social.mycelium.android.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for cached kind-0 profile metadata.
 * Keyed by hex pubkey (lowercase). Stores the JSON-serializable fields
 * from Author so profiles survive app restarts without re-fetching.
 */
@Entity(tableName = "cached_profiles")
data class CachedProfileEntity(
    @PrimaryKey val pubkey: String,
    val displayName: String,
    val username: String,
    val avatarUrl: String?,
    val about: String?,
    val nip05: String?,
    val website: String?,
    val lud16: String?,
    val banner: String?,
    val pronouns: String?,
    val updatedAt: Long = System.currentTimeMillis()
)
