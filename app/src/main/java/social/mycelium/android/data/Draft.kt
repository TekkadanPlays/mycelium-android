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
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
enum class DraftType {
    NOTE, TOPIC, REPLY_KIND1, REPLY_KIND1111, TOPIC_REPLY
}
