package social.mycelium.android.data

import androidx.compose.runtime.Immutable

/** A single NIP-17 gift-wrapped direct message (kind 14 rumor inside kind 13 seal inside kind 1059 gift wrap). */
@Immutable
data class DirectMessage(
    /** Gift wrap event ID (kind 1059). */
    val id: String,
    /** Hex pubkey of the actual sender (from seal pubkey). */
    val senderPubkey: String,
    /** Hex pubkey of the recipient (from gift wrap p-tag). */
    val recipientPubkey: String,
    /** Decrypted message content. */
    val content: String,
    /** Unix epoch seconds from the rumor (kind 14). */
    val createdAt: Long,
    /** Optional reply-to event ID (e-tag in rumor). */
    val replyToId: String? = null,
    /** Optional subject (subject tag in rumor). */
    val subject: String? = null,
    /** Whether this message was sent by us. */
    val isOutgoing: Boolean = false
)

/** A DM conversation with a specific user. */
@Immutable
data class Conversation(
    /** The other party's hex pubkey. */
    val peerPubkey: String,
    /** Display name (resolved from profile cache). */
    val peerDisplayName: String = "",
    /** Avatar URL (resolved from profile cache). */
    val peerAvatarUrl: String? = null,
    /** Most recent message in this conversation. */
    val lastMessage: DirectMessage? = null,
    /** Total message count in this conversation. */
    val messageCount: Int = 0,
    /** Number of unread messages. */
    val unreadCount: Int = 0
)
