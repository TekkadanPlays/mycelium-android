package social.mycelium.android.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for persisted notification state. Enables instant cold-start with
 * full notification history instead of re-fetching from relays every time.
 *
 * Complex fields (lists, maps) are stored as JSON strings. The Note objects
 * (note, targetNote) are NOT persisted — they are re-enriched from relay/Room
 * event cache after restoration.
 */
@Entity(
    tableName = "cached_notifications",
    indices = [
        Index(value = ["ownerPubkey"]),
        Index(value = ["sortTimestamp"]),
        Index(value = ["type"]),
    ]
)
data class CachedNotificationEntity(
    /** Notification ID (event ID for non-consolidated, "like:$eTag" etc. for consolidated). */
    @PrimaryKey val id: String,
    /** Hex pubkey of the account this notification belongs to. */
    val ownerPubkey: String,
    /** NotificationType.name (LIKE, REPLY, ZAP, etc.). */
    val type: String,
    /** Human-readable notification text (e.g. "Alice liked your post"). */
    val text: String,
    /** Sort key: latest event timestamp in millis. */
    val sortTimestamp: Long,
    // ── Author ──
    val authorId: String? = null,
    val authorDisplayName: String? = null,
    val authorUsername: String? = null,
    val authorAvatarUrl: String? = null,
    // ── Threading ──
    val targetNoteId: String? = null,
    val rootNoteId: String? = null,
    val replyNoteId: String? = null,
    val replyKind: Int? = null,
    // ── Reactions ──
    val reactionEmoji: String? = null,
    /** JSON array of unique reaction emojis, e.g. ["❤️","🔥"]. */
    val reactionEmojisJson: String? = null,
    val zapAmountSats: Long = 0L,
    // ── Consolidated actors ──
    /** JSON array of actor pubkeys, e.g. ["abc...","def..."]. */
    val actorPubkeysJson: String? = null,
    /** JSON object of custom emoji URLs, e.g. {"shortcode":"url"}. */
    val customEmojiUrlsJson: String? = null,
    val customEmojiUrl: String? = null,
    // ── Badge ──
    val badgeName: String? = null,
    val badgeImageUrl: String? = null,
    // ── Poll ──
    val pollId: String? = null,
    val pollQuestion: String? = null,
    val pollOptionCodesJson: String? = null,
    val pollOptionLabelsJson: String? = null,
    val pollAllOptionsJson: String? = null,
    val pollIsMultipleChoice: Boolean = false,
    // ── Raw content for cite detection ──
    val rawContent: String? = null,
    // ── Note content text (for display before re-enrichment) ──
    val noteContent: String? = null,
    val targetNoteContent: String? = null,
    /** Pubkey of the target note's author for proper profile reconstruction on cold start. */
    val targetNoteAuthorId: String? = null,
    // ── Metadata ──
    val cachedAt: Long = System.currentTimeMillis(),
)
