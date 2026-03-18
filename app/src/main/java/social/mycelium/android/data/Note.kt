package social.mycelium.android.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Date

/**
 * Per-note publish lifecycle. Drives the thin progress line on NoteCard.
 * Sending = signed, being sent to relays.
 * Confirmed = at least one relay accepted (OK true).
 * Failed = all relays rejected or timed out.
 * null = normal note from subscription (no publish state).
 */
enum class PublishState { Sending, Confirmed, Failed }

/**
 * NIP-92 imeta tag metadata for a single media URL.
 * Parsed at event-processing time so the UI can size containers
 * and show blurhash placeholders before the media loads.
 */
@Immutable
@Serializable
data class IMetaData(
    val url: String,
    /** Width x Height parsed from "dim" field (e.g. "1920x1080"). */
    val width: Int? = null,
    val height: Int? = null,
    /** Blurhash string for placeholder rendering. */
    val blurhash: String? = null,
    /** MIME type (e.g. "image/jpeg", "video/mp4"). */
    val mimeType: String? = null,
    /** Alt text / description. */
    val alt: String? = null,
) {
    /** Aspect ratio (width/height) or null if dimensions unknown. */
    fun aspectRatio(): Float? {
        if (width != null && height != null && height > 0 && width > 0) {
            return width.toFloat() / height.toFloat()
        }
        return null
    }

    companion object {
        /**
         * Parse a single NIP-92 imeta tag array into a list of IMetaData.
         * Tag format: ["imeta", "url https://...", "dim 1920x1080", "blurhash ...", "m image/jpeg", ...]
         */
        fun parseIMetaTag(tag: Array<String>): IMetaData? {
            if (tag.size < 2 || tag[0] != "imeta") return null
            var url: String? = null
            var width: Int? = null
            var height: Int? = null
            var blurhash: String? = null
            var mimeType: String? = null
            var alt: String? = null
            for (i in 1 until tag.size) {
                val parts = tag[i].split(" ", limit = 2)
                if (parts.size < 2) continue
                when (parts[0]) {
                    "url" -> url = parts[1]
                    "dim" -> {
                        val dims = parts[1].split("x")
                        if (dims.size == 2) {
                            width = dims[0].toIntOrNull()
                            height = dims[1].toIntOrNull()
                        }
                    }
                    "blurhash" -> blurhash = parts[1]
                    "m" -> mimeType = parts[1]
                    "alt" -> alt = parts[1]
                }
            }
            if (url.isNullOrBlank()) return null
            return IMetaData(url, width, height, blurhash, mimeType, alt)
        }

        /** Parse all imeta tags from an event's tag array into a map keyed by URL. */
        fun parseAll(tags: Array<Array<String>>): Map<String, IMetaData> {
            val result = mutableMapOf<String, IMetaData>()
            for (tag in tags) {
                val meta = parseIMetaTag(tag) ?: continue
                result[meta.url] = meta
            }
            return result
        }
    }
}

@Immutable
@Serializable
data class Note(
    val id: String,
    val author: Author,
    val content: String,
    val timestamp: Long,
    val likes: Int = 0,
    val shares: Int = 0,
    val comments: Int = 0,
    /** Number of zaps (NIP-57) on this note; shown in counts row. */
    val zapCount: Int = 0,
    /** NIP-25 emoji reactions to show (e.g. ["❤️", "🔥"]); order/count from relay. */
    val reactions: List<String> = emptyList(),
    val isLiked: Boolean = false,
    val isShared: Boolean = false,
    val mediaUrls: List<String> = emptyList(),
    /** NIP-92 imeta metadata keyed by media URL (dimensions, blurhash, mimeType). */
    val mediaMeta: Map<String, IMetaData> = emptyMap(),
    val hashtags: List<String> = emptyList(),
    val urlPreviews: List<UrlPreviewInfo> = emptyList(),
    /** Event IDs of quoted notes (from nostr:nevent1... / nostr:note1... in content). */
    val quotedEventIds: List<String> = emptyList(),
    /** Relay URL this note was received from (primary); used to filter feed by selected relay(s). */
    val relayUrl: String? = null,
    /** All relay URLs this note was seen on (same event from multiple relays); for relay orbs and display filter. */
    val relayUrls: List<String> = emptyList(),
    /** True if this kind-1 is a reply (NIP-10); shown only in thread view, not in primary feed. */
    val isReply: Boolean = false,
    /** Root note id (NIP-10 "root" e-tag); used to build threaded reply chains for kind-1. */
    val rootNoteId: String? = null,
    /** Direct parent reply/note id (NIP-10 "reply" e-tag); used to build threaded reply chains for kind-1. */
    val replyToId: String? = null,
    /** Nostr event kind (1 = text note, 11 = topic root, 1111 = thread reply). Used for NIP-25 reaction "k" tag. */
    val kind: Int = 1,
    /** Topic/subject title for kind-11 topic roots and kind-1111 thread roots; shown as SUBJECT row when set. */
    val topicTitle: String? = null,
    /** Raw event tags for NIP-22 I tags (anchors), NIP-10 e tags, etc. Each tag is an array of strings. */
    val tags: List<List<String>> = emptyList(),
    /** Original note event ID for reposts; used to deduplicate multiple boosts of the same note. */
    val originalNoteId: String? = null,
    /** Authors who reposted this note (kind-6); when non-empty, NoteCard shows repost label. */
    val repostedByAuthors: List<Author> = emptyList(),
    /** Timestamp (ms) of the latest repost event (kind-6 created_at); null for non-reposts. */
    val repostTimestamp: Long? = null,
    /** Pubkeys mentioned in p-tags of this event; used to auto-tag people in reply chain (Amethyst-style). */
    val mentionedPubkeys: List<String> = emptyList(),
    /** NIP-23 long-form content: article summary (from "summary" tag). */
    val summary: String? = null,
    /** NIP-23 long-form content: header/cover image URL (from "image" tag). */
    val imageUrl: String? = null,
    /** Parameterized replaceable event d-tag identifier (NIP-23 kind 30023, NIP-33). */
    val dTag: String? = null,
    /** NIP-88 poll data; non-null when kind == 1068. */
    val pollData: PollData? = null,
    /** Publish progress for locally-published notes; null for notes from subscriptions. Not serialized. */
    @Transient val publishState: PublishState? = null
) {
    /** First (most recent) reposter, or null if not a repost. Convenience for UI. */
    val repostedBy: Author? get() = repostedByAuthors.firstOrNull()
}

/** NIP-88 poll option (["option", "code", "label"]). */
@Immutable
@Serializable
data class PollOption(
    val code: String,
    val label: String
)

/** NIP-88 poll metadata parsed from kind-1068 event tags. */
@Immutable
@Serializable
data class PollData(
    val options: List<PollOption>,
    /** "singlechoice" or "multiplechoice". */
    val pollType: String = "singlechoice",
    /** Unix epoch seconds when poll closes; null = open-ended. */
    val endsAt: Long? = null,
    /** Relay URLs where responses should be collected. */
    val relays: List<String> = emptyList()
) {
    val isMultipleChoice: Boolean get() = pollType == "multiplechoice"
    val hasEnded: Boolean get() = endsAt != null && endsAt < System.currentTimeMillis() / 1000

    companion object {
        /** Parse NIP-88 poll tags from raw event tags. Returns null if not a poll. */
        fun parseFromTags(tags: List<List<String>>): PollData? {
            val options = mutableListOf<PollOption>()
            var pollType = "singlechoice"
            var endsAt: Long? = null
            val relays = mutableListOf<String>()
            for (tag in tags) {
                if (tag.size < 2) continue
                when (tag[0]) {
                    "option" -> if (tag.size >= 3) options.add(PollOption(tag[1], tag[2]))
                    "polltype" -> pollType = tag[1]
                    "endsAt" -> endsAt = tag[1].toLongOrNull()
                    "relay" -> relays.add(tag[1])
                }
            }
            if (options.isEmpty()) return null
            return PollData(options, pollType, endsAt, relays)
        }
    }
}

@Immutable
@Serializable
data class Author(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val isVerified: Boolean = false,
    /** NIP-01 kind-0 "about" / bio. */
    val about: String? = null,
    /** NIP-05 identifier (e.g. user@domain.com). */
    val nip05: String? = null,
    /** Profile website URL. */
    val website: String? = null,
    /** Lightning address (LUD-16) for zaps, e.g. user@walletofsatoshi.com */
    val lud16: String? = null,
    /** Profile banner image URL (kind-0). */
    val banner: String? = null,
    /** Pronouns (kind-0). */
    val pronouns: String? = null
)

@Immutable
@Serializable
data class Comment(
    val id: String,
    val author: Author,
    val content: String,
    val timestamp: Long,
    val likes: Int = 0,
    val isLiked: Boolean = false
)

@Immutable
@Serializable
data class WebSocketMessage(
    val type: String,
    val data: String
)

/** Metadata for a quoted note (event id, author id, full content + snippet for preview). */
@Immutable
@Serializable
data class QuotedNoteMeta(
    val eventId: String,
    val authorId: String,
    val contentSnippet: String,
    /** Full event content — used when user taps "read more". */
    val fullContent: String = contentSnippet,
    /** Unix epoch seconds. */
    val createdAt: Long = 0L,
    /** Relay URLs where this event was seen (for counts subscription). */
    val relayUrl: String? = null,
    /** NIP-10 root note id — set when this quoted event is a kind-1 reply, so navigation can open the full thread. */
    val rootNoteId: String? = null,
    /** Event kind (1 = text note, 11 = topic, 1111 = thread reply). */
    val kind: Int = 1
)

enum class NoteAction {
    LIKE, UNLIKE, SHARE, COMMENT, DELETE
}

@Immutable
@Serializable
data class NoteUpdate(
    val noteId: String,
    val action: String,
    val userId: String,
    val timestamp: Long
)
