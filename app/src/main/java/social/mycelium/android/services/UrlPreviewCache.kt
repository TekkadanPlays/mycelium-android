package social.mycelium.android.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.LruCache
import social.mycelium.android.data.UrlPreviewInfo
import social.mycelium.android.data.UrlPreviewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache for URL previews to avoid repeated network requests.
 * Singleton so it can be trimmed from AppMemoryTrimmer on memory pressure.
 * Persists to disk so link previews survive app restart.
 */
object UrlPreviewCache {

    private const val TAG = "UrlPreviewCache"
    private const val PREFS_NAME = "url_preview_cache"
    private const val PREFS_KEY = "entries"
    private const val DISK_CACHE_MAX = 100

    private val cache = LruCache<String, UrlPreviewInfo>(100)
    private val loadingStates = ConcurrentHashMap<String, UrlPreviewState>()
    private val mutex = Mutex()

    /** Increments on every put(). Composables can observe this to reactively pick up
     *  previews fetched by any screen without per-screen enrichment plumbing. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()
    private val diskScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var prefs: SharedPreferences? = null

    /** Call once from Application/Activity to load disk cache into memory. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromDisk()
    }
    
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
        _revision.value++
        scheduleDiskSave()
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
        prefs?.edit()?.remove(PREFS_KEY)?.apply()
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

    // ── Disk persistence ─────────────────────────────────────────────────

    private fun loadFromDisk() {
        try {
            val json = prefs?.getString(PREFS_KEY, null) ?: return
            val list = diskJson.decodeFromString<List<UrlPreviewInfo>>(json)
            for (info in list) cache.put(info.url, info)
            Log.d(TAG, "Loaded ${list.size} link previews from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load disk cache: ${e.message}")
        }
    }

    private var diskSaveJob: kotlinx.coroutines.Job? = null

    private fun scheduleDiskSave() {
        diskSaveJob?.cancel()
        diskSaveJob = diskScope.launch {
            kotlinx.coroutines.delay(3000)
            saveToDisk()
        }
    }

    private fun saveToDisk() {
        try {
            val snapshot = cache.snapshot()
            val entries = snapshot.values.toList().takeLast(DISK_CACHE_MAX)
            val json = diskJson.encodeToString(entries)
            prefs?.edit()?.putString(PREFS_KEY, json)?.apply()
            Log.d(TAG, "Saved ${entries.size} link previews to disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save disk cache: ${e.message}")
        }
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

