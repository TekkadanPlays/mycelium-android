package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.annotation.ExperimentalCoilApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Composable
fun DataStorageSettingsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text("Data and Storage", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Cache ──
            DataStorageSectionHeader("Cache")

            var clearImageCacheClicked by remember { mutableStateOf(false) }
            var showImageCacheDialog by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showImageCacheDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Clear image cache", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (clearImageCacheClicked) "Cache cleared" else "Free up space by clearing cached images and profile pictures",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (clearImageCacheClicked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (showImageCacheDialog) {
                AlertDialog(
                    onDismissRequest = { showImageCacheDialog = false },
                    title = { Text("Clear image cache?") },
                    text = { Text("This will remove all cached images and profile pictures. They will be re-downloaded as needed.") },
                    confirmButton = {
                        TextButton(onClick = {
                            Coil.imageLoader(context).diskCache?.clear()
                            Coil.imageLoader(context).memoryCache?.clear()
                            clearImageCacheClicked = true
                            showImageCacheDialog = false
                        }) { Text("Clear") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showImageCacheDialog = false }) { Text("Cancel") }
                    }
                )
            }

            var clearRelayCacheClicked by remember { mutableStateOf(false) }
            var showRelayCacheDialog by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRelayCacheDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Clear relay info cache", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (clearRelayCacheClicked) "Cache cleared" else "Clear cached NIP-11 relay information and icons",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (clearRelayCacheClicked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (showRelayCacheDialog) {
                AlertDialog(
                    onDismissRequest = { showRelayCacheDialog = false },
                    title = { Text("Clear relay info cache?") },
                    text = { Text("This will remove cached relay metadata (NIP-11). Relay info will be re-fetched when needed.") },
                    confirmButton = {
                        TextButton(onClick = {
                            social.mycelium.android.cache.Nip11CacheManager.getInstance(context).clearAllCache()
                            clearRelayCacheClicked = true
                            showRelayCacheDialog = false
                        }) { Text("Clear") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRelayCacheDialog = false }) { Text("Cancel") }
                    }
                )
            }

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            // ── Storage ──
            DataStorageSectionHeader("Storage")

            // TODO: Show actual cache sizes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Cache usage", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Image cache, relay data, and profile metadata",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DataStorageSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}
