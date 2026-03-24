package social.mycelium.android.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for cached raw Nostr events (NIP-01).
 * Stores the JSON wire format so events can be replayed through the
 * processing pipeline on cold start without re-fetching from relays.
 *
 * Indexed by kind + createdAt for efficient feed queries, and by
 * pubkey for profile-scoped lookups.
 */
@Entity(
    tableName = "cached_events",
    indices = [
        Index(value = ["kind", "createdAt"]),
        Index(value = ["pubkey"]),
        Index(value = ["referencedEventId"]),
    ]
)
data class CachedEventEntity(
    /** Nostr event ID (64-char hex). */
    @PrimaryKey val eventId: String,
    /** Event kind (1 = note, 6 = repost, 11 = topic, etc.). */
    val kind: Int,
    /** Author hex pubkey. */
    val pubkey: String,
    /** Event created_at (unix seconds). */
    val createdAt: Long,
    /** Raw NIP-01 JSON of the full signed event. */
    val eventJson: String,
    /** Relay URL this event was first received from (nullable). */
    val relayUrl: String? = null,
    /** All relay URLs that have delivered this event, comma-separated.
     *  Updated when relay merges happen in-memory so cold-start restoration
     *  preserves all relay orbs. */
    val relayUrls: String? = null,
    /** Timestamp (ms) when this row was inserted/updated. For cache pruning. */
    val cachedAt: Long = System.currentTimeMillis(),
    /** Primary referenced event ID (first "e" tag). Used for poll response → poll lookups. */
    val referencedEventId: String? = null
)
