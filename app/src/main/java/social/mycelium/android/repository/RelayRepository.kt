package social.mycelium.android.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import social.mycelium.android.data.UserRelay
import social.mycelium.android.data.RelayInformation
import social.mycelium.android.data.RelayConnectionStatus
import social.mycelium.android.data.RelayHealth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import social.mycelium.android.cache.Nip11CacheManager
import social.mycelium.android.network.MyceliumHttpClient
import social.mycelium.android.relay.RelayConnectionStateMachine
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import java.util.concurrent.atomic.AtomicReference

/**
 * Repository for managing user relays and NIP-11 information
 */
class RelayRepository(private val context: Context) {
    companion object {
        private const val TAG = "RelayRepository"
        private const val RELAYS_PREFS = "user_relays"
        private const val RELAYS_KEY = "relays_list"
        private val JSON = Json { ignoreUnknownKeys = true }
    }

    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(RELAYS_PREFS, Context.MODE_PRIVATE)
    private val httpClient = MyceliumHttpClient.instance
    private val nip11Cache = Nip11CacheManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })

    // StateFlow for reactive UI updates
    private val _relays = MutableStateFlow<List<UserRelay>>(emptyList())
    val relays: StateFlow<List<UserRelay>> = _relays.asStateFlow()

    // Deprecated: live connection status comes from RelayConnectionStateMachine.perRelayState.
    // This flow is only updated by testRelayConnection() manual probes.
    @Deprecated("Use RelayConnectionStateMachine.perRelayState for live status", level = DeprecationLevel.WARNING)
    private val _connectionStatus = MutableStateFlow<Map<String, RelayConnectionStatus>>(emptyMap())
    @Deprecated("Use RelayConnectionStateMachine.perRelayState for live status", level = DeprecationLevel.WARNING)
    val connectionStatus: StateFlow<Map<String, RelayConnectionStatus>> = _connectionStatus.asStateFlow()

    init {
        loadRelaysFromStorage()
        // Preload NIP-11 info for existing relays
        preloadRelayInfo()
    }

    /**
     * Add a new relay to the user's list
     */
    suspend fun addRelay(url: String, read: Boolean = true, write: Boolean = true): Result<UserRelay> = withContext(Dispatchers.IO) {
        try {
            // Normalize URL
            val normalizedUrl = normalizeRelayUrl(url)

            // Check if relay already exists
            val existingRelays = _relays.value
            if (existingRelays.any { it.url == normalizedUrl }) {
                return@withContext Result.failure(Exception("Relay already exists"))
            }

            // Get cached NIP-11 info immediately (returns null if not cached)
            val cachedInfo = nip11Cache.getCachedRelayInfo(normalizedUrl)

            // Create new relay with cached/empty NIP-11 info
            val newRelay = UserRelay(
                url = normalizedUrl,
                read = read,
                write = write,
                addedAt = System.currentTimeMillis(),
                info = cachedInfo,
                isOnline = false,
                lastChecked = System.currentTimeMillis()
            )

            // Add to list immediately for fast UI response
            val updatedRelays = existingRelays + newRelay
            _relays.value = updatedRelays

            // Persist to storage
            saveRelaysToStorage(updatedRelays)

            Log.d(TAG, "✅ Added relay: $normalizedUrl (loading NIP-11 info)")

            // Fetch fresh NIP-11 information immediately in background
            scope.launch {
                try {
                    val freshInfo = nip11Cache.getRelayInfo(normalizedUrl, forceRefresh = true)
                    if (freshInfo != null) {
                        val currentRelays = _relays.value
                        val relayIndex = currentRelays.indexOfFirst { it.url == normalizedUrl }
                        if (relayIndex != -1) {
                            val updatedRelay = currentRelays[relayIndex].copy(
                                info = freshInfo,
                                isOnline = true,
                                lastChecked = System.currentTimeMillis()
                            )
                            val updatedList = currentRelays.toMutableList().apply {
                                set(relayIndex, updatedRelay)
                            }
                            _relays.value = updatedList
                            saveRelaysToStorage(updatedList)
                            Log.d(TAG, "✅ Updated relay with fresh NIP-11 info: $normalizedUrl")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Failed to fetch NIP-11 info for $normalizedUrl: ${e.message}")
                }
            }

            Result.success(newRelay)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to add relay: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Remove a relay from the user's list
     */
    suspend fun removeRelay(url: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val existingRelays = _relays.value
            val updatedRelays = existingRelays.filter { it.url != url }

            if (updatedRelays.size == existingRelays.size) {
                return@withContext Result.failure(Exception("Relay not found"))
            }

            _relays.value = updatedRelays
            saveRelaysToStorage(updatedRelays)

            Log.d(TAG, "🗑️ Removed relay: $url")
            Result.success(true)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to remove relay: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Update relay settings (read/write permissions)
     */
    suspend fun updateRelaySettings(url: String, read: Boolean, write: Boolean): Result<UserRelay> = withContext(Dispatchers.IO) {
        try {
            val existingRelays = _relays.value
            val relayIndex = existingRelays.indexOfFirst { it.url == url }

            if (relayIndex == -1) {
                return@withContext Result.failure(Exception("Relay not found"))
            }

            val updatedRelay = existingRelays[relayIndex].copy(read = read, write = write)
            val updatedRelays = existingRelays.toMutableList().apply {
                set(relayIndex, updatedRelay)
            }

            _relays.value = updatedRelays
            saveRelaysToStorage(updatedRelays)

            Log.d(TAG, "✅ Updated relay settings: $url (read=$read, write=$write)")
            Result.success(updatedRelay)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to update relay settings: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Refresh relay information (NIP-11)
     */
    suspend fun refreshRelayInfo(url: String): Result<UserRelay> = withContext(Dispatchers.IO) {
        try {
            val existingRelays = _relays.value
            val relayIndex = existingRelays.indexOfFirst { it.url == url }

            if (relayIndex == -1) {
                return@withContext Result.failure(Exception("Relay not found"))
            }

            val relay = existingRelays[relayIndex]

            // Force refresh from NIP-11 cache
            val freshInfo = try {
                nip11Cache.getRelayInfo(url, forceRefresh = true)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Failed to force refresh $url: ${e.message}")
                null
            }

            val updatedRelay = relay.copy(
                info = freshInfo ?: relay.info,
                isOnline = freshInfo != null,
                lastChecked = System.currentTimeMillis()
            )

            val updatedRelays = existingRelays.toMutableList().apply {
                set(relayIndex, updatedRelay)
            }

            _relays.value = updatedRelays
            saveRelaysToStorage(updatedRelays)

            Log.d(TAG, "🔄 Refreshed relay info: $url")
            Result.success(updatedRelay)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to refresh relay info: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Test relay connection
     */
    suspend fun testRelayConnection(url: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Update connection status to connecting
            updateConnectionStatus(url, RelayConnectionStatus.CONNECTING)

            // Convert WebSocket URL to HTTP for NIP-11 check
            val httpUrl = url.replace("wss://", "https://").replace("ws://", "http://")

            val response = httpClient.get(httpUrl) {
                header("Accept", "application/nostr+json")
            }

            val isConnected = response.status.isSuccess()
            updateConnectionStatus(url, if (isConnected) RelayConnectionStatus.CONNECTED else RelayConnectionStatus.ERROR)

            Log.d(TAG, "🔍 Tested relay connection: $url -> $isConnected")
            Result.success(isConnected)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to test relay connection: ${e.message}", e)
            updateConnectionStatus(url, RelayConnectionStatus.ERROR)
            Result.failure(e)
        }
    }

    /**
     * Get relay health status
     */
    fun getRelayHealth(relay: UserRelay): RelayHealth {
        return when {
            !relay.isOnline -> RelayHealth.CRITICAL
            relay.lastChecked == 0L -> RelayHealth.UNKNOWN
            System.currentTimeMillis() - relay.lastChecked > 300000 -> RelayHealth.WARNING // 5 minutes
            else -> RelayHealth.HEALTHY
        }
    }

    /**
     * Get NIP-11 cache manager for external access
     */
    fun getNip11Cache(): Nip11CacheManager = nip11Cache

    /**
     * Preload NIP-11 information for existing relays
     */
    private fun preloadRelayInfo() {
        scope.launch {
            try {
                // Small delay to let the app initialize
                delay(2000)

                // Preload relay info for current relays
                val currentRelays = _relays.value
                Log.d(TAG, "📦 Preloading NIP-11 info for ${currentRelays.size} relays")

                currentRelays.forEach { relay ->
                    try {
                        nip11Cache.getRelayInfo(relay.url)
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Failed to preload ${relay.url}: ${e.message}")
                    }
                }

                Log.d(TAG, "🎉 NIP-11 preload completed")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Preload failed: ${e.message}", e)
            }
        }
    }

    /**
     * Normalize relay URL — delegates to shared utility.
     */
    private fun normalizeRelayUrl(url: String): String =
        social.mycelium.android.utils.normalizeRelayUrl(url)

    /**
     * Update connection status for a relay
     */
    private fun updateConnectionStatus(url: String, status: RelayConnectionStatus) {
        val currentStatus = _connectionStatus.value.toMutableMap()
        currentStatus[url] = status
        _connectionStatus.value = currentStatus
    }

    /**
     * Load relays from persistent storage
     */
    private fun loadRelaysFromStorage() {
        try {
            val relaysJson = sharedPrefs.getString(RELAYS_KEY, null)
            if (relaysJson != null) {
                val relays = JSON.decodeFromString<List<UserRelay>>(relaysJson)
                _relays.value = relays
                Log.d(TAG, "💾 Loaded ${relays.size} relays from storage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load relays from storage: ${e.message}", e)
        }
    }

    /**
     * Save relays to persistent storage
     */
    private fun saveRelaysToStorage(relays: List<UserRelay>) {
        try {
            val relaysJson = JSON.encodeToString(relays)
            sharedPrefs.edit()
                .putString(RELAYS_KEY, relaysJson)
                .apply()
            Log.d(TAG, "💾 Saved ${relays.size} relays to storage")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save relays to storage: ${e.message}", e)
        }
    }

    /**
     * Fetch user's relay list from the network using NIP-65 (kind 10002).
     * Uses indexer relays from RelayStorageManager for the given pubkey.
     */
    suspend fun fetchUserRelayList(pubkey: String): Result<List<UserRelay>> = withContext(Dispatchers.IO) {
        try {
            val storageManager = RelayStorageManager(context)
            val cacheUrls = storageManager.loadIndexerRelays(pubkey).map { it.url }
            if (cacheUrls.isEmpty()) {
                Log.d(TAG, "📡 No indexer relays for pubkey ${pubkey.take(8)}..., skipping NIP-65 fetch")
                return@withContext Result.success(emptyList())
            }
            Log.d(TAG, "📡 Fetching NIP-65 relay list for pubkey: ${pubkey.take(8)}... from ${cacheUrls.size} indexer relays")
            val filter = Filter(
                kinds = listOf(10002),
                authors = listOf(pubkey),
                limit = 1
            )
            val latestEventRef = AtomicReference<Event?>(null)
            val lastEventAt = java.util.concurrent.atomic.AtomicLong(0)
            val stateMachine = RelayConnectionStateMachine.getInstance()
            val handle = stateMachine.requestTemporarySubscription(cacheUrls, filter, priority = SubscriptionPriority.BACKGROUND) { event ->
                if (event.kind == 10002) {
                    latestEventRef.getAndUpdate { current ->
                        if (current == null || event.createdAt > current.createdAt) event else current
                    }
                    lastEventAt.set(System.currentTimeMillis())
                }
            }
            // Settle-based wait: break early when stream goes quiet (1s no new events)
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                delay(200)
                val lastAt = lastEventAt.get()
                if (lastAt > 0) {
                    val quietMs = System.currentTimeMillis() - lastAt
                    if (quietMs >= 1_000L) break
                }
            }
            handle.cancel()
            val event = latestEventRef.get()
            if (event == null) {
                Log.d(TAG, "📡 No kind 10002 event found for pubkey ${pubkey.take(8)}...")
                return@withContext Result.success(emptyList())
            }
            val relays = parseNip65RelayTags(event)
            Log.d(TAG, "📡 NIP-65 parsed ${relays.size} relays for pubkey ${pubkey.take(8)}...")
            Result.success(relays)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to fetch relay list: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Parse NIP-65 "r" tags: ["r", url] or ["r", url, "read"] or ["r", url, "write"]. No marker = both read and write. */
    private fun parseNip65RelayTags(event: Event): List<UserRelay> {
        val result = mutableListOf<UserRelay>()
        for (tag in event.tags) {
            val list = tag.toList()
            if (list.isEmpty() || list[0] != "r") continue
            val url = list.getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
            val normalizedUrl = normalizeRelayUrl(url)
            val marker = list.getOrNull(2)?.lowercase()
            val read = marker != "write"
            val write = marker != "read"
            result.add(
                UserRelay(
                    url = normalizedUrl,
                    read = read,
                    write = write
                )
            )
        }
        return result
    }

    /**
     * Clear all relays
     */
    suspend fun clearAllRelays(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            _relays.value = emptyList()
            _connectionStatus.value = emptyMap()
            sharedPrefs.edit().clear().apply()
            Log.d(TAG, "🧹 Cleared all relays")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear relays: ${e.message}", e)
            Result.failure(e)
        }
    }
}
