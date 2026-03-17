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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hd
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

        // Toolbar — auto-hides on single tap
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(Modifier.statusBarsPadding()) {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    },
                    windowInsets = WindowInsets(0),
                    actions = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    saveImageToGallery(context, urls[pagerState.currentPage])
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "Save image",
                                tint = Color.White
                            )
                        }
                        IconButton(
                            onClick = { useHd = !useHd }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Hd,
                                contentDescription = "Load HD",
                                tint = if (useHd) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
            }
        }
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
            val filename = "Mycelium_${UUID.randomUUID()}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
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
        } catch (_: Exception) { }
    }
}
