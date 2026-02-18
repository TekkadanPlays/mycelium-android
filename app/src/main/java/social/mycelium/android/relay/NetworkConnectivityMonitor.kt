package social.mycelium.android.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

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
                Log.i(TAG, "Network available after loss — triggering relay reconnect")
                RelayConnectionStateMachine.getInstance().requestReconnectOnResume()
                RelayConnectionStateMachine.getInstance().markEventReceived() // reset keepalive timer
            }
            hadNetwork = true
        }

        override fun onLost(network: Network) {
            // Check if we still have any network via the active network
            val active = connectivityManager.activeNetwork
            if (active == null) {
                hadNetwork = false
                Log.d(TAG, "All networks lost")
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (hasInternet && !hadNetwork) {
                val now = System.currentTimeMillis()
                if (now - lastReconnectAtMs > DEBOUNCE_MS) {
                    lastReconnectAtMs = now
                    Log.i(TAG, "Network capabilities restored — triggering relay reconnect")
                    RelayConnectionStateMachine.getInstance().requestReconnectOnResume()
                    RelayConnectionStateMachine.getInstance().markEventReceived()
                }
                hadNetwork = true
            }
        }
    }

    fun start() {
        if (isRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isRegistered = true
            Log.d(TAG, "Network connectivity monitor started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}", e)
        }
    }

    fun stop() {
        if (!isRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isRegistered = false
            Log.d(TAG, "Network connectivity monitor stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback: ${e.message}", e)
        }
    }
}
