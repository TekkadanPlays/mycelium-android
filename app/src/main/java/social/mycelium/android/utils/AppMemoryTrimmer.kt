package social.mycelium.android.utils

import android.content.Context
import android.util.Log
import social.mycelium.android.cache.Nip11CacheManager
import social.mycelium.android.cache.ThreadReplyCache
import social.mycelium.android.repository.cache.ProfileMetadataCache
import social.mycelium.android.repository.cache.QuotedNoteCache
import social.mycelium.android.services.UrlPreviewCache

/**
 * Central coordinator for releasing memory when the system requests it (onTrimMemory).
 * Does not hold references to Activity; uses singletons or context for caches.
 * See: https://developer.android.com/topic/performance/memory
 * All trim operations are guarded so one failing cache cannot cause process death.
 */
object AppMemoryTrimmer {

    private const val TAG = "AppMemoryTrimmer"

    /**
     * Trim UI-related and non-essential caches when the UI is hidden or system is under pressure.
     * Call when level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN.
     *
     * NOTE: NIP-11 relay metadata is NOT cleared here. It's small (~100-300KB for hundreds of
     * relays), persisted in Room, and critical for relay orb icons. Clearing it on every
     * UI_HIDDEN (notification shade, share sheet, permission dialog) causes a 14s re-fetch
     * waterfall that kills the relay orb experience. Only cleared at RUNNING_CRITICAL.
     */
    fun trimUiCaches(context: Context?) {
        try { ThreadReplyCache.trimToSize(ThreadReplyCache.TRIM_SIZE_UI_HIDDEN) } catch (e: Throwable) { Log.w(TAG, "ThreadReplyCache trim failed", e) }
        try { QuotedNoteCache.trimToSize(QuotedNoteCache.TRIM_SIZE_UI_HIDDEN) } catch (e: Throwable) { Log.w(TAG, "QuotedNoteCache trim failed", e) }
        try { UrlPreviewCache.clear() } catch (e: Throwable) { Log.w(TAG, "UrlPreviewCache clear failed", e) }
    }

    /**
     * Trim more aggressively when app is in background or memory is low.
     * Call when level >= TRIM_MEMORY_BACKGROUND (or RUNNING_LOW / MODERATE as desired).
     */
    fun trimBackgroundCaches(level: Int, context: Context?) {
        trimUiCaches(context)
        try { ThreadReplyCache.trimToSize(ThreadReplyCache.TRIM_SIZE_BACKGROUND) } catch (e: Throwable) { Log.w(TAG, "ThreadReplyCache background trim failed", e) }
        try { QuotedNoteCache.trimToSize(QuotedNoteCache.TRIM_SIZE_BACKGROUND) } catch (e: Throwable) { Log.w(TAG, "QuotedNoteCache background trim failed", e) }
        try { ProfileMetadataCache.getInstance().trimToSize(ProfileMetadataCache.TRIM_SIZE_BACKGROUND) } catch (e: Throwable) { Log.w(TAG, "ProfileMetadataCache trim failed", e) }
        // Only wipe NIP-11 memory cache at RUNNING_CRITICAL (level 15) — last resort.
        // Auto-reload from Room so icons recover without a 14s network re-fetch.
        // TRIM_MEMORY_RUNNING_CRITICAL = 15 (deprecated constant, using raw value)
        if (level >= 15) {
            try {
                context?.applicationContext?.let { app ->
                    val nip11 = Nip11CacheManager.getInstance(app)
                    nip11.clearMemoryCache()
                    nip11.reloadFromDisk()
                }
            } catch (e: Throwable) { Log.w(TAG, "Nip11CacheManager trim failed", e) }
        }
    }
}
