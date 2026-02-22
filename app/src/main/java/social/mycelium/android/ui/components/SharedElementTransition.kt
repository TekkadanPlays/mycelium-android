package social.mycelium.android.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import social.mycelium.android.data.Author
import social.mycelium.android.data.SampleData

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
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (author.avatarUrl != null) {
            val context = LocalContext.current
            val imageRequest = remember(author.avatarUrl, sizePx) {
                ImageRequest.Builder(context)
                    .data(author.avatarUrl)
                    .crossfade(true)
                    .size(sizePx)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .placeholderMemoryCacheKey(author.avatarUrl)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = "Profile picture",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = author.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
