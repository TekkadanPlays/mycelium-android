package social.mycelium.android.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for cached NIP-30 emoji pack events (kind 30030).
 * Stores the pack name, emojis JSON, and metadata so packs survive
 * across sessions without re-fetching from relays.
 *
 * Key is the addressable coordinate: "30030:pubkey:dtag".
 */
@Entity(tableName = "cached_emoji_packs")
data class CachedEmojiPackEntity(
    /** Addressable coordinate: "30030:pubkey:dtag". */
    @PrimaryKey val address: String,
    /** Pack display name. */
    val name: String,
    /** Author hex pubkey. */
    val author: String,
    /** d-tag of the pack. */
    val dTag: String,
    /** JSON object mapping shortcode → URL, e.g. {":smile:":"https://..."}. */
    val emojisJson: String,
    /** Event created_at (unix seconds). */
    val createdAt: Long,
    /** Timestamp (ms) when this row was inserted/updated. For cache pruning. */
    val cachedAt: Long = System.currentTimeMillis()
)
