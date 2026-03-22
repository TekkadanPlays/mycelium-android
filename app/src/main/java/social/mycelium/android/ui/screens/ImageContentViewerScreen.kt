package social.mycelium.android.ui.screens

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hd
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.UUID

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageContentViewerScreen(
    urls: List<String>,
    initialIndex: Int,
    onBackClick: () -> Unit,
    onPageChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (urls.isEmpty()) {
        LaunchedEffect(Unit) { onBackClick() }
        return
    }
    val context = LocalContext.current
    val pagerState = rememberPagerState(
        pageCount = { urls.size },
        initialPage = initialIndex.coerceIn(0, urls.size - 1)
    )
    // Per-page HD state: each image starts at standard resolution
    var useHd by remember { mutableStateOf(false) }
    val screenWidthPx = remember { context.resources.displayMetrics.widthPixels }
    var showControls by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        if (scale > 1f) {
            // Faster pan when zoomed (2.2x multiplier)
            offsetX += panChange.x * 2.2f
            offsetY += panChange.y * 2.2f
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }
    val scope = rememberCoroutineScope()

    // Reset zoom and HD when swiping to a different page; report page change
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        useHd = false
        onPageChanged(pagerState.currentPage)
    }

    // Consume back gesture so it only closes the viewer and does not pop the underlying screen
    BackHandler(onBack = onBackClick)

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = scale <= 1f // disable paging while zoomed in
        ) { page ->
            val url = urls[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    // Only intercept pan/zoom gestures when zoomed in; at 1x let pager handle swipes
                    .then(
                        if (scale > 1f) Modifier.transformable(transformState)
                        else Modifier
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // Single tap toggles controls visibility
                                showControls = !showControls
                            },
                            onDoubleTap = {
                                // Toggle between 1x and 2.5x zoom on double tap
                                if (scale > 1.1f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(url)
                        .crossfade(150)
                        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .apply {
                            if (useHd) {
                                size(coil.size.Size.ORIGINAL)
                            } else {
                                size(screenWidthPx, screenWidthPx)
                            }
                        }
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Indicator dots when multiple images (auto-hide with controls)
        AnimatedVisibility(
            visible = showControls && urls.size > 1,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier.padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(urls.size) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (pagerState.currentPage == index) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (pagerState.currentPage == index)
                                    MaterialTheme.colorScheme.primary
                                else
                                    Color.White.copy(alpha = 0.5f)
                            )
                    )
                }
            }
        }

        // Floating controls — minimal icons that don't cover the image
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                // Back button — top left
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Download + HD — top right
                Row(
                    modifier = Modifier.align(Alignment.TopEnd),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                saveImageToGallery(context, urls[pagerState.currentPage])
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Save image",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(
                        onClick = { useHd = !useHd },
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (useHd) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                else Color.Black.copy(alpha = 0.5f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Hd,
                            contentDescription = "Load HD",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun mimeTypeFromUrl(urlString: String): Pair<String, String> {
    val lower = urlString.lowercase().split("?").first()
    return when {
        lower.endsWith(".png") -> "image/png" to ".png"
        lower.endsWith(".gif") -> "image/gif" to ".gif"
        lower.endsWith(".webp") -> "image/webp" to ".webp"
        lower.endsWith(".svg") -> "image/svg+xml" to ".svg"
        else -> "image/jpeg" to ".jpg"
    }
}

private suspend fun saveImageToGallery(context: android.content.Context, urlString: String) {
    withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val connection = url.openConnection()
            connection.connect()
            val inputStream = connection.getInputStream()
            val bytes = inputStream.readBytes()
            inputStream.close()
            if (bytes.isEmpty()) return@withContext
            val (mimeType, ext) = mimeTypeFromUrl(urlString)
            val filename = "Mycelium_${UUID.randomUUID()}$ext"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            }
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.insert(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    contentValues
                )
            } else {
                @Suppress("DEPRECATION")
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            }
            uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) } }
            // Show toast on main thread
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Image saved", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Failed to save image", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
