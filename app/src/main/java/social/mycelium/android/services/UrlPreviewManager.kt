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
     * Process a note and add URL previews if URLs are detected
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










