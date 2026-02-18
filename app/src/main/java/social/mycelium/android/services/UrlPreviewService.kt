package social.mycelium.android.services

import android.util.Log
import social.mycelium.android.data.UrlPreviewInfo
import social.mycelium.android.data.UrlPreviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Service for fetching URL previews
 */
class UrlPreviewService {
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
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
            val response = fetchHtml(validatedUrl)
            val htmlContent = response.body?.string() ?: ""
            
            // Parse metadata
            val metadata = htmlParser.parseHtml(htmlContent, validatedUrl)
            
            val previewInfo = UrlPreviewInfo(
                url = validatedUrl,
                title = metadata.title,
                description = metadata.description,
                imageUrl = metadata.imageUrl,
                siteName = metadata.siteName,
                mimeType = response.header("Content-Type") ?: "text/html"
            )
            
            Log.d("UrlPreviewService", "Successfully fetched preview: ${previewInfo.title}")
            UrlPreviewState.Loaded(previewInfo)
            
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
    
    private fun fetchHtml(url: String): Response {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mycelium-Android/1.0")
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}: ${response.message}")
        }
        
        val contentType = response.header("Content-Type") ?: ""
        if (!contentType.contains("text/html")) {
            throw IOException("Content is not HTML: $contentType")
        }
        
        return response
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










