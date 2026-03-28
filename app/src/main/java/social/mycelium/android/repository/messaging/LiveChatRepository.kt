package social.mycelium.android.repository.messaging

import android.util.Log
import social.mycelium.android.data.LiveChatMessage
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.mycelium.android.repository.cache.ProfileMetadataCache

/**
 * Repository for NIP-53 live activity chat messages (kind:1311).
 * Subscribes to chat messages for a specific live activity and exposes them as a StateFlow.
 */
class LiveChatRepository private constructor() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val profileCache = ProfileMetadataCache.getInstance()
    private val relayStateMachine = RelayConnectionStateMachine.getInstance()

    private val _messages = MutableStateFlow<List<LiveChatMessage>>(emptyList())
    val messages: StateFlow<List<LiveChatMessage>> = _messages.asStateFlow()

    private val seenIds = mutableSetOf<String>()
    private var subscriptionHandle: TemporarySubscriptionHandle? = null
    private var currentActivityAddress: String? = null
    private var cacheRelayUrls: List<String> = emptyList()

    fun setCacheRelayUrls(urls: List<String>) {
        cacheRelayUrls = urls
    }

    /**
     * Subscribe to chat messages for a live activity.
     * @param activityAddress The NIP-53 addressable event coordinate, e.g. "30311:<pubkey>:<dtag>"
     * @param relayUrls Relay URLs to subscribe on (from the live activity's relay list)
     */
    fun subscribe(activityAddress: String, relayUrls: List<String>) {
        if (activityAddress == currentActivityAddress) return
        unsubscribe()

        currentActivityAddress = activityAddress
        seenIds.clear()
        _messages.value = emptyList()

        if (relayUrls.isEmpty()) {
            Log.w(TAG, "No relay URLs for live chat subscription")
            return
        }

        val filter = Filter(
            kinds = listOf(KIND_LIVE_CHAT),
            tags = mapOf("a" to listOf(activityAddress)),
            limit = 200
        )

        subscriptionHandle = relayStateMachine.requestTemporarySubscription(
            relayUrls = relayUrls,
            filter = filter,
            priority = SubscriptionPriority.HIGH,
            onEvent = { event -> handleChatEvent(event) }
        )

        Log.d(TAG, "Subscribed to live chat for $activityAddress on ${relayUrls.size} relays")
    }

    /**
     * Unsubscribe from the current live chat.
     */
    fun unsubscribe() {
        subscriptionHandle?.cancel()
        subscriptionHandle = null
        currentActivityAddress = null
    }

    private fun handleChatEvent(event: Event) {
        if (event.kind != KIND_LIVE_CHAT) return
        if (event.id in seenIds) return
        seenIds.add(event.id)

        val author = profileCache.resolveAuthor(event.pubKey)

        // Request profile if unknown
        if (profileCache.getAuthor(event.pubKey) == null) {
            scope.launch {
                try {
                    profileCache.requestProfiles(listOf(event.pubKey), cacheRelayUrls)
                } catch (_: Exception) {}
            }
        }

        val message = LiveChatMessage(
            id = event.id,
            pubkey = event.pubKey,
            content = event.content,
            createdAt = event.createdAt,
            author = author
        )

        val updated = (_messages.value + message).sortedBy { it.createdAt }
        _messages.value = updated
    }

    companion object {
        private const val TAG = "LiveChatRepo"
        const val KIND_LIVE_CHAT = 1311

        @Volatile
        private var instance: LiveChatRepository? = null
        fun getInstance(): LiveChatRepository =
            instance ?: synchronized(this) { instance ?: LiveChatRepository().also { instance = it } }
    }
}
