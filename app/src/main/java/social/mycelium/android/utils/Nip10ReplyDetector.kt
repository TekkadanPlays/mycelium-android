package social.mycelium.android.utils

import com.example.cybin.core.Event

/**
 * NIP-10 reply detection for kind-1 text events.
 * A note is a "reply" (and should be hidden from the primary feed) if it has "e" tags
 * that denote a reply to another event: either marked "reply"/"root" or positional (deprecated).
 * Reposts and notes with only "mention" e tags (citations) are not replies.
 */
object Nip10ReplyDetector {

    /**
     * Returns true if this kind-1 event is a reply to another event (direct reply or reply to root).
     * Such notes should only appear in thread view, not in the main feed.
     * Aligns with Amethyst's isNewThread() = (replyTo == null || replyTo.isEmpty()) for feed filtering.
     */
    fun isReply(event: Event): Boolean {
        if (event.kind != 1) return false
        val tags = event.tags ?: return false
        val eTags = tags.toList().filter { it.size >= 2 && it.getOrNull(0) == "e" && (it.getOrNull(1)?.length == 64) }
        if (eTags.isEmpty()) return false
        return eTags.any { tag ->
            when {
                tag.size <= 3 -> true  // positional: ["e", id] or ["e", id, relay] = reply
                tag.size >= 4 -> pickMarker(tag) == "reply" || pickMarker(tag) == "root"  // marked reply or root (index 3 or 4)
                else -> false
            }
        }
    }

    /**
     * Root event id for a reply (NIP-10): marked "root" e tag, or first e tag (positional).
     * Returns null if not a reply or no root.
     * Marker is read from index 3, then 4, then 2 (same as Amethyst) to support
     * both ["e", id, relay, "root"] and ["e", id, relay, pubkey, "root"].
     */
    fun getRootId(event: Event): String? {
        if (event.kind != 1) return null
        val tags = event.tags ?: return null
        val eTags = tags.toList().filter { it.size >= 2 && it.getOrNull(0) == "e" && (it.getOrNull(1)?.length == 64) }
        if (eTags.isEmpty()) return null
        val markedRoot = eTags.firstOrNull { tag -> pickMarker(tag) == "root" }?.getOrNull(1)
        if (markedRoot != null) return markedRoot
        return eTags.firstOrNull()?.getOrNull(1)
    }

    /**
     * Direct reply-to event id (NIP-10): marked "reply" e tag, or last e tag (positional).
     * Marker is read from index 3, then 4, then 2 to match Amethyst and support both tag orders.
     */
    fun getReplyToId(event: Event): String? {
        if (event.kind != 1) return null
        val tags = event.tags ?: return null
        val eTags = tags.toList().filter { it.size >= 2 && it.getOrNull(0) == "e" && (it.getOrNull(1)?.length == 64) }
        if (eTags.isEmpty()) return null
        val markedReply = eTags.lastOrNull { tag -> pickMarker(tag) == "reply" }?.getOrNull(1)
        if (markedReply != null) return markedReply
        if (eTags.size >= 2) return eTags.last().getOrNull(1)
        return eTags.firstOrNull()?.getOrNull(1)
    }

    private fun pickMarker(tag: Array<out String>): String? {
        val m3 = tag.getOrNull(3)
        if (m3 == "root" || m3 == "reply") return m3
        val m4 = tag.getOrNull(4)
        if (m4 == "root" || m4 == "reply") return m4
        val m2 = tag.getOrNull(2)
        if (m2 == "root" || m2 == "reply") return m2
        return null
    }
}
