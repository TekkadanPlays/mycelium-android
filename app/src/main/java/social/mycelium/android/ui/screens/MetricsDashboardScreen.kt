package social.mycelium.android.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import social.mycelium.android.debug.PipelineDiagnostics
import social.mycelium.android.pipeline.EnrichmentBudget
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.RelayHealthTracker

/**
 * Live metrics dashboard showing pipeline throughput, subscription budget,
 * relay slot utilization, and memory pressure. Refreshes every 2 seconds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricsDashboardScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pipelineSnap by remember { mutableStateOf(PipelineDiagnostics.snapshot()) }
    var budgetSnap by remember { mutableStateOf(EnrichmentBudget.snapshot()) }
    var slotSnapshots by remember { mutableStateOf(emptyList<com.example.cybin.relay.RelaySlotSnapshot>()) }
    var memoryInfo by remember { mutableStateOf(MemorySnapshot.capture()) }
    var healthCount by remember { mutableStateOf(HealthCounts()) }

    LaunchedEffect(Unit) {
        val sm = RelayConnectionStateMachine.getInstance()
        while (true) {
            pipelineSnap = PipelineDiagnostics.snapshot()
            budgetSnap = EnrichmentBudget.snapshot()
            slotSnapshots = try { sm.getRelaySlotSnapshots() } catch (_: Exception) { emptyList() }
            memoryInfo = MemorySnapshot.capture()
            healthCount = HealthCounts.from(RelayHealthTracker)
            delay(2_000)
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text("Metrics", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            PipelineThroughputCard(pipelineSnap)
            Spacer(Modifier.height(12.dp))

            SubscriptionBudgetCard(budgetSnap)
            Spacer(Modifier.height(12.dp))

            RelaySlotCard(slotSnapshots)
            Spacer(Modifier.height(12.dp))

            RelayHealthSummaryCard(healthCount)
            Spacer(Modifier.height(12.dp))

            MemoryCard(memoryInfo)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Data snapshots ──────────────────────────────────────────────────────────

private data class MemorySnapshot(
    val usedMb: Long,
    val maxMb: Long,
    val freeMb: Long,
) {
    val usedFraction: Float get() = if (maxMb > 0) usedMb.toFloat() / maxMb else 0f

    companion object {
        fun capture(): MemorySnapshot {
            val rt = Runtime.getRuntime()
            val maxMb = rt.maxMemory() / (1024 * 1024)
            val totalMb = rt.totalMemory() / (1024 * 1024)
            val freeMb = rt.freeMemory() / (1024 * 1024)
            val usedMb = totalMb - freeMb
            return MemorySnapshot(usedMb, maxMb, maxMb - usedMb)
        }
    }
}

private data class HealthCounts(
    val connected: Int = 0,
    val connecting: Int = 0,
    val failed: Int = 0,
    val flagged: Int = 0,
    val blocked: Int = 0,
    val totalRelays: Int = 0,
) {
    companion object {
        fun from(tracker: RelayHealthTracker): HealthCounts {
            val health = tracker.healthByRelay.value
            return HealthCounts(
                connected = health.count { it.value.consecutiveFailures == 0 && it.value.connectionAttempts > 0 },
                connecting = 0,
                failed = health.count { it.value.consecutiveFailures > 0 },
                flagged = tracker.flaggedRelays.value.size,
                blocked = tracker.blockedRelays.value.size,
                totalRelays = health.size,
            )
        }
    }
}

// ── Card composables ────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun MetricRow(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PipelineThroughputCard(snap: PipelineDiagnostics.DiagnosticsSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                SectionLabel("Pipeline Throughput")
            }
            Spacer(Modifier.height(8.dp))

            // Big number: events/sec
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    text = "${snap.eventsPerSec}",
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = if (snap.eventsPerSec > 50) MaterialTheme.colorScheme.error
                    else if (snap.eventsPerSec > 20) Color(0xFFFFA726)
                    else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "events/sec",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                modifier = Modifier.padding(vertical = 4.dp)
            )

            MetricRow("Total ingested", formatCount(snap.eventsIngested))
            MetricRow("Batch flushes", formatCount(snap.batchFlushes))
            MetricRow("Avg flush latency", "${snap.avgFlushLatencyMs}ms",
                color = when {
                    snap.avgFlushLatencyMs > 100 -> MaterialTheme.colorScheme.error
                    snap.avgFlushLatencyMs > 50 -> Color(0xFFFFA726)
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            MetricRow("DB commits", formatCount(snap.dbCommits))
            MetricRow("DB rows written", formatCount(snap.dbRows))
        }
    }
}

@Composable
private fun SubscriptionBudgetCard(snap: EnrichmentBudget.BudgetSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionLabel("Subscription Budget")
            Spacer(Modifier.height(8.dp))

            MetricRow("Active one-shot subs", "${snap.activeOneShot}")
            MetricRow("Active temp subs", "${snap.activeTemp}")
            MetricRow("Sub creates/sec", "${snap.createsPerSec}")
            MetricRow("Total creates", formatCount(snap.totalCreates))
            MetricRow("Total cancels", formatCount(snap.totalCancels))

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Text(
                text = "Semaphore slots",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            SemaphoreBar(
                "Quoted notes",
                snap.quoteSlotsAvailable,
                EnrichmentBudget.MAX_CONCURRENT_QUOTE_FETCHES
            )
            SemaphoreBar(
                "Profiles",
                snap.profileSlotsAvailable,
                EnrichmentBudget.MAX_CONCURRENT_PROFILE_FETCHES
            )
        }
    }
}

@Composable
private fun SemaphoreBar(label: String, available: Int, max: Int) {
    val used = max - available
    val fraction = if (max > 0) used.toFloat() / max else 0f
    val animatedFraction by animateFloatAsState(targetValue = fraction, label = "semaphore")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        LinearProgressIndicator(
            progress = { animatedFraction },
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = when {
                fraction >= 1f -> MaterialTheme.colorScheme.error
                fraction > 0.5f -> Color(0xFFFFA726)
                else -> MaterialTheme.colorScheme.primary
            },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$used/$max",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RelaySlotCard(snapshots: List<com.example.cybin.relay.RelaySlotSnapshot>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionLabel("Relay Slots")
            Spacer(Modifier.height(4.dp))

            if (snapshots.isEmpty()) {
                Text(
                    "No active relay connections",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val totalActive = snapshots.sumOf { it.activeCount }
                val totalQueued = snapshots.sumOf { it.queuedCount }
                val totalEose = snapshots.sumOf { it.eoseCount }

                MetricRow("Connected relays", "${snapshots.size}")
                MetricRow("Active subscriptions", "$totalActive")
                MetricRow("Queued", "$totalQueued",
                    color = if (totalQueued > 10) Color(0xFFFFA726) else MaterialTheme.colorScheme.onSurface
                )
                MetricRow("EOSE'd (reapable)", "$totalEose")

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // Per-relay breakdown (top 8 by active count)
                val topRelays = snapshots.sortedByDescending { it.activeCount + it.queuedCount }.take(8)
                for (snap in topRelays) {
                    RelaySlotRow(snap)
                }
                if (snapshots.size > 8) {
                    Text(
                        "+${snapshots.size - 8} more relays",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RelaySlotRow(snap: com.example.cybin.relay.RelaySlotSnapshot) {
    val shortUrl = snap.url
        .removePrefix("wss://")
        .removePrefix("ws://")
        .trimEnd('/')
    val fraction = if (snap.effectiveLimit > 0) snap.activeCount.toFloat() / snap.effectiveLimit else 0f
    val animFraction by animateFloatAsState(targetValue = fraction, label = "slot")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status dot
        val dotColor = when {
            fraction >= 0.9f -> MaterialTheme.colorScheme.error
            fraction >= 0.6f -> Color(0xFFFFA726)
            else -> Color(0xFF4CAF50)
        }
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(6.dp))

        Text(
            text = shortUrl,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )

        // Slot bar
        LinearProgressIndicator(
            progress = { animFraction },
            modifier = Modifier
                .width(60.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = dotColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.width(6.dp))

        Text(
            text = "${snap.activeCount}/${snap.effectiveLimit}",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (snap.queuedCount > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "+${snap.queuedCount}q",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = Color(0xFFFFA726)
            )
        }
    }
}

@Composable
private fun RelayHealthSummaryCard(counts: HealthCounts) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionLabel("Relay Health")
            Spacer(Modifier.height(4.dp))

            MetricRow("Total tracked", "${counts.totalRelays}")
            MetricRow("Connected", "${counts.connected}", color = Color(0xFF4CAF50))
            if (counts.failed > 0) {
                MetricRow("Failed", "${counts.failed}", color = MaterialTheme.colorScheme.error)
            }
            if (counts.flagged > 0) {
                MetricRow("Flagged", "${counts.flagged}", color = Color(0xFFFFA726))
            }
            if (counts.blocked > 0) {
                MetricRow("Blocked", "${counts.blocked}", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun MemoryCard(snap: MemorySnapshot) {
    val animatedFraction by animateFloatAsState(targetValue = snap.usedFraction, label = "memory")
    val memColor = when {
        snap.usedFraction > 0.85f -> MaterialTheme.colorScheme.error
        snap.usedFraction > 0.7f -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                SectionLabel("Memory")
            }
            Spacer(Modifier.height(8.dp))

            // Arc gauge
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            ) {
                MemoryArcGauge(animatedFraction, memColor)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${snap.usedMb}MB",
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color = memColor
                    )
                    Text(
                        text = "of ${snap.maxMb}MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            MetricRow("Used", "${snap.usedMb} MB")
            MetricRow("Free", "${snap.freeMb} MB")
            MetricRow("Max heap", "${snap.maxMb} MB")
            MetricRow(
                "Pressure",
                "${(snap.usedFraction * 100).toInt()}%",
                color = memColor
            )
        }
    }
}

@Composable
private fun MemoryArcGauge(fraction: Float, color: Color) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = Modifier.size(80.dp)) {
        val strokeWidth = 8.dp.toPx()
        val padding = strokeWidth / 2
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val topLeft = Offset(padding, padding)
        // Track
        drawArc(
            color = trackColor,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        // Fill
        drawArc(
            color = color,
            startAngle = 135f,
            sweepAngle = 270f * fraction,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> "${value / 1_000_000}.${(value % 1_000_000) / 100_000}M"
    value >= 1_000 -> "${value / 1_000}.${(value % 1_000) / 100}k"
    else -> "$value"
}
