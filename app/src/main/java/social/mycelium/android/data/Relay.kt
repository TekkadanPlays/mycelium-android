package social.mycelium.android.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * NIP-11 Relay Information Document
 * https://github.com/nostr-protocol/nips/blob/master/11.md
 */
@Immutable
@Serializable
data class RelayInformation(
    val name: String? = null,
    val description: String? = null,
    val pubkey: String? = null,
    val contact: String? = null,
    @Serializable(with = FlexibleIntListSerializer::class)
    val supported_nips: List<Int>? = null,
    val software: String? = null,
    val version: String? = null,
    val limitation: RelayLimitation? = null,
    val relay_countries: List<String>? = null,
    val language_tags: List<String>? = null,
    val tags: List<String>? = null,
    val posting_policy: String? = null,
    val payments_url: String? = null,
    val fees: RelayFees? = null,
    val icon: String? = null,
    val image: String? = null
)

@Immutable
@Serializable
data class RelayLimitation(
    val max_message_length: Int? = null,
    val max_subscriptions: Int? = null,
    val max_filters: Int? = null,
    val max_limit: Int? = null,
    val max_subid_length: Int? = null,
    val max_event_tags: Int? = null,
    val max_content_length: Int? = null,
    val min_pow_difficulty: Int? = null,
    val auth_required: Boolean? = null,
    val payment_required: Boolean? = null,
    val restricted_writes: Boolean? = null,
    val created_at_lower_limit: Long? = null,
    val created_at_upper_limit: Long? = null
)

@Immutable
@Serializable
data class RelayFees(
    val admission: List<RelayFee>? = null,
    val subscription: List<RelayFee>? = null,
    val publication: List<RelayFee>? = null
)

@Immutable
@Serializable
data class RelayFee(
    val amount: Long,
    val unit: String,
    val period: Long? = null
)

/**
 * How a relay was added to the user's configuration.
 */
@Serializable
enum class RelaySource {
    USER_ADDED,
    NIP65_IMPORT,
    OUTBOX_HINT,
    NIP66_DISCOVERY,
    DEFAULT_SEED,
    SYSTEM
}

/**
 * User's relay configuration
 */
@Immutable
@Serializable
data class UserRelay(
    val url: String,
    val read: Boolean = true,
    val write: Boolean = true,
    val info: RelayInformation? = null,
    val isOnline: Boolean = false,
    val lastChecked: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val source: RelaySource = RelaySource.USER_ADDED
) {
    val displayName: String
        get() = info?.name ?: url.removePrefix("wss://").removePrefix("ws://")
    
    val profileImage: String?
        get() = info?.icon ?: info?.image
    
    val description: String?
        get() = info?.description
    
    val supportedNips: List<Int>
        get() = info?.supported_nips ?: emptyList()
    
    val software: String?
        get() = info?.software?.let { software ->
            info?.version?.let { version -> "$software v$version" } ?: software
        }
}

/**
 * Relay health status
 */
enum class RelayHealth {
    HEALTHY,    // Online with good response time
    WARNING,    // Online but slow or other issues
    CRITICAL,   // Offline or major issues
    UNKNOWN     // No monitoring data
}

/**
 * Relay connection status
 */
enum class RelayConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    ERROR
}
