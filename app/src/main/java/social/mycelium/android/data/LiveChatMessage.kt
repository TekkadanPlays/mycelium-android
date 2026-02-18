package social.mycelium.android.data

import androidx.compose.runtime.Immutable

/**
 * A single chat message in a NIP-53 live activity stream (kind:1311).
 */
@Immutable
data class LiveChatMessage(
    /** Event id. */
    val id: String,
    /** Author pubkey. */
    val pubkey: String,
    /** Message content. */
    val content: String,
    /** Event created_at (seconds). */
    val createdAt: Long,
    /** Resolved author from ProfileMetadataCache. */
    val author: Author? = null
)
