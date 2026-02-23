package social.mycelium.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import social.mycelium.android.cache.Nip11CacheManager
import social.mycelium.android.data.Author
import social.mycelium.android.data.RelayInformation
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.cybin.relay.RelaySlotSnapshot
import kotlinx.coroutines.delay
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.RelayEndpointStatus
import social.mycelium.android.relay.RelayHealthInfo
import social.mycelium.android.relay.RelayHealthTracker
import social.mycelium.android.data.RelayType
import social.mycelium.android.repository.ContactListRepository
import social.mycelium.android.repository.Nip65RelayListRepository
import social.mycelium.android.repository.Nip66RelayDiscoveryRepository
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.ui.components.ProfilePicture
import social.mycelium.android.utils.normalizeAuthorIdForCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Relay directory entry: a relay URL with its health info, user lists, and purpose flags. */
private data class RelayDirectoryEntry(
    val url: String,
    val health: RelayHealthInfo?,
    val outboxUsers: List<String>,  // pubkeys who write to this relay
    val inboxUsers: List<String>,   // pubkeys who read from this relay
    val isMyOutbox: Boolean,
    val isMyInbox: Boolean,
    val isIndexer: Boolean
) {
    val totalUsers: Int get() = (outboxUsers + inboxUsers).distinct().size
}

/**
 * Relay Directory screen. Shows all relays the app has interacted with,
 * organized by connection purpose: My Relays (user's own inbox/outbox),
 * Following Outbox (relays followed users write to), Following Inbox
 * (relays followed users read from), Indexer, and Other. Each relay
 * shows user count, health metrics, and an expandable list of users.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayHealthScreen(
    onBackClick: () -> Unit,
    onOpenRelayManager: () -> Unit,
    onOpenRelayDiscovery: () -> Unit = {},
    onOpenRelayLog: (String) -> Unit = {},
    onOpenRelayUsers: (String) -> Unit = {},
    onOpenNeedsAttention: () -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val healthMap by RelayHealthTracker.healthByRelay.collectAsState()
    val flaggedRelays by RelayHealthTracker.flaggedRelays.collectAsState()
    val blockedRelays by RelayHealthTracker.blockedRelays.collectAsState()
    val publishReports by RelayHealthTracker.publishReports.collectAsState()
    val perRelayState by RelayConnectionStateMachine.getInstance().perRelayState.collectAsState()
    val profileCache = remember { ProfileMetadataCache.getInstance() }
    val scope = rememberCoroutineScope()

    // NIP-65 relay categories (user's own)
    val myOutboxUrls by Nip65RelayListRepository.writeRelays.collectAsState()
    val myInboxUrls by Nip65RelayListRepository.readRelays.collectAsState()

    // NIP-66 relay discovery — use T tag to identify Search/Indexer relays
    val discoveredRelays by Nip66RelayDiscoveryRepository.discoveredRelays.collectAsState()

    // Per-author relay lists from NIP-65 cache (populated by batch fetch)
    val authorRelaySnapshot by Nip65RelayListRepository.authorRelaySnapshot.collectAsState()

    // Follow list — only show relays used by people we follow
    var followSet by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchFetchTriggered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!batchFetchTriggered) {
            batchFetchTriggered = true
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val followList = ContactListRepository.getCachedFollowList(
                    Nip65RelayListRepository.currentPubkey ?: ""
                )
                if (!followList.isNullOrEmpty()) {
                    followSet = followList.toSet()
                    val discoveryRelays = profileCache.getConfiguredRelayUrls()
                    Nip65RelayListRepository.batchFetchRelayLists(followList.toList(), discoveryRelays)
                }
            }
        }
    }

    // Profile revision for live name updates — only recompose when a followed user's profile changes
    var profileRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(followSet) {
        if (followSet.isEmpty()) return@LaunchedEffect
        profileCache.profileUpdated
            .filter { it in followSet }
            .collect { profileRevision++ }
    }
    @Suppress("UNUSED_EXPRESSION") profileRevision

    // ── Build relay directory: relay URL → { users who write there, users who read there } ──
    val directory = remember(healthMap, myOutboxUrls, myInboxUrls, discoveredRelays, authorRelaySnapshot, followSet) {
        val myOutboxSet = myOutboxUrls.map { it.trim().removeSuffix("/") }.toSet()
        val myInboxSet = myInboxUrls.map { it.trim().removeSuffix("/") }.toSet()
        val nip66SearchUrls = discoveredRelays.values
            .filter { it.isSearch }
            .map { it.url.trim().removeSuffix("/").lowercase() }
            .toSet()

        // Build relay → users maps from author relay snapshot (only followed users)
        val outboxByRelay = mutableMapOf<String, MutableList<String>>()
        val inboxByRelay = mutableMapOf<String, MutableList<String>>()
        authorRelaySnapshot.filter { it.key in followSet }.forEach { (pk, relayList) ->
            relayList.writeRelays.forEach { url ->
                val norm = url.trim().removeSuffix("/")
                outboxByRelay.getOrPut(norm) { mutableListOf() }.add(pk)
            }
            relayList.readRelays.forEach { url ->
                val norm = url.trim().removeSuffix("/")
                inboxByRelay.getOrPut(norm) { mutableListOf() }.add(pk)
            }
        }

        // Collect all known relay URLs (from health tracker + NIP-65 data)
        val allUrls = (healthMap.keys + outboxByRelay.keys + inboxByRelay.keys + myOutboxSet + myInboxSet).distinct()

        allUrls.map { url ->
            val norm = url.trim().removeSuffix("/")
            RelayDirectoryEntry(
                url = url,
                health = healthMap[url],
                outboxUsers = outboxByRelay[norm] ?: emptyList(),
                inboxUsers = inboxByRelay[norm] ?: emptyList(),
                isMyOutbox = norm in myOutboxSet || url in myOutboxSet,
                isMyInbox = norm in myInboxSet || url in myInboxSet,
                isIndexer = norm.lowercase() in nip66SearchUrls
            )
        }
    }

    // Categorize into sections
    val myRelays = remember(directory) {
        directory.filter { it.isMyOutbox || it.isMyInbox }
            .sortedByDescending { it.totalUsers }
    }
    val followingOutbox = remember(directory) {
        directory.filter { !it.isMyOutbox && !it.isMyInbox && !it.isIndexer && it.outboxUsers.isNotEmpty() }
            .sortedByDescending { it.outboxUsers.size }
    }
    val followingInbox = remember(directory) {
        directory.filter { !it.isMyOutbox && !it.isMyInbox && !it.isIndexer && it.outboxUsers.isEmpty() && it.inboxUsers.isNotEmpty() }
            .sortedByDescending { it.inboxUsers.size }
    }
    val indexerRelays = remember(directory) {
        directory.filter { it.isIndexer && !it.isMyOutbox && !it.isMyInbox }
            .sortedByDescending { it.health?.eventsReceived ?: 0 }
    }
    val otherRelays = remember(directory) {
        directory.filter { !it.isMyOutbox && !it.isMyInbox && !it.isIndexer && it.outboxUsers.isEmpty() && it.inboxUsers.isEmpty() }
            .sortedByDescending { it.health?.eventsReceived ?: 0 }
    }

    // ── Live slot snapshots (refresh every 500ms for real-time feel) ──
    val relayStateMachine = remember { RelayConnectionStateMachine.getInstance() }
    var slotSnapshots by remember { mutableStateOf<List<RelaySlotSnapshot>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            slotSnapshots = relayStateMachine.getRelaySlotSnapshots()
            delay(500)
        }
    }
    // Periodically release expired auto-blocks (every 60s)
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            RelayHealthTracker.releaseExpiredAutoBlocks()
        }
    }
    val slotsByUrl = remember(slotSnapshots) { slotSnapshots.associateBy { it.url.trimEnd('/') } }

    val autoBlockExpiryMap by RelayHealthTracker.autoBlockExpiryMap.collectAsState()
    val troubleRelays = remember(flaggedRelays, blockedRelays) {
        (flaggedRelays + blockedRelays).distinct().sorted()
    }
    val connectedCount = perRelayState.count { it.value == RelayEndpointStatus.Connected || it.value == RelayEndpointStatus.Connecting }
    val totalTracked = healthMap.size
    val followingRelayCount = followingOutbox.size + followingInbox.size

    // Aggregate stats for overview card
    val totalEvents = remember(healthMap) { healthMap.values.sumOf { it.eventsReceived } }
    val totalActiveSubs = remember(slotSnapshots) { slotSnapshots.sumOf { it.activeCount } }
    val totalQueuedSubs = remember(slotSnapshots) { slotSnapshots.sumOf { it.queuedCount } }
    val avgLatency = remember(healthMap) {
        val latencies = healthMap.values.filter { it.avgLatencyMs > 0 }.map { it.avgLatencyMs }
        if (latencies.isNotEmpty()) latencies.average().toLong() else 0L
    }
    val healthScore = remember(connectedCount, totalTracked, troubleRelays) {
        if (totalTracked == 0) 0f
        else ((connectedCount.toFloat() / totalTracked) * 100f * (1f - troubleRelays.size.toFloat() / totalTracked.coerceAtLeast(1))).coerceIn(0f, 100f)
    }

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    // Section expand state
    var publishesExpanded by remember { mutableStateOf(true) }
    var myRelaysExpanded by remember { mutableStateOf(true) }
    var followingOutboxExpanded by remember { mutableStateOf(true) }
    var followingInboxExpanded by remember { mutableStateOf(false) }
    var indexerExpanded by remember { mutableStateOf(false) }
    var otherExpanded by remember { mutableStateOf(false) }

    // NIP-11 cache
    val context = LocalContext.current
    val nip11 = remember(context) { Nip11CacheManager.getInstance(context) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        Text(
                            text = "relay health",
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
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ── Needs Attention (flagged/blocked relays) — shown first ──
            if (troubleRelays.isNotEmpty()) {
                item(key = "attention_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenNeedsAttention)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${troubleRelays.size} relay${if (troubleRelays.size != 1) "s" else ""} need attention",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = "View all",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                item(key = "attention_spacer") {
                    Spacer(Modifier.height(4.dp))
                }
            }

            // ── Recent Publishes (publish failure reports) ──
            if (publishReports.isNotEmpty()) {
                item(key = "section_publishes") {
                    SectionHeader(
                        title = "Recent Publishes",
                        count = publishReports.size,
                        icon = Icons.Outlined.Publish,
                        expanded = publishesExpanded,
                        onToggle = { publishesExpanded = !publishesExpanded }
                    )
                }
                if (publishesExpanded) {
                    items(publishReports.take(20), key = { "pub_${it.eventId}" }) { report ->
                        PublishReportRow(report = report)
                    }
                }
            }

            // ── Network Overview Card ──
            item(key = "overview") {
                NetworkOverviewCard(
                    healthScore = healthScore,
                    connectedCount = connectedCount,
                    totalTracked = totalTracked,
                    totalEvents = totalEvents,
                    totalActiveSubs = totalActiveSubs,
                    totalQueuedSubs = totalQueuedSubs,
                    avgLatency = avgLatency,
                    troubleCount = troubleRelays.size,
                    followingRelayCount = followingRelayCount
                )
            }

            // ── Loading indicator for NIP-65 batch fetch ──
            if (authorRelaySnapshot.isEmpty() && batchFetchTriggered) {
                item(key = "loading_nip65") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Loading relay lists for followed users…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── My Relays (user's own inbox/outbox) ──
            if (myRelays.isNotEmpty()) {
                item(key = "section_my") {
                    SectionHeader(
                        title = "My Relays",
                        count = myRelays.size,
                        icon = Icons.Outlined.Person,
                        expanded = myRelaysExpanded,
                        onToggle = { myRelaysExpanded = !myRelaysExpanded }
                    )
                }
                if (myRelaysExpanded) {
                    items(myRelays, key = { "my_${it.url}" }) { entry ->
                        RelayDirectoryRow(
                            entry = entry,
                            liveStatus = perRelayState[entry.url],
                            slotSnapshot = slotsByUrl[entry.url.trimEnd('/')],
                            nip11 = nip11,
                            profileCache = profileCache,
                            profileRevision = profileRevision,
                            onRelayClick = { onOpenRelayLog(entry.url) },
                            onOpenRelayUsers = { onOpenRelayUsers(entry.url) },
                            onProfileClick = onProfileClick,
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            // ── Following Outbox (relays followed users write to) ──
            if (followingOutbox.isNotEmpty()) {
                item(key = "section_following_outbox") {
                    SectionHeader(
                        title = "Following · Outbox",
                        count = followingOutbox.size,
                        icon = Icons.Outlined.Upload,
                        expanded = followingOutboxExpanded,
                        onToggle = { followingOutboxExpanded = !followingOutboxExpanded }
                    )
                }
                if (followingOutboxExpanded) {
                    items(followingOutbox, key = { "fout_${it.url}" }) { entry ->
                        RelayDirectoryRow(
                            entry = entry,
                            liveStatus = perRelayState[entry.url],
                            slotSnapshot = slotsByUrl[entry.url.trimEnd('/')],
                            nip11 = nip11,
                            profileCache = profileCache,
                            profileRevision = profileRevision,
                            onRelayClick = { onOpenRelayLog(entry.url) },
                            onOpenRelayUsers = { onOpenRelayUsers(entry.url) },
                            onProfileClick = onProfileClick,
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            // ── Following Inbox (relays followed users read from) ──
            if (followingInbox.isNotEmpty()) {
                item(key = "section_following_inbox") {
                    SectionHeader(
                        title = "Following · Inbox",
                        count = followingInbox.size,
                        icon = Icons.Outlined.Download,
                        expanded = followingInboxExpanded,
                        onToggle = { followingInboxExpanded = !followingInboxExpanded }
                    )
                }
                if (followingInboxExpanded) {
                    items(followingInbox, key = { "fin_${it.url}" }) { entry ->
                        RelayDirectoryRow(
                            entry = entry,
                            liveStatus = perRelayState[entry.url],
                            slotSnapshot = slotsByUrl[entry.url.trimEnd('/')],
                            nip11 = nip11,
                            profileCache = profileCache,
                            profileRevision = profileRevision,
                            onRelayClick = { onOpenRelayLog(entry.url) },
                            onOpenRelayUsers = { onOpenRelayUsers(entry.url) },
                            onProfileClick = onProfileClick,
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            // ── Indexer Relays (collapsed by default) ──
            if (indexerRelays.isNotEmpty()) {
                item(key = "section_indexer") {
                    SectionHeader(
                        title = "Indexer Relays",
                        count = indexerRelays.size,
                        icon = Icons.Outlined.Storage,
                        expanded = indexerExpanded,
                        onToggle = { indexerExpanded = !indexerExpanded }
                    )
                }
                if (indexerExpanded) {
                    items(indexerRelays, key = { "idx_${it.url}" }) { entry ->
                        RelayDirectoryRow(
                            entry = entry,
                            liveStatus = perRelayState[entry.url],
                            slotSnapshot = slotsByUrl[entry.url.trimEnd('/')],
                            nip11 = nip11,
                            profileCache = profileCache,
                            profileRevision = profileRevision,
                            onRelayClick = { onOpenRelayLog(entry.url) },
                            onOpenRelayUsers = { onOpenRelayUsers(entry.url) },
                            onProfileClick = onProfileClick,
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            // ── Other Relays ──
            if (otherRelays.isNotEmpty()) {
                item(key = "section_other") {
                    SectionHeader(
                        title = "Other Relays",
                        count = otherRelays.size,
                        icon = Icons.Outlined.Public,
                        expanded = otherExpanded,
                        onToggle = { otherExpanded = !otherExpanded }
                    )
                }
                if (otherExpanded) {
                    items(otherRelays, key = { "oth_${it.url}" }) { entry ->
                        RelayDirectoryRow(
                            entry = entry,
                            liveStatus = perRelayState[entry.url],
                            slotSnapshot = slotsByUrl[entry.url.trimEnd('/')],
                            nip11 = nip11,
                            profileCache = profileCache,
                            profileRevision = profileRevision,
                            onRelayClick = { onOpenRelayLog(entry.url) },
                            onOpenRelayUsers = { onOpenRelayUsers(entry.url) },
                            onProfileClick = onProfileClick,
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            // ── Empty State ──
            if (healthMap.isEmpty() && authorRelaySnapshot.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.Public,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "No relay activity yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Health data will appear as relays connect",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── Publish report row ──

@Composable
private fun PublishReportRow(report: RelayHealthTracker.PublishReport) {
    var expanded by remember { mutableStateOf(false) }
    val kindLabel = when (report.kind) {
        1 -> "Note"
        7 -> "Reaction"
        11 -> "Topic"
        1111 -> "Reply"
        6 -> "Repost"
        else -> "Kind ${report.kind}"
    }
    val ageMs = System.currentTimeMillis() - report.timestamp
    val timeAgo = when {
        ageMs < 60_000 -> "just now"
        ageMs < 3_600_000 -> "${ageMs / 60_000}m ago"
        ageMs < 86_400_000 -> "${ageMs / 3_600_000}h ago"
        else -> "${ageMs / 86_400_000}d ago"
    }
    val hasFailures = report.hasFailures
    val containerColor = if (hasFailures)
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
    else
        MaterialTheme.colorScheme.surfaceContainerLow

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = containerColor
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status dot
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (hasFailures) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = kindLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${report.successCount}/${report.targetRelayCount} OK" +
                            if (hasFailures) " · ${report.failureCount} failed" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasFailures) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    report.results.forEach { result ->
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
                                tint = if (result.success) MaterialTheme.colorScheme.primary
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
                                Text(
                                    text = result.message.take(30),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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

// ── Collapsible section header ──

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(200),
        label = "chevron"
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onToggle
            )
            .padding(top = 12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(20.dp)
                    .rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}

// ── Pulsing status dot (for connecting relays) ──

@Composable
private fun PulsingDot(
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

// ── NIP-11 relay icon with color-coded border ──

@Composable
private fun RelayIcon(
    relayUrl: String,
    borderColor: Color,
    isConnecting: Boolean,
    nip11: Nip11CacheManager,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var iconUrl by remember(relayUrl) { mutableStateOf(nip11.getCachedRelayInfo(relayUrl)?.icon) }
    LaunchedEffect(relayUrl) {
        if (iconUrl.isNullOrBlank()) {
            nip11.getRelayInfo(relayUrl)?.icon?.let { iconUrl = it }
        }
    }

    // Pulsing border for connecting state
    val borderAlpha = if (isConnecting) {
        val infiniteTransition = rememberInfiniteTransition(label = "iconPulse_$relayUrl")
        val a by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "iconBorderAlpha"
        )
        a
    } else 1f

    Box(
        modifier = modifier
            .size(size)
            .border(
                width = 2.dp,
                color = borderColor.copy(alpha = borderAlpha),
                shape = CircleShape
            )
            .padding(2.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        if (!iconUrl.isNullOrBlank()) {
            var loadFailed by remember(iconUrl) { mutableStateOf(false) }
            if (!loadFailed) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(iconUrl)
                        .crossfade(true)
                        .size(72)
                        .memoryCacheKey("relay_health_icon_$relayUrl")
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
                    Icons.Outlined.Router,
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.5f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Icon(
                Icons.Outlined.Router,
                contentDescription = null,
                modifier = Modifier.size(size * 0.5f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Network Overview Card ──

@Composable
private fun NetworkOverviewCard(
    healthScore: Float,
    connectedCount: Int,
    totalTracked: Int,
    totalEvents: Long,
    totalActiveSubs: Int,
    totalQueuedSubs: Int,
    avgLatency: Long,
    troubleCount: Int,
    followingRelayCount: Int
) {
    val scoreColor = when {
        healthScore >= 80f -> Color(0xFF4CAF50)
        healthScore >= 50f -> Color(0xFFFFA726)
        healthScore > 0f -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val statusLabel = when {
        totalTracked == 0 -> "No relays"
        healthScore >= 80f -> "Healthy"
        healthScore >= 50f -> "Degraded"
        troubleCount > 0 -> "Issues"
        else -> "Connecting"
    }
    val animatedScore by animateFloatAsState(
        targetValue = healthScore / 100f,
        animationSpec = tween(800),
        label = "healthScore"
    )
    val animatedColor by animateColorAsState(
        targetValue = scoreColor,
        animationSpec = tween(500),
        label = "scoreColor"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top row: health ring + status + connection count
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Health score ring
                Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    Canvas(modifier = Modifier.size(56.dp)) {
                        val strokeWidth = 6.dp.toPx()
                        val arcSize = size.width - strokeWidth
                        val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
                        drawArc(
                            color = trackColor,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = Size(arcSize, arcSize),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = animatedColor,
                            startAngle = -90f,
                            sweepAngle = animatedScore * 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = Size(arcSize, arcSize),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        text = "${healthScore.toInt()}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = animatedColor
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = animatedColor
                    )
                    Text(
                        text = "$connectedCount / $totalTracked relays connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (troubleCount > 0) {
                        Text(
                            text = "$troubleCount need${if (troubleCount == 1) "s" else ""} attention",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(10.dp))

            // Stats grid: 2 rows × 3 columns
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OverviewStat(
                    label = "Events",
                    value = formatCount(totalEvents),
                    icon = Icons.Outlined.Email
                )
                OverviewStat(
                    label = "Subs",
                    value = "$totalActiveSubs",
                    icon = Icons.Outlined.Sync,
                    accent = if (totalQueuedSubs > 0) Color(0xFFFFA726) else null,
                    badge = if (totalQueuedSubs > 0) "+$totalQueuedSubs queued" else null
                )
                OverviewStat(
                    label = "Latency",
                    value = if (avgLatency > 0) "${avgLatency}ms" else "—",
                    icon = Icons.Outlined.Speed,
                    accent = when {
                        avgLatency in 1..199 -> Color(0xFF4CAF50)
                        avgLatency in 200..499 -> Color(0xFFFFA726)
                        avgLatency >= 500 -> MaterialTheme.colorScheme.error
                        else -> null
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OverviewStat(
                    label = "Following",
                    value = "$followingRelayCount",
                    icon = Icons.Outlined.People
                )
                OverviewStat(
                    label = "Flagged",
                    value = "$troubleCount",
                    icon = Icons.Outlined.Warning,
                    accent = if (troubleCount > 0) MaterialTheme.colorScheme.error else null
                )
                OverviewStat(
                    label = "Slots",
                    value = "$totalActiveSubs / ${totalActiveSubs + totalQueuedSubs}",
                    icon = Icons.Outlined.ViewWeek
                )
            }
        }
    }
}

@Composable
private fun OverviewStat(
    label: String,
    value: String,
    icon: ImageVector,
    accent: Color? = null,
    badge: String? = null
) {
    val color = accent ?: MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 4.dp).widthIn(min = 80.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp
            )
        }
    }
}

// ── Slot utilization bar ──

@Composable
private fun SlotUtilizationBar(snapshot: RelaySlotSnapshot) {
    val limit = snapshot.effectiveLimit.coerceAtLeast(1)
    val activeFraction = (snapshot.activeCount.toFloat() / limit).coerceIn(0f, 1f)
    val eoseFraction = (snapshot.eoseCount.toFloat() / limit).coerceIn(0f, activeFraction)
    val activeNonEose = activeFraction - eoseFraction

    val activeColor = MaterialTheme.colorScheme.primary
    val eoseColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(trackColor)
        ) {
            // EOSE'd portion (lighter)
            if (eoseFraction > 0f) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(eoseFraction)
                        .clip(RoundedCornerShape(2.dp))
                        .background(eoseColor)
                )
            }
            // Active (non-EOSE) portion on top
            if (activeFraction > 0f) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(activeFraction)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.horizontalGradient(
                                0f to eoseColor,
                                eoseFraction / activeFraction.coerceAtLeast(0.01f) to eoseColor,
                                eoseFraction / activeFraction.coerceAtLeast(0.01f) to activeColor,
                                1f to activeColor
                            )
                        )
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${snapshot.activeCount}/${snapshot.effectiveLimit}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
        if (snapshot.queuedCount > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "+${snapshot.queuedCount}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFA726),
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp
            )
        }
    }
}

// ── Relay directory row with user counts and expandable user list ──

@Composable
private fun RelayDirectoryRow(
    entry: RelayDirectoryEntry,
    liveStatus: RelayEndpointStatus?,
    slotSnapshot: RelaySlotSnapshot?,
    nip11: Nip11CacheManager,
    profileCache: ProfileMetadataCache,
    profileRevision: Int,
    onRelayClick: () -> Unit,
    onOpenRelayUsers: () -> Unit = {},
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val url = entry.url
    val health = entry.health
    val outboxUsers = entry.outboxUsers
    val inboxUsers = entry.inboxUsers
    val isMyOutbox = entry.isMyOutbox
    val isMyInbox = entry.isMyInbox
    val totalUsers = entry.totalUsers

    val isLive = liveStatus == RelayEndpointStatus.Connected || liveStatus == RelayEndpointStatus.Connecting

    val borderColor = when {
        health?.isBlocked == true -> MaterialTheme.colorScheme.error
        isLive -> MaterialTheme.colorScheme.primary
        liveStatus == RelayEndpointStatus.Failed -> MaterialTheme.colorScheme.error
        health?.isFlagged == true -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    val surfaceColor = when {
        health?.isBlocked == true -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        health?.isFlagged == true -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surface
    }

    val relayInfo = remember(url) { nip11.getCachedRelayInfo(url) }
    val displayName = relayInfo?.name?.takeIf { it.isNotBlank() }
        ?: url.removePrefix("wss://").removePrefix("ws://")

    Column(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRelayClick),
            color = surfaceColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RelayIcon(
                        relayUrl = url,
                        borderColor = borderColor,
                        isConnecting = liveStatus == RelayEndpointStatus.Connecting,
                        nip11 = nip11,
                        size = 36.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            // Purpose badges
                            if (isMyOutbox) {
                                StatusBadge(text = "outbox", color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                            }
                            if (isMyInbox) {
                                StatusBadge(text = "inbox", color = Color(0xFF4CAF50))
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        // Metrics row
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (totalUsers > 0) {
                                MetricLabel(
                                    icon = Icons.Outlined.People,
                                    text = "$totalUsers user${if (totalUsers != 1) "s" else ""}",
                                    tint = Color(0xFF4CAF50)
                                )
                            }
                            if (outboxUsers.isNotEmpty()) {
                                MetricLabel(
                                    icon = Icons.Outlined.Upload,
                                    text = "${outboxUsers.size} write"
                                )
                            }
                            if (inboxUsers.isNotEmpty()) {
                                MetricLabel(
                                    icon = Icons.Outlined.Download,
                                    text = "${inboxUsers.size} read"
                                )
                            }
                            if (health != null && health.eventsReceived > 0) {
                                MetricLabel(
                                    icon = Icons.Outlined.Email,
                                    text = formatCount(health.eventsReceived)
                                )
                            }
                            if (health != null && health.avgLatencyMs > 0) {
                                val latColor = when {
                                    health.avgLatencyMs < 200 -> Color(0xFF4CAF50)
                                    health.avgLatencyMs < 500 -> Color(0xFFFFA726)
                                    else -> MaterialTheme.colorScheme.error
                                }
                                MetricLabel(
                                    icon = Icons.Outlined.Speed,
                                    text = "${health.avgLatencyMs}ms",
                                    tint = latColor
                                )
                            }
                        }
                        // Slot utilization bar
                        if (slotSnapshot != null && slotSnapshot.effectiveLimit > 0) {
                            Spacer(Modifier.height(4.dp))
                            SlotUtilizationBar(snapshot = slotSnapshot)
                        }
                    }

                    // Status / expand toggle
                    if (health?.isBlocked == true) {
                        StatusBadge(text = "Blocked", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = { RelayHealthTracker.unblockRelay(url) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Outlined.LockOpen, "Unblock", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (health?.isFlagged == true) {
                        StatusBadge(text = "Flagged", color = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(2.dp))
                        IconButton(
                            onClick = { RelayHealthTracker.blockRelay(url) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Outlined.Block, "Block", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    } else if (totalUsers > 0) {
                        IconButton(
                            onClick = onOpenRelayUsers,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = "View users",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    } else {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 64.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    }
}

// ── Metric label with tiny icon ──

@Composable
private fun MetricLabel(
    icon: ImageVector,
    text: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = tint.copy(alpha = 0.6f)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

// ── Status badge pill ──

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ── Format large numbers ──

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}M"
    count >= 1_000 -> "${count / 1_000}K"
    else -> "$count"
}
