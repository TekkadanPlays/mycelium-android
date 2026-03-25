package social.mycelium.android.data

import kotlinx.serialization.Serializable

@Serializable
data class Draft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: DraftType,
    val content: String,
    val title: String? = null,
    val rootId: String? = null,
    val rootPubkey: String? = null,
    val parentId: String? = null,
    val parentPubkey: String? = null,
    val hashtags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val scheduledAt: Long? = null,
    val isScheduled: Boolean = false,
    val signedEventJson: String? = null,
    val relayUrls: List<String> = emptyList(),
    val publishError: String? = null,
    val isCompleted: Boolean = false
)

@Serializable
enum class DraftType {
    NOTE, ARTICLE, TOPIC, REPLY_KIND1, REPLY_KIND1111, TOPIC_REPLY
}
