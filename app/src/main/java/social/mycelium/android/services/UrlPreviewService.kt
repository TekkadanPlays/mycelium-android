package social.mycelium.android.services

import social.mycelium.android.debug.MLog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import social.mycelium.android.data.UrlPreviewInfo
import social.mycelium.android.data.UrlPreviewState
import social.mycelium.android.network.MyceliumHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readString
import java.io.IOException
import java.net.URL

/**
 * Service for fetching URL previews
 */
class UrlPreviewService {
    
    private val httpClient = MyceliumHttpClient.instance
    private val htmlParser = HtmlParser()
    
    /**
     * Fetch preview information for a URL
     */
    suspend fun fetchPreview(url: String): UrlPreviewState = withContext(Dispatchers.IO) {
        try {
            MLog.d("UrlPreviewService", "Fetching preview for: $url")
            
            // Validate URL
            val validatedUrl = validateUrl(url) ?: return@withContext UrlPreviewState.Error("Invalid URL")
            
            // Check if it's a direct image/video URL
            val directMediaPreview = checkDirectMedia(validatedUrl)
            if (directMediaPreview != null) {
                return@withContext UrlPreviewState.Loaded(directMediaPreview)
            }

            // YouTube shortcut: extract video ID and build preview from known thumbnail URL.
            // YouTube often blocks or returns consent pages for non-browser User-Agents,
            // so we skip the HTML fetch entirely for YouTube links.
            val ytPreview = checkYouTube(validatedUrl)
            if (ytPreview != null) {
                return@withContext UrlPreviewState.Loaded(ytPreview)
            }
            
            // Fetch HTML content
            val (htmlContent, contentType) = fetchHtml(validatedUrl)
            
            // Parse metadata
            val metadata = htmlParser.parseHtml(htmlContent, validatedUrl)
            
            val previewInfo = UrlPreviewInfo(
                url = validatedUrl,
                title = metadata.title,
                description = metadata.description,
                imageUrl = metadata.imageUrl,
                siteName = metadata.siteName,
                mimeType = contentType
            )
            
            MLog.d("UrlPreviewService", "Successfully fetched preview: ${previewInfo.title}")
            UrlPreviewState.Loaded(previewInfo)
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // propagate cancellation, don't swallow
        } catch (e: Exception) {
            MLog.e("UrlPreviewService", "Error fetching preview for $url", e)
            UrlPreviewState.Error(e.message ?: "Unknown error")
        }
    }
    
    private fun validateUrl(url: String): String? {
        return try {
            val urlObj = URL(url)
            if (urlObj.protocol in listOf("http", "https")) {
                url
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun checkDirectMedia(url: String): UrlPreviewInfo? {
        return try {
            val urlObj = URL(url)
            val path = urlObj.path.lowercase()
            
            when {
                path.endsWith(".jpg") || path.endsWith(".jpeg") || 
                path.endsWith(".png") || path.endsWith(".gif") || 
                path.endsWith(".webp") -> {
                    UrlPreviewInfo(
                        url = url,
                        title = "Image",
                        imageUrl = url,
                        mimeType = "image/*"
                    )
                }
                path.endsWith(".mp4") || path.endsWith(".webm") || 
                path.endsWith(".mov") || path.endsWith(".avi") -> {
                    UrlPreviewInfo(
                        url = url,
                        title = "Video",
                        imageUrl = url,
                        mimeType = "video/*"
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Extract YouTube video ID, fetch title via oEmbed API, and build preview.
     * oEmbed is a lightweight JSON endpoint that doesn't require scraping HTML.
     * Handles youtube.com/watch?v=ID, youtube.com/shorts/ID, youtu.be/ID,
     * youtube.com/embed/ID, youtube.com/v/ID, and m.youtube.com variants.
     */
    private suspend fun checkYouTube(url: String): UrlPreviewInfo? {
        return try {
            val urlObj = URL(url)
            val host = urlObj.host.lowercase().removePrefix("www.").removePrefix("m.")
            val videoId: String? = when {
                host == "youtu.be" -> urlObj.path.removePrefix("/").split("?").firstOrNull()?.takeIf { it.isNotBlank() }
                host == "youtube.com" || host == "youtube-nocookie.com" -> {
                    val path = urlObj.path
                    when {
                        path.startsWith("/watch") -> {
                            val query = urlObj.query ?: ""
                            query.split("&").firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                        }
                        path.startsWith("/shorts/") -> path.removePrefix("/shorts/").split("/").firstOrNull()
                        path.startsWith("/embed/") -> path.removePrefix("/embed/").split("/").firstOrNull()
                        path.startsWith("/v/") -> path.removePrefix("/v/").split("/").firstOrNull()
                        path.startsWith("/live/") -> path.removePrefix("/live/").split("/").firstOrNull()
                        else -> null
                    }
                }
                else -> null
            }?.takeIf { it.isNotBlank() && it.length in 5..20 }

            if (videoId != null) {
                // Fetch title + author via YouTube oEmbed (lightweight JSON, no HTML scraping)
                val canonicalUrl = "https://www.youtube.com/watch?v=$videoId"
                val oEmbedUrl = "https://www.youtube.com/oembed?url=${java.net.URLEncoder.encode(canonicalUrl, "UTF-8")}&format=json"
                var title = "YouTube video"
                var authorName = ""
                try {
                    val resp = httpClient.get(oEmbedUrl)
                    if (resp.status.isSuccess()) {
                        val json = org.json.JSONObject(resp.bodyAsText())
                        title = json.optString("title", title)
                        authorName = json.optString("author_name", "")
                    }
                } catch (e: Exception) {
                    MLog.w("UrlPreviewService", "YouTube oEmbed failed for $videoId: ${e.message}")
                }
                MLog.d("UrlPreviewService", "YouTube preview: videoId=$videoId, title=$title, author=$authorName")
                UrlPreviewInfo(
                    url = canonicalUrl,
                    title = title,
                    description = if (authorName.isNotBlank()) authorName else "",
                    imageUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                    siteName = "YouTube",
                    mimeType = "text/html"
                )
            } else null
        } catch (_: Exception) { null }
    }

    /** Max bytes to read from a page for metadata extraction.
     *  OG/Twitter meta tags live in &lt;head&gt; — no need to download megabytes. */
    private companion object {
        const val MAX_PREVIEW_BYTES = 65_536L // 64 KB
    }

    private suspend fun fetchHtml(url: String): Pair<String, String> {
        val response = httpClient.get(url) {
            header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.9")
            // Some sites require a Referer or return 403
            header("Referer", url)
        }
        
        if (!response.status.isSuccess()) {
            throw IOException("HTTP ${response.status.value} for $url")
        }
        
        val contentType = response.contentType()?.toString() ?: ""
        // Accept text/html, application/xhtml+xml, application/xml, or missing content-type.
        // Some servers return "text/html; charset=utf-8" so check with contains().
        val isHtmlLike = contentType.isEmpty()
                || contentType.contains("text/html", ignoreCase = true)
                || contentType.contains("xhtml", ignoreCase = true)
                || contentType.contains("application/xml", ignoreCase = true)
                || contentType.contains("text/xml", ignoreCase = true)
        if (!isHtmlLike) {
            throw IOException("Content is not HTML: $contentType")
        }
        
        // Read only the first 64KB — meta tags are in <head>, no need to download whole pages
        val channel = response.bodyAsChannel()
        val packet = channel.readRemaining(MAX_PREVIEW_BYTES)
        val text = packet.readString()
        
        if (text.isBlank()) {
            throw IOException("Empty response body from $url")
        }
        
        return text to contentType
    }
    
    /**
     * Extract URLs from text content
     */
    fun extractUrls(text: String): List<String> {
        val urlRegex = Regex(
            """https?://[^\s<>"{}|\\^`\[\]]+""",
            RegexOption.IGNORE_CASE
        )
        
        return urlRegex.findAll(text)
            .map { it.value }
            .distinct()
            .take(3) // Limit to first 3 URLs to avoid performance issues
            .toList()
    }
}










