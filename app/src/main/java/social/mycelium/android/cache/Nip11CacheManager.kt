package social.mycelium.android.cache

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import social.mycelium.android.data.RelayInformation
import social.mycelium.android.db.AppDatabase
import social.mycelium.android.db.CachedNip11Entity
import social.mycelium.android.db.Nip11Dao
import social.mycelium.android.utils.MemoryUtils

/**
 * Manages persistent caching of NIP-11 relay information with 24-hour expiration.
 * Persistence: Room SQLite (scales to thousands of relays).
 * Hot reads: in-memory ConcurrentHashMap (O(1) per orb).
 * Use getInstance(context) for process-wide singleton.
 */
class Nip11CacheManager(private val context: Context) {
    companion object {
        private const val TAG = "Nip11CacheManager"
        private const val CACHE_EXPIRY_HOURS = 24
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        // Legacy SharedPreferences keys (for one-time migration)
        private const val LEGACY_PREFS = "nip11_cache"
        private const val LEGACY_DATA_KEY = "cache_data"
        private const val LEGACY_TIMESTAMPS_KEY = "cache_timestamps"
        private const val LEGACY_MIGRATED_KEY = "migrated_to_room"

        @Volatile
        private var instance: Nip11CacheManager? = null

        /** Process-wide singleton using application context. Use for trim coordinator. */
        fun getInstance(context: Context): Nip11CacheManager =
            instance ?: synchronized(this) {
                instance ?: Nip11CacheManager(context.applicationContext).also { instance = it }
            }

        /** Return the already-initialized singleton, or null if not yet created.
         *  Safe for use from non-Context code paths like RelayConnectionStateMachine. */
        fun getInstanceOrNull(): Nip11CacheManager? = instance
    }

    private val dao: Nip11Dao = AppDatabase.getInstance(context).nip11Dao()
    private val dbScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /**
     * Dedicated HTTP client for NIP-11 fetches. Does NOT use MyceliumHttpClient because
     * its ContentNegotiation plugin overrides the Accept header, causing relays to serve
     * HTML instead of the NIP-11 JSON document.
     */
    private val httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
        engine {
            requestTimeout = 15_000
        }
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
        install(io.ktor.client.plugins.HttpRedirect) {
            checkHttpMethod = false
        }
    }
    
    // In-memory cache for O(1) hot reads (populated from Room on init)
    private val memoryCache = java.util.concurrent.ConcurrentHashMap<String, CachedRelayInfo>()

    // In-flight deduplication: only one network fetch per URL at a time
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Deferred<RelayInformation?>>()

    // Failed URL cooldown (5 min) to avoid hammering relays that consistently fail
    private val failedUrls = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val FAIL_COOLDOWN_MS = 5 * 60 * 1000L

    /** Monotonically increasing counter that ticks whenever any relay's NIP-11 data is cached.
     *  Kept for backward compatibility; prefer [relayUpdated] for per-orb reactivity. */
    private val _cacheVersion = MutableStateFlow(0L)
    val cacheVersion: StateFlow<Long> = _cacheVersion.asStateFlow()

    /** Emits the normalized relay URL whenever that specific relay's NIP-11 data is cached.
     *  RelayOrbIcon observes this filtered to its own URL for targeted recomposition
     *  (avoids the global recomposition storm caused by cacheVersion). */
    private val _relayUpdated = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val relayUpdated: SharedFlow<String> = _relayUpdated.asSharedFlow()
    
    init {
        dbScope.launch { loadCacheFromRoom() }
    }
    
    /**
     * Get relay information from cache or fetch if expired/missing
     */
    suspend fun getRelayInfo(url: String, forceRefresh: Boolean = false): RelayInformation? = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = normalizeRelayUrl(url)

            // Check if we have valid cached data
            if (!forceRefresh) {
                val cached = memoryCache[normalizedUrl]
                if (cached != null && !isExpired(cached.timestamp)) {
                    Log.d(TAG, "📋 Using cached NIP-11 data for $normalizedUrl")
                    return@withContext cached.info
                }
            }

            // Skip URLs that recently failed (cooldown)
            if (!forceRefresh) {
                val failedAt = failedUrls[normalizedUrl]
                if (failedAt != null && System.currentTimeMillis() - failedAt < FAIL_COOLDOWN_MS) {
                    return@withContext memoryCache[normalizedUrl]?.info
                }
            }

            // Deduplicate concurrent fetches: if another coroutine is already fetching, wait for it
            val existing = inFlight[normalizedUrl]
            if (existing != null) {
                return@withContext try { existing.await() } catch (_: Exception) { null }
            }

            val deferred = CompletableDeferred<RelayInformation?>()
            val prev = inFlight.putIfAbsent(normalizedUrl, deferred)
            if (prev != null) {
                // Another coroutine won the race
                return@withContext try { prev.await() } catch (_: Exception) { null }
            }

            try {
                Log.d(TAG, "🌐 Fetching fresh NIP-11 data for $normalizedUrl")
                val freshInfo = fetchRelayInfoFromNetwork(normalizedUrl)
                if (freshInfo != null) {
                    cacheRelayInfo(normalizedUrl, freshInfo)
                    failedUrls.remove(normalizedUrl)
                } else {
                    failedUrls[normalizedUrl] = System.currentTimeMillis()
                }
                deferred.complete(freshInfo)
                freshInfo
            } catch (e: Exception) {
                failedUrls[normalizedUrl] = System.currentTimeMillis()
                deferred.completeExceptionally(e)
                null
            } finally {
                inFlight.remove(normalizedUrl)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to get relay info for $url: ${e.message}")
            null
        }
    }
    
    /**
     * Get relay information from cache only (no network fetch).
     * Returns stale data if present — callers should trigger a background refresh
     * for expired entries, but stale icons/names are better than nothing.
     */
    fun getCachedRelayInfo(url: String): RelayInformation? {
        val normalizedUrl = normalizeRelayUrl(url)
        return memoryCache[normalizedUrl]?.info
    }
    
    /**
     * Check if relay info exists in cache (even if expired)
     */
    fun hasCachedRelayInfo(url: String): Boolean {
        val normalizedUrl = normalizeRelayUrl(url)
        return memoryCache.containsKey(normalizedUrl)
    }
    
    /**
     * Get all cached relay URLs (for background refresh)
     */
    fun getAllCachedRelayUrls(): List<String> {
        return memoryCache.keys.toList()
    }
    
    /**
     * Get relays that need refresh (older than 24 hours)
     */
    fun getStaleRelayUrls(): List<String> {
        val now = System.currentTimeMillis()
        return memoryCache.filter { (_, cached) ->
            isExpired(cached.timestamp)
        }.keys.toList()
    }
    
    /**
     * Refresh stale relay information in background.
     * Parallel with Semaphore(8) — refreshes 50 stale relays in ~4s instead of 25s serial.
     */
    fun refreshStaleRelays(scope: CoroutineScope, onComplete: (() -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            try {
                val staleUrls = getStaleRelayUrls()
                Log.d(TAG, "Refreshing ${staleUrls.size} stale relay info entries")
                val sem = Semaphore(8)
                staleUrls.map { url ->
                    launch {
                        sem.withPermit {
                            try {
                                val freshInfo = fetchRelayInfoFromNetwork(url)
                                if (freshInfo != null) cacheRelayInfo(url, freshInfo)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to refresh $url: ${e.message}")
                            }
                        }
                    }
                }.joinAll()
                onComplete?.invoke()
                Log.d(TAG, "Background refresh completed")
            } catch (e: Exception) {
                Log.e(TAG, "Background refresh failed: ${e.message}", e)
                onComplete?.invoke()
            }
        }
    }
    
    /**
     * Preload relay information for a list of URLs.
     * Parallel with Semaphore(8) — 28 relays in ~2s instead of 14s serial.
     * Precomputes the stale set once (O(N)) instead of per-URL (O(N×M)).
     */
    fun preloadRelayInfo(urls: List<String>, scope: CoroutineScope) {
        if (MemoryUtils.isLowMemory(context)) return
        scope.launch(Dispatchers.IO) {
            val staleSet = getStaleRelayUrls().toSet()
            val toFetch = urls.filter { !hasCachedRelayInfo(it) || it in staleSet }
            if (toFetch.isEmpty()) return@launch
            Log.d(TAG, "Parallel NIP-11 preload: ${toFetch.size} relays (${urls.size - toFetch.size} cached)")
            val sem = Semaphore(8)
            toFetch.map { url ->
                launch {
                    sem.withPermit {
                        try {
                            getRelayInfo(url)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to preload $url: ${e.message}")
                        }
                    }
                }
            }.joinAll()
        }
    }
    
    /**
     * Clear expired entries from cache
     */
    fun clearExpiredEntries() {
        val expiredUrls = memoryCache.filter { (_, cached) ->
            isExpired(cached.timestamp)
        }.keys.toList()
        
        expiredUrls.forEach { url ->
            memoryCache.remove(url)
        }
        
        if (expiredUrls.isNotEmpty()) {
            val cutoffMs = System.currentTimeMillis() - CACHE_EXPIRY_HOURS * 60 * 60 * 1000L
            dbScope.launch { dao.deleteOlderThan(cutoffMs) }
            Log.d(TAG, "Cleared ${expiredUrls.size} expired cache entries")
        }
    }
    
    /**
     * Check if a relay requires payment (from cached NIP-11 data).
     * Returns true only if we have cached data confirming payment_required = true.
     * Returns false if unknown (no cached data) — we don't block relays we haven't checked.
     */
    fun isPaymentRequired(url: String): Boolean {
        val normalizedUrl = normalizeRelayUrl(url)
        return memoryCache[normalizedUrl]?.info?.limitation?.payment_required == true
    }

    /**
     * Check if a relay requires authentication (from cached NIP-11 data).
     * Returns true only if we have cached data confirming auth_required = true.
     */
    fun isAuthRequired(url: String): Boolean {
        val normalizedUrl = normalizeRelayUrl(url)
        return memoryCache[normalizedUrl]?.info?.limitation?.auth_required == true
    }

    /**
     * Check if a relay should be skipped for general-purpose subscriptions
     * (payment required or auth required without signer).
     */
    fun shouldSkipRelay(url: String): Boolean {
        val normalizedUrl = normalizeRelayUrl(url)
        val info = memoryCache[normalizedUrl]?.info ?: return false
        return info.limitation?.payment_required == true || info.limitation?.auth_required == true
    }

    /**
     * Clear only the in-memory cache to free RAM. Room data is unchanged; data can be
     * reloaded from Room on next access. Call from trim coordinator on memory pressure.
     */
    fun clearMemoryCache() {
        memoryCache.clear()
        Log.d(TAG, "Cleared NIP-11 memory cache (Room unchanged)")
    }

    /**
     * Reload memory cache from Room (e.g. after clearMemoryCache on memory pressure recovery).
     */
    fun reloadFromDisk() {
        dbScope.launch { loadCacheFromRoom() }
    }

    /**
     * Clear all cache data (memory and Room)
     */
    fun clearAllCache() {
        memoryCache.clear()
        dbScope.launch { dao.deleteAll() }
        Log.d(TAG, "Cleared all NIP-11 cache data")
    }
    
    /**
     * Fetch relay information from network
     */
    private suspend fun fetchRelayInfoFromNetwork(url: String): RelayInformation? = withContext(Dispatchers.IO) {
        try {
            // Convert WebSocket URL to HTTP for NIP-11. Ensure trailing slash — many relays
            // (especially behind Cloudflare) redirect bare domain to domain/ which can drop headers.
            val httpUrl = url.replace("wss://", "https://").replace("ws://", "http://")
                .trimEnd('/') + "/"
            
            val response = httpClient.get(httpUrl) {
                header("Accept", "application/nostr+json")
            }
            
            if (response.status.isSuccess()) {
                val responseBody = response.bodyAsText()
                if (responseBody.startsWith("{")) {
                    try {
                        val relayInfo = JSON.decodeFromString<RelayInformation>(responseBody)
                        Log.d(TAG, "Fetched NIP-11 info for $url: ${relayInfo.name} icon=${relayInfo.icon?.take(80)}")
                        return@withContext relayInfo
                    } catch (parseEx: Exception) {
                        Log.e(TAG, "NIP-11 JSON parse failed for $url: ${parseEx.message}\n  body=${responseBody.take(300)}")
                    }
                } else {
                    Log.w(TAG, "NIP-11 response for $url is not JSON: ${responseBody.take(120)}")
                }
            } else {
                Log.w(TAG, "NIP-11 HTTP ${response.status.value} for $url")
            }
            
            null
            
        } catch (e: Exception) {
            Log.w(TAG, "NIP-11 fetch error for $url: ${e.message}")
            null
        }
    }
    
    /**
     * Cache relay information (memory + Room).
     */
    private fun cacheRelayInfo(url: String, info: RelayInformation) {
        val now = System.currentTimeMillis()
        val cached = CachedRelayInfo(url = url, info = info, timestamp = now)
        memoryCache[url] = cached
        _cacheVersion.value++
        _relayUpdated.tryEmit(url)
        // Persist to Room asynchronously
        val infoJson = try { JSON.encodeToString(info) } catch (_: Exception) { return }
        dbScope.launch {
            try {
                dao.upsert(CachedNip11Entity(
                    relayUrl = url,
                    infoJson = infoJson,
                    iconUrl = info.icon,
                    name = info.name,
                    fetchedAt = now
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Room upsert failed for $url: ${e.message}")
            }
        }
    }
    
    /**
     * Check if cached data is expired
     */
    private fun isExpired(timestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        val expiryTime = CACHE_EXPIRY_HOURS * 60 * 60 * 1000L
        return (now - timestamp) > expiryTime
    }
    
    /**
     * Normalize relay URL
     */
    private fun normalizeRelayUrl(url: String): String {
        return social.mycelium.android.utils.normalizeRelayUrl(url)
    }
    
    /**
     * Load cache from Room into memory. Also runs the one-time SharedPreferences migration.
     */
    private suspend fun loadCacheFromRoom() {
        try {
            // One-time migration from SharedPreferences → Room
            migrateFromSharedPrefsIfNeeded()

            val entities = dao.getAll()
            var loaded = 0
            for (entity in entities) {
                try {
                    val info = JSON.decodeFromString<RelayInformation>(entity.infoJson)
                    val normalizedKey = normalizeRelayUrl(entity.relayUrl)
                    val existing = memoryCache[normalizedKey]
                    if (existing == null || existing.timestamp < entity.fetchedAt) {
                        memoryCache[normalizedKey] = CachedRelayInfo(normalizedKey, info, entity.fetchedAt)
                        loaded++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping corrupt NIP-11 row: ${entity.relayUrl}: ${e.message}")
                }
            }
            Log.d(TAG, "Loaded $loaded NIP-11 entries from Room (${entities.size} rows)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load NIP-11 cache from Room: ${e.message}", e)
        }
    }

    /**
     * One-time migration: import existing SharedPreferences NIP-11 data into Room,
     * then clear the SharedPreferences to free up the JSON blob storage.
     */
    private suspend fun migrateFromSharedPrefsIfNeeded() {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LEGACY_MIGRATED_KEY, false)) return

        try {
            val cacheDataJson = prefs.getString(LEGACY_DATA_KEY, null)
            val timestampsJson = prefs.getString(LEGACY_TIMESTAMPS_KEY, null)

            if (cacheDataJson != null && timestampsJson != null) {
                val cacheData = JSON.decodeFromString<Map<String, RelayInformation>>(cacheDataJson)
                val timestamps = JSON.decodeFromString<Map<String, Long>>(timestampsJson)

                val entities = cacheData.mapNotNull { (url, info) ->
                    val normalizedUrl = normalizeRelayUrl(url)
                    val timestamp = timestamps[url] ?: System.currentTimeMillis()
                    val infoJson = try { JSON.encodeToString(info) } catch (_: Exception) { return@mapNotNull null }
                    CachedNip11Entity(
                        relayUrl = normalizedUrl,
                        infoJson = infoJson,
                        iconUrl = info.icon,
                        name = info.name,
                        fetchedAt = timestamp
                    )
                }

                if (entities.isNotEmpty()) {
                    dao.upsertAll(entities)
                    Log.d(TAG, "Migrated ${entities.size} NIP-11 entries from SharedPreferences to Room")
                }
            }

            // Mark migration done and clear legacy data
            prefs.edit()
                .putBoolean(LEGACY_MIGRATED_KEY, true)
                .remove(LEGACY_DATA_KEY)
                .remove(LEGACY_TIMESTAMPS_KEY)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "SharedPreferences → Room migration failed: ${e.message}", e)
            // Mark as migrated anyway to avoid retrying a broken migration forever
            prefs.edit().putBoolean(LEGACY_MIGRATED_KEY, true).apply()
        }
    }
    
    /**
     * Data class for cached relay information
     */
    private data class CachedRelayInfo(
        val url: String,
        val info: RelayInformation,
        val timestamp: Long
    )
}
