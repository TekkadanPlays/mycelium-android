package social.mycelium.android.service

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import social.mycelium.android.data.UserRelay
import social.mycelium.android.data.RelayConnectionStatus
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * Manages WebSocket connections to Nostr relays
 * Based on RelayTools architecture but simplified for Mycelium
 */
class RelayConnectionManager {
    companion object {
        private const val TAG = "RelayConnectionManager"
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    // Active WebSocket connections
    private val activeConnections = mutableMapOf<String, WebSocket>()
    
    // Connection status for each relay
    private val _connectionStatus = MutableStateFlow<Map<String, RelayConnectionStatus>>(emptyMap())
    val connectionStatus: StateFlow<Map<String, RelayConnectionStatus>> = _connectionStatus.asStateFlow()
    
    // Coroutine scope for connection management
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Connect to a relay
     */
    suspend fun connectToRelay(relay: UserRelay): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = relay.url
            Log.d(TAG, "🔌 Connecting to relay: $url")
            
            // Update status to connecting
            updateConnectionStatus(url, RelayConnectionStatus.CONNECTING)
            
            // Check if already connected
            if (activeConnections.containsKey(url)) {
                Log.d(TAG, "♻️ Already connected to $url")
                updateConnectionStatus(url, RelayConnectionStatus.CONNECTED)
                return@withContext Result.success(true)
            }
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            val webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "✅ Connected to $url")
                    updateConnectionStatus(url, RelayConnectionStatus.CONNECTED)
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.v(TAG, "📨 Message from $url: ${text.take(100)}...")
                    // TODO: Handle incoming messages (events, notices, etc.)
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "🔌 Closing connection to $url: $code $reason")
                    webSocket.close(1000, null)
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "❌ Closed connection to $url: $code $reason")
                    activeConnections.remove(url)
                    updateConnectionStatus(url, RelayConnectionStatus.DISCONNECTED)
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "❌ Failed to connect to $url: ${t.message}", t)
                    activeConnections.remove(url)
                    updateConnectionStatus(url, RelayConnectionStatus.ERROR)
                }
            })
            
            activeConnections[url] = webSocket
            Result.success(true)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception connecting to ${relay.url}: ${e.message}", e)
            updateConnectionStatus(relay.url, RelayConnectionStatus.ERROR)
            Result.failure(e)
        }
    }
    
    /**
     * Disconnect from a relay
     */
    suspend fun disconnectFromRelay(url: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val webSocket = activeConnections[url]
            if (webSocket != null) {
                Log.d(TAG, "🔌 Disconnecting from $url")
                webSocket.close(1000, "User requested disconnect")
                activeConnections.remove(url)
                updateConnectionStatus(url, RelayConnectionStatus.DISCONNECTED)
                Result.success(true)
            } else {
                Log.d(TAG, "⚠️ Not connected to $url")
                Result.success(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception disconnecting from $url: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Disconnect from all relays
     */
    suspend fun disconnectFromAllRelays(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val urls = activeConnections.keys.toList()
            Log.d(TAG, "🔌 Disconnecting from ${urls.size} relays")
            
            urls.forEach { url ->
                disconnectFromRelay(url)
            }
            
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception disconnecting from all relays: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Send a message to a relay
     */
    suspend fun sendMessage(url: String, message: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val webSocket = activeConnections[url]
            if (webSocket != null) {
                val success = webSocket.send(message)
                if (success) {
                    Log.d(TAG, "📤 Sent message to $url: ${message.take(100)}...")
                    Result.success(true)
                } else {
                    Log.e(TAG, "❌ Failed to send message to $url")
                    Result.failure(Exception("Failed to send message"))
                }
            } else {
                Log.e(TAG, "❌ Not connected to $url")
                Result.failure(Exception("Not connected to relay"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception sending message to $url: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Check if connected to a relay
     */
    fun isConnectedTo(url: String): Boolean {
        return activeConnections.containsKey(url) && 
               _connectionStatus.value[url] == RelayConnectionStatus.CONNECTED
    }
    
    /**
     * Get list of connected relay URLs
     */
    fun getConnectedRelays(): List<String> {
        return activeConnections.keys.filter { url ->
            _connectionStatus.value[url] == RelayConnectionStatus.CONNECTED
        }
    }
    
    /**
     * Update connection status for a relay
     */
    private fun updateConnectionStatus(url: String, status: RelayConnectionStatus) {
        val currentStatus = _connectionStatus.value.toMutableMap()
        currentStatus[url] = status
        _connectionStatus.value = currentStatus
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        connectionScope.launch {
            disconnectFromAllRelays()
        }
        connectionScope.cancel()
    }
}
