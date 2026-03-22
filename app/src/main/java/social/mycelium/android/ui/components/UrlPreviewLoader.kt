package social.mycelium.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.mycelium.android.data.UrlPreviewState
import social.mycelium.android.services.UrlPreviewCache
import social.mycelium.android.services.UrlPreviewService
import kotlinx.coroutines.launch

/**
 * Component that loads and displays URL previews
 */
@Composable
fun UrlPreviewLoader(
    url: String,
    modifier: Modifier = Modifier,
    urlPreviewService: UrlPreviewService,
    urlPreviewCache: UrlPreviewCache,
    onUrlClick: (String) -> Unit = {},
    onUrlLongClick: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    // Use mutableStateOf so Compose recomposes when the fetch completes.
    // derivedStateOf + ConcurrentHashMap was broken: HashMap mutations don't
    // trigger Compose snapshots, so the preview stayed on "Loading..." forever.
    var previewState by remember(url) {
        val cached = urlPreviewCache.get(url)
        mutableStateOf<UrlPreviewState>(
            if (cached != null) UrlPreviewState.Loaded(cached) else UrlPreviewState.Loading
        )
    }

    LaunchedEffect(url) {
        if (previewState is UrlPreviewState.Loaded) return@LaunchedEffect
        val cached = urlPreviewCache.get(url)
        if (cached != null) {
            previewState = UrlPreviewState.Loaded(cached)
            return@LaunchedEffect
        }
        if (!urlPreviewCache.isLoading(url)) {
            urlPreviewCache.setLoadingState(url, UrlPreviewState.Loading)
            val result = urlPreviewService.fetchPreview(url)
            urlPreviewCache.setLoadingState(url, result)
            previewState = result
        }
    }

    when (val currentState = previewState) {
        is UrlPreviewState.Loading -> {
            UrlPreviewLoadingCard(url = url, modifier = modifier)
        }
        is UrlPreviewState.Loaded -> {
            UrlPreviewCard(
                previewInfo = currentState.previewInfo,
                modifier = modifier,
                onUrlClick = onUrlClick,
                onUrlLongClick = onUrlLongClick
            )
        }
        is UrlPreviewState.Error -> {
            UrlPreviewErrorCard(
                url = url,
                error = currentState.message,
                modifier = modifier,
                onRetry = {
                    scope.launch {
                        urlPreviewCache.remove(url)
                        urlPreviewCache.setLoadingState(url, UrlPreviewState.Loading)
                        val result = urlPreviewService.fetchPreview(url)
                        urlPreviewCache.setLoadingState(url, result)
                    }
                }
            )
        }
    }
}

/**
 * Loading state — matches UrlPreviewCard layout (Surface + Row + 12/8dp padding)
 * so the transition to loaded state causes zero layout shift.
 */
@Composable
private fun UrlPreviewLoadingCard(
    url: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                SageLoadingIndicator(
                    size = 20.dp,
                    strokeWidth = 2.dp
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Error state — matches UrlPreviewCard layout (Surface + Row + 12/8dp padding)
 * so the transition causes zero layout shift.
 */
@Composable
private fun UrlPreviewErrorCard(
    url: String,
    error: String,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
