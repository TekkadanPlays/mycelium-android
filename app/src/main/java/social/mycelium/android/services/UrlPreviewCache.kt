package social.mycelium.android.services

import android.util.LruCache
import social.mycelium.android.data.UrlPreviewInfo
import social.mycelium.android.data.UrlPreviewState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache for URL previews to avoid repeated network requests.
 * Singleton so it can be trimmed from AppMemoryTrimmer on memory pressure.
 */
object UrlPreviewCache {

    private val cache = LruCache<String, UrlPreviewInfo>(50) // Cache up to 50 previews
    private val loadingStates = ConcurrentHashMap<String, UrlPreviewState>()
    private val mutex = Mutex()
    
    /**
     * Get cached preview or return null if not cached
     */
    fun get(url: String): UrlPreviewInfo? {
        return cache.get(url)
    }
    
    /**
     * Put preview in cache
     */
    fun put(url: String, previewInfo: UrlPreviewInfo) {
        cache.put(url, previewInfo)
    }
    
    /**
     * Check if URL is currently being loaded
     */
    fun isLoading(url: String): Boolean {
        return loadingStates[url] is UrlPreviewState.Loading
    }
    
    /**
     * Set loading state for URL
     * ✅ FIX: Make this function synchronous to prevent blocking
     */
    fun setLoadingState(url: String, state: UrlPreviewState) {
        when (state) {
            is UrlPreviewState.Loaded -> {
                loadingStates.remove(url)
                put(url, state.previewInfo)
            }
            is UrlPreviewState.Error -> {
                loadingStates.remove(url)
            }
            is UrlPreviewState.Loading -> {
                loadingStates[url] = state
            }
        }
    }
    
    /**
     * Get current loading state
     */
    fun getLoadingState(url: String): UrlPreviewState? {
        return loadingStates[url]
    }
    
    /**
     * Clear all cached data
     */
    fun clear() {
        cache.evictAll()
        loadingStates.clear()
    }
    
    /**
     * Remove specific URL from cache
     */
    fun remove(url: String) {
        cache.remove(url)
        loadingStates.remove(url)
    }
    
    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats {
        return CacheStats(
            size = cache.size(),
            maxSize = cache.maxSize(),
            loadingCount = loadingStates.size
        )
    }
}

/**
 * Cache statistics
 */
data class CacheStats(
    val size: Int,
    val maxSize: Int,
    val loadingCount: Int
)

