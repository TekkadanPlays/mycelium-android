package social.mycelium.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.cybin.nip19.NPub
import com.example.cybin.nip19.Nip19Parser
import com.example.cybin.nip19.toNpub
import social.mycelium.android.repository.ProfileMetadataCache

/**
 * Screen for starting a new DM conversation.
 * Shows a search field and a list of known profiles to pick from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewDmScreen(
    onBackClick: () -> Unit,
    onPeerSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val profileCache = ProfileMetadataCache.getInstance()

    // Get all cached profiles and filter by query
    val allProfiles = remember { profileCache.getAllCached() }
    val filtered = remember(query, allProfiles) {
        if (query.isBlank()) {
            allProfiles.entries
                .sortedBy { it.value.username.lowercase().ifBlank { it.value.displayName.lowercase().ifBlank { "zzz" } } }
                .take(50)
        } else {
            val q = query.lowercase()
            allProfiles.entries
                .filter { (pubkey, meta) ->
                    meta.username.lowercase().contains(q) ||
                    meta.displayName.lowercase().contains(q) ||
                    meta.nip05?.lowercase()?.contains(q) == true ||
                    pubkey.startsWith(q) ||
                    (try { pubkey.toNpub() } catch (_: Exception) { "" }).contains(q)
                }
                .sortedBy { it.value.username.lowercase().ifBlank { it.value.displayName.lowercase().ifBlank { "zzz" } } }
                .take(50)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Message") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search field
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search by name or paste npub/hex...") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Direct npub/hex entry — if the query looks like a pubkey, show a "Message this user" option
            val directPubkey = remember(query) {
                val trimmed = query.trim()
                when {
                    trimmed.startsWith("npub1") || trimmed.startsWith("nostr:npub1") -> {
                        try {
                            val parsed = Nip19Parser.uriToRoute(trimmed)
                            (parsed?.entity as? NPub)?.hex
                        } catch (_: Exception) { null }
                    }
                    trimmed.length == 64 && trimmed.all { it in '0'..'9' || it in 'a'..'f' } -> trimmed
                    else -> null
                }
            }

            if (directPubkey != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { onPeerSelected(directPubkey) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Message ${directPubkey.take(8)}...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Profile list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filtered, key = { it.key }) { (pubkey, meta) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPeerSelected(pubkey) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = meta.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = meta.displayName.ifBlank { meta.username.ifBlank { pubkey.take(12) + "..." } },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (meta.nip05 != null) {
                                Text(
                                    text = meta.nip05!!,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
