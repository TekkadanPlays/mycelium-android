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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
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
import social.mycelium.android.relay.RelayDeliveryTracker
import social.mycelium.android.relay.RelayHealthTracker
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayType
import social.mycelium.android.repository.ContactListRepository
import social.mycelium.android.repository.RelayStorageManager
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
    onOpenPublishResults: () -> Unit = {},
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

    // ── Load relay manager categories from active profile ──
    val context = LocalContext.current
    val storageManager = remember { RelayStorageManager(context) }
    val currentPubkey = Nip65RelayListRepository.currentPubkey ?: ""
    val managedCategories = remember(currentPubkey) {
        if (currentPubkey.isBlank()) emptyList()
        else {
            val profiles = storageManager.loadProfiles(currentPubkey)
            val active = profiles.firstOrNull { it.isActive } ?: profiles.firstOrNull()
            active?.categories?.filter { it.relays.isNotEmpty() } ?: emptyList()
        }
    }
    // Build set of all managed relay URLs (normalized) for quick lookup
    val managedUrlSet = remember(managedCategories) {
        managedCategories.flatMap { cat -> cat.relays.map { RelayStorageManager.normalizeRelayUrl(it.url) } }.toSet()
    }
    // Build category → directory entries map
    val categoryRelayMap = remember(managedCategories, directory) {
        managedCategories.associate { cat ->
            val catUrls = cat.relays.map { RelayStorageManager.normalizeRelayUrl(it.url) }.toSet()
            cat to directory.filter { entry ->
                val norm = entry.url.trim().removeSuffix("/").lowercase()
                norm in catUrls || entry.url in catUrls
            }.sortedByDescending { it.totalUsers }
        }
    }

    // Categorize into sections
    // My Relays that aren't in any managed category (uncategorized)
    val myRelaysUncategorized = remember(directory, managedUrlSet) {
        directory.filter { (it.isMyOutbox || it.isMyInbox) && it.url.trim().removeSuffix("/").lowercase() !in managedUrlSet }
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
    val avgConnectTime = remember(healthMap) {
        val times = healthMap.values.filter { it.connectTimeMs > 0 }.map { it.connectTimeMs }
        if (times.isNotEmpty()) times.average().toLong() else 0L
    }
    // Health score: weighted average of per-relay uptime ratios (only relays with >=2 attempts).
    // Penalizes relays with high failure rates proportionally, not just by count.
    val healthScore = remember(healthMap, connectedCount, totalTracked) {
        val eligible = healthMap.values.filter { it.connectionAttempts >= 2 }
        if (eligible.isEmpty()) {
            if (connectedCount > 0) 100f else 0f
        } else {
            (eligible.map { it.uptimeRatio }.average() * 100f).toFloat().coerceIn(0f, 100f)
        }
    }

    // NIP-11 cache
    val nip11 = remember(context) { Nip11CacheManager.getInstance(context) }

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                TopAppBar(
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
        val tabTitles = listOf("Overview", "Relays", "Delivery", "Attention")
        val tabIcons = listOf(
            Icons.Outlined.Dashboard,
            Icons.Outlined.Router,
            Icons.Outlined.Outbox,
            Icons.Outlined.Warning
        )
        val pagerState = rememberPagerState(pageCount = { tabTitles.size })

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Tab row ──
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 0.dp,
                divider = {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            ) {
                tabTitles.forEachIndexed { index, title ->
                    val selected = pagerState.currentPage == index
                    val showBadge = index == 3 && troubleRelays.isNotEmpty()
                    Tab(
                        selected = selected,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                                if (showBadge) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error)
                                    )
                                }
                            }
                        },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Pager body ──
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                when (page) {
                    0 -> OverviewTab(
                        healthScore = healthScore,
                        connectedCount = connectedCount,
                        totalTracked = totalTracked,
                        totalEvents = totalEvents,
                        totalActiveSubs = totalActiveSubs,
                        totalQueuedSubs = totalQueuedSubs,
                        avgConnectTime = avgConnectTime,
                        troubleCount = troubleRelays.size,
                        followingRelayCount = followingRelayCount,
                        slotSnapshots = slotSnapshots,
                        healthMap = healthMap,
                        perRelayState = perRelayState
                    )
                    1 -> RelaysTab(
                        categoryRelayMap = categoryRelayMap,
                        myRelaysUncategorized = myRelaysUncategorized,
                        followingOutbox = followingOutbox,
                        followingInbox = followingInbox,
                        indexerRelays = indexerRelays,
                        otherRelays = otherRelays,
                        perRelayState = perRelayState,
                        slotsByUrl = slotsByUrl,
                        nip11 = nip11,
                        profileCache = profileCache,
                        profileRevision = profileRevision,
                        authorRelaySnapshot = authorRelaySnapshot,
                        batchFetchTriggered = batchFetchTriggered,
                        healthMap = healthMap,
                        onOpenRelayLog = onOpenRelayLog,
                        onOpenRelayUsers = onOpenRelayUsers,
                        onProfileClick = onProfileClick
                    )
                    2 -> DeliveryTab(
                        publishReports = publishReports,
                        onOpenPublishResults = onOpenPublishResults,
                        onOpenRelayLog = onOpenRelayLog
                    )
                    3 -> AttentionTab(
                        troubleRelays = troubleRelays,
                        healthMap = healthMap,
                        autoBlockExpiryMap = autoBlockExpiryMap,
                        onOpenRelayLog = onOpenRelayLog
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// ── TAB 0: Overview ──
// ══════════════════════════════════════════════════════

@Composable
private fun OverviewTab(
    healthScore: Float,
    connectedCount: Int,
    totalTracked: Int,
    totalEvents: Long,
    totalActiveSubs: Int,
    totalQueuedSubs: Int,
    avgConnectTime: Long,
    troubleCount: Int,
    followingRelayCount: Int,
    slotSnapshots: List<RelaySlotSnapshot>,
    healthMap: Map<String, RelayHealthInfo>,
    perRelayState: Map<String, RelayEndpointStatus>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Health score donut + status
        item(key = "overview_card") {
            NetworkOverviewCard(
                healthScore = healthScore,
                connectedCount = connectedCount,
                totalTracked = totalTracked,
                totalEvents = totalEvents,
                totalActiveSubs = totalActiveSubs,
                totalQueuedSubs = totalQueuedSubs,
                avgConnectTime = avgConnectTime,
                troubleCount = troubleCount,
                followingRelayCount = followingRelayCount
            )
        }

        // Live connection summary — compact relay status list
        val connectedRelays = perRelayState.filter { it.value == RelayEndpointStatus.Connected }
            .keys.sorted()
        val failedRelays = perRelayState.filter { it.value == RelayEndpointStatus.Failed }
            .keys.sorted()

        if (connectedRelays.isNotEmpty()) {
            item(key = "connected_summary") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RectangleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${connectedRelays.size} Connected",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF4CAF50)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        connectedRelays.take(8).forEach { url ->
                            val h = healthMap[url]
                            val uptimePct = h?.let { (it.uptimeRatio * 100).toInt() } ?: 0
                            val evts = h?.eventsReceived ?: 0
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (evts > 0) {
                                    Text(
                                        formatCount(evts),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    )
                                }
                                Text(
                                    "${uptimePct}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = when {
                                        uptimePct >= 80 -> Color(0xFF4CAF50)
                                        uptimePct >= 50 -> Color(0xFFFFA726)
                                        else -> MaterialTheme.colorScheme.error
                                    },
                                    modifier = Modifier.width(32.dp)
                                )
                            }
                        }
                        if (connectedRelays.size > 8) {
                            Text(
                                "+${connectedRelays.size - 8} more",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        if (failedRelays.isNotEmpty()) {
            item(key = "failed_summary") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RectangleShape,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${failedRelays.size} Failed",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        failedRelays.take(5).forEach { url ->
                            val h = healthMap[url]
                            Text(
                                buildString {
                                    append(url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"))
                                    h?.lastError?.let { append(" · $it") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        // Global slot utilization
        if (slotSnapshots.isNotEmpty()) {
            item(key = "global_slots") {
                val totalActive = slotSnapshots.sumOf { it.activeCount }
                val totalQueued = slotSnapshots.sumOf { it.queuedCount }
                val totalEose = slotSnapshots.sumOf { it.eoseCount }
                val totalLimit = slotSnapshots.sumOf { it.effectiveLimit }
                val utilization = if (totalLimit > 0) totalActive.toFloat() / totalLimit else 0f
                val barColor = when {
                    utilization >= 0.9f -> MaterialTheme.colorScheme.error
                    utilization >= 0.7f -> Color(0xFFFFA726)
                    else -> Color(0xFF4CAF50)
                }
                val animatedUtil by animateFloatAsState(
                    targetValue = utilization.coerceIn(0f, 1f),
                    animationSpec = tween(400),
                    label = "globalSlotUtil"
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RectangleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Tune, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Subscription Slots", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            OverviewStat(label = "Active", value = "$totalActive", icon = Icons.Outlined.Sync)
                            OverviewStat(label = "Queued", value = "$totalQueued", icon = Icons.Outlined.HourglassTop,
                                accent = if (totalQueued > 0) Color(0xFFFFA726) else null)
                            OverviewStat(label = "EOSE", value = "$totalEose", icon = Icons.Outlined.CheckCircle,
                                accent = Color(0xFF4CAF50))
                            OverviewStat(label = "Capacity", value = "$totalLimit", icon = Icons.Outlined.ViewWeek)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Utilization", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
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
                                        .fillMaxWidth(animatedUtil)
                                        .clip(RectangleShape)
                                        .background(barColor)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("${(utilization * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold, color = barColor)
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// ── TAB 1: Relays Directory ──
// ══════════════════════════════════════════════════════

@Composable
private fun RelaysTab(
    categoryRelayMap: Map<RelayCategory, List<RelayDirectoryEntry>>,
    myRelaysUncategorized: List<RelayDirectoryEntry>,
    followingOutbox: List<RelayDirectoryEntry>,
    followingInbox: List<RelayDirectoryEntry>,
    indexerRelays: List<RelayDirectoryEntry>,
    otherRelays: List<RelayDirectoryEntry>,
    perRelayState: Map<String, RelayEndpointStatus>,
    slotsByUrl: Map<String, RelaySlotSnapshot>,
    nip11: Nip11CacheManager,
    profileCache: ProfileMetadataCache,
    profileRevision: Int,
    authorRelaySnapshot: Map<String, Nip65RelayListRepository.AuthorRelayList>,
    batchFetchTriggered: Boolean,
    healthMap: Map<String, RelayHealthInfo>,
    onOpenRelayLog: (String) -> Unit,
    onOpenRelayUsers: (String) -> Unit,
    onProfileClick: (String) -> Unit
) {
    // Per-category expanded state (managed categories default to expanded)
    val categoryExpandedState = remember { mutableStateMapOf<String, Boolean>() }
    var uncategorizedExpanded by remember { mutableStateOf(true) }
    var followingOutboxExpanded by remember { mutableStateOf(true) }
    var followingInboxExpanded by remember { mutableStateOf(false) }
    var indexerExpanded by remember { mutableStateOf(false) }
    var otherExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Loading indicator for NIP-65 batch fetch
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

        // ── Managed relay categories (from relay manager) ──
        categoryRelayMap.forEach { (category, entries) ->
            if (entries.isNotEmpty()) {
                val expanded = categoryExpandedState.getOrPut(category.id) { true }
                item(key = "section_cat_${category.id}") {
                    SectionHeader(title = category.name, count = entries.size, icon = Icons.Outlined.Folder,
                        expanded = expanded, onToggle = { categoryExpandedState[category.id] = !expanded })
                }
                if (expanded) {
                    items(entries, key = { "cat_${category.id}_${it.url}" }) { entry ->
                        RelayDirectoryRow(entry = entry, liveStatus = perRelayState[entry.url],
                            slotSnapshot = slotsByUrl[entry.url.trimEnd('/')], nip11 = nip11,
                            profileCache = profileCache, profileRevision = profileRevision,
                            onRelayClick = { onOpenRelayLog(entry.url) },
                            onOpenRelayUsers = { onOpenRelayUsers(entry.url) },
                            onProfileClick = onProfileClick, modifier = Modifier.animateItem())
                    }
                }
            }
        }

        // Uncategorized My Relays (NIP-65 relays not in any managed category)
        if (myRelaysUncategorized.isNotEmpty()) {
            item(key = "section_uncategorized") {
                SectionHeader(title = "My Relays · Other", count = myRelaysUncategorized.size, icon = Icons.Outlined.Person,
                    expanded = uncategorizedExpanded, onToggle = { uncategorizedExpanded = !uncategorizedExpanded })
            }
            if (uncategorizedExpanded) {
                items(myRelaysUncategorized, key = { "uncat_${it.url}" }) { entry ->
                    RelayDirectoryRow(entry = entry, liveStatus = perRelayState[entry.url],
                        slotSnapshot = slotsByUrl[entry.url.trimEnd('/')], nip11 = nip11,
                        profileCache = profileCache, profileRevision = profileRevision,
                        onRelayClick = { onOpenRelayLog(entry.url) },
                        onOpenRelayUsers = { onOpenRelayUsers(entry.url) },
                        onProfileClick = onProfileClick, modifier = Modifier.animateItem())
                }
            }
        }

        // Following · Outbox
        if (followingOutbox.isNotEmpty()) {
            item(key = "section_following_outbox") {
                SectionHeader(title = "Following · Outbox", count = followingOutbox.size, icon = Icons.Outlined.Upload,
                    expanded = followingOutboxExpanded, onToggle = { followingOutboxExpanded = !followingOutboxExpanded })
            }
            if (followingOutboxExpanded) {
                items(followingOutbox, key = { "fout_${it.url}" }) { entry ->
                    RelayDirectoryRow(entry = entry, liveStatus = perRelayState[entry.url],
                        slotSnapshot = slotsByUrl[entry.url.trimEnd('/')], nip11 = nip11,
                        profileCache = profileCache, profileRevision = profileRevision,
                        onRelayClick = { onOpenRelayLog(entry.url) },
                        onOpenRelayUsers = { onOpenRelayUsers(entry.url) },
                        onProfileClick = onProfileClick, modifier = Modifier.animateItem())
                }
            }
        }

        // Following · Inbox
        if (followingInbox.isNotEmpty()) {
            item(key = "section_following_inbox") {
                SectionHeader(title = "Following · Inbox", count = followingInbox.size, icon = Icons.Outlined.Download,
                    expanded = followingInboxExpanded, onToggle = { followingInboxExpanded = !followingInboxExpanded })
            }
            if (followingInboxExpanded) {
                items(followingInbox, key = { "fin_${it.url}" }) { entry ->
                    RelayDirectoryRow(entry = entry, liveStatus = perRelayState[entry.url],
                        slotSnapshot = slotsByUrl[entry.url.trimEnd('/')], nip11 = nip11,
                        profileCache = profileCache, profileRevision = profileRevision,
                        onRelayClick = { onOpenRelayLog(entry.url) },
                        onOpenRelayUsers = { onOpenRelayUsers(entry.url) },
                        onProfileClick = onProfileClick, modifier = Modifier.animateItem())
                }
            }
        }

        // Indexer Relays
        if (indexerRelays.isNotEmpty()) {
            item(key = "section_indexer") {
                SectionHeader(title = "Indexer Relays", count = indexerRelays.size, icon = Icons.Outlined.Storage,
                    expanded = indexerExpanded, onToggle = { indexerExpanded = !indexerExpanded })
            }
            if (indexerExpanded) {
                items(indexerRelays, key = { "idx_${it.url}" }) { entry ->
                    RelayDirectoryRow(entry = entry, liveStatus = perRelayState[entry.url],
                        slotSnapshot = slotsByUrl[entry.url.trimEnd('/')], nip11 = nip11,
                        profileCache = profileCache, profileRevision = profileRevision,
                        onRelayClick = { onOpenRelayLog(entry.url) },
                        onOpenRelayUsers = { onOpenRelayUsers(entry.url) },
                        onProfileClick = onProfileClick, modifier = Modifier.animateItem())
                }
            }
        }

        // Other Relays
        if (otherRelays.isNotEmpty()) {
            item(key = "section_other") {
                SectionHeader(title = "Other Relays", count = otherRelays.size, icon = Icons.Outlined.Public,
                    expanded = otherExpanded, onToggle = { otherExpanded = !otherExpanded })
            }
            if (otherExpanded) {
                items(otherRelays, key = { "oth_${it.url}" }) { entry ->
                    RelayDirectoryRow(entry = entry, liveStatus = perRelayState[entry.url],
                        slotSnapshot = slotsByUrl[entry.url.trimEnd('/')], nip11 = nip11,
                        profileCache = profileCache, profileRevision = profileRevision,
                        onRelayClick = { onOpenRelayLog(entry.url) },
                        onOpenRelayUsers = { onOpenRelayUsers(entry.url) },
                        onProfileClick = onProfileClick, modifier = Modifier.animateItem())
                }
            }
        }

        // Empty state
        if (categoryRelayMap.values.all { it.isEmpty() } && myRelaysUncategorized.isEmpty() && followingOutbox.isEmpty() && followingInbox.isEmpty()
            && indexerRelays.isEmpty() && otherRelays.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(shape = RectangleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(72.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Public, null, Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("No relay activity yet", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text("Health data will appear as relays connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// ── TAB 2: Delivery ──
// ══════════════════════════════════════════════════════

@Composable
private fun DeliveryTab(
    publishReports: List<RelayHealthTracker.PublishReport>,
    onOpenPublishResults: () -> Unit,
    onOpenRelayLog: (String) -> Unit
) {
    val deliveryStats = remember { RelayDeliveryTracker.getStats() }
    val allRelays = remember(deliveryStats) {
        deliveryStats.entries
            .filter { it.value.expected >= 1.0 }
            .sortedByDescending { it.value.successRate }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Publish results card
        if (publishReports.isNotEmpty()) {
            item(key = "publish_card") {
                val failedCount = publishReports.count { it.hasFailures }
                val latestReport = publishReports.firstOrNull()
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenPublishResults),
                    shape = RectangleShape,
                    color = if (failedCount > 0)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                    tonalElevation = 0.5.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Publish, null, Modifier.size(20.dp),
                            tint = if (failedCount > 0) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Publish Results", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    append("${publishReports.size} events")
                                    if (failedCount > 0) append(" · $failedCount with failures")
                                    latestReport?.let {
                                        val ageMs = System.currentTimeMillis() - it.timestamp
                                        val ago = when {
                                            ageMs < 60_000 -> "just now"
                                            ageMs < 3_600_000 -> "${ageMs / 60_000}m ago"
                                            else -> "${ageMs / 3_600_000}h ago"
                                        }
                                        append(" · latest $ago")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Filled.ChevronRight, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }

        // Outbox delivery summary card
        if (allRelays.isNotEmpty()) {
            item(key = "delivery_summary") {
                val topRelays = allRelays.take(6)
                OutboxDeliveryCard(topRelays = topRelays)
            }
        }

        // Full delivery stats — every relay with data
        if (allRelays.isNotEmpty()) {
            item(key = "full_delivery_header") {
                Text(
                    "All Relays",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(allRelays, key = { "delivery_${it.key}" }) { (url, stat) ->
                val displayUrl = url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
                val rate = stat.successRate
                val rateColor = when {
                    rate >= 0.8 -> Color(0xFF4CAF50)
                    rate >= 0.5 -> Color(0xFFFFA726)
                    rate > 0.0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                }
                val animatedRate by animateFloatAsState(
                    targetValue = rate.toFloat().coerceIn(0f, 1f),
                    animationSpec = tween(400),
                    label = "delRate_$displayUrl"
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRelayLog(url) },
                    shape = RectangleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(rateColor)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                displayUrl,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${(rate * 100).toInt()}%",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = rateColor
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // Rate bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RectangleShape)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(animatedRate)
                                    .clip(RectangleShape)
                                    .background(rateColor)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("${stat.delivered.toInt()} delivered",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${stat.expected.toInt()} expected",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val missed = (stat.expected - stat.delivered).toInt().coerceAtLeast(0)
                            if (missed > 0) {
                                Text("$missed missed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        // Empty state
        if (allRelays.isEmpty() && publishReports.isEmpty()) {
            item(key = "delivery_empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(shape = RectangleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(72.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Outbox, null, Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("No delivery data yet", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text("Publish events to see relay delivery performance",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// ── TAB 3: Attention ──
// ══════════════════════════════════════════════════════

@Composable
private fun AttentionTab(
    troubleRelays: List<String>,
    healthMap: Map<String, RelayHealthInfo>,
    autoBlockExpiryMap: Map<String, Long>,
    onOpenRelayLog: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (troubleRelays.isEmpty()) {
            item(key = "attention_empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(shape = RectangleShape, color = Color(0xFF4CAF50).copy(alpha = 0.12f),
                        modifier = Modifier.size(72.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(36.dp),
                                tint = Color(0xFF4CAF50))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("All clear", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("No relays need attention right now",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            item(key = "attention_summary") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RectangleShape,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Warning, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${troubleRelays.size} relay${if (troubleRelays.size != 1) "s" else ""} need attention",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            items(troubleRelays, key = { "trouble_$it" }) { url ->
                val health = healthMap[url]
                val isBlocked = health?.isBlocked == true
                val isFlagged = health?.isFlagged == true
                val expiry = autoBlockExpiryMap[url]

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRelayLog(url) },
                    shape = RectangleShape,
                    color = if (isBlocked) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.12f)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isBlocked) Icons.Outlined.Block else Icons.Outlined.Warning,
                                null, Modifier.size(16.dp),
                                tint = if (isBlocked) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Surface(
                                shape = RectangleShape,
                                color = if (isBlocked) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                        else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    if (isBlocked) "Blocked" else "Flagged",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isBlocked) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        if (health != null) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${health.consecutiveFailures} consecutive failures",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (health.connectionFailures > 0) {
                                    Text("${health.connectionFailures} total",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            health.lastError?.let { err ->
                                Spacer(Modifier.height(4.dp))
                                Text("$err",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }

                        if (expiry != null) {
                            val remaining = expiry - System.currentTimeMillis()
                            if (remaining > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text("Auto-unblocks in ${remaining / 3_600_000}h ${(remaining % 3_600_000) / 60_000}m",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isBlocked) {
                                Surface(
                                    onClick = { RelayHealthTracker.unblockRelay(url) },
                                    shape = RectangleShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Outlined.LockOpen, null, Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary)
                                        Text("Unblock", style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            if (isFlagged && !isBlocked) {
                                Surface(
                                    onClick = { RelayHealthTracker.unflagRelay(url) },
                                    shape = RectangleShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary)
                                        Text("Dismiss", style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Surface(
                                    onClick = { RelayHealthTracker.blockRelay(url) },
                                    shape = RectangleShape,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Outlined.Block, null, Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.error)
                                        Text("Block", style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            Surface(
                                onClick = { RelayHealthTracker.resetRelay(url) },
                                shape = RectangleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Outlined.RestartAlt, null, Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Reset", style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Outbox Delivery Card (Thompson Sampling stats) ──

@Composable
private fun OutboxDeliveryCard(
    topRelays: List<Map.Entry<String, RelayDeliveryTracker.RelayStats>>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.TrendingUp,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Outbox Delivery",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Thompson Sampling",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 10.sp
                )
            }
            Spacer(Modifier.height(10.dp))

            topRelays.forEach { (url, stat) ->
                val displayUrl = url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
                val rate = stat.successRate
                val rateColor = when {
                    rate >= 0.8 -> Color(0xFF4CAF50)
                    rate >= 0.5 -> Color(0xFFFFA726)
                    rate > 0.0 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(rateColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = displayUrl,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    // Mini delivery bar
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(3.dp)
                            .clip(RectangleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(rate.toFloat().coerceIn(0f, 1f))
                                .clip(RectangleShape)
                                .background(rateColor)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${(rate * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = rateColor,
                        modifier = Modifier.width(30.dp)
                    )
                    Text(
                        text = "${stat.delivered.toInt()}/${stat.expected.toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        modifier = Modifier.width(32.dp)
                    )
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
    avgConnectTime: Long,
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
        shape = RectangleShape,
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
                    label = "Connect",
                    value = if (avgConnectTime > 0) "${avgConnectTime}ms" else "—",
                    icon = Icons.Outlined.Speed,
                    accent = when {
                        avgConnectTime in 1..999 -> Color(0xFF4CAF50)
                        avgConnectTime in 1000..2999 -> Color(0xFFFFA726)
                        avgConnectTime >= 3000 -> MaterialTheme.colorScheme.error
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
                .clip(RectangleShape)
                .background(trackColor)
        ) {
            // EOSE'd portion (lighter)
            if (eoseFraction > 0f) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(eoseFraction)
                        .clip(RectangleShape)
                        .background(eoseColor)
                )
            }
            // Active (non-EOSE) portion on top
            if (activeFraction > 0f) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(activeFraction)
                        .clip(RectangleShape)
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                        ) {
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
                            // Restriction badges (payment / auth)
                            if (relayInfo?.limitation?.payment_required == true) {
                                StatusBadge(text = "💰 Paid", color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(4.dp))
                            }
                            if (relayInfo?.limitation?.auth_required == true) {
                                StatusBadge(text = "🔐 Auth", color = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.width(4.dp))
                            }
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
                        // Metrics row — horizontally scrollable to prevent vertical skew
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
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
                            if (health != null && health.connectTimeMs > 0) {
                                val ctColor = when {
                                    health.connectTimeMs < 1000 -> Color(0xFF4CAF50)
                                    health.connectTimeMs < 3000 -> Color(0xFFFFA726)
                                    else -> MaterialTheme.colorScheme.error
                                }
                                MetricLabel(
                                    icon = Icons.Outlined.Speed,
                                    text = "${health.connectTimeMs}ms",
                                    tint = ctColor
                                )
                            }
                            if (health != null && health.lastEventAt > 0) {
                                val recency = formatEventRecency(health.lastEventAt)
                                val recencyColor = when {
                                    System.currentTimeMillis() - health.lastEventAt < 60_000 -> Color(0xFF4CAF50)
                                    System.currentTimeMillis() - health.lastEventAt < 300_000 -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.outline
                                }
                                MetricLabel(
                                    icon = Icons.Outlined.Schedule,
                                    text = recency,
                                    tint = recencyColor
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
        shape = RectangleShape,
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

// ── Format event recency ──

private fun formatEventRecency(epochMs: Long): String {
    val delta = System.currentTimeMillis() - epochMs
    return when {
        delta < 0 -> "now"
        delta < 60_000 -> "${delta / 1000}s ago"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        delta < 86_400_000 -> "${delta / 3_600_000}h ago"
        else -> "${delta / 86_400_000}d ago"
    }
}

// ── Format large numbers ──

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}M"
    count >= 1_000 -> "${count / 1_000}K"
    else -> "$count"
}
