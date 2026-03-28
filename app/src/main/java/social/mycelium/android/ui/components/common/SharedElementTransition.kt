package social.mycelium.android.ui.components.common

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.CachePolicy
import coil.request.ImageRequest
import social.mycelium.android.data.Author
import social.mycelium.android.data.SampleData

/** Negative cache for avatar URLs that failed to load (404, etc). Prevents repeated network requests
 *  and log spam for permanently broken URLs. Entries expire after 5 minutes. */
private object AvatarFailureCache {
    private const val TTL_MS = 5 * 60 * 1000L
    private const val MAX_SIZE = 200
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun isFailed(url: String): Boolean {
        val ts = failures[url] ?: return false
        if (System.currentTimeMillis() - ts > TTL_MS) { failures.remove(url); return false }
        return true
    }
    fun markFailed(url: String) {
        failures[url] = System.currentTimeMillis()
        if (failures.size > MAX_SIZE) {
            val cutoff = System.currentTimeMillis() - TTL_MS
            failures.entries.removeIf { it.value < cutoff }
        }
    }
}

@Composable
fun SharedElementTransition(
    author: Author,
    isExpanded: Boolean,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Simplified transition - just show profile picture for now
    ProfilePicture(
        author = author,
        size = if (isExpanded) 120.dp else 40.dp,
        modifier = modifier
    )
}


@Composable
fun ProfilePicture(
    author: Author,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    val sizePx = remember(size) { with(density) { size.roundToPx() } }
    // Deterministic hue from pubkey so each user gets a unique, consistent fallback color
    val fallbackHue = remember(author.id) {
        (author.id.hashCode() and 0x7FFFFFFF) % 360
    }
    val fallbackBg = remember(fallbackHue) {
        Color.hsl(fallbackHue.toFloat(), 0.45f, 0.25f)
    }
    val fallbackFg = remember(fallbackHue) {
        Color.hsl(fallbackHue.toFloat(), 0.55f, 0.82f)
    }
    // Detect if displayName is a real name or a hex pubkey placeholder
    val isHexPlaceholder = remember(author.displayName) {
        author.displayName.isBlank() ||
                author.displayName.all { it.isLetterOrDigit() || it == '.' } &&
                author.displayName.length >= 8 &&
                author.displayName.endsWith("...")
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (author.avatarUrl != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else fallbackBg)
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (author.avatarUrl != null && !AvatarFailureCache.isFailed(author.avatarUrl!!)) {
            val context = LocalContext.current
            val avatarUrl = author.avatarUrl!!
            val imageRequest = remember(avatarUrl, sizePx) {
                ImageRequest.Builder(context)
                    .data(avatarUrl)
                    .crossfade(false)
                    .size(sizePx)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .placeholderMemoryCacheKey(avatarUrl)
                    .build()
            }
            SubcomposeAsyncImage(
                model = imageRequest,
                contentDescription = "Profile picture",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Error -> {
                        AvatarFailureCache.markFailed(avatarUrl)
                        // Show fallback initial/icon on error
                        Box(
                            modifier = Modifier.fillMaxSize().background(fallbackBg),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isHexPlaceholder) {
                                Icon(Icons.Filled.Person, null, tint = fallbackFg, modifier = Modifier.size(size * 0.55f))
                            } else {
                                val fs = with(density) { (size * 0.45f).toSp() }
                                Text(author.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = fs, lineHeight = fs, color = fallbackFg))
                            }
                        }
                    }
                    else -> SubcomposeAsyncImageContent()
                }
            }
        } else if (isHexPlaceholder) {
            // No real name — show person silhouette icon, scaled to container
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = "Unknown user",
                tint = fallbackFg,
                modifier = Modifier.size(size * 0.55f)
            )
        } else {
            // Real display name — show initial, scaled proportionally to container
            val fontSize = with(density) { (size * 0.45f).toSp() }
            Text(
                text = author.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    color = fallbackFg
                )
            )
        }
    }
}
