package social.mycelium.android.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for cached kind-3 follow list.
 * Keyed by the user's hex pubkey. Stores the raw kind-3 event JSON
 * so follow/unfollow logic can reconstruct the Event object on cold start.
 */
@Entity(tableName = "cached_follow_list")
data class CachedFollowListEntity(
    @PrimaryKey val pubkey: String,
    /** Raw JSON of the kind-3 Event (same format as ContactListRepository.eventToJson). */
    val eventJson: String,
    /** created_at from the kind-3 event (unix seconds). */
    val eventCreatedAt: Long,
    /** Comma-separated hex pubkeys from p-tags (the follow set). */
    val followPubkeys: String,
    val updatedAt: Long = System.currentTimeMillis()
)
