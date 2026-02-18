package social.mycelium.android.utils

import android.content.Context
import android.util.Log
import social.mycelium.android.cache.Nip11CacheManager
import social.mycelium.android.cache.ThreadReplyCache
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.repository.QuotedNoteCache
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
     */
    fun trimUiCaches(context: Context?) {
        try { ThreadReplyCache.trimToSize(ThreadReplyCache.TRIM_SIZE_UI_HIDDEN) } catch (e: Throwable) { Log.w(TAG, "ThreadReplyCache trim failed", e) }
        try { QuotedNoteCache.trimToSize(QuotedNoteCache.TRIM_SIZE_UI_HIDDEN) } catch (e: Throwable) { Log.w(TAG, "QuotedNoteCache trim failed", e) }
        try { UrlPreviewCache.clear() } catch (e: Throwable) { Log.w(TAG, "UrlPreviewCache clear failed", e) }
        try {
            context?.applicationContext?.let { app ->
                Nip11CacheManager.getInstance(app).clearMemoryCache()
            }
        } catch (e: Throwable) { Log.w(TAG, "Nip11CacheManager clear failed", e) }
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
    }
}
