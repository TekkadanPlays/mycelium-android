package social.mycelium.android.ui.screens

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.mycelium.android.ui.components.media.InlineVideoPlayer
import java.net.URL
import java.util.UUID

@Composable
fun VideoContentViewerScreen(
    urls: List<String>,
    initialIndex: Int,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    instanceKey: String? = null
) {
    if (urls.isEmpty()) {
        LaunchedEffect(Unit) { onBackClick() }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        pageCount = { urls.size },
        initialPage = initialIndex.coerceIn(0, urls.size - 1)
    )

    // Consume back gesture so it only closes the viewer without animation
    BackHandler(onBack = onBackClick)

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = urls.size > 1
        ) { page ->
            val url = urls[page]
            val isCurrentPage = pagerState.currentPage == page
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                InlineVideoPlayer(
                    url = url,
                    modifier = Modifier.fillMaxSize(),
                    autoPlay = true,
                    isVisible = isCurrentPage,
                    instanceKey = instanceKey,
                    onExitFullscreen = onBackClick
                )
            }
        }

        // Indicator dots when multiple videos
        if (urls.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
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

        // Floating controls — back button (top left) + download (top right)
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

            // Download button — top right
            IconButton(
                onClick = {
                    scope.launch {
                        saveVideoToGallery(context, urls[pagerState.currentPage])
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Save video",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

private fun videoMimeTypeFromUrl(urlString: String): Pair<String, String> {
    val lower = urlString.lowercase().split("?").first()
    return when {
        lower.endsWith(".webm") -> "video/webm" to ".webm"
        lower.endsWith(".mov") -> "video/quicktime" to ".mov"
        lower.endsWith(".mkv") -> "video/x-matroska" to ".mkv"
        lower.endsWith(".avi") -> "video/x-msvideo" to ".avi"
        else -> "video/mp4" to ".mp4"
    }
}

private suspend fun saveVideoToGallery(context: android.content.Context, urlString: String) {
    withContext(Dispatchers.Main) {
        android.widget.Toast.makeText(context, "Downloading video\u2026", android.widget.Toast.LENGTH_SHORT).show()
    }
    withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val connection = url.openConnection()
            connection.connect()
            val inputStream = connection.getInputStream()
            val bytes = inputStream.readBytes()
            inputStream.close()
            if (bytes.isEmpty()) return@withContext
            val (mimeType, ext) = videoMimeTypeFromUrl(urlString)
            val filename = "Mycelium_${UUID.randomUUID()}$ext"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Mycelium")
                }
            }
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.insert(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    contentValues
                )
            } else {
                @Suppress("DEPRECATION")
                context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            }
            uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) } }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Video saved", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Failed to save video", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
