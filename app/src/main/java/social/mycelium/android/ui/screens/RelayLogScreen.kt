package social.mycelium.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.cybin.relay.RelaySlotSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import social.mycelium.android.cache.Nip11CacheManager
import social.mycelium.android.data.DiscoveredRelay
import social.mycelium.android.data.RelayInformation
import social.mycelium.android.relay.LogType
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.RelayDeliveryTracker
import social.mycelium.android.relay.RelayHealthTracker
import social.mycelium.android.relay.RelayLogBuffer
import social.mycelium.android.relay.RelayLogEntry
import social.mycelium.android.repository.Nip66RelayDiscoveryRepository

/** Types we show by default (connection lifecycle + errors/notices). RECEIVED/EOSE/SENT are hidden unless verbose. */
private val RELEVANT_LOG_TYPES = setOf(
    LogType.CONNECTING,
    LogType.CONNECTED,
    LogType.DISCONNECTED,
    LogType.ERROR,
    LogType.NOTICE
)

/**
 * Relay detail screen: NIP-11 info header, NIP-66 discovery data, health stats, and activity log.
 * Logs display newest-first. Inspired by Amethyst's relay detail page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayLogScreen(
    relayUrl: String,
    onBack: () -> Unit,
    /** Called when user wants to add this relay to a relay profile. Passes (relayUrl, profileType).
     *  profileType is "outbox", "inbox", "indexer", or "profile:<profileId>" for custom profiles. */
    onAddToRelayProfile: ((relayUrl: String, profileType: String) -> Unit)? = null,
    /** Custom relay profiles for the "Add to..." menu. */
    relayProfiles: List<social.mycelium.android.data.RelayProfile> = emptyList(),
    /** Navigate to dedicated relay users screen. */
    onOpenRelayUsers: (() -> Unit)? = null,
    /** Number of followed users who write/read to this relay per NIP-65. */
    followedUserCount: Int = 0,
    /** Current user's hex pubkey — used to detect relay operator authority for NIP-86. */
    currentUserPubkey: String? = null,
    /** Signer for the current user — used to authenticate NIP-86 management API calls. */
    currentSigner: com.example.cybin.signer.NostrSigner? = null
) {
    val context = LocalContext.current
    val nip11 = remember(context) { Nip11CacheManager.getInstance(context) }
    var relayInfo by remember(relayUrl) { mutableStateOf<RelayInformation?>(nip11.getCachedRelayInfo(relayUrl)) }
    LaunchedEffect(relayUrl) {
        nip11.getRelayInfo(relayUrl).let { info -> relayInfo = info }
    }

    // NIP-66 discovery data for this relay
    val discoveredRelays by Nip66RelayDiscoveryRepository.discoveredRelays.collectAsState()
    val discoveryData = remember(discoveredRelays, relayUrl) {
        val normalized = relayUrl.lowercase().trimEnd('/')
        discoveredRelays.values.firstOrNull { it.url.lowercase().trimEnd('/') == normalized }
    }

    val allLogs by RelayLogBuffer.getLogsForRelay(relayUrl).collectAsState()
    var showVerbose by remember { mutableStateOf(false) }
    // Newest-first log ordering
    val filteredLogs = remember(allLogs, showVerbose) {
        val logs = if (showVerbose) allLogs else allLogs.filter { it.type in RELEVANT_LOG_TYPES }
        logs.sortedByDescending { it.timestamp }
    }
    val listState = rememberLazyListState()

    // ── Live subscription slot snapshot (refreshes every 1s) ──
    val relayStateMachine = remember { RelayConnectionStateMachine.getInstance() }
    var slotSnapshot by remember { mutableStateOf<RelaySlotSnapshot?>(null) }
    LaunchedEffect(relayUrl) {
        while (true) {
            val normalized = relayUrl.trimEnd('/')
            slotSnapshot = relayStateMachine.getRelaySlotSnapshots()
                .firstOrNull { it.url.trimEnd('/') == normalized }
            delay(1000)
        }
    }

    // ── Delivery stats from Thompson Sampling tracker ──
    val deliveryStat = remember(relayUrl) {
        val normalized = relayUrl.trimEnd('/')
        RelayDeliveryTracker.getStats().entries
            .firstOrNull { it.key.trimEnd('/') == normalized }
            ?.value
    }

    // ── Health data (live) ──
    val healthMap by RelayHealthTracker.healthByRelay.collectAsState()
    val health = healthMap[relayUrl]

    // ── NIP-86 Relay Admin Detection ──
    val operatorPubkeyRaw = relayInfo?.pubkey ?: discoveryData?.operatorPubkey
    val isRelayOperator = remember(operatorPubkeyRaw, currentUserPubkey) {
        operatorPubkeyRaw != null && currentUserPubkey != null &&
            operatorPubkeyRaw.lowercase() == currentUserPubkey.lowercase()
    }
    var nip86Methods by remember { mutableStateOf<List<String>?>(null) }
    var nip86Probing by remember { mutableStateOf(false) }
    var nip86Error by remember { mutableStateOf<String?>(null) }
    var nip86Toast by remember { mutableStateOf<String?>(null) }

    // Probe NIP-86 supported methods when operator is detected
    LaunchedEffect(isRelayOperator, currentSigner) {
        if (isRelayOperator && currentSigner != null && nip86Methods == null && !nip86Probing) {
            nip86Probing = true
            val client = social.mycelium.android.services.Nip86Client(currentSigner)
            when (val result = client.supportedMethods(relayUrl)) {
                is social.mycelium.android.services.Nip86Client.Nip86Result.Success -> {
                    nip86Methods = result.data
                    nip86Error = null
                }
                is social.mycelium.android.services.Nip86Client.Nip86Result.Error -> {
                    nip86Methods = emptyList()
                    nip86Error = result.message
                }
            }
            nip86Probing = false
        }
    }

    // Re-fetch NIP-11 after NIP-86 admin change
    var nip86RefreshTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(nip86RefreshTrigger) {
        if (nip86RefreshTrigger > 0) {
            nip11.getRelayInfo(relayUrl, forceRefresh = true)?.let { relayInfo = it }
        }
    }

    // Show toast for NIP-86 feedback
    LaunchedEffect(nip86Toast) {
        if (nip86Toast != null) {
            delay(3000)
            nip86Toast = null
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = discoveryData?.name ?: relayInfo?.name?.take(24) ?: relayUrl.takeAfterLastSlash(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = relayUrl.removePrefix("wss://").removePrefix("ws://"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        var showAddMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showAddMenu = true }) {
                                Icon(Icons.Outlined.Add, contentDescription = "Add to my relays")
                            }
                            DropdownMenu(
                                expanded = showAddMenu,
                                onDismissRequest = { showAddMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Add to Outbox Relays") },
                                    leadingIcon = { Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = { showAddMenu = false; onAddToRelayProfile?.invoke(relayUrl, "outbox") }
                                )
                                DropdownMenuItem(
                                    text = { Text("Add to Inbox Relays") },
                                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = { showAddMenu = false; onAddToRelayProfile?.invoke(relayUrl, "inbox") }
                                )
                                DropdownMenuItem(
                                    text = { Text("Add to Indexer Relays") },
                                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = { showAddMenu = false; onAddToRelayProfile?.invoke(relayUrl, "indexer") }
                                )
                                if (relayProfiles.isNotEmpty()) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    relayProfiles.forEach { profile ->
                                        DropdownMenuItem(
                                            text = { Text("Add to ${profile.name}") },
                                            leadingIcon = { Icon(Icons.Outlined.RssFeed, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                            onClick = { showAddMenu = false; onAddToRelayProfile?.invoke(relayUrl, "profile:${profile.id}") }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.surface),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Relay Info Header ──
            item(key = "relay_header") {
                RelayInfoHeader(relayUrl = relayUrl, info = relayInfo, discovery = discoveryData)
            }

            // ── NIP-86 Relay Admin Controls ──
            val methods = nip86Methods
            if (isRelayOperator && currentSigner != null && methods != null && methods.isNotEmpty()) {
                item(key = "nip86_admin") {
                    Nip86AdminCard(
                        relayUrl = relayUrl,
                        relayInfo = relayInfo,
                        supportedMethods = methods,
                        signer = currentSigner,
                        onToast = { nip86Toast = it },
                        onInfoUpdated = { nip86RefreshTrigger++ }
                    )
                }
            } else if (isRelayOperator && nip86Probing) {
                item(key = "nip86_probing") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Checking relay management API…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else if (isRelayOperator && nip86Error != null) {
                item(key = "nip86_error") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.AdminPanelSettings, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Relay Operator", style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                Text("NIP-86 management API unavailable", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }

            // ── NIP-86 Toast ──
            if (nip86Toast != null) {
                item(key = "nip86_toast") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = nip86Toast ?: "",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // ── Connection Status ──
            item(key = "connection_status") {
                val perRelayState by relayStateMachine.perRelayState.collectAsState()
                val connState = perRelayState[relayUrl]
                ConnectionStatusCard(relayUrl = relayUrl, connState = connState, health = health)
            }

            // ── Subscription Slots (live) ──
            if (slotSnapshot != null) {
                item(key = "subscription_slots") {
                    SubscriptionSlotsCard(snapshot = slotSnapshot!!)
                }
            }

            // ── Delivery Performance (Thompson Sampling) ──
            if (deliveryStat != null && deliveryStat.expected >= 1.0) {
                item(key = "delivery_stats") {
                    DeliveryPerformanceCard(stat = deliveryStat)
                }
            }

            // ── NIP-66 Discovery Data ──
            if (discoveryData != null) {
                item(key = "nip66_data") {
                    Nip66DataCard(discovery = discoveryData!!)
                }
            }

            // ── Limitations & Policies ──
            val limitations = relayInfo?.limitation
            val hasFees = relayInfo?.fees != null
            val hasLimitations = limitations != null || relayInfo?.posting_policy != null || hasFees
            if (hasLimitations) {
                item(key = "limitations") {
                    LimitationsCard(info = relayInfo!!)
                }
            }

            // ── Supported NIPs ──
            val allNips = (relayInfo?.supported_nips ?: emptyList()).toSet() +
                (discoveryData?.supportedNips ?: emptySet())
            if (allNips.isNotEmpty()) {
                item(key = "supported_nips") {
                    SupportedNipsCard(nips = allNips.sorted())
                }
            }

            // ── Operator ──
            val operatorPubkey = relayInfo?.pubkey ?: discoveryData?.operatorPubkey
            val contact = relayInfo?.contact
            if (operatorPubkey != null || contact != null) {
                item(key = "operator") {
                    OperatorCard(pubkey = operatorPubkey, contact = contact)
                }
            }

            // ── Followed Users ──
            if (followedUserCount > 0 && onOpenRelayUsers != null) {
                item(key = "followed_users") {
                    Surface(
                        onClick = onOpenRelayUsers,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        Icons.Outlined.People, null,
                                        Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Followed Users",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "$followedUserCount user${if (followedUserCount != 1) "s" else ""} write or read here",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.Outlined.ChevronRight, null,
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            // ── Health Stats ──
            item(key = "health_stats") {
                if (health != null && health.connectionAttempts > 0) {
                    HealthCard(
                        health = health,
                        relayUrl = relayUrl
                    )
                }
            }

            // ── Activity Log ──
            item(key = "activity_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Activity",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${filteredLogs.size} entries",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilterChip(
                            selected = showVerbose,
                            onClick = { showVerbose = !showVerbose },
                            label = { Text("Verbose", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }

            if (filteredLogs.isEmpty()) {
                item(key = "empty") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Outlined.History, null,
                                Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (allLogs.isEmpty()) "No activity yet" else "No connection or notice events",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(filteredLogs, key = { index, it -> "$index-${it.timestamp}-${it.type}" }) { _, entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

private fun String.takeAfterLastSlash(): String = substringAfterLast('/')

// ── Relay Info Header ──

@Composable
private fun RelayInfoHeader(
    relayUrl: String,
    info: RelayInformation?,
    discovery: DiscoveredRelay?
) {
    val infoImage = discovery?.icon ?: info?.icon ?: info?.image
    val relayName = discovery?.name ?: info?.name
    val description = discovery?.description ?: info?.description

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column {
            // Banner (if available from NIP-66)
            discovery?.banner?.let { bannerUrl ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(bannerUrl)
                        .crossfade(true)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Relay icon
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(56.dp)
                ) {
                    if (infoImage != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(infoImage)
                                .crossfade(true)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Outlined.Router, null,
                                Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    if (relayName != null) {
                        Text(
                            text = relayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = relayUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = desc.take(200).let { if (desc.length > 200) "$it…" else it },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // NIP-11 metadata chips
                    val softwareName = info?.software ?: discovery?.softwareShort
                    if (softwareName != null || info?.supported_nips?.isNotEmpty() == true) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            softwareName?.let { sw ->
                                val label = sw.removePrefix("git+").removePrefix("https://github.com/")
                                    .removeSuffix(".git").substringAfterLast("/").take(20)
                                RelayDetailChip(label, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                            info?.version?.takeIf { it.isNotBlank() }?.let { v ->
                                RelayDetailChip("v$v", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── NIP-66 Discovery Data Card ──

@Composable
private fun Nip66DataCard(discovery: DiscoveredRelay) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Discovery",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))

            // Type tags
            if (discovery.types.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    discovery.types.forEach { type ->
                        RelayDetailChip(type.displayName, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Stats grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RelayStatItem("Monitors", "${discovery.monitorCount}")
                discovery.avgRttOpen?.let { rtt ->
                    val rttColor = when {
                        rtt < 500 -> Color(0xFF66BB6A)
                        rtt < 1000 -> Color(0xFFFFA726)
                        else -> MaterialTheme.colorScheme.error
                    }
                    RelayStatItem("Latency", "${rtt}ms", rttColor)
                } ?: RelayStatItem("Latency", "—")
                RelayStatItem("NIPs", "${discovery.supportedNips.size}")
                RelayStatItem("NIP-11", if (discovery.hasNip11) "Yes" else "No")
            }

            // Metadata row
            val hasMetadata = discovery.countryCode != null || discovery.softwareShort != null ||
                discovery.paymentRequired || discovery.authRequired
            if (hasMetadata) {
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    discovery.countryCode?.let { cc ->
                        val flag = countryCodeToFlag(cc)
                        val name = COUNTRY_NAMES[cc.uppercase()] ?: cc
                        RelayDetailChip("$flag $name", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    discovery.softwareShort?.let { sw ->
                        RelayDetailChip(sw, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    if (discovery.paymentRequired) {
                        RelayDetailChip("Payment Required", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    }
                    if (discovery.authRequired) {
                        RelayDetailChip("Auth Required", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    if (discovery.restrictedWrites) {
                        RelayDetailChip("Restricted Writes", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }

        }
    }
}

// ── Connection Status Card (enhanced) ──

@Composable
private fun ConnectionStatusCard(
    relayUrl: String,
    connState: social.mycelium.android.relay.RelayEndpointStatus?,
    health: social.mycelium.android.relay.RelayHealthInfo?
) {
    val statusLabel: String
    val statusColor: Color
    val statusIcon: androidx.compose.ui.graphics.vector.ImageVector
    when {
        connState == social.mycelium.android.relay.RelayEndpointStatus.Connected -> {
            statusLabel = "Connected"
            statusColor = Color(0xFF66BB6A)
            statusIcon = Icons.Outlined.CheckCircle
        }
        connState == social.mycelium.android.relay.RelayEndpointStatus.Connecting -> {
            statusLabel = "Connecting…"
            statusColor = MaterialTheme.colorScheme.primary
            statusIcon = Icons.Outlined.Sync
        }
        health?.isBlocked == true -> {
            statusLabel = "Blocked"
            statusColor = MaterialTheme.colorScheme.error
            statusIcon = Icons.Outlined.Block
        }
        health?.isFlagged == true -> {
            statusLabel = "Flagged"
            statusColor = MaterialTheme.colorScheme.tertiary
            statusIcon = Icons.Outlined.Warning
        }
        connState == social.mycelium.android.relay.RelayEndpointStatus.Failed -> {
            statusLabel = "Connection Failed"
            statusColor = MaterialTheme.colorScheme.error
            statusIcon = Icons.Outlined.ErrorOutline
        }
        else -> {
            statusLabel = "Disconnected"
            statusColor = MaterialTheme.colorScheme.outline
            statusIcon = Icons.Outlined.CloudOff
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top row: status icon + label + events
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(shape = CircleShape, color = statusColor.copy(alpha = 0.12f), modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(statusIcon, null, Modifier.size(20.dp), tint = statusColor)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(statusLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = statusColor)
                    if (health != null && health.connectTimeMs > 0) {
                        Text("${health.connectTimeMs}ms connect", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (health != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${health.eventsReceived}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("events", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Uptime ratio bar (if we have enough data)
            if (health != null && health.connectionAttempts >= 2) {
                Spacer(Modifier.height(12.dp))
                val uptimePercent = (health.uptimeRatio * 100).toInt()
                val barColor = when {
                    health.uptimeRatio >= 0.8f -> Color(0xFF4CAF50)
                    health.uptimeRatio >= 0.5f -> Color(0xFFFFA726)
                    else -> MaterialTheme.colorScheme.error
                }
                val animatedRatio by animateFloatAsState(
                    targetValue = health.uptimeRatio.coerceIn(0f, 1f),
                    animationSpec = tween(600),
                    label = "uptimeBar"
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Uptime", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedRatio)
                                .clip(RoundedCornerShape(3.dp))
                                .background(barColor)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("$uptimePercent%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = barColor)
                }
            }

            // Timeline: last connected, last failed, last event, first seen
            if (health != null) {
                val timestamps = mutableListOf<Pair<String, Long>>()
                if (health.lastConnectedAt > 0) timestamps.add("Connected" to health.lastConnectedAt)
                if (health.lastFailedAt > 0) timestamps.add("Failed" to health.lastFailedAt)
                if (health.lastEventAt > 0) timestamps.add("Event" to health.lastEventAt)
                if (health.firstSeenAt > 0) timestamps.add("First seen" to health.firstSeenAt)

                if (timestamps.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    timestamps.forEach { (label, ts) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatRelativeTime(ts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            // Last error
            if (health?.lastError != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            health.lastError!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ── Subscription Slots Card ──

@Composable
private fun SubscriptionSlotsCard(snapshot: RelaySlotSnapshot) {
    val utilization = if (snapshot.effectiveLimit > 0)
        snapshot.activeCount.toFloat() / snapshot.effectiveLimit else 0f
    val barColor = when {
        utilization >= 0.9f -> MaterialTheme.colorScheme.error
        utilization >= 0.7f -> Color(0xFFFFA726)
        else -> Color(0xFF4CAF50)
    }
    val animatedUtil by animateFloatAsState(
        targetValue = utilization.coerceIn(0f, 1f),
        animationSpec = tween(400),
        label = "slotUtil"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Subscription Slots", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))

            // Stats row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RelayStatItem("Active", "${snapshot.activeCount}")
                RelayStatItem("Queued", "${snapshot.queuedCount}",
                    if (snapshot.queuedCount > 0) Color(0xFFFFA726) else null)
                RelayStatItem("EOSE", "${snapshot.eoseCount}", Color(0xFF4CAF50))
                RelayStatItem("Limit", "${snapshot.effectiveLimit}")
            }

            // Utilization bar
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Utilization", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedUtil)
                            .clip(RoundedCornerShape(3.dp))
                            .background(barColor)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${(utilization * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = barColor
                )
            }

            // Priority breakdown (if available)
            if (snapshot.activePriorityBreakdown.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    snapshot.activePriorityBreakdown.forEach { (priority, count) ->
                        RelayDetailChip(
                            "$priority: $count",
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── Delivery Performance Card ──

@Composable
private fun DeliveryPerformanceCard(stat: RelayDeliveryTracker.RelayStats) {
    val rate = stat.successRate
    val rateColor = when {
        rate >= 0.8 -> Color(0xFF4CAF50)
        rate >= 0.5 -> Color(0xFFFFA726)
        rate > 0.0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val animatedRate by animateFloatAsState(
        targetValue = rate.toFloat().coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "deliveryRate"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Outbox, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Delivery", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("Thompson Sampling", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), fontSize = 10.sp)
            }
            Spacer(Modifier.height(12.dp))

            // Delivery rate bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Success", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedRate)
                            .clip(RoundedCornerShape(4.dp))
                            .background(rateColor)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("${(rate * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = rateColor)
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RelayStatItem("Delivered", "${stat.delivered.toInt()}")
                RelayStatItem("Expected", "${stat.expected.toInt()}")
                RelayStatItem("Missed", "${(stat.expected - stat.delivered).toInt().coerceAtLeast(0)}",
                    if (stat.expected - stat.delivered > 0) MaterialTheme.colorScheme.error else null)
            }
        }
    }
}

// ── Limitations Card ──

@Composable
private fun LimitationsCard(info: RelayInformation) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Policies & Limits", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            // Access policy chips
            val chips = mutableListOf<Pair<String, Color>>()
            if (info.limitation?.auth_required == true) chips.add("Auth Required" to MaterialTheme.colorScheme.tertiary)
            if (info.limitation?.payment_required == true) chips.add("Payment Required" to MaterialTheme.colorScheme.error)
            if (info.limitation?.restricted_writes == true) chips.add("Restricted Writes" to MaterialTheme.colorScheme.secondary)
            if (chips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    chips.forEach { (label, color) ->
                        Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.12f)) {
                            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Limits grid
            val limits = info.limitation
            if (limits != null) {
                val items = mutableListOf<Pair<String, String>>()
                limits.max_message_length?.let { items.add("Max Message" to formatBytes(it)) }
                limits.max_content_length?.let { items.add("Max Content" to formatBytes(it)) }
                limits.max_event_tags?.let { items.add("Max Tags" to "$it") }
                limits.max_subscriptions?.let { items.add("Max Subs" to "$it") }
                limits.max_filters?.let { items.add("Max Filters" to "$it") }
                limits.max_limit?.let { items.add("Max Limit" to "$it") }
                limits.min_pow_difficulty?.let { items.add("Min PoW" to "$it") }
                limits.created_at_lower_limit?.let {
                    val days = ((System.currentTimeMillis() / 1000 - it) / 86400).toInt()
                    if (days > 0) items.add("History" to "${days}d")
                }

                if (items.isNotEmpty()) {
                    val rows = items.chunked(3)
                    rows.forEachIndexed { idx, row ->
                        if (idx > 0) Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { (label, value) ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                    Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            // Fill remaining columns if row is short
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            // Fees
            info.fees?.let { fees ->
                val feeItems = mutableListOf<String>()
                fees.admission?.forEach { fee -> feeItems.add("Admission: ${fee.amount} ${fee.unit}") }
                fees.subscription?.forEach { fee ->
                    val period = fee.period?.let { p -> " / ${p / 86400}d" } ?: ""
                    feeItems.add("Subscription: ${fee.amount} ${fee.unit}$period")
                }
                fees.publication?.forEach { fee -> feeItems.add("Publication: ${fee.amount} ${fee.unit}") }
                if (feeItems.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    feeItems.forEach { feeText ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Payments, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(feeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Posting policy
            info.posting_policy?.takeIf { it.isNotBlank() }?.let { policy ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Policy, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(policy, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ── Supported NIPs Card ──

@Composable
private fun SupportedNipsCard(nips: List<Int>) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Supported NIPs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${nips.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    nips.forEach { nip ->
                        val desc = NIP_DESCRIPTIONS[nip]
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.width(48.dp)) {
                                Text(
                                    text = "$nip",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = desc ?: "NIP-$nip",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (desc != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Operator Card ──

@Composable
private fun OperatorCard(pubkey: String?, contact: String?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Operator", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            pubkey?.takeIf { it.isNotBlank() }?.let { pk ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Key, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = pk.take(8) + "…" + pk.takeLast(8),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            contact?.takeIf { it.isNotBlank() }?.let { c ->
                if (pubkey != null) Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AlternateEmail, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(c, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

// ── Health Card (enhanced with quick actions) ──

@Composable
private fun HealthCard(
    health: social.mycelium.android.relay.RelayHealthInfo,
    relayUrl: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MonitorHeart, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Health", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))

            // Stats grid row 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RelayStatItem("Connections", "${health.connectionAttempts}")
                RelayStatItem("Failures", "${health.connectionFailures}",
                    if (health.connectionFailures > 0) MaterialTheme.colorScheme.error else null)
                val ratePercent = (health.failureRate * 100).toInt()
                RelayStatItem("Fail Rate", "$ratePercent%",
                    if (ratePercent > 30) MaterialTheme.colorScheme.error else null)
            }
            Spacer(Modifier.height(8.dp))

            // Stats grid row 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RelayStatItem("Connect", if (health.connectTimeMs > 0) "${health.connectTimeMs}ms" else "—")
                RelayStatItem("Events", "${health.eventsReceived}")
                RelayStatItem("Streak", "${health.consecutiveFailures}",
                    if (health.consecutiveFailures > 2) MaterialTheme.colorScheme.error else null)
            }

            // Flagged/Blocked status badge
            if (health.isFlagged || health.isBlocked) {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val statusText = when {
                        health.isBlocked -> "Blocked"
                        health.isFlagged -> "Flagged — unreliable"
                        else -> ""
                    }
                    val statusColor = if (health.isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // ── Quick Actions strip ──
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Block / Unblock
                if (health.isBlocked) {
                    ActionChip(
                        label = "Unblock",
                        icon = Icons.Outlined.LockOpen,
                        onClick = { RelayHealthTracker.unblockRelay(relayUrl) }
                    )
                } else {
                    ActionChip(
                        label = "Block",
                        icon = Icons.Outlined.Block,
                        onClick = { RelayHealthTracker.blockRelay(relayUrl) },
                        isDestructive = true
                    )
                }
                // Dismiss flag
                if (health.isFlagged && !health.isBlocked) {
                    ActionChip(
                        label = "Dismiss",
                        icon = Icons.Outlined.CheckCircle,
                        onClick = { RelayHealthTracker.unflagRelay(relayUrl) }
                    )
                }
                // Reset health data
                ActionChip(
                    label = "Reset",
                    icon = Icons.Outlined.RestartAlt,
                    onClick = { RelayHealthTracker.resetRelay(relayUrl) }
                )
                // Clear logs
                ActionChip(
                    label = "Clear Log",
                    icon = Icons.Outlined.DeleteSweep,
                    onClick = { RelayLogBuffer.clearLogsForRelay(relayUrl) }
                )
            }
        }
    }
}

// ── Action Chip (compact tappable action) ──

@Composable
private fun ActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    val color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, Modifier.size(14.dp), tint = color)
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = color)
        }
    }
}

// ── Log Entry Row ──

@Composable
private fun LogEntryRow(entry: RelayLogEntry) {
    val (iconTint, label) = when (entry.type) {
        LogType.CONNECTING -> MaterialTheme.colorScheme.primary to "CONN"
        LogType.CONNECTED -> Color(0xFF66BB6A) to "OK"
        LogType.DISCONNECTED -> MaterialTheme.colorScheme.outline to "DISC"
        LogType.ERROR -> MaterialTheme.colorScheme.error to "ERR"
        LogType.NOTICE -> MaterialTheme.colorScheme.tertiary to "NOTE"
        LogType.SENT -> MaterialTheme.colorScheme.secondary to "SEND"
        LogType.RECEIVED -> MaterialTheme.colorScheme.onSurfaceVariant to "RECV"
        LogType.EOSE -> MaterialTheme.colorScheme.onSurfaceVariant to "EOSE"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Timestamp
        Text(
            text = entry.formattedTime(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.width(68.dp)
        )
        // Type badge
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = iconTint.copy(alpha = 0.12f),
            modifier = Modifier.width(42.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = iconTint,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        // Message
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

// ── Small Components ──

@Composable
private fun RelayStatItem(
    label: String,
    value: String,
    valueColor: Color? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RelayDetailChip(text: String, backgroundColor: Color, textColor: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = backgroundColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** Format epoch ms timestamp to human-readable relative time. */
private fun formatRelativeTime(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val delta = now - epochMs
    return when {
        delta < 0 -> "just now"
        delta < 60_000 -> "${delta / 1000}s ago"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        delta < 86_400_000 -> "${delta / 3_600_000}h ago"
        delta < 604_800_000 -> "${delta / 86_400_000}d ago"
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
            sdf.format(java.util.Date(epochMs))
        }
    }
}

/** Convert ISO 3166-1 alpha-2 country code to flag emoji. */
private fun countryCodeToFlag(code: String): String {
    if (code.length != 2) return code
    val first = Character.codePointAt(code.uppercase(), 0) - 0x41 + 0x1F1E6
    val second = Character.codePointAt(code.uppercase(), 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

/** Format byte count to human-readable string. */
private fun formatBytes(bytes: Int): String = when {
    bytes >= 1_048_576 -> "${bytes / 1_048_576}MB"
    bytes >= 1_024 -> "${bytes / 1_024}KB"
    else -> "${bytes}B"
}

/** Human-readable descriptions for common NIPs. */
private val NIP_DESCRIPTIONS = mapOf(
    1 to "Basic protocol flow",
    2 to "Follow list",
    3 to "OpenTimestamps",
    4 to "Encrypted DMs (deprecated)",
    5 to "Event deletion",
    6 to "Reposts",
    7 to "Reactions",
    8 to "Mentions",
    9 to "Event replacement",
    10 to "Conventions on relay lists",
    11 to "Relay information document",
    13 to "Proof of Work",
    14 to "Subject tag",
    15 to "Nostr Marketplace",
    17 to "Private DMs",
    18 to "Reposts",
    19 to "bech32 entities",
    21 to "nostr: URI scheme",
    22 to "Comment",
    23 to "Long-form content",
    24 to "Extra metadata",
    25 to "Reactions",
    26 to "Delegated event signing",
    27 to "Text note references",
    28 to "Public chat",
    29 to "Relay-based groups",
    30 to "Custom emoji",
    31 to "Lists",
    32 to "Labeling",
    33 to "Parameterized replaceable events",
    34 to "git stuff",
    36 to "Sensitive content",
    38 to "User statuses",
    39 to "External identities",
    40 to "Expiration timestamp",
    42 to "Authentication",
    44 to "Versioned encryption",
    45 to "Counting",
    46 to "Nostr Connect",
    47 to "Wallet Connect",
    48 to "Proxy tags",
    49 to "Private key encryption",
    50 to "Search",
    51 to "Lists",
    52 to "Calendar events",
    53 to "Live activities",
    54 to "Wiki",
    55 to "Android signer",
    56 to "Reporting",
    57 to "Lightning zaps",
    58 to "Badges",
    59 to "Gift wrap",
    64 to "Chess",
    65 to "Relay list metadata",
    70 to "Protected events",
    71 to "Video events",
    72 to "Moderated communities",
    73 to "Subscriptions",
    75 to "Zap goals",
    78 to "Application-specific data",
    84 to "Highlights",
    89 to "Recommended app handlers",
    90 to "Data vending machine",
    92 to "Media attachments",
    94 to "File metadata",
    96 to "HTTP file storage",
    98 to "HTTP Auth",
    99 to "Classified listings"
)

// ── NIP-86 Admin Card ──

@Composable
private fun Nip86AdminCard(
    relayUrl: String,
    relayInfo: RelayInformation?,
    supportedMethods: List<String>,
    signer: com.example.cybin.signer.NostrSigner,
    onToast: (String) -> Unit,
    onInfoUpdated: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val client = remember(signer) { social.mycelium.android.services.Nip86Client(signer) }

    val canChangeName = "changerelayname" in supportedMethods
    val canChangeDesc = "changerelaydescription" in supportedMethods
    val canChangeIcon = "changerelayicon" in supportedMethods

    var editingName by remember { mutableStateOf(false) }
    var editingDesc by remember { mutableStateOf(false) }
    var editingIcon by remember { mutableStateOf(false) }
    var nameText by remember(relayInfo?.name) { mutableStateOf(relayInfo?.name ?: "") }
    var descText by remember(relayInfo?.description) { mutableStateOf(relayInfo?.description ?: "") }
    var iconText by remember(relayInfo?.icon, relayInfo?.image) { mutableStateOf(relayInfo?.icon ?: relayInfo?.image ?: "") }
    var isSaving by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AdminPanelSettings, null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Relay Administration",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "NIP-86 · ${supportedMethods.size} method${if (supportedMethods.size != 1) "s" else ""} available",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(12.dp))

            // ── Relay Name ──
            if (canChangeName) {
                Nip86EditableField(
                    label = "Name",
                    value = nameText,
                    onValueChange = { nameText = it },
                    isEditing = editingName,
                    onEditToggle = { editingName = !editingName },
                    isSaving = isSaving,
                    onSave = {
                        isSaving = true
                        scope.launch {
                            when (val r = client.changeRelayName(relayUrl, nameText)) {
                                is social.mycelium.android.services.Nip86Client.Nip86Result.Success -> {
                                    onToast("Relay name updated")
                                    editingName = false
                                    onInfoUpdated()
                                }
                                is social.mycelium.android.services.Nip86Client.Nip86Result.Error -> onToast("Failed: ${r.message}")
                            }
                            isSaving = false
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
            }

            // ── Relay Description ──
            if (canChangeDesc) {
                Nip86EditableField(
                    label = "Description",
                    value = descText,
                    onValueChange = { descText = it },
                    isEditing = editingDesc,
                    onEditToggle = { editingDesc = !editingDesc },
                    isSaving = isSaving,
                    singleLine = false,
                    onSave = {
                        isSaving = true
                        scope.launch {
                            when (val r = client.changeRelayDescription(relayUrl, descText)) {
                                is social.mycelium.android.services.Nip86Client.Nip86Result.Success -> {
                                    onToast("Relay description updated")
                                    editingDesc = false
                                    onInfoUpdated()
                                }
                                is social.mycelium.android.services.Nip86Client.Nip86Result.Error -> onToast("Failed: ${r.message}")
                            }
                            isSaving = false
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
            }

            // ── Relay Icon ──
            if (canChangeIcon) {
                Nip86EditableField(
                    label = "Icon URL",
                    value = iconText,
                    onValueChange = { iconText = it },
                    isEditing = editingIcon,
                    onEditToggle = { editingIcon = !editingIcon },
                    isSaving = isSaving,
                    onSave = {
                        isSaving = true
                        scope.launch {
                            when (val r = client.changeRelayIcon(relayUrl, iconText)) {
                                is social.mycelium.android.services.Nip86Client.Nip86Result.Success -> {
                                    onToast("Relay icon updated")
                                    editingIcon = false
                                    onInfoUpdated()
                                }
                                is social.mycelium.android.services.Nip86Client.Nip86Result.Error -> onToast("Failed: ${r.message}")
                            }
                            isSaving = false
                        }
                    }
                )
            }

            // ── Other available methods (informational) ──
            val otherMethods = supportedMethods.filter {
                it !in setOf("supportedmethods", "changerelayname", "changerelaydescription", "changerelayicon")
            }
            if (otherMethods.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.15f))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Additional capabilities",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    otherMethods.forEach { method ->
                        RelayDetailChip(
                            method,
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Nip86EditableField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isEditing: Boolean,
    onEditToggle: () -> Unit,
    isSaving: Boolean,
    singleLine: Boolean = true,
    onSave: () -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f)
            )
            if (!isEditing) {
                IconButton(onClick = onEditToggle, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Edit, "Edit $label", Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f))
                }
            }
        }
        if (isEditing) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 4,
                textStyle = MaterialTheme.typography.bodySmall,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.3f)
                )
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onSave,
                    enabled = !isSaving,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(
                    onClick = onEditToggle,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            Text(
                text = value.ifBlank { "(not set)" },
                style = MaterialTheme.typography.bodySmall,
                color = if (value.isBlank()) MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = if (singleLine) 1 else 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val COUNTRY_NAMES = mapOf(
    "AD" to "Andorra", "AE" to "UAE", "AF" to "Afghanistan", "AR" to "Argentina",
    "AT" to "Austria", "AU" to "Australia", "BE" to "Belgium", "BG" to "Bulgaria",
    "BR" to "Brazil", "CA" to "Canada", "CH" to "Switzerland", "CL" to "Chile",
    "CN" to "China", "CO" to "Colombia", "CZ" to "Czechia", "DE" to "Germany",
    "DK" to "Denmark", "EE" to "Estonia", "ES" to "Spain", "FI" to "Finland",
    "FR" to "France", "GB" to "United Kingdom", "GR" to "Greece", "HK" to "Hong Kong",
    "HR" to "Croatia", "HU" to "Hungary", "ID" to "Indonesia", "IE" to "Ireland",
    "IL" to "Israel", "IN" to "India", "IS" to "Iceland", "IT" to "Italy",
    "JP" to "Japan", "KR" to "South Korea", "LT" to "Lithuania", "LU" to "Luxembourg",
    "LV" to "Latvia", "MX" to "Mexico", "MY" to "Malaysia", "NL" to "Netherlands",
    "NO" to "Norway", "NZ" to "New Zealand", "PH" to "Philippines", "PL" to "Poland",
    "PT" to "Portugal", "RO" to "Romania", "RS" to "Serbia", "RU" to "Russia",
    "SA" to "Saudi Arabia", "SE" to "Sweden", "SG" to "Singapore", "SI" to "Slovenia",
    "SK" to "Slovakia", "TH" to "Thailand", "TR" to "Turkey", "TW" to "Taiwan",
    "UA" to "Ukraine", "US" to "United States", "VN" to "Vietnam", "ZA" to "South Africa"
)
