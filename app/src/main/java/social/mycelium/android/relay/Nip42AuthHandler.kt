package social.mycelium.android.relay

import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.eventTemplate
import com.example.cybin.core.nowUnixSeconds
import com.example.cybin.relay.NostrProtocol
import com.example.cybin.relay.RelayConnectionListener
import com.example.cybin.signer.NostrSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * NIP-42 relay authentication handler — **connection-driven, not time-driven**.
 *
 * ## Model
 * Authentication is tied to a **WebSocket connection lifetime**:
 * - Relay connects → sends `["AUTH", "<challenge>"]`
 * - We attempt **one** background ContentProvider sign via Amber
 * - If it succeeds → send AUTH response → relay OKs → authenticated for this connection
 * - If it fails → relay stays unauthenticated until the **next reconnect**
 * - On disconnect → all state for that relay is wiped clean
 * - On reconnect → relay sends a new challenge → fresh single attempt
 *
 * There are **no timers, no cooldowns, no retry counters**. The reconnect cycle itself
 * is the natural retry mechanism. If Amber's ContentProvider is frozen by Android, the
 * sign fails once, the relay is unauthenticated, and that's it until the connection
 * drops and re-establishes.
 *
 * ## Foreground signing
 * NIP-42 AUTH **never** launches Amber's foreground Activity. It is strictly
 * background-only (ContentProvider). Launching Amber would steal focus from whatever
 * the user is doing — browsing, sleeping, using other apps. Only user-initiated
 * actions (reactions, posts, zaps) may trigger foreground Amber approval.
 *
 * ## Publish-triggered re-auth
 * If a relay rejects a published event with "auth-required", we queue the event for
 * replay and force a reconnect to trigger a fresh AUTH handshake. This is the only
 * case where we proactively reconnect for auth purposes.
 */
class Nip42AuthHandler(
    private val stateMachine: RelayConnectionStateMachine
) {
    companion object {
        private const val TAG = "Nip42Auth"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Sequential signing queue — one background sign at a time, never foreground. */
    private data class AuthJob(val url: String, val challenge: String, val signer: NostrSigner)
    private val authQueue = Channel<AuthJob>(Channel.UNLIMITED)

    /** Current signer — set after login, cleared on logout. */
    @Volatile
    private var signer: NostrSigner? = null

    /** Normalized relay URLs the user has configured in their relay manager.
     *  AUTH challenges from relays NOT in this set are silently ignored. */
    @Volatile
    private var allowedRelayUrls: Set<String> = emptySet()

    /** Update the set of relay URLs the user has configured. Only these relays
     *  will be authenticated via NIP-42. Call when relay config changes or on login. */
    fun setAllowedRelayUrls(urls: Set<String>) {
        allowedRelayUrls = urls.map { social.mycelium.android.utils.normalizeRelayUrl(it) }.toSet()
        Log.d(TAG, "Allowed relay URLs updated: ${allowedRelayUrls.size} relays")
    }

    /** Add relay URLs to the allowed set without replacing existing entries.
     *  Use when a subsystem (e.g. DM relays) discovers relays after the initial
     *  allowed set is populated at login. Thread-safe (volatile read + immutable set). */
    fun addAllowedRelayUrls(urls: Collection<String>) {
        if (urls.isEmpty()) return
        val normalized = urls.map { social.mycelium.android.utils.normalizeRelayUrl(it) }.toSet()
        val newUrls = normalized - allowedRelayUrls
        if (newUrls.isEmpty()) return
        allowedRelayUrls = allowedRelayUrls + newUrls
        Log.d(TAG, "Added ${newUrls.size} relay(s) to allowed set (total: ${allowedRelayUrls.size})")
    }

    /** Session-scoped relay URLs temporarily allowed for auth (e.g. foreign relays
     *  encountered via quoted event relay hints). Cleared on logout. Not persisted. */
    @Volatile
    private var sessionAllowedUrls: Set<String> = emptySet()

    /** Temporarily allow auth with a foreign relay for this session only.
     *  Does NOT add the relay to the user's permanent relay manager.
     *  Used when the user manually chooses to authenticate with a relay
     *  encountered via a quoted event hint. */
    fun allowSessionRelay(url: String) {
        val normalized = social.mycelium.android.utils.normalizeRelayUrl(url)
        if (normalized in allowedRelayUrls || normalized in sessionAllowedUrls) return
        sessionAllowedUrls = sessionAllowedUrls + normalized
        Log.d(TAG, "Session-allowed relay: $normalized (total session: ${sessionAllowedUrls.size})")
    }

    /** Whether a signer is available to respond to AUTH challenges. */
    fun hasSigner(): Boolean = signer != null

    /** Check if a relay URL is allowed (permanent or session). */
    fun isRelayAllowed(url: String): Boolean {
        val normalized = social.mycelium.android.utils.normalizeRelayUrl(url)
        return normalized in allowedRelayUrls || normalized in sessionAllowedUrls
    }

    /** Per-relay auth status for UI observation. */
    enum class AuthStatus { NONE, CHALLENGED, AUTHENTICATING, AUTHENTICATED, FAILED }

    private val _authStatusByRelay = MutableStateFlow<Map<String, AuthStatus>>(emptyMap())
    val authStatusByRelay: StateFlow<Map<String, AuthStatus>> = _authStatusByRelay.asStateFlow()

    /** Challenges we've already attempted for the current connection (dedup within one connection). */
    private val respondedChallenges = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<ChallengeKey, Boolean>()
    )

    /** Track event IDs we sent for auth so we can match OK responses. */
    private val pendingAuthEventIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /** Events awaiting auth retry, keyed by relay URL. */
    private val pendingReplayEvents = java.util.concurrent.ConcurrentHashMap<String, MutableList<Event>>()

    /** Recent events for lookup by event ID (30s TTL). */
    private val recentEvents = java.util.concurrent.ConcurrentHashMap<String, Pair<Event, Long>>()
    private val RECENT_EVENT_TTL_MS = 30_000L

    private data class ChallengeKey(val relayUrl: String, val challenge: String)

    private val listener = object : RelayConnectionListener {
        override fun onAuth(url: String, challenge: String) {
            handleAuthChallenge(url, challenge)
        }

        override fun onOk(url: String, eventId: String, success: Boolean, message: String) {
            handleOkMessage(url, eventId, success, message)
        }

        override fun onConnecting(url: String) {
            // New connection → wipe all auth state for this relay so it gets a clean handshake
            resetRelayState(url)
            updateStatus(url, AuthStatus.NONE)
        }

        override fun onDisconnected(url: String) {
            // Connection gone → wipe state; next connect will start fresh
            resetRelayState(url)
        }
    }

    init {
        stateMachine.relayPool.addListener(listener)
        Log.d(TAG, "NIP-42 auth handler registered")

        // Process AUTH signing jobs sequentially — one background sign at a time
        scope.launch {
            for (job in authQueue) {
                processAuthJob(job)
            }
        }
    }

    /** Set the signer after login. NIP-42 auth will only work when a signer is available. */
    fun setSigner(newSigner: NostrSigner?) {
        signer = newSigner
        if (newSigner != null) {
            Log.d(TAG, "Signer set — NIP-42 auth enabled")
        } else {
            Log.d(TAG, "Signer cleared — NIP-42 auth disabled")
            _authStatusByRelay.value = emptyMap()
            respondedChallenges.clear()
            pendingAuthEventIds.clear()
            sessionAllowedUrls = emptySet()
        }
    }

    /** Wipe all per-relay auth state (called on connect/disconnect). */
    private fun resetRelayState(url: String) {
        respondedChallenges.removeAll { it.relayUrl == url }
    }

    private fun handleAuthChallenge(url: String, challenge: String) {
        // Only authenticate with relays the user has configured or session-allowed.
        // If neither set contains the relay, we refuse — never leak identity to
        // relays the user hasn't explicitly chosen.
        val normalizedUrl = social.mycelium.android.utils.normalizeRelayUrl(url)
        if (normalizedUrl !in allowedRelayUrls && normalizedUrl !in sessionAllowedUrls) {
            Log.d(TAG, "AUTH[$url] Relay not in allowed or session set — ignoring")
            return
        }

        // Dedup: only one attempt per (relay, challenge) per connection
        val key = ChallengeKey(url, challenge)
        if (!respondedChallenges.add(key)) return

        val currentSigner = signer
        if (currentSigner == null) {
            Log.w(TAG, "AUTH[$url] No signer — skipping (will retry on next reconnect)")
            updateStatus(url, AuthStatus.FAILED)
            return
        }

        Log.d(TAG, "AUTH challenge from $url: ${challenge.take(16)}…")
        updateStatus(url, AuthStatus.AUTHENTICATING)

        // Queue for sequential background processing
        authQueue.trySend(AuthJob(url, challenge, currentSigner))
    }

    /** Max background sign attempts per AUTH challenge. Amber's ContentProvider may not
     *  be accessible immediately after app launch (Android froze the process). We retry
     *  a few times with short delays to let Android wake it up. This is a bounded warmup
     *  within a single connection's AUTH attempt — not a timer-based retry schedule. */
    private val MAX_SIGN_ATTEMPTS = 4
    private val SIGN_RETRY_DELAY_MS = 1500L

    /**
     * Process a single AUTH job — background ContentProvider signing only.
     *
     * Attempts [MAX_SIGN_ATTEMPTS] background signs with [SIGN_RETRY_DELAY_MS] between
     * each. This handles the common case where Amber's process is frozen by Android on
     * app launch — the first query to its ContentProvider fails, but a short delay lets
     * Android thaw the process so subsequent attempts succeed.
     *
     * If all attempts fail, the relay stays unauthenticated until the next reconnect
     * brings a fresh challenge. No timers, no scheduled retries beyond this window.
     */
    private suspend fun processAuthJob(job: AuthJob) {
        val (url, challenge, currentSigner) = job
        try {
            val template = eventTemplate(22242, "", nowUnixSeconds()) {
                add(arrayOf("relay", url))
                add(arrayOf("challenge", challenge))
            }
            var signed: Event? = null
            for (attempt in 1..MAX_SIGN_ATTEMPTS) {
                signed = currentSigner.signBackgroundOnly(template)
                if (signed != null && signed.sig.isNotBlank()) break
                if (attempt < MAX_SIGN_ATTEMPTS) {
                    Log.d(TAG, "AUTH[$url] Background sign attempt $attempt/$MAX_SIGN_ATTEMPTS failed — retrying in ${SIGN_RETRY_DELAY_MS}ms")
                    kotlinx.coroutines.delay(SIGN_RETRY_DELAY_MS)
                }
            }
            if (signed == null || signed.sig.isBlank()) {
                Log.d(TAG, "AUTH[$url] Background sign unavailable after $MAX_SIGN_ATTEMPTS attempts — unauthenticated until reconnect")
                updateStatus(url, AuthStatus.FAILED)
                return
            }
            Log.d(TAG, "AUTH[$url] signed id=${signed.id.take(8)}")
            pendingAuthEventIds.add(signed.id)
            stateMachine.relayPool.sendToRelay(url, NostrProtocol.buildAuth(signed))
            Log.d(TAG, "AUTH[$url] response sent — waiting for OK")
        } catch (e: Exception) {
            Log.e(TAG, "AUTH[$url] Sign failed: ${e.message}")
            updateStatus(url, AuthStatus.FAILED)
        }
    }

    private fun handleOkMessage(url: String, eventId: String, success: Boolean, message: String) {
        if (eventId !in pendingAuthEventIds) return
        pendingAuthEventIds.remove(eventId)

        if (success) {
            Log.d(TAG, "AUTH[$url] ✓ authenticated")
            updateStatus(url, AuthStatus.AUTHENTICATED)
            stateMachine.relayPool.resubscribeRelay(url)
            // Replay any events that were rejected with auth-required
            val eventsToReplay = pendingReplayEvents.remove(url)
            if (!eventsToReplay.isNullOrEmpty()) {
                Log.d(TAG, "Replaying ${eventsToReplay.size} event(s) to $url after auth")
                for (event in eventsToReplay) {
                    stateMachine.relayPool.send(event, setOf(url))
                }
            }
        } else {
            Log.w(TAG, "AUTH[$url] ✗ rejected: $message")
            updateStatus(url, AuthStatus.FAILED)
            pendingReplayEvents.remove(url)
        }
    }

    /**
     * Track a recently published event so it can be replayed if a relay
     * rejects it with auth-required.
     */
    fun trackPublishedEvent(event: Event, relayUrls: Set<String>) {
        val now = System.currentTimeMillis()
        recentEvents[event.id] = event to now
        recentEvents.keys.removeAll { id ->
            recentEvents[id]?.let { now - it.second > RECENT_EVENT_TTL_MS } == true
        }
    }

    /**
     * Called when a relay responds with OK false + "auth-required" for a published event.
     * Queues the event for replay and forces a reconnect to trigger a fresh AUTH handshake.
     * This is the only case where we proactively reconnect for auth purposes.
     */
    fun onAuthRequiredPublishFailure(relayUrl: String, eventId: String) {
        // Only attempt re-auth for relays in the user's relay manager
        val normalizedUrl = social.mycelium.android.utils.normalizeRelayUrl(relayUrl)
        if (normalizedUrl !in allowedRelayUrls) {
            Log.d(TAG, "AUTH[$relayUrl] auth-required from non-managed relay — ignoring")
            return
        }
        val (event, _) = recentEvents[eventId] ?: return
        pendingReplayEvents.getOrPut(relayUrl) { mutableListOf() }.add(event)
        Log.d(TAG, "Queued event ${eventId.take(8)}… for replay on $relayUrl — forcing reconnect")
        val norm = social.mycelium.android.utils.normalizeRelayUrl(relayUrl)
        RelayLogBuffer.logDiagnostic(
            norm,
            "nip42",
            "publish rejected auth-required — queued replay; forceReconnect in ~2s if still not AUTHENTICATED",
        )
        // Wipe challenge state so the fresh connection gets a clean attempt
        resetRelayState(relayUrl)
        scope.launch {
            kotlinx.coroutines.delay(2000)
            val status = _authStatusByRelay.value[relayUrl]
            if (status != AuthStatus.AUTHENTICATED && status != AuthStatus.AUTHENTICATING) {
                stateMachine.relayPool.forceReconnect(relayUrl)
            }
        }
    }

    /**
     * Fully reset all auth state for a relay. Call when a relay is re-enabled
     * (e.g. from the sidebar or relay manager) so the next AUTH challenge from
     * that relay gets a completely clean handshake.
     */
    fun clearAuthStateForRelay(relayUrl: String) {
        resetRelayState(relayUrl)
        pendingReplayEvents.remove(relayUrl)
        updateStatus(relayUrl, AuthStatus.NONE)
        Log.d(TAG, "Cleared all auth state for $relayUrl")
    }

    private fun updateStatus(relayUrl: String, status: AuthStatus) {
        _authStatusByRelay.value = _authStatusByRelay.value + (relayUrl to status)
    }

    /** Clean up when no longer needed. */
    fun destroy() {
        stateMachine.relayPool.removeListener(listener)
        Log.d(TAG, "NIP-42 auth handler unregistered")
    }
}
