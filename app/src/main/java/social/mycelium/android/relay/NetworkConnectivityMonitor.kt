package social.mycelium.android.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import social.mycelium.android.debug.MLog
import social.mycelium.android.BuildConfig
import social.mycelium.android.debug.DebugVerboseLog

/**
 * Monitors network connectivity changes and triggers relay reconnection when
 * the device regains network access (e.g. WiFi→cellular switch, airplane mode off).
 *
 * WebSocket connections die silently on network changes; this ensures the relay
 * state machine re-applies the subscription promptly instead of waiting for the
 * keepalive timer or the next ON_RESUME.
 *
 * Usage: call [start] once from MainActivity.onCreate and [stop] from onDestroy.
 */
class NetworkConnectivityMonitor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkConnectivityMon"
        /** Debounce: ignore rapid network flaps within this window. */
        private const val DEBOUNCE_MS = 3_000L
    }

    @Volatile private var lastReconnectAtMs: Long = 0L
    @Volatile private var isRegistered = false
    @Volatile private var hadNetwork = true

    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val now = System.currentTimeMillis()
            if (!hadNetwork && now - lastReconnectAtMs > DEBOUNCE_MS) {
                lastReconnectAtMs = now
                MLog.i(TAG, "Network available after loss — granting amnesty and triggering relay reconnect")
                if (BuildConfig.DEBUG) {
                    DebugVerboseLog.record(DebugVerboseLog.Layer.NETWORK, TAG, "onAvailable after loss → reconnect")
                }
                onNetworkRegained()
            }
            hadNetwork = true
        }

        override fun onLost(network: Network) {
            // Check if we still have any network via the active network
            val active = connectivityManager.activeNetwork
            if (active == null) {
                hadNetwork = false
                MLog.d(TAG, "All networks lost")
                if (BuildConfig.DEBUG) {
                    DebugVerboseLog.record(DebugVerboseLog.Layer.NETWORK, TAG, "onLost: no active network")
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (hasInternet && !hadNetwork) {
                val now = System.currentTimeMillis()
                if (now - lastReconnectAtMs > DEBOUNCE_MS) {
                    lastReconnectAtMs = now
                    MLog.i(TAG, "Network capabilities restored — granting amnesty and triggering relay reconnect")
                    if (BuildConfig.DEBUG) {
                        DebugVerboseLog.record(DebugVerboseLog.Layer.NETWORK, TAG, "capabilities restored → reconnect")
                    }
                    onNetworkRegained()
                }
                hadNetwork = true
            }
        }
    }

    /**
     * Called when the device regains network after a loss period.
     * Clears all penalty state accumulated while offline, then reconnects.
     *
     * Order matters:
     * 1. RelayHealthTracker amnesty — clears consecutive failures, flags, auto-blocks
     * 2. CybinRelayPool reconnect state — clears session blacklist + attempt counters + cooldowns
     * 3. State machine retry counter reset
     * 4. requestReconnectOnResume — re-applies subscriptions (now with all relays unblocked)
     */
    private fun onNetworkRegained() {
        val rcsm = RelayConnectionStateMachine.getInstance()
        // 1. Clear health tracker penalties (consecutive failures, flags, auto-blocks)
        RelayHealthTracker.grantOfflineAmnesty()
        // 2. Clear pool-level reconnect state (session blacklist, attempt counters, cooldowns)
        rcsm.relayPool.clearReconnectState()
        // 3. Reset keepalive timer so stale-connection check doesn't fire immediately
        rcsm.markEventReceived()
        // 4. Re-apply subscriptions — filterBlocked() now returns all relays
        rcsm.requestReconnectOnResume()
    }

    fun start() {
        if (isRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isRegistered = true
            MLog.d(TAG, "Network connectivity monitor started")
        } catch (e: Exception) {
            MLog.e(TAG, "Failed to register network callback: ${e.message}", e)
        }
    }

    fun stop() {
        if (!isRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isRegistered = false
            MLog.d(TAG, "Network connectivity monitor stopped")
        } catch (e: Exception) {
            MLog.e(TAG, "Failed to unregister network callback: ${e.message}", e)
        }
    }
}
