package social.mycelium.android.cache.nip11

import android.util.Log
import android.util.LruCache
import social.mycelium.android.data.RelayInformation
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Cached retriever for NIP-11 relay information with LruCache and state management.
 * Based on Amethyst's Nip11CachedRetriever pattern.
 *
 * Features:
 * - In-memory LruCache for fast access
 * - State management (Empty, Loading, Success, Error)
 * - Time-based cache validation (1 hour)
 * - Immediate empty state with fallback data (displayUrl + favicon)
 */
class Nip11CachedRetriever(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "Nip11CachedRetriever"
        private const val CACHE_SIZE = 1000
    }

    // Cache for empty/default relay information
    private val relayInformationEmptyCache = LruCache<String, RelayInformation>(CACHE_SIZE)

    // Cache for retrieve results with state
    private val relayInformationDocumentCache = LruCache<String, RetrieveResult?>(CACHE_SIZE)

    // Network retriever
    private val retriever = Nip11Retriever(okHttpClient)

    /**
     * Get or create empty/default relay information for a URL.
     * This is used as a fallback when real data isn't available yet.
     */
    fun getEmpty(relayUrl: String): RelayInformation {
        relayInformationEmptyCache.get(relayUrl)?.let { return it }

        val displayUrl = retriever.getDisplayUrl(relayUrl)
        val faviconUrl = retriever.getFaviconUrl(relayUrl)

        val info = RelayInformation(
            name = displayUrl,
            icon = faviconUrl
        )

        relayInformationEmptyCache.put(relayUrl, info)
        Log.d(TAG, "📋 Created empty relay info for $relayUrl")

        return info
    }

    /**
     * Get relay information from cache (or empty state if not cached).
     * This is synchronous and returns immediately.
     */
    fun getFromCache(relayUrl: String): RelayInformation {
        val normalizedUrl = retriever.normalizeRelayUrl(relayUrl)
        val result = relayInformationDocumentCache.get(normalizedUrl)

        if (result == null) {
            // No cache entry - create empty state and return
            val empty = getEmpty(normalizedUrl)
            relayInformationDocumentCache.put(normalizedUrl, RetrieveResult.Empty(empty))
            return empty
        }

        // Return data from cached result (regardless of state)
        return when (result) {
            is RetrieveResult.Success -> result.data
            is RetrieveResult.Error -> result.data
            is RetrieveResult.Empty -> result.data
            is RetrieveResult.Loading -> result.data
        }
    }

    /**
     * Load relay information with smart caching and state management.
     *
     * Behavior:
     * - If cached and valid (< 1 hour old), return immediately
     * - If loading and still valid, wait (don't trigger duplicate fetch)
     * - If error but still valid (< 1 hour old), return error
     * - Otherwise, fetch fresh data from network
     *
     * @param relayUrl WebSocket URL of the relay
     * @param onInfo Callback when information is retrieved (from cache or network)
     * @param onError Callback when an error occurs
     */
    suspend fun loadRelayInfo(
        relayUrl: String,
        onInfo: (RelayInformation) -> Unit,
        onError: (String, Nip11Retriever.ErrorCode, String?) -> Unit
    ) {
        val normalizedUrl = retriever.normalizeRelayUrl(relayUrl)
        val doc = relayInformationDocumentCache.get(normalizedUrl)

        when (doc) {
            is RetrieveResult.Success -> {
                // We have successful data - return it
                Log.d(TAG, "✅ Using cached success for $normalizedUrl")
                onInfo(doc.data)
            }
            is RetrieveResult.Loading -> {
                if (doc.isValid()) {
                    // Already loading and recent - just wait, don't duplicate fetch
                    Log.d(TAG, "⏳ Already loading $normalizedUrl, skipping duplicate fetch")
                } else {
                    // Loading state is stale - retry
                    Log.d(TAG, "🔄 Loading state stale for $normalizedUrl, retrying")
                    retrieve(normalizedUrl, onInfo, onError)
                }
            }
            is RetrieveResult.Error -> {
                if (doc.isValid()) {
                    // Error is recent - don't retry yet, return error
                    Log.d(TAG, "⚠️ Using cached error for $normalizedUrl")
                    onError(normalizedUrl, doc.errorCode, doc.message)
                } else {
                    // Error is old - retry
                    Log.d(TAG, "🔄 Error state stale for $normalizedUrl, retrying")
                    retrieve(normalizedUrl, onInfo, onError)
                }
            }
            is RetrieveResult.Empty, null -> {
                // No data yet - fetch it
                Log.d(TAG, "🌐 Fetching fresh data for $normalizedUrl")
                retrieve(normalizedUrl, onInfo, onError)
            }
        }
    }

    /**
     * Perform the actual network retrieval and update cache
     */
    private suspend fun retrieve(
        relayUrl: String,
        onInfo: (RelayInformation) -> Unit,
        onError: (String, Nip11Retriever.ErrorCode, String?) -> Unit
    ) {
        // Mark as loading
        relayInformationDocumentCache.put(relayUrl, RetrieveResult.Loading(getEmpty(relayUrl)))

        retriever.loadRelayInfo(
            relayUrl = relayUrl,
            onInfo = { info ->
                // Success - cache it and notify
                relayInformationDocumentCache.put(relayUrl, RetrieveResult.Success(info))
                relayInformationEmptyCache.remove(relayUrl)
                Log.d(TAG, "✅ Cached success for $relayUrl: ${info.name}")
                onInfo(info)
            },
            onError = { url, errorCode, errorMsg ->
                // Error - cache error state with fallback data
                relayInformationDocumentCache.put(
                    relayUrl,
                    RetrieveResult.Error(getEmpty(relayUrl), errorCode, errorMsg)
                )
                relayInformationEmptyCache.remove(relayUrl)
                Log.w(TAG, "⚠️ Cached error for $relayUrl: $errorCode - $errorMsg")
                onError(url, errorCode, errorMsg)
            }
        )
    }

    /**
     * Preload relay information in the background (fire and forget)
     */
    suspend fun preload(relayUrl: String) {
        loadRelayInfo(
            relayUrl = relayUrl,
            onInfo = { info ->
                Log.d(TAG, "📦 Preloaded relay info: ${info.name}")
            },
            onError = { url, code, msg ->
                Log.w(TAG, "⚠️ Failed to preload $url: $code - $msg")
            }
        )
    }

    /**
     * Clear all cached data
     */
    fun clearCache() {
        relayInformationEmptyCache.evictAll()
        relayInformationDocumentCache.evictAll()
        Log.d(TAG, "🧹 Cleared all NIP-11 cache")
    }

    /**
     * Clear cached data for a specific relay
     */
    fun clearCache(relayUrl: String) {
        val normalizedUrl = retriever.normalizeRelayUrl(relayUrl)
        relayInformationEmptyCache.remove(normalizedUrl)
        relayInformationDocumentCache.remove(normalizedUrl)
        Log.d(TAG, "🧹 Cleared cache for $normalizedUrl")
    }

    /**
     * Force refresh relay information (ignores cache)
     */
    suspend fun forceRefresh(
        relayUrl: String,
        onInfo: (RelayInformation) -> Unit,
        onError: (String, Nip11Retriever.ErrorCode, String?) -> Unit
    ) {
        val normalizedUrl = retriever.normalizeRelayUrl(relayUrl)
        clearCache(normalizedUrl)
        retrieve(normalizedUrl, onInfo, onError)
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            emptyCount = relayInformationEmptyCache.size(),
            documentCount = relayInformationDocumentCache.size(),
            maxSize = CACHE_SIZE
        )
    }

    data class CacheStats(
        val emptyCount: Int,
        val documentCount: Int,
        val maxSize: Int
    )
}
