package social.mycelium.android.repository.feed

import social.mycelium.android.debug.MLog
import social.mycelium.android.relay.RelayConnectionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority

/**
 * Repository for managing anchor subscriptions (kind:30073)
 * Tracks which anchors (topics, relays, geohashes) a user is subscribed to
 */
class AnchorSubscriptionRepository private constructor() {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _subscribedAnchors = MutableStateFlow<Set<String>>(emptySet())
    val subscribedAnchors: StateFlow<Set<String>> = _subscribedAnchors.asStateFlow()
    
    private val _privateAnchors = MutableStateFlow<Set<String>>(emptySet())
    val privateAnchors: StateFlow<Set<String>> = _privateAnchors.asStateFlow()
    
    private var currentUserPubkey: String? = null
    private var latestSubscriptionEvent: Event? = null
    
    companion object {
        @Volatile
        private var instance: AnchorSubscriptionRepository? = null
        
        fun getInstance(): AnchorSubscriptionRepository {
            return instance ?: synchronized(this) {
                instance ?: AnchorSubscriptionRepository().also { instance = it }
            }
        }
    }
    
    init {
        // Register handler for kind:30073 events
        RelayConnectionStateMachine.getInstance().registerKind30073Handler { event ->
            handleSubscriptionEvent(event)
        }
    }
    
    /**
     * Set the current user's pubkey to filter subscription events
     */
    fun setCurrentUser(pubkey: String?) {
        currentUserPubkey = pubkey
        if (pubkey == null) {
            _subscribedAnchors.value = emptySet()
            _privateAnchors.value = emptySet()
            latestSubscriptionEvent = null
        }
    }
    
    /**
     * Handle incoming kind:30073 subscription events
     */
    private fun handleSubscriptionEvent(event: Event) {
        // Only process events from the current user
        if (event.pubKey != currentUserPubkey) return
        
        // Check if this is newer than our current event
        val current = latestSubscriptionEvent
        if (current != null && event.createdAt <= current.createdAt) return
        
        latestSubscriptionEvent = event
        
        // Extract public anchors from I tags
        val publicAnchors = event.tags
            .filter { it.size >= 2 && it[0] == "I" }
            .map { it[1] }
            .toSet()
        
        _subscribedAnchors.value = publicAnchors
        
        // TODO: Decrypt private anchors from content field using NIP-44
        // For now, just track public subscriptions
        
        MLog.d("AnchorSubscriptionRepo", "Updated subscriptions for ${event.pubKey.take(8)}: ${publicAnchors.size} public anchors")
    }
    
    /**
     * Optimistically add an anchor to the local subscription set (call before publishing).
     */
    fun addLocalAnchor(anchor: String) {
        _subscribedAnchors.value = _subscribedAnchors.value + anchor
        MLog.d("AnchorSubscriptionRepo", "Optimistic add: $anchor (total: ${_subscribedAnchors.value.size})")
    }

    /**
     * Optimistically remove an anchor from the local subscription set (call before publishing).
     */
    fun removeLocalAnchor(anchor: String) {
        _subscribedAnchors.value = _subscribedAnchors.value - anchor
        MLog.d("AnchorSubscriptionRepo", "Optimistic remove: $anchor (total: ${_subscribedAnchors.value.size})")
    }

    /**
     * Check if user is subscribed to an anchor
     */
    fun isSubscribed(anchor: String): Boolean {
        return anchor in _subscribedAnchors.value || anchor in _privateAnchors.value
    }
    
    /**
     * Get all subscribed anchors (public + private)
     */
    fun getAllSubscriptions(): Set<String> {
        return _subscribedAnchors.value + _privateAnchors.value
    }
    
    /**
     * Request subscription events for a specific user.
     * Opens a temporary subscription for kind:30073 filtered by the user's pubkey,
     * then auto-cancels after a short window.
     */
    fun requestSubscriptionsFor(pubkey: String, relays: Set<String>) {
        setCurrentUser(pubkey)
        if (relays.isEmpty()) return
        val filter = Filter(
            kinds = listOf(30073),
            authors = listOf(pubkey),
            limit = 5
        )
        RelayConnectionStateMachine.getInstance().requestOneShotSubscription(
            relayUrls = relays.toList(),
            filter = filter,
            priority = SubscriptionPriority.BACKGROUND,
            settleMs = 300L, maxWaitMs = 4_000L,
            onEvent = { event -> handleSubscriptionEvent(event) }
        )
        MLog.d("AnchorSubscriptionRepo", "Fetching kind:30073 (one-shot) for ${pubkey.take(8)} from ${relays.size} relays")
    }
}
