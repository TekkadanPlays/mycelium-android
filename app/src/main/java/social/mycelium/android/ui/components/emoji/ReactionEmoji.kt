package social.mycelium.android.ui.components.emoji

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Renders a reaction emoji: if the emoji is a NIP-30 custom emoji (:shortcode:) and
 * a URL is available in [customEmojiUrls], renders an inline image; otherwise renders
 * the emoji text as-is.
 */
@Composable
fun ReactionEmoji(
    emoji: String,
    customEmojiUrls: Map<String, String> = emptyMap(),
    fontSize: TextUnit = 13.sp,
    imageSize: Dp = 16.dp,
    style: TextStyle = TextStyle.Default,
    modifier: Modifier = Modifier
) {
    val url = customEmojiUrls[emoji]
    if (url != null) {
        // NIP-30 custom emoji with resolved URL
        AsyncImage(
            model = url,
            contentDescription = emoji.removeSurrounding(":"),
            modifier = modifier.size(imageSize)
        )
    } else if (isImageUrl(emoji)) {
        // GIF/image reaction: content is a direct image URL
        AsyncImage(
            model = emoji,
            contentDescription = "Image reaction",
            modifier = modifier.size(imageSize)
        )
    } else if (emoji.startsWith(":") && emoji.endsWith(":") && emoji.length > 2) {
        // Custom emoji shortcode without a resolved URL — show the shortcode name
        Text(
            text = emoji.removeSurrounding(":"),
            style = style.copy(fontSize = fontSize),
            modifier = modifier
        )
    } else {
        Text(
            text = emoji,
            style = style.copy(fontSize = fontSize),
            modifier = modifier
        )
    }
}

/** Check if a string looks like an image URL (http(s) ending with common image extensions). */
internal fun isImageUrl(s: String): Boolean {
    if (!s.startsWith("http://") && !s.startsWith("https://")) return false
    val lower = s.lowercase().split("?").first()
    return lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".png") ||
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".svg")
}
