package social.mycelium.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import social.mycelium.android.cache.Nip11CacheManager
import social.mycelium.android.data.Note
import social.mycelium.android.data.RelayInformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Relay URLs for display (relayUrls if set, else single relayUrl). Normalizes trailing slashes for dedup. */
fun Note.displayRelayUrls(): List<String> {
    val raw = relayUrls.ifEmpty { listOfNotNull(relayUrl) }
    val seen = mutableSetOf<String>()
    return raw.filter { url -> seen.add(url.trimEnd('/').lowercase()) }
}

/** Max orbs shown in the stacked group before showing a "+N" badge. */
private const val MAX_VISIBLE_ORBS = 3
private val ORB_SIZE = 20.dp
private val ORB_OVERLAP = 10.dp

/**
 * Compact stacked relay orbs: up to [MAX_VISIBLE_ORBS] overlapping icons with a "+N" count
 * badge when there are more. Tapping the group opens a popup dialog listing all relays.
 * Scales to hundreds of relays without layout overflow.
 */
@Composable
fun RelayOrbs(
    relayUrls: List<String>,
    onRelayClick: (relayUrl: String) -> Unit = {},
    /** When set, tapping navigates to the dedicated relay list screen instead of opening a popup. */
    onNavigateToRelayList: ((List<String>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (relayUrls.isEmpty()) return
    val context = LocalContext.current
    val nip11 = remember(context) { Nip11CacheManager.getInstance(context) }
    var showRelayListDialog by remember { mutableStateOf(false) }

    val visibleUrls = relayUrls.take(MAX_VISIBLE_ORBS)
    val extraCount = (relayUrls.size - MAX_VISIBLE_ORBS).coerceAtLeast(0)

    Row(
        modifier = modifier.clickable {
            if (onNavigateToRelayList != null) onNavigateToRelayList(relayUrls)
            else showRelayListDialog = true
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(ORB_SIZE + ORB_OVERLAP * (visibleUrls.size - 1).coerceAtLeast(0)),
            contentAlignment = Alignment.CenterStart
        ) {
            visibleUrls.forEachIndexed { index, relayUrl ->
                Box(
                    modifier = Modifier
                        .offset(x = ORB_OVERLAP * index)
                        .size(ORB_SIZE)
                        .clip(CircleShape)
                        .zIndex((MAX_VISIBLE_ORBS - index).toFloat()),
                    contentAlignment = Alignment.Center
                ) {
                    RelayOrbIcon(relayUrl = relayUrl, nip11 = nip11, context = context)
                }
            }
        }
        if (extraCount > 0 || relayUrls.size > 1) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = if (extraCount > 0) "+$extraCount" else "${relayUrls.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }

    if (showRelayListDialog) {
        RelayListPopup(
            relayUrls = relayUrls,
            onRelayClick = onRelayClick,
            onDismiss = { showRelayListDialog = false }
        )
    }
}

/** Single relay orb icon — shows NIP-11 icon if cached, else Router fallback. */
@Composable
private fun RelayOrbIcon(relayUrl: String, nip11: Nip11CacheManager, context: android.content.Context) {
    var iconUrl by remember(relayUrl) { mutableStateOf(nip11.getCachedRelayInfo(relayUrl)?.icon) }
    LaunchedEffect(relayUrl) {
        if (iconUrl.isNullOrBlank()) {
            withContext(Dispatchers.IO) { nip11.getRelayInfo(relayUrl)?.icon }?.let { iconUrl = it }
        }
    }
    if (!iconUrl.isNullOrBlank()) {
        var loadFailed by remember(iconUrl) { mutableStateOf(false) }
        if (!loadFailed) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(iconUrl)
                    .crossfade(false)
                    .size(44)
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
            RelayOrbFallback()
        }
    } else {
        RelayOrbFallback()
    }
}

@Composable
private fun RelayOrbFallback() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Router,
            contentDescription = "Relay",
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Popup dialog listing all relays a note was found on. Each row shows the relay icon,
 * name (from NIP-11), and URL. Tapping a row calls [onRelayClick] and dismisses.
 */
@Composable
private fun RelayListPopup(
    relayUrls: List<String>,
    onRelayClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val nip11 = remember(context) { Nip11CacheManager.getInstance(context) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Seen on ${relayUrls.size} relay${if (relayUrls.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Spacer(modifier = Modifier.padding(4.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    relayUrls.forEach { url ->
                        RelayListRow(
                            relayUrl = url,
                            nip11 = nip11,
                            context = context,
                            onClick = {
                                onRelayClick(url)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelayListRow(
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            RelayOrbIcon(relayUrl = relayUrl, nip11 = nip11, context = context)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = relayUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * Dialog showing NIP-11 relay information for a given URL. Uses cached info from Nip11CacheManager;
 * optionally refreshes on show. Can be used from note cards (tap orb) or anywhere that has a relay URL.
 */
@Composable
fun RelayInfoDialog(
    relayUrl: String?,
    onDismiss: () -> Unit
) {
    if (relayUrl == null) return
    val context = LocalContext.current
    val nip11 = remember(context) { Nip11CacheManager.getInstance(context) }
    var info by remember(relayUrl) { mutableStateOf(nip11.getCachedRelayInfo(relayUrl)) }
    LaunchedEffect(relayUrl) {
        withContext(Dispatchers.IO) {
            nip11.getRelayInfo(relayUrl, forceRefresh = false)?.let { info = it }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Relay Information",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Spacer(modifier = Modifier.padding(16.dp))
                RelayInfoContent(relayUrl = relayUrl, info = info)
            }
        }
    }
}

@Composable
private fun RelayInfoContent(relayUrl: String, info: RelayInformation?) {
    val displayName = info?.name?.takeIf { it.isNotBlank() } ?: relayUrl
    Text(
        text = displayName,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = relayUrl,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.padding(16.dp))
    info?.description?.takeIf { it.isNotBlank() }?.let { description ->
        Column {
            Text(
                text = "Description",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.padding(4.dp))
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.padding(12.dp))
        }
    }
    info?.software?.takeIf { it.isNotBlank() }?.let { software ->
        Column {
            Text(
                text = "Software",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.padding(4.dp))
            Text(text = software, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.padding(12.dp))
        }
    }
    info?.contact?.takeIf { it.isNotBlank() }?.let { contact ->
        Column {
            Text(
                text = "Contact",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.padding(4.dp))
            Text(text = contact, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
