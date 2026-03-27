package social.mycelium.android.ui.screens

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import social.mycelium.android.relay.RelayDeliveryTracker
import social.mycelium.android.relay.RelayHealthTracker

private fun kindLabel(kind: Int): String = when (kind) {
    1 -> "Note"
    6 -> "Repost"
    7 -> "Reaction"
    11 -> "Topic"
    1111 -> "Comment"
    9735 -> "Zap Receipt"
    1059 -> "Gift Wrap"
    30023 -> "Article"
    10002 -> "Relay List"
    3 -> "Contacts"
    0 -> "Metadata"
    else -> "Kind $kind"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDeliveryScreen(
    onBack: () -> Unit,
    onOpenRelayLog: (relayUrl: String) -> Unit = {},
    onProfileClick: (pubkey: String) -> Unit = {}
) {
    val publishReports by RelayHealthTracker.publishReports.collectAsState()
    val deliveryStats = remember { RelayDeliveryTracker.getStats() }
    val missedAuthors = remember { RelayDeliveryTracker.getMissedAuthors() }
    val scope = rememberCoroutineScope()

    val totalPublishes = publishReports.size
    val totalSuccess = publishReports.sumOf { it.successCount }
    val totalFailed = publishReports.sumOf { it.failureCount }
    val totalTargeted = publishReports.sumOf { it.targetRelayCount }
    val overallRate = if (totalTargeted > 0) (totalSuccess.toFloat() / totalTargeted * 100) else 0f

    val sortedDelivery = remember(deliveryStats) {
        deliveryStats.entries
            .filter { it.value.expected >= 1.0 }
            .sortedByDescending { it.value.expected }
    }

    var showAllPublishes by remember { mutableStateOf(false) }
    val maxVisible = 20
    val visibleReports = if (showAllPublishes) publishReports else publishReports.take(maxVisible)

    var showMissedAuthors by remember { mutableStateOf(false) }
    var showOutboxRelays by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text("Event delivery", fontWeight = FontWeight.Medium) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Aggregate Stats
            item(key = "stats") {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        DeliveryStatItem("Publishes", "$totalPublishes", Icons.Outlined.Send)
                        DeliveryStatItem(
                            "Success",
                            "%.0f%%".format(overallRate),
                            Icons.Outlined.CheckCircle,
                            accent = if (overallRate >= 80) Color(0xFF4CAF50) else if (overallRate >= 50) Color(0xFFFFA726) else MaterialTheme.colorScheme.error
                        )
                        DeliveryStatItem(
                            "Failed",
                            "$totalFailed",
                            Icons.Outlined.ErrorOutline,
                            accent = if (totalFailed > 0) MaterialTheme.colorScheme.error else null
                        )
                        DeliveryStatItem("Relays", "${sortedDelivery.size}", Icons.Outlined.Hub)
                    }
                }
            }

            // Recent Publishes header
            if (publishReports.isNotEmpty()) {
                item(key = "publishes_header") {
                    Text(
                        "Recent Publishes",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }

                items(visibleReports, key = { it.eventId }) { report ->
                    PublishReportCard(report = report, scope = scope, onOpenRelayLog = onOpenRelayLog)
                }

                if (!showAllPublishes && publishReports.size > maxVisible) {
                    item(key = "show_all_publishes") {
                        TextButton(onClick = { showAllPublishes = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Show all ${publishReports.size} publishes")
                        }
                    }
                }
            } else {
                item(key = "publishes_empty") {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Outlined.Send, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(8.dp))
                            Text("No publish activity yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Published events will appear here with per-relay delivery status.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }

            // Outbox learning (collapsed by default — this screen is about publish delivery, not relay rankings)
            if (sortedDelivery.isNotEmpty()) {
                item(key = "delivery_toggle") {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showOutboxRelays = !showOutboxRelays }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Outbox relay learning",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${sortedDelivery.size} relays · tap to ${if (showOutboxRelays) "hide" else "show"} (used internally to pick outbox relays)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            }
                            Icon(
                                if (showOutboxRelays) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                if (showOutboxRelays) {
                    val cap = 12
                    val visible = sortedDelivery.take(cap)
                    items(visible, key = { it.key }) { (relayUrl, stats) ->
                        OutboxDeliveryRow(
                            relayUrl = relayUrl,
                            stats = stats,
                            onClick = { onOpenRelayLog(relayUrl) }
                        )
                    }
                    if (sortedDelivery.size > cap) {
                        item(key = "delivery_more") {
                            Text(
                                "… and ${sortedDelivery.size - cap} more (open any relay from the list above to inspect)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Author Diagnostics
            if (missedAuthors.isNotEmpty()) {
                item(key = "missed_header") {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth().clickable { showMissedAuthors = !showMissedAuthors }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${missedAuthors.size} author${if (missedAuthors.size != 1) "s" else ""} consistently missed",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    if (showMissedAuthors) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    null, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            Text(
                                "These authors' events are not being delivered by their configured outbox relays.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            AnimatedVisibility(visible = showMissedAuthors) {
                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                    missedAuthors.take(20).forEach { pubkey ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onProfileClick(pubkey) }
                                                .padding(vertical = 3.dp)
                                        ) {
                                            Icon(Icons.Outlined.Person, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "${pubkey.take(8)}...${pubkey.takeLast(8)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                            )
                                        }
                                    }
                                    if (missedAuthors.size > 20) {
                                        Text(
                                            "and ${missedAuthors.size - 20} more",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun DeliveryStatItem(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color? = null
) {
    val color = accent ?: MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 72.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = color.copy(alpha = 0.7f))
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    }
}

@Composable
private fun PublishReportCard(
    report: RelayHealthTracker.PublishReport,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenRelayLog: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val canRetry = remember(report.eventId) { RelayHealthTracker.hasPublishedEvent(report.eventId) }
    val relativeTime = remember(report.timestamp) {
        DateUtils.getRelativeTimeSpanString(report.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }

    val statusColor = when {
        report.pendingCount > 0 -> Color(0xFFFFA726)
        report.failureCount > 0 -> MaterialTheme.colorScheme.error
        else -> Color(0xFF4CAF50)
    }
    val statusIcon = when {
        report.pendingCount > 0 -> Icons.Outlined.HourglassBottom
        report.failureCount > 0 -> Icons.Filled.ErrorOutline
        else -> Icons.Filled.CheckCircle
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(statusIcon, null, Modifier.size(18.dp), tint = statusColor)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        kindLabel(report.kind),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${report.successCount}/${report.targetRelayCount} relays" +
                            if (report.failureCount > 0) " · ${report.failureCount} failed" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(relativeTime, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }

            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    Spacer(Modifier.height(6.dp))

                    Text(
                        "Event: ${report.eventId.take(16)}...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(6.dp))

                    report.results.forEach { result ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenRelayLog(result.relayUrl) }
                                .padding(vertical = 3.dp)
                        ) {
                            Icon(
                                if (result.success) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                                null, Modifier.size(12.dp),
                                tint = if (result.success) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                result.relayUrl.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (!result.success && result.message.isNotBlank()) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    result.message.take(30),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    fontSize = 9.sp, maxLines = 1
                                )
                            }
                        }
                    }

                    if (canRetry && report.hasFailures) {
                        val failedUrls = report.results.filter { !it.success }.map { it.relayUrl }.toSet()
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    RelayHealthTracker.retryPublish(report.eventId, failedUrls)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry ${failedUrls.size} failed relay${if (failedUrls.size != 1) "s" else ""}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutboxDeliveryRow(
    relayUrl: String,
    stats: RelayDeliveryTracker.RelayStats,
    onClick: () -> Unit
) {
    val rate = (stats.successRate * 100).toFloat()
    val barColor = when {
        rate >= 80 -> Color(0xFF4CAF50)
        rate >= 50 -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.error
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    relayUrl.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((rate / 100f).coerceIn(0f, 1f).coerceAtLeast(0.01f))
                            .clip(RoundedCornerShape(2.dp))
                            .background(barColor)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("%.0f%%".format(rate), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = barColor)
                Text(
                    "%.0f/%.0f".format(stats.delivered, stats.expected),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }
    }
}
