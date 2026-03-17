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
 * NIP-42 relay authentication handler.
 *
 * Subscribes to the shared [CybinRelayPool] as a [RelayConnectionListener], intercepts AUTH
 * challenge messages from relays, signs kind-22242 events via the current [NostrSigner]
 * (Amber external signer), and sends the AUTH response back. Tracks per-relay auth status
 * so the UI can show which relays required authentication and whether it succeeded.
 *
 * ## Flow
 * 1. Relay sends `["AUTH", "<challenge>"]`
 * 2. We build a kind-22242 event template with the relay URL + challenge
 * 3. We sign it via the Amber signer (background ContentProvider — no UI prompt for pre-approved kinds)
 * 4. We send `["AUTH", <signed_event>]` back to the relay
 * 5. Relay responds with `["OK", "<event_id>", true/false, "..."]`
 * 6. On success we ask the pool to renew filters on that relay so subscriptions resume
 */
class Nip42AuthHandler(
    private val stateMachine: RelayConnectionStateMachine
) {
    companion object {
        private const val TAG = "Nip42Auth"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Sequential signing queue. AUTH challenges are sent here and processed one at a time,
     * so at most one Amber foreground prompt is shown at a time (matching Amethyst's UX).
     */
    private data class AuthJob(val url: String, val challenge: String, val key: ChallengeKey, val signer: NostrSigner)
    private val authQueue = Channel<AuthJob>(Channel.UNLIMITED)

    /** Current signer — set after login, cleared on logout. */
    @Volatile
    private var signer: NostrSigner? = null

    /** Timestamp (ms) of the last successful foreground Amber sign.
     *  Suppresses foreground retry for FOREGROUND_COOLDOWN_MS after the last success
     *  to prevent repeated Amber UI flashing, while still allowing periodic retry
     *  in case background permission lapses. */
    @Volatile
    private var lastForegroundSignMs = 0L
    private val FOREGROUND_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes

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

    /** Per-relay auth status for UI observation. */
    enum class AuthStatus { NONE, CHALLENGED, AUTHENTICATING, AUTHENTICATED, FAILED }

    private val _authStatusByRelay = MutableStateFlow<Map<String, AuthStatus>>(emptyMap())
    val authStatusByRelay: StateFlow<Map<String, AuthStatus>> = _authStatusByRelay.asStateFlow()

    /** Track which challenge strings we've already responded to (avoid infinite loops). */
    private val respondedChallenges = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<ChallengeKey, Boolean>())

    /** Track event IDs we sent for auth so we can match OK responses. */
    private val pendingAuthEventIds = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

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
            respondedChallenges.removeAll { it.relayUrl == url }
            authFailCooldownMs.remove(url)
            updateStatus(url, AuthStatus.NONE)
        }

        override fun onDisconnected(url: String) {
            respondedChallenges.removeAll { it.relayUrl == url }
            authFailCooldownMs.remove(url)
        }
    }

    init {
        stateMachine.relayPool.addListener(listener)
        Log.d(TAG, "NIP-42 auth handler registered")

        // Process AUTH signing jobs sequentially — one Amber interaction at a time
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
            lastForegroundSignMs = 0L
        }
    }

    /** Rate-limit "no signer" warnings: last log time per relay URL. */
    private val lastNoSignerLogMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val NO_SIGNER_LOG_INTERVAL_MS = 30_000L

    /** Per-relay cooldown after a failed AUTH attempt to avoid spamming Amber. */
    private val authFailCooldownMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val AUTH_FAIL_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes

    private fun handleAuthChallenge(url: String, challenge: String) {
        // Only authenticate with relays the user has configured in their relay manager.
        // Random relays encountered via outbox or other discovery should not get our identity.
        val normalizedUrl = social.mycelium.android.utils.normalizeRelayUrl(url)
        if (allowedRelayUrls.isNotEmpty() && normalizedUrl !in allowedRelayUrls) {
            Log.d(TAG, "AUTH[$url] Relay not in user's relay manager — ignoring challenge")
            return
        }

        // Dedup FIRST — prevents processing the same challenge regardless of signer state
        val key = ChallengeKey(url, challenge)
        if (key in respondedChallenges) return
        respondedChallenges.add(key)

        val currentSigner = signer

        if (currentSigner == null) {
            // Rate-limit log spam from relays that send AUTH repeatedly
            val now = System.currentTimeMillis()
            val lastLog = lastNoSignerLogMs[url] ?: 0L
            if (now - lastLog > NO_SIGNER_LOG_INTERVAL_MS) {
                Log.w(TAG, "No signer available — cannot respond to AUTH from $url")
                lastNoSignerLogMs[url] = now
            }
            updateStatus(url, AuthStatus.FAILED)
            // Remove so re-challenge can be processed once signer becomes available
            respondedChallenges.remove(key)
            return
        }

        // Per-relay cooldown: don't retry AUTH if we recently failed for this relay
        val now = System.currentTimeMillis()
        val cooldownUntil = authFailCooldownMs[url] ?: 0L
        if (now < cooldownUntil) {
            Log.d(TAG, "AUTH cooldown active for $url (${(cooldownUntil - now) / 1000}s remaining)")
            // Remove so the challenge can be re-processed after cooldown expires
            respondedChallenges.remove(key)
            return
        }

        Log.d(TAG, "AUTH challenge from $url: ${challenge.take(16)}…")
        updateStatus(url, AuthStatus.CHALLENGED)

        updateStatus(url, AuthStatus.AUTHENTICATING)

        // Queue for sequential processing (one Amber prompt at a time)
        authQueue.trySend(AuthJob(url, challenge, key, currentSigner))
    }

    /**
     * Process a single AUTH job. Called sequentially from the authQueue consumer.
     * Uses signer.sign() which tries background (ContentProvider) first, then
     * foreground (Amber activity) — matching Amethyst's approach.
     */
    private suspend fun processAuthJob(job: AuthJob) {
        val (url, challenge, key, currentSigner) = job
        try {
            Log.d(TAG, "AUTH[$url] signer=${currentSigner::class.simpleName}")
            val template = eventTemplate(22242, "", nowUnixSeconds()) {
                add(arrayOf("relay", url))
                add(arrayOf("challenge", challenge))
            }
            // Try background (ContentProvider) first — no Amber UI popup.
            // If background fails, fall back to foreground sign() if the cooldown
            // has elapsed. This prevents repeated Amber flashing while allowing
            // periodic retry when background permission lapses.
            var signed = currentSigner.signBackgroundOnly(template)
            if (signed == null || signed.sig.isBlank()) {
                val now = System.currentTimeMillis()
                val elapsed = now - lastForegroundSignMs
                if (lastForegroundSignMs > 0 && elapsed < FOREGROUND_COOLDOWN_MS) {
                    // Recently did foreground sign — suppress to avoid Amber flash spam.
                    Log.d(TAG, "AUTH[$url] Background sign failed, foreground cooldown ${(FOREGROUND_COOLDOWN_MS - elapsed) / 1000}s remaining — skipping")
                    updateStatus(url, AuthStatus.FAILED)
                    respondedChallenges.remove(key)
                    return
                }
                Log.d(TAG, "AUTH[$url] Background sign failed, trying foreground approval")
                try {
                    signed = currentSigner.sign(template)
                    if (signed != null && signed.sig.isNotBlank()) {
                        lastForegroundSignMs = System.currentTimeMillis()
                        Log.d(TAG, "AUTH[$url] Foreground approval granted — suppressing foreground for ${FOREGROUND_COOLDOWN_MS / 1000}s")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AUTH[$url] Foreground sign failed: ${e.message}")
                    lastForegroundSignMs = System.currentTimeMillis() // Cooldown even on failure to prevent spam
                }
                if (signed == null || signed.sig.isBlank()) {
                    Log.w(TAG, "AUTH[$url] Signing unavailable — skipping")
                    updateStatus(url, AuthStatus.FAILED)
                    respondedChallenges.remove(key)
                    return
                }
            }
            Log.d(TAG, "AUTH[$url] signed id=${signed.id.take(8)} pubKey=${signed.pubKey.take(16)}")
            val authMsg = NostrProtocol.buildAuth(signed)

            pendingAuthEventIds.add(signed.id)
            stateMachine.relayPool.sendToRelay(url, authMsg)
            authFailCooldownMs.remove(url)
            Log.d(TAG, "AUTH[$url] response sent (event ${signed.id.take(8)}\u2026) \u2014 waiting for OK")
        } catch (e: Exception) {
            Log.e(TAG, "AUTH[$url] Failed: ${e.message}", e)
            updateStatus(url, AuthStatus.FAILED)
            authFailCooldownMs[url] = System.currentTimeMillis() + AUTH_FAIL_COOLDOWN_MS
            respondedChallenges.remove(key)
        }
    }

    private fun handleOkMessage(url: String, eventId: String, success: Boolean, message: String) {
        Log.d(TAG, "OK[$url] eventId=${eventId.take(8)} success=$success msg='$message' isPendingAuth=${eventId in pendingAuthEventIds}")
        if (eventId !in pendingAuthEventIds) return

        pendingAuthEventIds.remove(eventId)

        if (success) {
            Log.d(TAG, "AUTH successful for $url")
            updateStatus(url, AuthStatus.AUTHENTICATED)
            // Re-apply subscriptions: CLOSED auth-required removes subs from
            // the connection's activeSubscriptions, so renewFilters finds nothing.
            // resubscribeRelay looks up pool-level subscriptions and re-sends REQs.
            stateMachine.relayPool.resubscribeRelay(url)
            // Replay any events that failed due to auth-required on this relay
            val eventsToReplay = pendingReplayEvents.remove(url)
            if (!eventsToReplay.isNullOrEmpty()) {
                Log.d(TAG, "Replaying ${eventsToReplay.size} event(s) to $url after auth")
                for (event in eventsToReplay) {
                    stateMachine.relayPool.send(event, setOf(url))
                }
            }
        } else {
            Log.w(TAG, "AUTH rejected by $url: $message")
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
        // Prune expired entries (ConcurrentHashMap iterator is weakly consistent — safe)
        recentEvents.keys.removeAll { id -> recentEvents[id]?.let { now - it.second > RECENT_EVENT_TTL_MS } == true }
    }

    /**
     * Called when a relay responds with OK false + "auth-required" for a published event.
     * Queues the event for replay after successful authentication and clears
     * any auth cooldown so the next AUTH challenge from this relay will be processed.
     */
    fun onAuthRequiredPublishFailure(relayUrl: String, eventId: String) {
        val (event, _) = recentEvents[eventId] ?: return
        pendingReplayEvents.getOrPut(relayUrl) { mutableListOf() }.add(event)
        Log.d(TAG, "Queued event ${eventId.take(8)}… for replay on $relayUrl after auth")
        // Clear cooldown + consumed challenges so the next AUTH from this relay is processed
        authFailCooldownMs.remove(relayUrl)
        respondedChallenges.removeAll { it.relayUrl == relayUrl }
        // If the relay doesn't send a fresh AUTH challenge spontaneously, force a
        // reconnect after a short delay to trigger a new AUTH handshake.
        scope.launch {
            kotlinx.coroutines.delay(2000)
            val status = _authStatusByRelay.value[relayUrl]
            if (status != AuthStatus.AUTHENTICATED && status != AuthStatus.AUTHENTICATING) {
                Log.d(TAG, "No fresh AUTH from $relayUrl after publish failure — forcing reconnect")
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
        respondedChallenges.removeAll { it.relayUrl == relayUrl }
        authFailCooldownMs.remove(relayUrl)
        pendingAuthEventIds.removeAll { false } // keep all; no per-relay ID tracking needed
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
