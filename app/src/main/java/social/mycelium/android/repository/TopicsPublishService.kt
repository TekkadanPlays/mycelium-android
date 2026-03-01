package social.mycelium.android.repository

import com.example.cybin.core.Event
import com.example.cybin.core.EventTemplate

/**
 * Builds Kind 11 (topic) and Kind 1111 (thread reply) event templates
 * for signing via Amber and publishing to relays.
 * Tag structure matches RelayTools / NIP-22 so relays that support kind-11/1111 accept them.
 */
object TopicsPublishService {

    /**
     * Build a Kind 11 topic event template. Tags: "title", "t" (hashtags). Caller signs and sends.
     */
    fun buildTopicEventTemplate(
        title: String,
        content: String,
        hashtags: List<String>
    ): EventTemplate {
        return Event.build(11, content) {
            add(arrayOf("title", title))
            hashtags.forEach { tag ->
                add(arrayOf("t", tag.removePrefix("#").trim()))
            }
        }
    }

    // ── NIP-22: Anchored Events ──────────────────────────────────────────

    /**
     * Build a Kind 1011 scoped moderation event: mark a note as off-topic within an anchor.
     * Tags: ["I", anchor], ["e", noteId]. Content is a human-readable reason.
     */
    fun buildOffTopicModerationTemplate(
        anchor: String,
        noteId: String,
        reason: String = "off-topic"
    ): EventTemplate {
        return Event.build(1011, reason) {
            add(arrayOf("I", anchor))
            add(arrayOf("e", noteId))
        }
    }

    /**
     * Build a Kind 1011 scoped moderation event: exclude a user from an anchor.
     * Tags: ["I", anchor], ["p", pubkey]. Content is a human-readable reason.
     */
    fun buildUserExclusionModerationTemplate(
        anchor: String,
        pubkey: String,
        reason: String = "removed from topic"
    ): EventTemplate {
        return Event.build(1011, reason) {
            add(arrayOf("I", anchor))
            add(arrayOf("p", pubkey))
        }
    }

    /**
     * Build a Kind 30073 anchor subscription event (parameterized replaceable, d="").
     * Public anchors are stored as I tags; moderator pubkeys as p tags with "moderator" marker.
     */
    fun buildAnchorSubscriptionTemplate(
        anchors: List<String>,
        moderators: Map<String, List<String>> = emptyMap()
    ): EventTemplate {
        return Event.build(30073, "") {
            add(arrayOf("d", ""))
            anchors.forEach { anchor ->
                add(arrayOf("I", anchor))
            }
            moderators.forEach { (anchor, pubkeys) ->
                pubkeys.forEach { pubkey ->
                    add(arrayOf("p", pubkey, "", "moderator"))
                    add(arrayOf("I", anchor))
                }
            }
        }
    }

    /**
     * Build a Kind 1111 thread reply event template. Root: E, K, P; parent: e, k, p. Caller signs and sends.
     */
    fun buildThreadReplyEventTemplate(
        rootThreadId: String,
        rootThreadPubkey: String,
        parentReplyId: String?,
        parentReplyPubkey: String?,
        content: String,
        taggedPubkeys: List<String> = emptyList()
    ): EventTemplate {
        return Event.build(1111, content) {
            add(arrayOf("E", rootThreadId, "", rootThreadPubkey))
            add(arrayOf("K", "11"))
            add(arrayOf("P", rootThreadPubkey, ""))
            if (parentReplyId != null && parentReplyPubkey != null) {
                add(arrayOf("e", parentReplyId, "", parentReplyPubkey))
                add(arrayOf("k", "1111"))
                add(arrayOf("p", parentReplyPubkey, ""))
            } else {
                add(arrayOf("e", rootThreadId, "", rootThreadPubkey))
                add(arrayOf("k", "11"))
                add(arrayOf("p", rootThreadPubkey, ""))
            }
            // Amethyst-style: forward all tagged pubkeys from the reply chain
            // (skip any already added as the primary p-tag above)
            val primaryPubkey = parentReplyPubkey ?: rootThreadPubkey
            taggedPubkeys.distinct()
                .filter { it != primaryPubkey }
                .forEach { pk -> add(arrayOf("p", pk, "")) }
        }
    }
}
