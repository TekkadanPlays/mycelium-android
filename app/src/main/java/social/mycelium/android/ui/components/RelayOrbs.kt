package social.mycelium.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

/** Relay URLs for display (relayUrls if set, else single relayUrl). */
fun Note.displayRelayUrls(): List<String> = relayUrls.ifEmpty { listOfNotNull(relayUrl) }.distinct()

/**
 * Small orbs in the top-right of a note card: each orb shows the relay's NIP-11 icon.
 * Tapping an orb opens the relay information dialog. When no icon is cached, a Router
 * fallback is shown; NIP-11 may be fetched in the background so the icon can appear.
 */
@Composable
fun RelayOrbs(
    relayUrls: List<String>,
    onRelayClick: (relayUrl: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (relayUrls.isEmpty()) return
    val context = LocalContext.current
    val nip11 = remember(context) { Nip11CacheManager.getInstance(context) }
    val urls = relayUrls.take(6)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        urls.forEach { relayUrl ->
            var iconUrl by remember(relayUrl) { mutableStateOf(nip11.getCachedRelayInfo(relayUrl)?.icon) }
            LaunchedEffect(relayUrl) {
                if (iconUrl.isNullOrBlank()) {
                    nip11.getRelayInfo(relayUrl)?.icon?.let { iconUrl = it }
                }
            }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable { onRelayClick(relayUrl) },
                contentAlignment = Alignment.Center
            ) {
                if (!iconUrl.isNullOrBlank()) {
                    var loadFailed by remember(iconUrl) { mutableStateOf(false) }
                    if (!loadFailed) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(iconUrl)
                                .crossfade(true)
                                .size(44) // 22dp * 2 for density — avoids decoding full-size images
                                .memoryCacheKey("relay_icon_$relayUrl")
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build(),
                            contentDescription = "Relay icon",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                            onError = { loadFailed = true }
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Router,
                            contentDescription = "Relay",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Router,
                        contentDescription = "Relay",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
