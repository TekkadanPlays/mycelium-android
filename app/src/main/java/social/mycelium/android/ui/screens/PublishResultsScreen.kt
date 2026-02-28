package social.mycelium.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.relay.RelayDeliveryTracker
import social.mycelium.android.relay.RelayHealthTracker

// ── Data models for per-relay aggregation ──

private data class RelayPublishStats(
    val relayUrl: String,
    val totalPublishes: Int,
    val successCount: Int,
    val failureCount: Int,
    val timeoutCount: Int,
    val results: List<RelayEventResult>
) {
    val successRate: Float get() = if (totalPublishes > 0) successCount.toFloat() / totalPublishes else 0f
    val displayUrl: String get() = relayUrl.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
}

private data class RelayEventResult(
    val eventId: String,
    val kind: Int,
    val timestamp: Long,
    val success: Boolean,
    val message: String
)

/**
 * Dedicated screen for viewing publish results. Two tabs:
 * - **Timeline**: Latest-first list of all publish events with per-relay breakdowns.
 * - **Per Relay**: Aggregated stats grouped by relay URL, sorted by failure rate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishResultsScreen(
    onBackClick: () -> Unit,
    onOpenRelayLog: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val publishReports by RelayHealthTracker.publishReports.collectAsState()
    val deliveryStats = remember { RelayDeliveryTracker.getStats() }

    // Tab state: 0 = Timeline, 1 = Per Relay
    var selectedTab by remember { mutableIntStateOf(0) }

    // Aggregate stats
    val totalPublishes = publishReports.size
    val totalSuccess = remember(publishReports) { publishReports.sumOf { it.successCount } }
    val totalFailures = remember(publishReports) { publishReports.sumOf { it.failureCount } }
    val overallSuccessRate = remember(totalSuccess, totalFailures) {
        val total = totalSuccess + totalFailures
        if (total > 0) totalSuccess.toFloat() / total else 0f
    }

    // Per-relay aggregation
    val relayStats = remember(publishReports) { aggregateByRelay(publishReports) }

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        Text(
                            text = "publish results",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )

                // Tab row
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Schedule, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Timeline", style = MaterialTheme.typography.labelMedium)
                                if (totalPublishes > 0) {
                                    Spacer(Modifier.width(4.dp))
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ) {
                                        Text("$totalPublishes")
                                    }
                                }
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Router, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Per Relay", style = MaterialTheme.typography.labelMedium)
                                if (relayStats.isNotEmpty()) {
                                    Spacer(Modifier.width(4.dp))
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ) {
                                        Text("${relayStats.size}")
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        if (publishReports.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.Publish,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "No publishes yet",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Results will appear when you publish notes, replies, or reactions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            when (selectedTab) {
                0 -> TimelineTab(
                    publishReports = publishReports,
                    overallSuccessRate = overallSuccessRate,
                    totalSuccess = totalSuccess,
                    totalFailures = totalFailures,
                    modifier = modifier.padding(paddingValues)
                )
                1 -> PerRelayTab(
                    relayStats = relayStats,
                    deliveryStats = deliveryStats,
                    onOpenRelayLog = onOpenRelayLog,
                    modifier = modifier.padding(paddingValues)
                )
            }
        }
    }
}

// ── Timeline Tab ──

@Composable
private fun TimelineTab(
    publishReports: List<RelayHealthTracker.PublishReport>,
    overallSuccessRate: Float,
    totalSuccess: Int,
    totalFailures: Int,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Summary banner
        item(key = "timeline_summary") {
            TimelineSummaryCard(
                overallSuccessRate = overallSuccessRate,
                totalSuccess = totalSuccess,
                totalFailures = totalFailures,
                totalPublishes = publishReports.size
            )
        }

        // Publish events, newest first (already sorted by RelayHealthTracker)
        items(publishReports, key = { "tl_${it.eventId}" }) { report ->
            TimelinePublishRow(report = report)
        }
    }
}

@Composable
private fun TimelineSummaryCard(
    overallSuccessRate: Float,
    totalSuccess: Int,
    totalFailures: Int,
    totalPublishes: Int
) {
    val rateColor = when {
        overallSuccessRate >= 0.9f -> Color(0xFF4CAF50)
        overallSuccessRate >= 0.7f -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.error
    }
    val animatedRate by animateFloatAsState(
        targetValue = overallSuccessRate,
        animationSpec = tween(600),
        label = "successRate"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Success rate circle
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = rateColor.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "${(animatedRate * 100).toInt()}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = rateColor
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Relay Success Rate",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "$totalPublishes events · $totalSuccess OK · $totalFailures failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TimelinePublishRow(report: RelayHealthTracker.PublishReport) {
    var expanded by remember { mutableStateOf(false) }
    val kindLabel = kindToLabel(report.kind)
    val timeAgo = formatTimeAgo(report.timestamp)
    val hasFailures = report.hasFailures
    val successFraction = if (report.targetRelayCount > 0)
        report.successCount.toFloat() / report.targetRelayCount else 0f

    val barColor = when {
        successFraction >= 1f -> Color(0xFF4CAF50)
        successFraction >= 0.7f -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = if (hasFailures)
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        else
            MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .animateContentSize()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Kind icon
                Icon(
                    imageVector = kindToIcon(report.kind),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (hasFailures) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = kindLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(8.dp))
                        // Mini success bar
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(successFraction)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(barColor)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${report.successCount}/${report.targetRelayCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (hasFailures) {
                        val failedRelays = report.results.filter { !it.success }
                            .joinToString(", ") { it.relayUrl.removePrefix("wss://").removeSuffix("/").take(20) }
                        Text(
                            text = "Failed: $failedRelays",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = timeAgo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(if (expanded) 0f else -90f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            // Expanded: per-relay results
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    // Event ID
                    Text(
                        text = "Event: ${report.eventId.take(16)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    // Sort: failures first, then successes
                    val sortedResults = report.results.sortedBy { it.success }
                    sortedResults.forEach { result ->
                        RelayResultRow(result = result)
                    }
                }
            }
        }
    }
}

// ── Per Relay Tab ──

@Composable
private fun PerRelayTab(
    relayStats: List<RelayPublishStats>,
    deliveryStats: Map<String, RelayDeliveryTracker.RelayStats>,
    onOpenRelayLog: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Sorted by failure rate descending (worst relays first), then by total publishes
        val sorted = relayStats.sortedWith(
            compareByDescending<RelayPublishStats> { it.failureCount }
                .thenByDescending { it.totalPublishes }
        )

        items(sorted, key = { "relay_${it.relayUrl}" }) { stats ->
            RelayPublishCard(
                stats = stats,
                deliveryStat = deliveryStats[stats.relayUrl],
                onRelayClick = { onOpenRelayLog(stats.relayUrl) }
            )
        }
    }
}

@Composable
private fun RelayPublishCard(
    stats: RelayPublishStats,
    deliveryStat: RelayDeliveryTracker.RelayStats?,
    onRelayClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val successRate = stats.successRate
    val rateColor = when {
        successRate >= 0.95f -> Color(0xFF4CAF50)
        successRate >= 0.7f -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.error
    }
    val animatedRate by animateFloatAsState(
        targetValue = successRate,
        animationSpec = tween(400),
        label = "relayRate"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.5.dp
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .animateContentSize()
        ) {
            // Header: relay name + success rate badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status indicator
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(rateColor)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stats.displayUrl,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${stats.totalPublishes} publishes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (stats.failureCount > 0) {
                            Text(
                                text = " · ${stats.failureCount} failed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (stats.timeoutCount > 0) {
                            Text(
                                text = " · ${stats.timeoutCount} timeout",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFA726)
                            )
                        }
                    }
                }

                // Success rate badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = rateColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${(animatedRate * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = rateColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (expanded) 0f else -90f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            // Success bar
            Spacer(Modifier.height(8.dp))
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
                        .fillMaxWidth(animatedRate)
                        .clip(RoundedCornerShape(2.dp))
                        .background(rateColor)
                )
            }

            // Outbox delivery stats (from Thompson Sampling)
            if (deliveryStat != null && deliveryStat.expected >= 1.0) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.TrendingUp,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Outbox delivery: ${(deliveryStat.successRate * 100).toInt()}% " +
                            "(${deliveryStat.delivered.toInt()}/${deliveryStat.expected.toInt()} sessions)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            // Expanded: individual event results for this relay
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    // "View relay log" button
                    TextButton(
                        onClick = onRelayClick,
                        modifier = Modifier.align(Alignment.End),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            "View relay log",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.ChevronRight,
                            null,
                            Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    // Show latest events first, cap at 20
                    stats.results.sortedByDescending { it.timestamp }.take(20).forEach { result ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (result.success) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = if (result.success) Color(0xFF4CAF50)
                                       else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = kindToLabel(result.kind),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(48.dp)
                            )
                            Text(
                                text = formatTimeAgo(result.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (result.message.isNotBlank() && !result.success) {
                                Text(
                                    text = result.message.take(24),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (stats.results.size > 20) {
                        Text(
                            text = "+${stats.results.size - 20} more…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Shared components ──

@Composable
private fun RelayResultRow(result: RelayHealthTracker.PublishRelayResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (result.success) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = if (result.success) Color(0xFF4CAF50)
                   else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = result.relayUrl.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (result.message.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = result.message.take(30),
                style = MaterialTheme.typography.labelSmall,
                color = if (result.success) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Utility functions ──

private fun kindToLabel(kind: Int): String = when (kind) {
    0 -> "Profile"
    1 -> "Note"
    3 -> "Contacts"
    6 -> "Repost"
    7 -> "Reaction"
    11 -> "Topic"
    1111 -> "Reply"
    9734 -> "Zap Req"
    10002 -> "Relay List"
    else -> "Kind $kind"
}

private fun kindToIcon(kind: Int) = when (kind) {
    1 -> Icons.Outlined.Edit
    6 -> Icons.Outlined.Repeat
    7 -> Icons.Outlined.FavoriteBorder
    11 -> Icons.Outlined.Forum
    1111 -> Icons.Outlined.Reply
    9734 -> Icons.Outlined.ElectricBolt
    else -> Icons.Outlined.Send
}

private fun formatTimeAgo(timestamp: Long): String {
    val ageMs = System.currentTimeMillis() - timestamp
    return when {
        ageMs < 60_000 -> "just now"
        ageMs < 3_600_000 -> "${ageMs / 60_000}m ago"
        ageMs < 86_400_000 -> "${ageMs / 3_600_000}h ago"
        else -> "${ageMs / 86_400_000}d ago"
    }
}

private fun aggregateByRelay(
    reports: List<RelayHealthTracker.PublishReport>
): List<RelayPublishStats> {
    val byRelay = mutableMapOf<String, MutableList<RelayEventResult>>()

    for (report in reports) {
        for (result in report.results) {
            byRelay.getOrPut(result.relayUrl) { mutableListOf() }.add(
                RelayEventResult(
                    eventId = report.eventId,
                    kind = report.kind,
                    timestamp = report.timestamp,
                    success = result.success,
                    message = result.message
                )
            )
        }
    }

    return byRelay.map { (url, results) ->
        RelayPublishStats(
            relayUrl = url,
            totalPublishes = results.size,
            successCount = results.count { it.success },
            failureCount = results.count { !it.success && !it.message.contains("timeout", ignoreCase = true) },
            timeoutCount = results.count { !it.success && it.message.contains("timeout", ignoreCase = true) },
            results = results
        )
    }
}
