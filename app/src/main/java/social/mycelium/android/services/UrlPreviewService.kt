package social.mycelium.android.services

import android.util.Log
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import social.mycelium.android.data.UrlPreviewInfo
import social.mycelium.android.data.UrlPreviewState
import social.mycelium.android.network.MyceliumHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            Log.d("UrlPreviewService", "Fetching preview for: $url")
            
            // Validate URL
            val validatedUrl = validateUrl(url) ?: return@withContext UrlPreviewState.Error("Invalid URL")
            
            // Check if it's a direct image/video URL
            val directMediaPreview = checkDirectMedia(validatedUrl)
            if (directMediaPreview != null) {
                return@withContext UrlPreviewState.Loaded(directMediaPreview)
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
            
            Log.d("UrlPreviewService", "Successfully fetched preview: ${previewInfo.title}")
            UrlPreviewState.Loaded(previewInfo)
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // propagate cancellation, don't swallow
        } catch (e: Exception) {
            Log.e("UrlPreviewService", "Error fetching preview for $url", e)
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
    
    private suspend fun fetchHtml(url: String): Pair<String, String> {
        val response = httpClient.get(url) {
            header("User-Agent", "Mycelium-Android/1.0")
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
        
        if (!response.status.isSuccess()) {
            throw IOException("HTTP ${response.status}")
        }
        
        val contentType = response.contentType()?.toString() ?: ""
        if (!contentType.contains("text/html")) {
            throw IOException("Content is not HTML: $contentType")
        }
        
        return response.bodyAsText() to contentType
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










