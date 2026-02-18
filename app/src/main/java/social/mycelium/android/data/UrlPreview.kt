package social.mycelium.android.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.net.URL

/**
 * Data model for URL preview information
 */
@Immutable
@Serializable
data class UrlPreviewInfo(
    val url: String,
    val title: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val siteName: String = "",
    val mimeType: String = "text/html"
) {
    @kotlinx.serialization.Transient
    val verifiedUrl: URL? = runCatching { URL(url) }.getOrNull()
    
    val imageUrlFullPath: String
        get() = if (imageUrl.startsWith("/") && verifiedUrl != null) {
            URL(verifiedUrl, imageUrl).toString()
        } else {
            imageUrl
        }
    
    fun hasCompleteInfo(): Boolean = title.isNotEmpty() && description.isNotEmpty() && imageUrl.isNotEmpty()
    
    fun hasBasicInfo(): Boolean = title.isNotEmpty() || imageUrl.isNotEmpty()

    /** Root domain for display (e.g. "irishstar.com" from full URL). */
    val rootDomain: String
        get() = verifiedUrl?.host ?: url
}

/**
 * States for URL preview loading
 */
sealed class UrlPreviewState {
    object Loading : UrlPreviewState()
    data class Loaded(val previewInfo: UrlPreviewInfo) : UrlPreviewState()
    data class Error(val message: String) : UrlPreviewState()
}
