package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.mycelium.android.relay.RelayHealthTracker
import social.mycelium.android.relay.RelayHealthInfo

/**
 * Dedicated screen showing all flagged and blocked relays with detailed status,
 * auto-block expiry countdowns, and actions (block/unblock/dismiss).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayNeedsAttentionScreen(
    onBack: () -> Unit,
    onOpenRelayLog: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val healthMap by RelayHealthTracker.healthByRelay.collectAsState()
    val flaggedRelays by RelayHealthTracker.flaggedRelays.collectAsState()
    val blockedRelays by RelayHealthTracker.blockedRelays.collectAsState()
    val autoBlockExpiryMap by RelayHealthTracker.autoBlockExpiryMap.collectAsState()

    val troubleRelays = remember(flaggedRelays, blockedRelays) {
        (flaggedRelays + blockedRelays).distinct().sorted()
    }

    // Separate into categories
    val autoBlocked = remember(blockedRelays, autoBlockExpiryMap) {
        blockedRelays.filter { it in autoBlockExpiryMap }.sorted()
    }
    val manuallyBlocked = remember(blockedRelays, autoBlockExpiryMap) {
        blockedRelays.filter { it !in autoBlockExpiryMap }.sorted()
    }
    val flaggedOnly = remember(flaggedRelays, blockedRelays) {
        (flaggedRelays - blockedRelays).sorted()
    }

    // Live countdown tick (every 30s)
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            tick++
            RelayHealthTracker.releaseExpiredAutoBlocks()
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text("Relays", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        modifier = modifier
    ) { paddingValues ->
        if (troubleRelays.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle, null,
                        Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "All relays healthy",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "No relays need attention right now",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Summary pill
                item(key = "summary") {
                    Text(
                        "${troubleRelays.size} relay${if (troubleRelays.size != 1) "s" else ""} need attention",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                // Auto-blocked section
                if (autoBlocked.isNotEmpty()) {
                    item(key = "header_auto") {
                        AttentionSectionHeader("Auto-blocked", autoBlocked.size)
                    }
                    items(autoBlocked, key = { "auto_$it" }) { url ->
                        AttentionRelayCard(
                            url = url,
                            health = healthMap[url],
                            isBlocked = true,
                            autoBlockExpiry = autoBlockExpiryMap[url],
                            currentTimeMs = System.currentTimeMillis(),
                            onUnblock = { RelayHealthTracker.unblockRelay(url) },
                            onBlock = {},
                            onDismiss = {},
                            onClick = { onOpenRelayLog(url) }
                        )
                    }
                }

                // Manually blocked section
                if (manuallyBlocked.isNotEmpty()) {
                    item(key = "header_manual") {
                        AttentionSectionHeader("Blocked", manuallyBlocked.size)
                    }
                    items(manuallyBlocked, key = { "manual_$it" }) { url ->
                        AttentionRelayCard(
                            url = url,
                            health = healthMap[url],
                            isBlocked = true,
                            autoBlockExpiry = null,
                            currentTimeMs = System.currentTimeMillis(),
                            onUnblock = { RelayHealthTracker.unblockRelay(url) },
                            onBlock = {},
                            onDismiss = {},
                            onClick = { onOpenRelayLog(url) }
                        )
                    }
                }

                // Flagged (not blocked) section
                if (flaggedOnly.isNotEmpty()) {
                    item(key = "header_flagged") {
                        AttentionSectionHeader("Flagged", flaggedOnly.size)
                    }
                    items(flaggedOnly, key = { "flagged_$it" }) { url ->
                        AttentionRelayCard(
                            url = url,
                            health = healthMap[url],
                            isBlocked = false,
                            autoBlockExpiry = null,
                            currentTimeMs = System.currentTimeMillis(),
                            onUnblock = {},
                            onBlock = { RelayHealthTracker.blockRelay(url) },
                            onDismiss = { RelayHealthTracker.unflagRelay(url) },
                            onClick = { onOpenRelayLog(url) }
                        )
                    }
                }

                item(key = "bottom_spacer") { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun AttentionSectionHeader(label: String, count: Int) {
    Text(
        "$label ($count)",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttentionRelayCard(
    url: String,
    health: RelayHealthInfo?,
    isBlocked: Boolean,
    autoBlockExpiry: Long?,
    currentTimeMs: Long,
    onUnblock: () -> Unit,
    onBlock: () -> Unit,
    onDismiss: () -> Unit,
    onClick: () -> Unit
) {
    val displayName = url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Status indicator
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isBlocked) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.tertiary
                    )
            )
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                // Relay URL
                Text(
                    displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Status line
                val statusText = when {
                    isBlocked && autoBlockExpiry != null -> {
                        val remainMs = (autoBlockExpiry - currentTimeMs).coerceAtLeast(0)
                        val remainH = (remainMs / 3600000).toInt()
                        val remainM = ((remainMs % 3600000) / 60000).toInt()
                        "Auto-blocked · ${remainH}h ${remainM}m left"
                    }
                    isBlocked -> "Blocked"
                    health != null && health.consecutiveFailures > 0 ->
                        "${health.consecutiveFailures} consecutive failures"
                    else -> "Flagged"
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isBlocked) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Error detail
                health?.lastError?.let { error ->
                    Text(
                        error.take(60),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Failure stats
                if (health != null && health.connectionAttempts > 0) {
                    val rate = (health.failureRate * 100).toInt()
                    Text(
                        "${health.connectionFailures}/${health.connectionAttempts} failed ($rate%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            // Actions
            if (isBlocked) {
                TextButton(
                    onClick = onUnblock,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) { Text("Unblock", style = MaterialTheme.typography.labelSmall) }
            } else {
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) { Text("Dismiss", style = MaterialTheme.typography.labelSmall) }
                TextButton(
                    onClick = onBlock,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Block", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
