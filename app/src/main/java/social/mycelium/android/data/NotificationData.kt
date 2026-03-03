package social.mycelium.android.data

/**
 * Notification item for the notifications feed (replies, likes, reposts, zaps, etc.).
 * Amethyst-style: target note for likes/zaps, root for replies, consolidated reposts.
 */
data class NotificationData(
    val id: String,
    val type: NotificationType,
    val text: String,
    /** The notification event as a note (e.g. reply content, reaction event). */
    val note: Note? = null,
    val author: Author? = null,
    /** Note that was liked (kind 7 "e" tag) or zapped (9735 "e" tag); for display below the line. */
    val targetNote: Note? = null,
    /** Id of the target note when we don't have the note yet (e.g. still fetching). */
    val targetNoteId: String? = null,
    /** Root note id (e-tag root) for replies; open thread at this note. */
    val rootNoteId: String? = null,
    /** Reply note id (this notification's event id) for replies. */
    val replyNoteId: String? = null,
    /** Reply kind: 1 = thread (kind-1), 1111 = topic (kind-1111); null for non-reply or legacy. */
    val replyKind: Int? = null,
    /** For consolidated reposts: pubkeys of users who reposted this note (one row per reposted note). */
    val reposterPubkeys: List<String> = emptyList(),
    /** For consolidated notifications: pubkeys of actors (likes/zaps/reposts). */
    val actorPubkeys: List<String> = emptyList(),
    /** Sort key: latest repost time or notification time. */
    val sortTimestamp: Long = 0L,
    /** Total zap amount in sats (for ZAP type, parsed from bolt11 invoice). */
    val zapAmountSats: Long = 0L,
    /** NIP-25 reaction emoji for LIKE type (e.g. "❤️", "🔥", "👍", or "+" for default like). */
    val reactionEmoji: String? = null,
    /** All unique reaction emojis for this consolidated like notification (e.g. ["❤️", "🔥", "👍"]). */
    val reactionEmojis: List<String> = emptyList(),
    /** NIP-30 custom emoji URL for :shortcode: reactions (from "emoji" tag in kind-7 event). */
    val customEmojiUrl: String? = null,
    /** NIP-30 custom emoji URLs keyed by :shortcode: for all reactions in this consolidated notification. */
    val customEmojiUrls: Map<String, String> = emptyMap(),
    /** Media URLs (images/videos) found in the notification note content. */
    val mediaUrls: List<String> = emptyList(),
    /** Quoted event IDs (nevent/note1 references) found in the notification note content. */
    val quotedEventIds: List<String> = emptyList(),
    /** NIP-58 badge name (from kind 30009 definition) for BADGE_AWARD notifications. */
    val badgeName: String? = null,
    /** NIP-58 badge image URL (thumb or image from kind 30009 definition) for BADGE_AWARD notifications. */
    val badgeImageUrl: String? = null
)

enum class NotificationType {
    LIKE,
    REPLY,
    COMMENT,
    MENTION,
    REPOST,
    ZAP,
    DM,
    HIGHLIGHT,
    REPORT,
    QUOTE,
    BADGE_AWARD
}
