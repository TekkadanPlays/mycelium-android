package social.mycelium.android.relay

import android.util.Log
import com.example.cybin.core.eventTemplate
import com.example.cybin.core.nowUnixSeconds
import com.example.cybin.relay.NostrProtocol
import com.example.cybin.relay.RelayConnectionListener
import com.example.cybin.signer.NostrSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** Current signer — set after login, cleared on logout. */
    @Volatile
    private var signer: NostrSigner? = null

    /** Per-relay auth status for UI observation. */
    enum class AuthStatus { NONE, CHALLENGED, AUTHENTICATING, AUTHENTICATED, FAILED }

    private val _authStatusByRelay = MutableStateFlow<Map<String, AuthStatus>>(emptyMap())
    val authStatusByRelay: StateFlow<Map<String, AuthStatus>> = _authStatusByRelay.asStateFlow()

    /** Track which challenge strings we've already responded to (avoid infinite loops). */
    private val respondedChallenges = mutableSetOf<ChallengeKey>()

    /** Track event IDs we sent for auth so we can match OK responses. */
    private val pendingAuthEventIds = mutableSetOf<String>()

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
            updateStatus(url, AuthStatus.NONE)
        }

        override fun onDisconnected(url: String) {
            respondedChallenges.removeAll { it.relayUrl == url }
        }
    }

    init {
        stateMachine.relayPool.addListener(listener)
        Log.d(TAG, "NIP-42 auth handler registered")
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
        }
    }

    /** Rate-limit "no signer" warnings: last log time per relay URL. */
    private val lastNoSignerLogMs = mutableMapOf<String, Long>()
    private val NO_SIGNER_LOG_INTERVAL_MS = 30_000L

    private fun handleAuthChallenge(url: String, challenge: String) {
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
            return
        }

        Log.d(TAG, "AUTH challenge from $url: ${challenge.take(16)}…")
        updateStatus(url, AuthStatus.CHALLENGED)

        updateStatus(url, AuthStatus.AUTHENTICATING)

        scope.launch {
            try {
                // Build kind-22242 auth event (NIP-42) using Cybin
                val template = eventTemplate(22242, "", nowUnixSeconds()) {
                    add(arrayOf("relay", url))
                    add(arrayOf("challenge", challenge))
                }
                val signed = currentSigner.sign(template)
                val authMsg = NostrProtocol.buildAuth(signed)

                pendingAuthEventIds.add(signed.id)
                stateMachine.relayPool.sendToRelay(url, authMsg)
                Log.d(TAG, "AUTH response sent to $url (event ${signed.id.take(8)}…)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sign/send AUTH for $url: ${e.message}", e)
                updateStatus(url, AuthStatus.FAILED)
                respondedChallenges.remove(key)
            }
        }
    }

    private fun handleOkMessage(url: String, eventId: String, success: Boolean, message: String) {
        if (eventId !in pendingAuthEventIds) return

        pendingAuthEventIds.remove(eventId)

        if (success) {
            Log.d(TAG, "AUTH successful for $url")
            updateStatus(url, AuthStatus.AUTHENTICATED)
            // Renew filters so subscriptions resume after auth
            stateMachine.relayPool.renewFilters(url)
        } else {
            Log.w(TAG, "AUTH rejected by $url: $message")
            updateStatus(url, AuthStatus.FAILED)
        }
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
