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
     * "mention" e-tags (NIP-10 inline citations / NIP-27 quotes) are NOT reply markers.
     * Aligns with Amethyst's isNewThread() = (replyTo == null || replyTo.isEmpty()) for feed filtering.
     */
    fun isReply(event: Event): Boolean {
        if (event.kind != 1) return false
        val tags = event.tags ?: return false
        val eTags = tags.toList().filter { it.size >= 2 && it.getOrNull(0) == "e" && (it.getOrNull(1)?.length == 64) }
        if (eTags.isEmpty()) return false
        // Check for explicit root/reply markers first
        if (eTags.any { tag -> pickMarker(tag) == "reply" || pickMarker(tag) == "root" }) return true
        // If ANY e-tag has a marker (including "mention"), this uses the marked style — mention-only = NOT a reply
        val hasAnyMarker = eTags.any { tag -> pickMarker(tag) != null }
        if (hasAnyMarker) return false
        // Pure positional (deprecated, no markers at all): any e-tag = reply
        return true
    }

    /**
     * Root event id for a reply (NIP-10): marked "root" e tag, or first e tag (positional).
     * Returns null if not a reply or no root.
     * "mention" e-tags are excluded — a note that only quotes/cites another is NOT a reply.
     * Marker is read from index 3, then 4, then 2 (same as Amethyst) to support
     * both ["e", id, relay, "root"] and ["e", id, relay, pubkey, "root"].
     */
    fun getRootId(event: Event): String? {
        if (event.kind != 1) return null
        val tags = event.tags ?: return null
        val eTags = tags.toList().filter { it.size >= 2 && it.getOrNull(0) == "e" && (it.getOrNull(1)?.length == 64) }
        if (eTags.isEmpty()) return null
        // Prefer explicitly marked root
        val markedRoot = eTags.firstOrNull { tag -> pickMarker(tag) == "root" }?.getOrNull(1)
        if (markedRoot != null) return markedRoot
        // If any e-tag has a marker (including "mention"), this uses the marked style — no root found
        val hasAnyMarker = eTags.any { tag -> pickMarker(tag) != null }
        if (hasAnyMarker) return null
        // Pure positional (deprecated, no markers): first e-tag is root
        return eTags.firstOrNull()?.getOrNull(1)
    }

    /**
     * Direct reply-to event id (NIP-10): marked "reply" e tag, or last e tag (positional).
     * "mention" e-tags are excluded.
     * Marker is read from index 3, then 4, then 2 to match Amethyst and support both tag orders.
     */
    fun getReplyToId(event: Event): String? {
        if (event.kind != 1) return null
        val tags = event.tags ?: return null
        val eTags = tags.toList().filter { it.size >= 2 && it.getOrNull(0) == "e" && (it.getOrNull(1)?.length == 64) }
        if (eTags.isEmpty()) return null
        // Prefer explicitly marked reply
        val markedReply = eTags.lastOrNull { tag -> pickMarker(tag) == "reply" }?.getOrNull(1)
        if (markedReply != null) return markedReply
        // If any e-tag has a marker (including "mention"), this uses the marked style — no reply found
        val hasAnyMarker = eTags.any { tag -> pickMarker(tag) != null }
        if (hasAnyMarker) return null
        // Pure positional (deprecated, no markers): last e-tag is reply-to (if 2+ e-tags), else first
        if (eTags.size >= 2) return eTags.last().getOrNull(1)
        return eTags.firstOrNull()?.getOrNull(1)
    }

    private fun pickMarker(tag: Array<out String>): String? {
        val m3 = tag.getOrNull(3)
        if (m3 == "root" || m3 == "reply" || m3 == "mention") return m3
        val m4 = tag.getOrNull(4)
        if (m4 == "root" || m4 == "reply" || m4 == "mention") return m4
        val m2 = tag.getOrNull(2)
        if (m2 == "root" || m2 == "reply" || m2 == "mention") return m2
        return null
    }
}
