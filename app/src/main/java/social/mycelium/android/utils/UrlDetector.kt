package social.mycelium.android.utils

import java.util.regex.Pattern

/**
 * Utility for detecting URLs in text content
 */
object UrlDetector {
    
    // Regex pattern for detecting URLs
    private val URL_PATTERN = Pattern.compile(
        "(?:(?:https?|ftp)://)" +  // Protocol
        "(?:\\S+(?::\\S*)?@)?" +   // User info
        "(?:" +
        "(?!(?:10|127)(?:\\.\\d{1,3}){3})" +  // Exclude private IP ranges
        "(?!(?:169\\.254|192\\.168)(?:\\.\\d{1,3}){2})" +
        "(?!172\\.(?:1[6-9]|2\\d|3[0-1])(?:\\.\\d{1,3}){2})" +
        "(?:[1-9]\\d?|1\\d\\d|2[01]\\d|22[0-3])" +
        "(?:\\.(?:1?\\d{1,2}|2[0-4]\\d|25[0-5])){2}" +
        "(?:\\.(?:[0-9]\\d?|1\\d\\d|2[0-4]\\d|25[0-4]))" +
        "|" +
        "(?:(?:[a-z\\u00a1-\\uffff0-9]+-?)*[a-z\\u00a1-\\uffff0-9]+)" +  // Domain name
        "(?:\\.(?:[a-z\\u00a1-\\uffff0-9]+-?)*[a-z\\u00a1-\\uffff0-9]+)*" +
        "(?:\\.(?:[a-z\\u00a1-\\uffff]{2,}))" +  // TLD
        ")" +
        "(?::\\d{2,5})?" +  // Port
        "(?:/[^\\s]*)?" +   // Path
        "(?:\\?[^\\s]*)?" + // Query
        "(?:#[^\\s]*)?" +   // Fragment
        "(?=\\s|$|[.,;:!?])",  // Word boundary
        Pattern.CASE_INSENSITIVE
    )
    
    /**
     * Find all URLs in the given text
     */
    fun findUrls(text: String): List<String> {
        val matcher = URL_PATTERN.matcher(text)
        val urls = mutableListOf<String>()
        
        while (matcher.find()) {
            val url = cleanTrailingParens(matcher.group())
            if (isValidUrl(url)) {
                urls.add(url)
            }
        }
        
        return urls.distinct()
    }

    /**
     * Find all URLs with their character ranges in order of appearance.
     * Used to place HTTP metadata (preview) directly beneath its URL in the content.
     * Range is inclusive (e.g. range.last + 1 is the exclusive end for substring).
     */
    fun findUrlsWithPositions(text: String): List<Pair<IntRange, String>> {
        val matcher = URL_PATTERN.matcher(text)
        val result = mutableListOf<Pair<IntRange, String>>()
        while (matcher.find()) {
            val raw = matcher.group()
            val url = cleanTrailingParens(raw)
            if (isValidUrl(url)) {
                // Adjust end position if trailing chars were stripped
                val stripped = raw.length - url.length
                result.add(IntRange(matcher.start(), matcher.end() - 1 - stripped) to url)
            }
        }
        return result
    }
    
    /**
     * Find the first URL in the given text
     */
    fun findFirstUrl(text: String): String? {
        return findUrls(text).firstOrNull()
    }
    
    /**
     * Check if text contains any URLs
     */
    fun containsUrl(text: String): Boolean {
        return findFirstUrl(text) != null
    }
    
    /**
     * Extract URLs and replace them with placeholders in text
     */
    fun extractUrlsWithPlaceholders(text: String): Pair<String, List<String>> {
        val urls = findUrls(text)
        var processedText = text
        
        urls.forEachIndexed { index, url ->
            processedText = processedText.replace(url, "{{URL_$index}}")
        }
        
        return Pair(processedText, urls)
    }
    
    /**
     * Restore URLs from placeholders in text
     */
    fun restoreUrlsFromPlaceholders(text: String, urls: List<String>): String {
        var restoredText = text
        
        urls.forEachIndexed { index, url ->
            restoredText = restoredText.replace("{{URL_$index}}", url)
        }
        
        return restoredText
    }
    
    /**
     * Strip unmatched trailing parentheses from a URL.
     * Markdown-style links like `[text](url)` cause the regex to capture a trailing `)`.
     * We only strip trailing `)` when there's no matching `(` in the URL path.
     */
    private fun cleanTrailingParens(url: String): String {
        var cleaned = url
        while (cleaned.endsWith(')')) {
            val openCount = cleaned.count { it == '(' }
            val closeCount = cleaned.count { it == ')' }
            if (closeCount > openCount) {
                cleaned = cleaned.dropLast(1)
            } else break
        }
        return cleaned
    }

    /**
     * Validate if a string is a proper URL
     */
    private fun isValidUrl(url: String): Boolean {
        return try {
            val urlObj = java.net.URL(url)
            urlObj.protocol in listOf("http", "https", "ftp")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get domain from URL
     */
    fun getDomain(url: String): String? {
        return try {
            val urlObj = java.net.URL(url)
            urlObj.host.removePrefix("www.")
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if URL is likely an image
     */
    fun isImageUrl(url: String): Boolean {
        val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg")
        val lowerUrl = url.lowercase()
        return imageExtensions.any { lowerUrl.contains(it) }
    }
    
    /**
     * Check if URL is likely a video
     */
    fun isVideoUrl(url: String): Boolean {
        val videoExtensions = listOf(".mp4", ".webm", ".mov", ".avi", ".mkv", ".flv")
        val lowerUrl = url.lowercase()
        return videoExtensions.any { lowerUrl.contains(it) }
    }
}










