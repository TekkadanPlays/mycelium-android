package social.mycelium.android.services

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL

/**
 * HTML parser for extracting metadata from web pages
 */
class HtmlParser {
    
    /**
     * Parse HTML content and extract metadata
     */
    fun parseHtml(html: String, baseUrl: String): HtmlMetadata {
        return try {
            val doc = Jsoup.parse(html, baseUrl)
            extractMetadata(doc)
        } catch (e: Exception) {
            HtmlMetadata()
        }
    }
    
    private fun extractMetadata(doc: Document): HtmlMetadata {
        val title = extractTitle(doc)
        val description = extractDescription(doc)
        val imageUrl = extractImageUrl(doc)
        val siteName = extractSiteName(doc)
        
        return HtmlMetadata(
            title = title,
            description = description,
            imageUrl = imageUrl,
            siteName = siteName
        )
    }
    
    private fun extractTitle(doc: Document): String {
        // Try OpenGraph title first (both property= and name= variants exist in the wild)
        doc.select("meta[property=og:title]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return it }
        doc.select("meta[name=og:title]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return it }
        
        // Try Twitter title
        doc.select("meta[name=twitter:title]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return it }
        
        // Fall back to HTML title
        doc.select("title").first()?.text()?.takeIf { it.isNotBlank() }?.let { return it }
        
        return ""
    }
    
    private fun extractDescription(doc: Document): String {
        // Try OpenGraph description first (both property= and name= variants)
        doc.select("meta[property=og:description]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return it }
        doc.select("meta[name=og:description]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return it }
        
        // Try Twitter description
        doc.select("meta[name=twitter:description]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return it }
        
        // Try meta description
        doc.select("meta[name=description]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return it }
        
        return ""
    }
    
    private fun extractImageUrl(doc: Document): String {
        // Try OpenGraph image first (both property= and name= variants)
        doc.select("meta[property=og:image]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return resolveUrl(it, doc.baseUri()) }
        doc.select("meta[name=og:image]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return resolveUrl(it, doc.baseUri()) }
        doc.select("meta[property=og:image:url]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return resolveUrl(it, doc.baseUri()) }
        
        // Try Twitter image
        doc.select("meta[name=twitter:image]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return resolveUrl(it, doc.baseUri()) }
        doc.select("meta[name=twitter:image:src]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return resolveUrl(it, doc.baseUri()) }
        
        // Try to find the first large image in the page
        doc.select("img").forEach { img ->
            val src = img.attr("abs:src") // abs: resolves relative URLs
            val width = img.attr("width").toIntOrNull() ?: 0
            val height = img.attr("height").toIntOrNull() ?: 0
            
            // Prefer images that are reasonably sized (not too small)
            if (src.isNotEmpty() && (width > 200 || height > 200)) {
                return src
            }
        }
        
        return ""
    }

    /** Resolve potentially relative image URLs against the page base URL. */
    private fun resolveUrl(imageUrl: String, baseUrl: String): String {
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://") || imageUrl.startsWith("//")) {
            return if (imageUrl.startsWith("//")) "https:$imageUrl" else imageUrl
        }
        return try {
            URL(URL(baseUrl), imageUrl).toString()
        } catch (_: Exception) {
            imageUrl
        }
    }
    
    private fun extractSiteName(doc: Document): String {
        // Try OpenGraph site name first
        doc.select("meta[property=og:site_name]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return it }
        
        // Try Twitter site
        doc.select("meta[name=twitter:site]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return it }
        
        // Try application-name
        doc.select("meta[name=application-name]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let { return it }
        
        // Try to extract from URL
        try {
            val url = URL(doc.baseUri())
            return url.host.removePrefix("www.")
        } catch (e: Exception) {
            // Ignore
        }
        
        return ""
    }
}

/**
 * Metadata extracted from HTML
 */
data class HtmlMetadata(
    val title: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val siteName: String = ""
)










