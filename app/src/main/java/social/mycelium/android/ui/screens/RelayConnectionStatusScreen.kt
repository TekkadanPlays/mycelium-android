package social.mycelium.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.RelayEndpointStatus
import social.mycelium.android.relay.RelayHealthInfo
import social.mycelium.android.relay.RelayHealthTracker

/**
 * Live relay connection status page. Shows every relay in the current subscription
 * with its real-time connection state, health metrics, and tap-through to relay log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayConnectionStatusScreen(
    onBackClick: () -> Unit = {},
    onRelayClick: (String) -> Unit = {}
) {
    val perRelayState by RelayConnectionStateMachine.getInstance().perRelayState.collectAsState()
    val healthByRelay by RelayHealthTracker.healthByRelay.collectAsState()

    val entries = remember(perRelayState) {
        perRelayState.entries
            .sortedWith(
                compareBy<Map.Entry<String, RelayEndpointStatus>> {
                    when (it.value) {
                        RelayEndpointStatus.Connected -> 0
                        RelayEndpointStatus.Connecting -> 1
                        RelayEndpointStatus.Failed -> 2
                    }
                }.thenBy { it.key }
            )
    }

    val connectedCount = entries.count { it.value == RelayEndpointStatus.Connected }
    val connectingCount = entries.count { it.value == RelayEndpointStatus.Connecting }
    val failedCount = entries.count { it.value == RelayEndpointStatus.Failed }
    val totalCount = entries.size

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Relay Connections", style = MaterialTheme.typography.titleMedium)
                        if (totalCount > 0) {
                            Text(
                                text = buildString {
                                    append("$connectedCount connected")
                                    if (connectingCount > 0) append(" \u00b7 $connectingCount connecting")
                                    if (failedCount > 0) append(" \u00b7 $failedCount failed")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        if (totalCount == 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No active relay connections",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(entries, key = { it.key }) { (url, status) ->
                    val health = healthByRelay[url.trim().removeSuffix("/")]
                    RelayConnectionRow(
                        url = url,
                        status = status,
                        health = health,
                        onClick = { onRelayClick(url) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RelayConnectionRow(
    url: String,
    status: RelayEndpointStatus,
    health: RelayHealthInfo?,
    onClick: () -> Unit
) {
    val statusColor = when (status) {
        RelayEndpointStatus.Connected -> Color(0xFF4CAF50)
        RelayEndpointStatus.Connecting -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        RelayEndpointStatus.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    }
    val animColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(400),
        label = "row_status_color"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RectangleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status orb
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(animColor, shape = RectangleShape)
            )

            Spacer(Modifier.width(12.dp))

            // Relay info
            Column(modifier = Modifier.weight(1f)) {
                // Display name: strip wss:// prefix for readability
                val displayName = url
                    .removePrefix("wss://")
                    .removePrefix("ws://")
                    .removeSuffix("/")
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Subtitle: status + health metrics
                val subtitle = buildString {
                    append(when (status) {
                        RelayEndpointStatus.Connected -> "Connected"
                        RelayEndpointStatus.Connecting -> "Connecting\u2026"
                        RelayEndpointStatus.Failed -> "Failed"
                    })
                    if (health != null) {
                        if (health.connectTimeMs > 0 && status == RelayEndpointStatus.Connected) {
                            append(" · ${health.connectTimeMs}ms")
                        }
                        if (health.eventsReceived > 0) {
                            append(" \u00b7 ${formatEventCount(health.eventsReceived)} events")
                        }
                        if (health.isFlagged) {
                            append(" \u00b7 Flagged")
                        }
                        if (health.lastError != null && status == RelayEndpointStatus.Failed) {
                            append(" \u00b7 ${health.lastError!!.take(40)}")
                        }
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        health?.isFlagged == true -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                        status == RelayEndpointStatus.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "View relay details",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}

private fun formatEventCount(count: Long): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}M"
    count >= 1_000 -> "${count / 1_000}K"
    else -> "$count"
}
