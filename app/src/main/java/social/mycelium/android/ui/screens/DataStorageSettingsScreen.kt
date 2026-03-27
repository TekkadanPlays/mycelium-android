package social.mycelium.android.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import social.mycelium.android.db.AppDatabase

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Composable
fun DataStorageSettingsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Live database stats (refreshed every 5s)
    var eventCount by remember { mutableIntStateOf(0) }
    var eventJsonBytes by remember { mutableStateOf(0L) }
    var profileCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val db = AppDatabase.getInstance(context)
        while (true) {
            withContext(Dispatchers.IO) {
                try {
                    eventCount = db.eventDao().count()
                    eventJsonBytes = db.eventDao().totalJsonBytes()
                    profileCount = db.profileDao().count()
                } catch (_: Exception) {}
            }
            delay(5_000)
        }
    }

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
            // ── Database Storage ──
            DataStorageSectionHeader("Database")

            DatabaseStatsCard(
                eventCount = eventCount,
                eventJsonBytes = eventJsonBytes,
                profileCount = profileCount
            )

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

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

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DatabaseStatsCard(
    eventCount: Int,
    eventJsonBytes: Long,
    profileCount: Int
) {
    val estimatedDbBytes = (eventJsonBytes * 1.5).toLong()
    val sizeLabel = formatDataBytes(estimatedDbBytes)
    val jsonSizeLabel = formatDataBytes(eventJsonBytes)

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Storage, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Local Database", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DataStatItem(label = "Events", value = formatDataCount(eventCount.toLong()), icon = Icons.Outlined.Description, accent = if (eventCount > 0) Color(0xFF4CAF50) else null)
                DataStatItem(label = "JSON", value = jsonSizeLabel, icon = Icons.Outlined.DataObject)
                DataStatItem(label = "Profiles", value = formatDataCount(profileCount.toLong()), icon = Icons.Outlined.People, accent = if (profileCount > 0) Color(0xFF4CAF50) else null)
                DataStatItem(label = "DB Size", value = sizeLabel, icon = Icons.Outlined.SdStorage)
            }
            if (eventCount > 0) {
                Spacer(Modifier.height(8.dp))
                val dataDir = android.os.Environment.getDataDirectory()
                val stat = android.os.StatFs(dataDir.path)
                val availableBytes = stat.availableBytes
                val totalBudget = availableBytes + estimatedDbBytes
                val usageFraction = if (totalBudget > 0) (estimatedDbBytes.toFloat() / totalBudget).coerceIn(0f, 1f) else 0f
                val barColor = when {
                    availableBytes < 500_000_000L -> MaterialTheme.colorScheme.error
                    availableBytes < 2_000_000_000L -> Color(0xFFFFA726)
                    else -> Color(0xFF4CAF50)
                }
                val animatedUsage by animateFloatAsState(
                    targetValue = usageFraction,
                    animationSpec = tween(400),
                    label = "storageUsage"
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Storage", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(60.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RectangleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedUsage.coerceAtLeast(0.01f))
                                .clip(RectangleShape)
                                .background(barColor)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("$sizeLabel / ${formatDataBytes(totalBudget)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp)
                }
                Spacer(Modifier.height(4.dp))
                val avgEventBytes = if (eventCount > 0) eventJsonBytes / eventCount else 0L
                val estimatedCapacity = if (avgEventBytes > 0) availableBytes / (avgEventBytes * 1.5).toLong() else 0L
                Text(
                    buildString {
                        if (avgEventBytes > 0) append("~${avgEventBytes} bytes/event")
                        if (estimatedCapacity > 0) append(" · ${formatDataBytes(availableBytes)} free (~${formatDataCount(estimatedCapacity)} more events)")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun DataStatItem(
    label: String,
    value: String,
    icon: ImageVector,
    accent: Color? = null
) {
    val color = accent ?: MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 4.dp).widthIn(min = 64.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = color.copy(alpha = 0.7f))
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
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

private fun formatDataBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private fun formatDataCount(count: Long): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}M"
    count >= 1_000 -> "${count / 1_000}K"
    else -> "$count"
}
