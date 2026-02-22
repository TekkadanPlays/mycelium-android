package social.mycelium.android.services

import social.mycelium.android.data.Note
import social.mycelium.android.data.UrlPreviewInfo
import social.mycelium.android.utils.UrlDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manager for handling URL previews in notes.
 * Network and parsing run on Dispatchers.IO; only the resulting Note (with urlPreviews) is used on the main thread in note cards.
 */
class UrlPreviewManager(
    private val urlPreviewService: UrlPreviewService,
    private val urlPreviewCache: UrlPreviewCache
) {
    
    /**
     * Process a note and add URL previews if URLs are detected.
     * Each fetched preview is stored to cache immediately so cancelled runs don't lose work.
     */
    suspend fun processNoteForUrlPreviews(note: Note): Note = withContext(Dispatchers.IO) {
        val urls = UrlDetector.findUrls(note.content)
        val embeddedMedia = note.mediaUrls.toSet()
        
        if (urls.isEmpty()) {
            return@withContext note
        }
        
        val urlPreviews = mutableListOf<UrlPreviewInfo>()
        
        // Process up to 3 URLs; skip URLs that are embedded as images (no link/preview for embedded media)
        urls.filter { it !in embeddedMedia }.take(3).forEach { url ->
            try {
                val cached = urlPreviewCache.get(url)
                if (cached != null) {
                    urlPreviews.add(cached)
                } else {
                    val result = urlPreviewService.fetchPreview(url)
                    if (result is social.mycelium.android.data.UrlPreviewState.Loaded) {
                        urlPreviewCache.put(url, result.previewInfo)
                        urlPreviews.add(result.previewInfo)
                    }
                }
            } catch (e: Exception) {
                // Skip failed URLs
            }
        }
        
        note.copy(urlPreviews = urlPreviews)
    }
    
    /**
     * Process multiple notes for URL previews
     */
    suspend fun processNotesForUrlPreviews(notes: List<Note>): List<Note> = withContext(Dispatchers.IO) {
        notes.map { note ->
            processNoteForUrlPreviews(note)
        }
    }

    /**
     * Enrich the top N notes with URL previews. Returns a map of noteId → previews.
     * Uses cache for already-fetched URLs so re-runs after cancellation are fast.
     * Only processes notes that have non-media URLs and don't already have previews.
     */
    suspend fun enrichTopNotes(notes: List<Note>, limit: Int = 20): Map<String, List<UrlPreviewInfo>> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, List<UrlPreviewInfo>>()
        var processed = 0
        for (note in notes) {
            if (processed >= limit) break
            val urls = UrlDetector.findUrls(note.content)
            val embeddedMedia = note.mediaUrls.toSet()
            val linkUrls = urls.filter { it !in embeddedMedia && !UrlDetector.isImageUrl(it) && !UrlDetector.isVideoUrl(it) }.take(2)
            if (linkUrls.isEmpty()) continue

            processed++
            val previews = mutableListOf<UrlPreviewInfo>()
            for (url in linkUrls) {
                try {
                    val cached = urlPreviewCache.get(url)
                    if (cached != null) {
                        previews.add(cached)
                    } else {
                        val state = urlPreviewService.fetchPreview(url)
                        if (state is social.mycelium.android.data.UrlPreviewState.Loaded) {
                            urlPreviewCache.put(url, state.previewInfo)
                            previews.add(state.previewInfo)
                        }
                    }
                } catch (_: Exception) { }
            }
            if (previews.isNotEmpty()) {
                result[note.id] = previews
            }
        }
        result
    }
    
    /**
     * Check if a note contains URLs that could have previews
     */
    fun noteContainsUrls(note: Note): Boolean {
        return UrlDetector.containsUrl(note.content)
    }
    
    /**
     * Get URLs from note content
     */
    fun getUrlsFromNote(note: Note): List<String> {
        return UrlDetector.findUrls(note.content)
    }
    
    /**
     * Preload URL previews for a list of notes
     */
    fun preloadUrlPreviews(notes: List<Note>, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            notes.forEach { note ->
                if (noteContainsUrls(note)) {
                    val urls = getUrlsFromNote(note)
                    urls.take(2).forEach { url -> // Preload only first 2 URLs
                        if (urlPreviewCache.get(url) == null && !urlPreviewCache.isLoading(url)) {
                            try {
                                urlPreviewService.fetchPreview(url)
                            } catch (e: Exception) {
                                // Ignore errors during preloading
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Clear all cached previews
     */
    fun clearCache() {
        urlPreviewCache.clear()
    }
    
    /**
     * Get cache statistics
     */
    fun getCacheStats() = urlPreviewCache.getStats()
}










