package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import social.mycelium.android.cache.Nip11CacheManager

/**
 * Full-screen relay list showing all relays a note was seen on.
 * Each relay row shows its NIP-11 icon, name, URL, description snippet,
 * and software badge. Tapping navigates to the relay log/detail screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteRelaysScreen(
    relayUrls: List<String>,
    onBackClick: () -> Unit,
    onRelayClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val nip11 = remember(context) { Nip11CacheManager.getInstance(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Seen on ${relayUrls.size} relay${if (relayUrls.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(relayUrls, key = { it }) { url ->
                NoteRelayRow(
                    relayUrl = url,
                    nip11 = nip11,
                    context = context,
                    onClick = { onRelayClick(url) }
                )
                if (url != relayUrls.last()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteRelayRow(
    relayUrl: String,
    nip11: Nip11CacheManager,
    context: android.content.Context,
    onClick: () -> Unit
) {
    var info by remember(relayUrl) { mutableStateOf(nip11.getCachedRelayInfo(relayUrl)) }
    LaunchedEffect(relayUrl) {
        if (info == null) {
            withContext(Dispatchers.IO) { nip11.getRelayInfo(relayUrl) }?.let { info = it }
        }
    }
    val displayName = info?.name?.takeIf { it.isNotBlank() }
        ?: relayUrl.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
    val description = info?.description?.takeIf { it.isNotBlank() }
    val software = info?.software?.takeIf { it.isNotBlank() }?.let { sw ->
        sw.substringAfterLast("/").substringAfterLast(":").take(30)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Relay icon
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            NoteRelayIcon(relayUrl = relayUrl, nip11 = nip11, context = context)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (software != null) {
                    Text(
                        text = software,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1
                    )
                }
            }
            Text(
                text = relayUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Resolve best icon URL: NIP-11 icon → NIP-11 image → favicon.ico */
private fun resolveNoteRelayIconUrl(info: social.mycelium.android.data.RelayInformation?, relayUrl: String): String? {
    info?.icon?.takeIf { it.isNotBlank() }?.let { return it }
    info?.image?.takeIf { it.isNotBlank() }?.let { return it }
    val httpBase = relayUrl.replace("wss://", "https://").replace("ws://", "http://").trimEnd('/')
    return "$httpBase/favicon.ico"
}

@Composable
private fun NoteRelayIcon(relayUrl: String, nip11: Nip11CacheManager, context: android.content.Context) {
    var iconUrl by remember(relayUrl) { mutableStateOf(resolveNoteRelayIconUrl(nip11.getCachedRelayInfo(relayUrl), relayUrl)) }
    LaunchedEffect(relayUrl) {
        if (nip11.getCachedRelayInfo(relayUrl) == null) {
            withContext(Dispatchers.IO) { nip11.getRelayInfo(relayUrl) }
            iconUrl = resolveNoteRelayIconUrl(nip11.getCachedRelayInfo(relayUrl), relayUrl)
        }
    }
    if (!iconUrl.isNullOrBlank()) {
        var loadFailed by remember(iconUrl) { mutableStateOf(false) }
        if (!loadFailed) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(iconUrl)
                    .crossfade(false)
                    .size(72)
                    .memoryCacheKey("relay_icon_$relayUrl")
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = "Relay icon",
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
                onError = { loadFailed = true }
            )
        } else {
            NoteRelayIconFallback()
        }
    } else {
        NoteRelayIconFallback()
    }
}

@Composable
private fun NoteRelayIconFallback() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Router,
            contentDescription = "Relay",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
