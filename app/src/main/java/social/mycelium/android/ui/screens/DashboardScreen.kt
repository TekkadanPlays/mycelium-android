package social.mycelium.android.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.consumeWindowInsets
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import social.mycelium.android.data.Note
import social.mycelium.android.ui.components.AdaptiveHeader
import social.mycelium.android.ui.components.BottomNavigationBar
import social.mycelium.android.ui.components.SmartBottomNavigationBar
import social.mycelium.android.ui.components.ScrollAwareBottomNavigationBar
import social.mycelium.android.ui.components.BottomNavDestinations
import social.mycelium.android.ui.components.ModernSearchBar
import social.mycelium.android.ui.components.GlobalSidebar
import social.mycelium.android.ui.components.NoteCard
import social.mycelium.android.ui.components.LoadingAnimation
import social.mycelium.android.ui.components.NoteCard
import social.mycelium.android.viewmodel.DashboardViewModel
import social.mycelium.android.viewmodel.AuthViewModel
import social.mycelium.android.viewmodel.RelayManagementViewModel
import social.mycelium.android.viewmodel.FeedStateViewModel
import social.mycelium.android.viewmodel.HomeSortOrder
import social.mycelium.android.viewmodel.ScrollPosition
import social.mycelium.android.data.RelayConnectionStatus
import social.mycelium.android.relay.RelayState
import social.mycelium.android.repository.RelayRepository
import social.mycelium.android.repository.RelayStorageManager
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import social.mycelium.android.repository.LiveActivityRepository
import social.mycelium.android.repository.FeedSessionState
import social.mycelium.android.utils.normalizeRelayUrl
import social.mycelium.android.viewmodel.FeedState
import social.mycelium.android.ui.performance.animatedYOffset
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ✅ PERFORMANCE: Cached date formatter (Thread view pattern)
private val dateFormatter by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

/**
 * Extracted relay connection loading indicator — progress ring, animated orbs, status text.
 * Splits ~190 lines from FeedOverlay to reduce JIT from 7.3MB.
 */
@Composable
private fun RelayConnectionLoadingIndicator(
    perRelayState: Map<String, social.mycelium.android.relay.RelayEndpointStatus>,
    feedSession: FeedSessionState,
    onNavigateTo: (String) -> Unit,
) {
    val relayEntries = perRelayState.entries.toList()
    val connectedCount = relayEntries.count {
        it.value == social.mycelium.android.relay.RelayEndpointStatus.Connected
    }
    val connectingCount = relayEntries.count {
        it.value == social.mycelium.android.relay.RelayEndpointStatus.Connecting
    }
    val failedCount = relayEntries.count {
        it.value == social.mycelium.android.relay.RelayEndpointStatus.Failed
    }
    val totalCount = relayEntries.size

    val targetProgress = if (totalCount > 0) connectedCount.toFloat() / totalCount else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "ring_progress"
    )

    // Fixed-size column so nothing shifts as relay states change.
    // Total height: 80 (ring) + 20 (spacer) + 28 (orbs) + 8 (spacer) + 36 (text) = 172dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 48.dp)
            .height(172.dp)
    ) {
        // ── Progress ring + mushroom (fixed 80dp) ──
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(80.dp)
        ) {
            CircularProgressIndicator(
                progress = { 1f },
                modifier = Modifier.size(80.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                trackColor = Color.Transparent
            )
            if (totalCount > 0 && animatedProgress > 0f) {
                CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.size(80.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    trackColor = Color.Transparent
                )
            }
            if (connectingCount > 0 || totalCount == 0) {
                CircularProgressIndicator(
                    modifier = Modifier.size(80.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                )
            }
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = social.mycelium.android.R.drawable.ic_mushroom_purple),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = Color.Unspecified
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Relay orbs (fixed 28dp container so appearing/disappearing doesn't shift text) ──
        Box(
            modifier = Modifier.height(28.dp),
            contentAlignment = Alignment.Center
        ) {
            if (relayEntries.isNotEmpty()) {
                val maxIndividualOrbs = 8
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .clickable { onNavigateTo("relay_connection_status") }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    if (totalCount <= maxIndividualOrbs) {
                        for ((_, status) in relayEntries) {
                            val targetColor = when (status) {
                                social.mycelium.android.relay.RelayEndpointStatus.Connected ->
                                    Color(0xFF4CAF50)
                                social.mycelium.android.relay.RelayEndpointStatus.Connecting ->
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                social.mycelium.android.relay.RelayEndpointStatus.Failed ->
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                            }
                            val animColor by animateColorAsState(
                                targetValue = targetColor,
                                animationSpec = tween(400),
                                label = "orb_color"
                            )
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(animColor, shape = androidx.compose.foundation.shape.CircleShape)
                            )
                        }
                    } else {
                        if (connectedCount > 0) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(Color(0xFF4CAF50), shape = androidx.compose.foundation.shape.CircleShape)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "$connectedCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4CAF50).copy(alpha = 0.8f),
                                fontSize = 10.sp
                            )
                        }
                        if (connectingCount > 0) {
                            if (connectedCount > 0) Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), shape = androidx.compose.foundation.shape.CircleShape)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "$connectingCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                fontSize = 10.sp
                            )
                        }
                        if (failedCount > 0) {
                            if (connectedCount > 0 || connectingCount > 0) Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.4f), shape = androidx.compose.foundation.shape.CircleShape)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "$failedCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Status text (fixed 36dp — all text crossfades in place) ──
        val statusText = when (feedSession) {
            FeedSessionState.Loading -> {
                if (connectedCount > 0) "Receiving notes\u2026"
                else if (totalCount > 0) "Connecting to relays\u2026"
                else "Connecting\u2026"
            }
            FeedSessionState.Idle -> {
                if (totalCount > 0 && connectedCount > 0) "Waiting for notes\u2026"
                else "Connecting\u2026"
            }
            else -> "Loading\u2026"
        }
        val detailText = if (totalCount > 0 && (failedCount > 0 || connectingCount > 0)) {
            buildString {
                if (connectingCount > 0) append("$connectingCount connecting")
                if (failedCount > 0) {
                    if (isNotEmpty()) append(" \u00b7 ")
                    append("$failedCount failed")
                }
            }
        } else ""
        Box(
            modifier = Modifier.height(36.dp).fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.animation.Crossfade(
                    targetState = statusText,
                    animationSpec = tween(300),
                    label = "status_crossfade"
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        letterSpacing = 0.3.sp,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                androidx.compose.animation.Crossfade(
                    targetState = detailText,
                    animationSpec = tween(300),
                    label = "detail_crossfade"
                ) { text ->
                    if (text.isNotEmpty()) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (failedCount > 0) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Extracted overlay: loading indicator + onboarding prompt.
 * Reduces the PullToRefreshBox content lambda JIT from 9.2MB by splitting ~265 lines
 * into a separate compilation unit.
 */
@Composable
private fun FeedOverlay(
    feedIsEmpty: Boolean,
    feedTimedOut: Boolean,
    hasOutboxRelays: Boolean,
    hasAnyConfiguredRelays: Boolean,
    onboardingComplete: Boolean,
    feedSession: FeedSessionState,
    feedCacheChecked: Boolean,
    perRelayState: Map<String, social.mycelium.android.relay.RelayEndpointStatus>,
    onNavigateTo: (String) -> Unit,
) {
    // If any relay is connected or still connecting, never show the "Connect to Relays" fallback.
    // That screen is ONLY for genuinely unconfigured accounts with zero relays.
    val anyRelayActive = perRelayState.values.any {
        it == social.mycelium.android.relay.RelayEndpointStatus.Connected ||
            it == social.mycelium.android.relay.RelayEndpointStatus.Connecting
    }
    val noRelaysAtAll = !hasOutboxRelays && !hasAnyConfiguredRelays && perRelayState.isEmpty()
    val showOnboarding = feedIsEmpty && noRelaysAtAll && onboardingComplete && feedSession != FeedSessionState.Loading
    // Never show loading overlay when feed is Live or Refreshing — notes may be briefly empty
    // during recomposition on resume but the subscription is active and will deliver notes.
    // Also suppress until feedCacheChecked — prevents flash on resume after process death
    // while the disk cache is still being loaded asynchronously.
    val showLoading = feedCacheChecked && feedIsEmpty && !showOnboarding &&
        feedSession != FeedSessionState.Live && feedSession != FeedSessionState.Refreshing

    val overlayAlpha by animateFloatAsState(
        targetValue = if (showLoading || showOnboarding) 1f else 0f,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "overlay_alpha"
    )

    if (overlayAlpha > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = overlayAlpha }
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            if (showOnboarding) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Icon(
                        Icons.Outlined.Explore,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Connect to Relays",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Discover relays to start receiving notes from the Nostr network.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { onNavigateTo("relay_discovery") },
                        modifier = Modifier.fillMaxWidth(0.7f)
                    ) {
                        Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Discover Relays")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { onNavigateTo("settings/relay_health") }
                    ) {
                        Text(
                            "Set up manually",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (showLoading) {
                RelayConnectionLoadingIndicator(
                    perRelayState = perRelayState,
                    feedSession = feedSession,
                    onNavigateTo = onNavigateTo,
                )
            }
        }
    }
}

/**
 * Extracted feed content: PullToRefreshBox + prefetch + LazyColumn + overlay.
 * Splits the DashboardScreen 10MB JIT by moving ~200 lines of feed rendering
 * into a separate compilation unit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardFeedContent(
    paddingValues: PaddingValues,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    listState: LazyListState,
    engagementFilteredNotes: List<Note>,
    sortedNotes: List<Note>,
    homeFeedState: FeedState,
    uiState: social.mycelium.android.viewmodel.DashboardUiState,
    countsByNoteId: Map<String, social.mycelium.android.repository.NoteCounts>,
    replyCountByNoteId: Map<String, Int>,
    zapInProgressNoteIds: Set<String>,
    zappedNoteIds: Set<String>,
    zappedAmountByNoteId: Map<String, Long>,
    boostedNoteIds: Set<String>,
    accountNpub: String?,
    shouldCloseZapMenus: Boolean,
    feedTimedOut: Boolean,
    hasOutboxRelays: Boolean,
    hasAnyConfiguredRelays: Boolean,
    onboardingComplete: Boolean,
    feedSession: FeedSessionState,
    feedCacheChecked: Boolean,
    perRelayState: Map<String, social.mycelium.android.relay.RelayEndpointStatus>,
    failedUserRelayCount: Int,
    viewModel: DashboardViewModel,
    accountStateViewModel: social.mycelium.android.viewmodel.AccountStateViewModel,
    onThreadClick: (Note, List<String>?) -> Unit,
    onProfileClick: (String) -> Unit,
    onImageTap: (Note, List<String>, Int) -> Unit,
    onOpenImageViewer: (List<String>, Int) -> Unit,
    onVideoClick: (List<String>, Int) -> Unit,
    onRelayClick: (String) -> Unit,
    onNavigateToRelayList: ((List<String>) -> Unit)?,
    onNavigateTo: (String) -> Unit,
    mediaPageForNote: (String) -> Int,
    onMediaPageChanged: (String, Int) -> Unit,
    onShowZapConfig: () -> Unit,
    onSeeAllReactions: (Note) -> Unit,
    isDashboardVisible: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        // Pre-compute lowercase follow set for O(1) isAuthorFollowed checks
        val followSetLower = remember(uiState.followList) { uiState.followList.map { it.lowercase() }.toSet() }
        // Track which note keys are currently visible so off-screen videos can pause
        val visibleKeys by remember {
            derivedStateOf {
                listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet()
            }
        }
        // Prefetch images so they're warm before the user sees them
        val imageLoader = remember { coil.Coil.imageLoader(context) }
        // Also cache aspect ratios so media containers have correct size before scrolling into view
        val prefetchListener = remember {
            object : coil.request.ImageRequest.Listener {
                override fun onSuccess(request: coil.request.ImageRequest, result: coil.request.SuccessResult) {
                    val url = request.data as? String ?: return
                    val w = result.drawable.intrinsicWidth
                    val h = result.drawable.intrinsicHeight
                    if (w > 0 && h > 0) {
                        social.mycelium.android.utils.MediaAspectRatioCache.add(url, w, h)
                    }
                }
            }
        }
        LaunchedEffect(engagementFilteredNotes) {
            val prefetchCount = 8.coerceAtMost(engagementFilteredNotes.size)
            for (i in 0 until prefetchCount) {
                engagementFilteredNotes[i].mediaUrls.forEach { url ->
                    if (!social.mycelium.android.utils.UrlDetector.isVideoUrl(url)) {
                        imageLoader.enqueue(
                            coil.request.ImageRequest.Builder(context)
                                .data(url)
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                .size(1080, 1080)
                                .listener(prefetchListener)
                                .build()
                        )
                    }
                }
            }
        }
        // Continue prefetching 5 notes ahead as user scrolls
        LaunchedEffect(listState) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            }.collect { lastVisible ->
                val prefetchEnd = (lastVisible + 5).coerceAtMost(engagementFilteredNotes.size - 1)
                for (i in (lastVisible + 1)..prefetchEnd) {
                    val note = engagementFilteredNotes.getOrNull(i) ?: continue
                    note.mediaUrls.forEach { url ->
                        if (!social.mycelium.android.utils.UrlDetector.isVideoUrl(url)) {
                            imageLoader.enqueue(
                                coil.request.ImageRequest.Builder(context)
                                    .data(url)
                                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                    .size(1080, 1080)
                                    .listener(prefetchListener)
                                    .build()
                            )
                        }
                    }
                }
            }
        }
        // Report visible range to ViewModel for viewport-aware URL preview prefetch
        LaunchedEffect(listState) {
            snapshotFlow {
                val info = listState.layoutInfo
                val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                first to last
            }.collect { (first, last) ->
                viewModel.updateVisibleRange(first, last)
            }
        }
        // Infinite scroll: detect end-of-list and load older notes.
        // Key on isLoadingOlder so the effect restarts when a load finishes — if the
        // user is still at the bottom, the next page triggers immediately.
        val notesRepo = remember { social.mycelium.android.repository.NotesRepository.getInstance() }
        val isLoadingOlder by notesRepo.isLoadingOlder.collectAsState()
        val paginationExhausted by notesRepo.paginationExhausted.collectAsState()
        LaunchedEffect(listState, isLoadingOlder) {
            snapshotFlow {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible to totalItems
            }.collect { (lastVisible, totalItems) ->
                // Pre-fetch aggressively: trigger 200 items before the bottom so
                // the user effectively never sees the end of the feed. Events are
                // cheap JSON; rendering is the bottleneck, not data.
                if (totalItems > 200 && lastVisible >= totalItems - 200 && !isLoadingOlder && !paginationExhausted) {
                    notesRepo.loadOlderNotes()
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp)
        ) {
            // New notes counter (tap to load)
            run {
                val isFollowing = homeFeedState.isFollowing
                val newCount = if (isFollowing) uiState.newNotesCountFollowing else uiState.newNotesCountAll
                if (newCount > 0) {
                    item(key = "new_notes_counter") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        viewModel.applyPendingNotes()
                                        listState.scrollToItem(0)
                                    }
                                },
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "\u2191 $newCount new note${if (newCount == 1) "" else "s"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            items(
                items = engagementFilteredNotes,
                key = { it.id },
                contentType = { "note_card" }
            ) { note ->
                val counts = countsByNoteId[note.originalNoteId ?: note.id] ?: countsByNoteId[note.id]
                NoteCard(
                    note = note,
                    onLike = { noteId -> viewModel.toggleLike(noteId) },
                    onShare = { noteId -> /* Handle share */ },
                    onComment = { noteId -> onThreadClick(note, null) },
                    onReact = { reactedNote, emoji ->
                        val error = accountStateViewModel.sendReaction(reactedNote, emoji)
                        if (error != null) {
                            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onBoost = { n ->
                        val err = accountStateViewModel.publishRepost(n.id, n.author.id, originalNote = n)
                        if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    },
                    onQuote = { n ->
                        val nevent = com.example.cybin.nip19.encodeNevent(n.id, authorHex = n.author.id)
                        val encoded = android.net.Uri.encode(android.net.Uri.encode("\nnostr:$nevent\n"))
                        onNavigateTo("compose?initialContent=$encoded")
                    },
                    onFork = { n ->
                        val encoded = android.net.Uri.encode(android.net.Uri.encode(n.content))
                        onNavigateTo("compose?initialContent=$encoded")
                    },
                    onProfileClick = onProfileClick,
                    onNoteClick = { n -> onThreadClick(n, null) },
                    onImageTap = onImageTap,
                    onOpenImageViewer = onOpenImageViewer,
                    onVideoClick = onVideoClick,
                    onZap = { _, amount ->
                        val err = accountStateViewModel.sendZap(note, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                        if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    },
                    onCustomZapSend = { n, amount, zapType, msg ->
                        val err = accountStateViewModel.sendZap(n, amount, zapType, msg)
                        if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    },
                    onZapSettings = onShowZapConfig,
                    shouldCloseZapMenus = shouldCloseZapMenus,
                    onRelayClick = onRelayClick,
                    onNavigateToRelayList = onNavigateToRelayList,
                    isAuthorFollowed = note.author.id.lowercase() in followSetLower,
                    onFollowAuthor = { pubkey ->
                        val err = accountStateViewModel.followUser(pubkey)
                        if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    },
                    onUnfollowAuthor = { pubkey ->
                        val err = accountStateViewModel.unfollowUser(pubkey)
                        if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    },
                    onBlockAuthor = { pubkey ->
                        social.mycelium.android.repository.MuteListRepository.blockUser(pubkey)
                        Toast.makeText(context, "Blocked", Toast.LENGTH_SHORT).show()
                    },
                    onMuteAuthor = { pubkey ->
                        val signer = accountStateViewModel.getCurrentSigner()
                        if (signer != null) {
                            val relays = accountStateViewModel.getOutboxRelayUrlSet()
                            social.mycelium.android.repository.MuteListRepository.muteUser(pubkey, signer, relays)
                            Toast.makeText(context, "Muted", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Sign in to mute", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onBookmarkToggle = { noteId, isCurrentlyBookmarked ->
                        val signer = accountStateViewModel.getCurrentSigner()
                        if (signer != null) {
                            val relays = accountStateViewModel.getOutboxRelayUrlSet()
                            if (isCurrentlyBookmarked) {
                                social.mycelium.android.repository.BookmarkRepository.removeBookmark(noteId, signer, relays)
                                Toast.makeText(context, "Bookmark removed", Toast.LENGTH_SHORT).show()
                            } else {
                                social.mycelium.android.repository.BookmarkRepository.addBookmark(noteId, signer, relays)
                                Toast.makeText(context, "Bookmarked", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Sign in to bookmark", Toast.LENGTH_SHORT).show()
                        }
                    },
                    accountNpub = accountNpub,
                    isZapInProgress = note.id in zapInProgressNoteIds,
                    isZapped = note.id in zappedNoteIds || social.mycelium.android.repository.NoteCountsRepository.isOwnZap(note.id),
                    isBoosted = note.id in boostedNoteIds || social.mycelium.android.repository.NoteCountsRepository.isOwnBoost(note.id),
                    myZappedAmount = zappedAmountByNoteId[note.id],
                    overrideReplyCount = replyCountByNoteId[note.id] ?: counts?.replyCount,
                    overrideZapCount = counts?.zapCount,
                    overrideZapTotalSats = counts?.zapTotalSats,
                    overrideReactions = counts?.reactions,
                    overrideReactionAuthors = counts?.reactionAuthors,
                    overrideZapAuthors = counts?.zapAuthors,
                    overrideZapAmountByAuthor = counts?.zapAmountByAuthor,
                    overrideCustomEmojiUrls = counts?.customEmojiUrls,
                    countsByNoteId = countsByNoteId,
                    onSeeAllReactions = { onSeeAllReactions(note) },
                    showHashtagsSection = false,
                    initialMediaPage = mediaPageForNote(note.id),
                    onMediaPageChanged = { page -> onMediaPageChanged(note.id, page) },
                    isVisible = isDashboardVisible && note.id in visibleKeys,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ═══ Load older notes indicator ═══
            // Always show a bottom item when feed has content and pagination isn't exhausted.
            // This ensures the user always sees feedback: spinner when loading, placeholder when idle.
            if (!paginationExhausted && sortedNotes.isNotEmpty()) {
                item(key = "loading_older") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoadingOlder) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Loading older notes…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            // Idle placeholder — keeps scroll area so pre-fetch triggers
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }
        }

        // ═══ OVERLAY: Loading indicator / Onboarding prompt ═══
        FeedOverlay(
            feedIsEmpty = sortedNotes.isEmpty(),
            feedTimedOut = feedTimedOut,
            hasOutboxRelays = hasOutboxRelays,
            hasAnyConfiguredRelays = hasAnyConfiguredRelays,
            onboardingComplete = onboardingComplete,
            feedSession = feedSession,
            feedCacheChecked = feedCacheChecked,
            perRelayState = perRelayState,
            onNavigateTo = onNavigateTo,
        )

        // ═══ BANNER: Relay failure warning ═══
        AnimatedVisibility(
            visible = failedUserRelayCount > 0 && sortedNotes.isNotEmpty(),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
                .zIndex(3f)
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clickable { onNavigateTo("relay_connection_status") },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = if (failedUserRelayCount == 1) "1 relay unreachable"
                               else "$failedUserRelayCount relays unreachable",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "View",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ✅ PERFORMANCE: Consistent animation specs (Thread view pattern)
private val standardAnimation = tween<IntSize>(durationMillis = 200, easing = FastOutSlowInEasing)
private val fastAnimation = tween<IntSize>(durationMillis = 150, easing = FastOutSlowInEasing)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    isSearchMode: Boolean = false,
    onSearchModeChange: (Boolean) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNavigateTo: (String) -> Unit = {},
    onThreadClick: (Note, List<String>?) -> Unit = { _, _ -> },
    onImageTap: (social.mycelium.android.data.Note, List<String>, Int) -> Unit = { _, _, _ -> },
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    onScrollToTop: () -> Unit = {},
    /** Retrieve the shared media album page for a note (from AppViewModel). */
    mediaPageForNote: (String) -> Int = { 0 },
    /** Store the media album page when user swipes (to AppViewModel). */
    onMediaPageChanged: (String, Int) -> Unit = { _, _ -> },
    listState: LazyListState = rememberLazyListState(),
    viewModel: DashboardViewModel = viewModel(),
    feedStateViewModel: FeedStateViewModel = viewModel(),
    accountStateViewModel: social.mycelium.android.viewmodel.AccountStateViewModel = viewModel(),
    relayRepository: RelayRepository? = null,
    onLoginClick: (() -> Unit)? = null,
    onTopAppBarStateChange: (TopAppBarState) -> Unit = {},
    initialTopAppBarState: TopAppBarState? = null,
    isDashboardVisible: Boolean = true,
    onQrClick: () -> Unit = {},
    onSidebarRelayHealthClick: () -> Unit = {},
    onSidebarRelayDiscoveryClick: () -> Unit = {},
    onRelayClick: (String) -> Unit = {},
    /** When set, tapping relay orbs navigates to a dedicated relay list screen. */
    onNavigateToRelayList: ((List<String>) -> Unit)? = null,
    /** Called when user taps "See all" in the reaction details panel; receives noteId. */
    onSeeAllReactions: (Note) -> Unit = {},
    /** Note IDs hidden from feed via "Clear Read" (from AppViewModel). */
    hiddenNoteIds: Set<String> = emptySet(),
    /** Callback to clear read notes (moves viewed IDs into hidden set). */
    onClearRead: () -> Unit = {},
    /** Whether there are read notes available to clear. */
    hasReadNotes: Boolean = false,
    /** Navigate to the full-page zap settings screen. */
    onNavigateToZapSettings: () -> Unit = {},
    onDrawerStateChanged: (Boolean) -> Unit = {},
    drawerState: DrawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val homeFeedState by feedStateViewModel.homeFeedState.collectAsState()
    val authState by accountStateViewModel.authState.collectAsState()
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
    val accountsRestored by accountStateViewModel.accountsRestored.collectAsState()
    val onboardingComplete by accountStateViewModel.onboardingComplete.collectAsState()
    val zapInProgressNoteIds by accountStateViewModel.zapInProgressNoteIds.collectAsState()
    val zappedNoteIds by accountStateViewModel.zappedNoteIds.collectAsState()
    val zappedAmountByNoteId by accountStateViewModel.zappedAmountByNoteId.collectAsState()
    val boostedNoteIds by accountStateViewModel.boostedNoteIds.collectAsState()
    val replyCountByNoteId by social.mycelium.android.repository.ReplyCountCache.replyCountByNoteId.collectAsState()
    val countsByNoteId by social.mycelium.android.repository.NoteCountsRepository.countsByNoteId.collectAsState()
    val scope = rememberCoroutineScope()

    // Report drawer state to parent (for hiding bottom nav when drawer is open)
    LaunchedEffect(drawerState.currentValue) {
        onDrawerStateChanged(drawerState.isOpen)
    }

    // NIP-53: true when a followed user is currently hosting a live activity
    val hasFollowedLive by LiveActivityRepository.getInstance().hasFollowedLiveActivity.collectAsState()

    // Feed session lifecycle: Idle → Loading → Live (drives loading indicator)
    val feedSession by viewModel.feedSessionState.collectAsState()
    // True after disk feed cache has been checked — suppresses loading overlay flash on process death resume
    val feedCacheChecked by viewModel.feedCacheChecked.collectAsState()

    // Real per-relay connection status from RelayConnectionStateMachine
    val perRelayState by social.mycelium.android.relay.RelayConnectionStateMachine.getInstance().perRelayState.collectAsState()
    val liveConnectionStatus = remember(perRelayState) {
        perRelayState.mapKeys { (url, _) -> normalizeRelayUrl(url) }
            .mapValues { (_, status) ->
                when (status) {
                    social.mycelium.android.relay.RelayEndpointStatus.Connected -> RelayConnectionStatus.CONNECTED
                    social.mycelium.android.relay.RelayEndpointStatus.Connecting -> RelayConnectionStatus.CONNECTING
                    social.mycelium.android.relay.RelayEndpointStatus.Failed -> RelayConnectionStatus.ERROR
                }
            }
    }

    // Relay management
    val storageManager = remember { RelayStorageManager(context) }
    val relayViewModel: RelayManagementViewModel? = relayRepository?.let {
        viewModel { RelayManagementViewModel(it, storageManager) }
    }
    val relayUiState = if (relayViewModel != null) {
        relayViewModel.uiState.collectAsState().value
    } else {
        social.mycelium.android.viewmodel.RelayManagementUiState()
    }

    // Sidebar relay count: only count user-configured relays (categories + outbox + inbox),
    // NOT indexer relays or other one-shot connections that appear in perRelayState.
    val userRelayUrls = remember(currentAccount, relayUiState) {
        val pubkey = currentAccount?.toHexKey() ?: return@remember emptySet<String>()
        val categoryUrls = relayUiState.relayCategories
            .flatMap { it.relays }.map { normalizeRelayUrl(it.url) }
        val outboxUrls = relayUiState.outboxRelays
            .map { normalizeRelayUrl(it.url) }
        val inboxUrls = relayUiState.inboxRelays
            .map { normalizeRelayUrl(it.url) }
        (categoryUrls + outboxUrls + inboxUrls).toSet()
    }
    val connectedRelayCount = remember(perRelayState, userRelayUrls) {
        perRelayState.count { (url, status) ->
            status == social.mycelium.android.relay.RelayEndpointStatus.Connected &&
                normalizeRelayUrl(url) in userRelayUrls
        }
    }
    val failedUserRelayCount = remember(perRelayState, userRelayUrls) {
        perRelayState.count { (url, status) ->
            status == social.mycelium.android.relay.RelayEndpointStatus.Failed &&
                normalizeRelayUrl(url) in userRelayUrls
        }
    }
    val subscribedRelayCount = userRelayUrls.size

    // (Indexer relay details removed from sidebar — icon-only access via onIndexerClick)

    // Load user relays when account changes
    LaunchedEffect(currentAccount) {
        currentAccount?.toHexKey()?.let { pubkey ->
            relayViewModel?.loadUserRelays(pubkey)
        }
    }

    // Get active profile for sidebar + feed logic
    val activeProfile = relayUiState.relayProfiles.firstOrNull { it.isActive }
    val relayCategories = activeProfile?.categories ?: emptyList()

    // Initialize sidebar sections as expanded on first load
    LaunchedEffect(relayCategories) {
        if (relayCategories.isNotEmpty()) {
            feedStateViewModel.initializeExpandedCategories(relayCategories.map { it.id })
        }
    }

    // Synchronous check: does the user have ANY saved relay config?
    // Resolves instantly from local storage before the async ViewModel loads.
    val hasSavedRelayConfig = remember(currentAccount) {
        currentAccount?.toHexKey()?.let { pubkey ->
            storageManager.loadCategories(pubkey).flatMap { it.relays }.isNotEmpty()
        } ?: false
    }

    // Track if we've already loaded relays on this mount — reset on account change
    var hasLoadedRelays by remember { mutableStateOf(false) }
    LaunchedEffect(currentAccount) { hasLoadedRelays = false }

    // Onboarding: detect when feed has been empty for too long (new account / no relays)
    var feedTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(isDashboardVisible, uiState.notes.isEmpty(), hasLoadedRelays) {
        feedTimedOut = false
        if (isDashboardVisible && uiState.notes.isEmpty()) {
            kotlinx.coroutines.delay(10_000L)
            if (uiState.notes.isEmpty()) feedTimedOut = true
        }
    }
    // Reset timeout when notes arrive
    LaunchedEffect(uiState.notes.size) {
        if (uiState.notes.isNotEmpty()) feedTimedOut = false
    }
    val hasOutboxRelays = relayUiState.outboxRelays.isNotEmpty()
    // Cache flattened relay URLs — avoids repeated flatMap allocations on every recomposition
    val allCategoryRelayUrls = remember(relayCategories) {
        relayCategories.flatMap { it.relays }.map { it.url }.distinct()
    }
    val hasAnyConfiguredRelays = allCategoryRelayUrls.isNotEmpty() || hasSavedRelayConfig

    // If the selected relay/category was removed, fall back to Global
    LaunchedEffect(relayCategories, homeFeedState) {
        val selectedUrl = homeFeedState.selectedRelayUrl
        val selectedCatId = homeFeedState.selectedCategoryId
        if (selectedUrl != null && selectedUrl !in allCategoryRelayUrls) {
            feedStateViewModel.setHomeGlobal()
            feedStateViewModel.setTopicsGlobal()
            if (allCategoryRelayUrls.isNotEmpty()) viewModel.setDisplayFilterOnly(allCategoryRelayUrls)
        } else if (selectedCatId != null && relayCategories.none { it.id == selectedCatId }) {
            feedStateViewModel.setHomeGlobal()
            feedStateViewModel.setTopicsGlobal()
            if (allCategoryRelayUrls.isNotEmpty()) viewModel.setDisplayFilterOnly(allCategoryRelayUrls)
        }
    }

    // When dashboard is visible, apply feed subscription. Key by selection only so expand/collapse does not reload.
    // When categories have no relays (e.g. fresh install or default category empty), fall back to cache + outbox so feed still loads.
    // Subscription setup: only re-run when visibility, account, or relay config changes.
    // Sidebar relay/category selection is handled by setDisplayFilterOnly in onItemClick — NOT here.
    // Key on allCategoryRelayUrls (stable URL list) instead of relayCategories (object list that
    // changes on every NIP-11 info update, causing cascading re-fires that delay feed loading).
    LaunchedEffect(
        isDashboardVisible,
        currentAccount,
        allCategoryRelayUrls,
        relayUiState.outboxRelays,
        homeFeedState.isGlobal,
        onboardingComplete
    ) {
        if (!onboardingComplete) return@LaunchedEffect
        if (!isDashboardVisible || (allCategoryRelayUrls.isEmpty() && relayUiState.outboxRelays.isEmpty())) return@LaunchedEffect
        // Debounce: keys settle in rapid succession (visibility, categories, outbox);
        // wait briefly so we only fire the subscription once.
        kotlinx.coroutines.delay(150)
        val pubkey = currentAccount?.toHexKey()
        // Merge category relays + outbox relays so adding a relay to a category
        // doesn't drop notes from outbox relays that the user was already reading.
        // Indexer relays are NOT included — they are only for NIP-65 lookups.
        val outboxUrls = if (pubkey != null) relayUiState.outboxRelays.map { it.url } else emptyList()
        val relayUrlsToUse = (allCategoryRelayUrls + outboxUrls).map { normalizeRelayUrl(it) }.distinct()
        val displayUrls = when {
            homeFeedState.isGlobal -> relayUrlsToUse
            homeFeedState.selectedCategoryId != null -> relayCategories
                .firstOrNull { it.id == homeFeedState.selectedCategoryId }?.relays?.map { it.url }
                ?: relayUrlsToUse
            homeFeedState.selectedRelayUrl != null -> listOf(homeFeedState.selectedRelayUrl!!)
            else -> relayUrlsToUse
        }
        if (relayUrlsToUse.isNotEmpty()) {
            hasLoadedRelays = true
            // Always run subscription path so connections resume after app close (notes may be from cache).
            // When notes are already present, ensureSubscriptionToNotes only re-applies subscription and does not clear the feed.
            viewModel.loadNotesFromFavoriteCategory(relayUrlsToUse, displayUrls)
            social.mycelium.android.repository.QuotedNoteCache.setRelayUrls(relayUrlsToUse)
            currentAccount?.toHexKey()?.let { pk ->
                social.mycelium.android.repository.QuotedNoteCache.setIndexerRelayUrls(
                    storageManager.loadIndexerRelays(pk).map { it.url }
                )
            }
        }
    }

    // When feed is visible, sync profile cache into notes so names/avatars render (e.g. after debug Fetch all or returning from profile)
    LaunchedEffect(isDashboardVisible) {
        if (isDashboardVisible) {
            kotlinx.coroutines.delay(400)
            viewModel.syncFeedAuthorsFromCache()
        }
    }

    // Fetch user's NIP-65 relay list when account changes
    LaunchedEffect(currentAccount) {
        currentAccount?.toHexKey()?.let { pubkey ->
            relayViewModel?.fetchUserRelaysFromNetwork(pubkey)
        }
    }

    // Set cache relay URLs, request kind-0 for current user, load follow list, and fetch NIP-65 relay list
    LaunchedEffect(currentAccount, relayUiState.outboxRelays) {
        currentAccount?.toHexKey()?.let { pubkey ->
            val cacheUrls = storageManager.loadIndexerRelays(pubkey).map { it.url }
            val outboxUrls = relayUiState.outboxRelays.map { it.url }
            val followRelayUrls = (cacheUrls + outboxUrls).distinct()
            if (followRelayUrls.isNotEmpty()) {
                viewModel.setCacheRelayUrls(cacheUrls)
                social.mycelium.android.repository.ProfileMetadataCache.getInstance()
                    .requestProfiles(listOf(pubkey), cacheUrls)
                viewModel.loadFollowList(pubkey, followRelayUrls)
                // NIP-65: fetch kind-10002 relay list for outbox model (counts use indexer relays)
                social.mycelium.android.repository.Nip65RelayListRepository.fetchRelayList(pubkey, cacheUrls)
                // NIP-66 is initialized globally in MainActivity — no per-account trigger needed
            }
        }
    }

    // Apply follow filter when Following/Global or follow list changes.
    // Always call setFollowFilter — the repository handles the empty-list case safely
    // (drops all notes + uses lastAppliedKind1Filter for subscription). Skipping the call
    // when followList is empty was causing the filter to never be applied on cold start,
    // allowing global notes to bleed into the Following feed.
    LaunchedEffect(homeFeedState.isFollowing, uiState.followList) {
        viewModel.setFollowFilter(homeFeedState.isFollowing)
    }

    // Pending notes build up in the background. User pulls down to refresh to see them.
    // The "X new notes" banner at the top of the feed shows the count.
    // Do NOT auto-apply; let users decide when to see new notes (swipe-to-refresh or tap banner).

    // Search state - using simple String instead of TextFieldValue
    var searchQuery by remember { mutableStateOf("") }

    // Pull-to-refresh state
    var isRefreshing by remember { mutableStateOf(false) }

    // Feed view state
    var currentFeedView by remember { mutableStateOf("Home") }

    // Account switcher state
    var showAccountSwitcher by remember { mutableStateOf(false) }

    // Zap menu state - shared across all note cards
    var shouldCloseZapMenus by remember { mutableStateOf(false) }

    // Zap configuration dialog state
    var showZapConfigDialog by remember { mutableStateOf(false) }
    var showWalletConnectDialog by remember { mutableStateOf(false) }
    // Relay orb tap navigates to relay log page via onRelayClick callback

    // Restore home feed scroll position when returning to dashboard (one-shot; do not re-run on notes.size).
    // Skip restoration when coming fresh from onboarding (hasLoadedRelays is false) to avoid
    // landing in the middle of a stale cached feed.
    val scrollPos = homeFeedState.scrollPosition
    LaunchedEffect(isDashboardVisible, scrollPos.firstVisibleItem, scrollPos.scrollOffset) {
        if (isDashboardVisible && hasLoadedRelays && scrollPos.firstVisibleItem > 0 && uiState.notes.isNotEmpty()) {
            listState.scrollToItem(
                scrollPos.firstVisibleItem.coerceAtMost(uiState.notes.size - 1),
                scrollPos.scrollOffset
            )
            feedStateViewModel.updateHomeFeedState { copy(scrollPosition = ScrollPosition(0, 0)) }
        }
    }

    // On fresh mount (app restart), ensure feed starts at the top.
    // Guard: ViewModel-scoped flag survives LaunchedEffect lifecycle restarts (STOPPED→STARTED
    // during navigation to image_viewer/video_viewer). Without this, the LaunchedEffect re-fires
    // on every pop-back and scrolls the feed to the top.
    // Debounce: wait for the note count to stabilize (300ms with no new notes) before scrolling,
    // so the feed settles before we snap to top — prevents landing mid-feed after relay events stream in.
    LaunchedEffect(Unit) {
        if (!feedStateViewModel.hasInitializedHomeScroll) {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            snapshotFlow { uiState.notes.size }
                .filter { it > 0 }
                .debounce(300)
                .first()
            listState.scrollToItem(0)
            feedStateViewModel.hasInitializedHomeScroll = true
        }
    }

    // Notes are always at index 0 in the LazyColumn (loading indicator is an overlay).
    // No scroll-to-top hack needed — the list starts at the top naturally.

    // Close zap menus when feed scroll starts (not during scroll)
    var wasScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !wasScrolling) {
            // Scroll just started - close zap menus immediately
            shouldCloseZapMenus = true
            kotlinx.coroutines.delay(100)
            shouldCloseZapMenus = false
        }
        wasScrolling = listState.isScrollInProgress
    }

    // Use Material3's built-in scroll behavior for top app bar (shared with nav so back gesture doesn't flash)
    val topAppBarState = initialTopAppBarState ?: rememberTopAppBarState()
    val scrollBehavior = if (isSearchMode) {
        TopAppBarDefaults.pinnedScrollBehavior(topAppBarState)
    } else {
        TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)
    }

    // Notify parent of TopAppBarState changes for thread view inheritance
    LaunchedEffect(topAppBarState) {
        onTopAppBarStateChange(topAppBarState)
    }

    // When new notes arrive, expand the top app bar so the counter is visible
    // without requiring the user to pull down. Only triggers on 0→>0 transition.
    val newNotesCount = if (homeFeedState.isFollowing) uiState.newNotesCountFollowing else uiState.newNotesCountAll
    LaunchedEffect(newNotesCount) {
        if (newNotesCount > 0 && topAppBarState.heightOffset < 0f) {
            topAppBarState.heightOffset = 0f
        }
    }

    // Engagement filter: null = all, "replies" / "likes" / "zaps" — persisted in FeedStateViewModel
    val engagementFilter = homeFeedState.engagementFilter

    // Scroll-to-top trigger: incremented when filter/sort changes; LaunchedEffect
    // waits one frame so the new list recomposes before scrolling (fixes race condition).
    var scrollToTopTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            // Yield to let derivedStateOf / recomposition settle before scrolling
            kotlinx.coroutines.yield()
            listState.scrollToItem(0)
        }
    }

    // Merge enrichment side channel (url previews) into notes — only copy notes that actually have new previews
    val notesWithPreviews by remember(uiState.notes, uiState.urlPreviewsByNoteId) {
        derivedStateOf {
            val previews = uiState.urlPreviewsByNoteId
            if (previews.isEmpty()) {
                uiState.notes
            } else {
                uiState.notes.map { n ->
                    val newPreviews = previews[n.id]
                    if (newPreviews != null && newPreviews != n.urlPreviews) n.copy(urlPreviews = newPreviews) else n
                }
            }
        }
    }
    val notesList = notesWithPreviews
    // Home feed sort: Latest (by time) or Popular (cumulative engagement score)
    // distinctBy(id) prevents duplicate-key crashes in LazyColumn when the same note arrives from multiple relays
    // Popular score = total reactions + zap count + reply count (simple cumulative; later becomes WoT-extensible)
    val sortedNotes by remember(notesList, homeFeedState.homeSortOrder, countsByNoteId, replyCountByNoteId) {
        derivedStateOf {
            val deduped = notesList.distinctBy { it.id }
            when (homeFeedState.homeSortOrder) {
                HomeSortOrder.Latest -> deduped.sortedByDescending { it.timestamp }
                HomeSortOrder.Popular -> deduped.sortedWith(
                    compareByDescending<Note> { note ->
                        val counts = countsByNoteId[note.originalNoteId ?: note.id] ?: countsByNoteId[note.id]
                        val reactionCount = counts?.reactionAuthors?.values?.sumOf { it.size } ?: 0
                        val zapCount = counts?.zapAuthors?.size ?: 0
                        val replyCount = replyCountByNoteId[note.id] ?: 0
                        reactionCount + zapCount + replyCount
                    }.thenByDescending { it.timestamp }
                )
            }
        }
    }

    // Mute/block filtering: hide notes from muted or blocked authors
    val mutedPubkeys by social.mycelium.android.repository.MuteListRepository.mutedPubkeys.collectAsState()
    val blockedPubkeys by social.mycelium.android.repository.MuteListRepository.blockedPubkeys.collectAsState()
    val mutedWords by social.mycelium.android.repository.MuteListRepository.mutedWords.collectAsState()

    // Stage 1: mute/block/hidden filtering — only recomputes when the note list or mute lists change.
    // countsByNoteId is deliberately excluded so frequent count updates don't trigger full refilter.
    val baseFilteredNotes by remember(sortedNotes, hiddenNoteIds, mutedPubkeys, blockedPubkeys, mutedWords) {
        derivedStateOf {
            val hiddenAuthors = mutedPubkeys + blockedPubkeys
            sortedNotes.filter { note ->
                note.id !in hiddenNoteIds &&
                note.author.id.lowercase() !in hiddenAuthors &&
                (mutedWords.isEmpty() || mutedWords.none { word -> note.content.contains(word, ignoreCase = true) })
            }
        }
    }
    // Stage 2: engagement sort — only recomputes when filter type or counts change.
    // For the default chronological feed (engagementFilter == null/empty), this is a no-op pass-through.
    val engagementFilteredNotes by remember(baseFilteredNotes, engagementFilter, replyCountByNoteId, countsByNoteId) {
        derivedStateOf {
            when (engagementFilter) {
                "replies" -> baseFilteredNotes.sortedByDescending { replyCountByNoteId[it.id] ?: 0 }
                "likes" -> baseFilteredNotes.sortedByDescending {
                    countsByNoteId[it.id]?.reactionAuthors?.values?.sumOf { authors -> authors.size } ?: 0
                }
                "zaps" -> baseFilteredNotes.sortedByDescending { countsByNoteId[it.id]?.zapTotalSats ?: 0L }
                else -> baseFilteredNotes
            }
        }
    }

    // ✅ PERFORMANCE: Optimized search filtering (Thread view pattern)
    val searchResults by remember(searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                emptyList()
            } else {
                uiState.notes.filter { note ->
                    note.content.contains(searchQuery, ignoreCase = true) ||
                    note.author.displayName.contains(searchQuery, ignoreCase = true) ||
                    note.author.username.contains(searchQuery, ignoreCase = true) ||
                    note.hashtags.any { it.contains(searchQuery, ignoreCase = true) }
                }
            }
        }
    }

    // ✅ Performance: Cache divider color (don't recreate on every item)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)

    val uriHandler = LocalUriHandler.current

    // Relay health: trouble count for sidebar badge
    val flaggedRelays by social.mycelium.android.relay.RelayHealthTracker.flaggedRelays.collectAsState()
    val blockedRelays by social.mycelium.android.relay.RelayHealthTracker.blockedRelays.collectAsState()
    val troubleRelayCount = remember(flaggedRelays, blockedRelays) {
        (flaggedRelays + blockedRelays).distinct().size
    }

    GlobalSidebar(
        drawerState = drawerState,
        activeProfile = activeProfile,
        outboxRelays = relayUiState.outboxRelays,
        inboxRelays = relayUiState.inboxRelays,
        feedState = homeFeedState,
        selectedDisplayName = feedStateViewModel.getHomeDisplayName(),
        relayState = uiState.relayState,
        connectionStatus = liveConnectionStatus,
        connectedRelayCount = connectedRelayCount,
        subscribedRelayCount = subscribedRelayCount,
        onIndexerClick = { onNavigateTo("relays?tab=indexer") },
        onRelayHealthClick = { onSidebarRelayHealthClick() },
        onRelayDiscoveryClick = { onSidebarRelayDiscoveryClick() },
        troubleRelayCount = troubleRelayCount,
        gesturesEnabled = currentAccount != null,
        onToggleCategorySubscription = { categoryId ->
            relayViewModel?.toggleCategorySubscription(categoryId)
        },
        onItemClick = { itemId ->
            when {
                itemId == "global" -> {
                    feedStateViewModel.setHomeGlobal()
                    feedStateViewModel.setTopicsGlobal()
                    if (allCategoryRelayUrls.isNotEmpty()) viewModel.setDisplayFilterOnly(allCategoryRelayUrls)
                }
                itemId.startsWith("relay_category:") -> {
                    val categoryId = itemId.removePrefix("relay_category:")
                    val category = activeProfile?.categories?.firstOrNull { it.id == categoryId }
                    val relayUrls = category?.relays?.map { it.url } ?: emptyList()
                    if (relayUrls.isNotEmpty()) {
                        feedStateViewModel.setHomeSelectedCategory(categoryId, category?.name)
                        feedStateViewModel.setTopicsSelectedCategory(categoryId, category?.name)
                        viewModel.setDisplayFilterOnly(relayUrls)
                    }
                }
                itemId.startsWith("relay:") -> {
                    val relayUrl = itemId.removePrefix("relay:")
                    val relay = activeProfile?.categories?.flatMap { it.relays }?.firstOrNull { it.url == relayUrl }
                    feedStateViewModel.setHomeSelectedRelay(relayUrl, relay?.displayName)
                    feedStateViewModel.setTopicsSelectedRelay(relayUrl, relay?.displayName)
                    viewModel.setDisplayFilterOnly(listOf(relayUrl))
                }
                itemId == "user_profile" -> onNavigateTo("user_profile")
                itemId == "relays" -> onNavigateTo("relays")
                itemId == "login" -> onLoginClick?.invoke()
                itemId == "logout" -> onNavigateTo("settings")
                itemId == "settings" -> onNavigateTo("settings")
                else -> viewModel.onSidebarItemClick(itemId)
            }
        },
        onToggleCategory = { categoryId ->
            feedStateViewModel.toggleHomeExpandedCategory(categoryId)
        },
        modifier = modifier
    ) {
        // Hide header and bottom bar when not logged in
        val isLoggedIn = currentAccount != null

        // When not logged in, bypass Scaffold entirely to avoid bottom line artifact
        if (!isLoggedIn) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // GIF banner at the top — spans full width, natural height
                val gifContext = LocalContext.current
                val gifModel = remember {
                    coil.request.ImageRequest.Builder(gifContext)
                        .data(social.mycelium.android.R.drawable.huge_scroller)
                        .decoderFactory(coil.decode.GifDecoder.Factory())
                        .build()
                }
                coil.compose.AsyncImage(
                    model = gifModel,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )

                if (!accountsRestored) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    // Sign-in controls
                    var loginVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { loginVisible = true }

                    val loginAlpha by animateFloatAsState(
                        targetValue = if (loginVisible) 1f else 0f,
                        animationSpec = tween(500, easing = FastOutSlowInEasing),
                        label = "login_alpha"
                    )
                    val loginOffsetY by animateFloatAsState(
                        targetValue = if (loginVisible) 0f else 40f,
                        animationSpec = tween(500, easing = FastOutSlowInEasing),
                        label = "login_offset"
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 32.dp)
                            .graphicsLayer {
                                alpha = loginAlpha
                                translationY = loginOffsetY
                            },
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = social.mycelium.android.R.drawable.ic_mushroom_purple),
                            contentDescription = "Mycelium",
                            modifier = Modifier.size(80.dp),
                            tint = Color.Unspecified
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        if (onLoginClick != null) {
                            Button(
                                onClick = { onLoginClick.invoke() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Login with Amber")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(modifier = Modifier.weight(1f))
                            Text(
                                text = "  or  ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider(modifier = Modifier.weight(1f))
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val loginContext = LocalContext.current
                        TextButton(
                            onClick = {
                                loginContext.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/greenart7c3/Amber")))
                            }
                        ) {
                            Text("Download Amber")
                        }
                    }
                }
            }
            return@GlobalSidebar
        }

        Scaffold(
            modifier = if (!isSearchMode) {
                Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
            } else {
                Modifier
            },
            topBar = {
                if (isSearchMode) {
                    // Search mode - show docked SearchBar
                    ModernSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { /* Optional: Handle explicit search submission */ },
                        searchResults = searchResults,
                        onResultClick = { note ->
                            onThreadClick(note, null)
                            onSearchModeChange(false)
                            searchQuery = ""
                        },
                        active = isSearchMode,
                        onActiveChange = { active ->
                            if (!active) {
                                onSearchModeChange(false)
                                searchQuery = ""
                            }
                        },
                        onBackClick = {
                            searchQuery = ""
                            onSearchModeChange(false)
                        },
                        placeholder = { Text("Search notes, users, hashtags...") }
                    )
                } else {
                    // Normal mode - show scrollable header
                    AdaptiveHeader(
                        title = "mycelium",
                        isSearchMode = false,
                        searchQuery = androidx.compose.ui.text.input.TextFieldValue(""),
                        onSearchQueryChange = { },
                        onMenuClick = {
                            scope.launch {
                                if (drawerState.isClosed) {
                                    drawerState.open()
                                } else {
                                    drawerState.close()
                                }
                            }
                        },
                        onSearchClick = { onSearchModeChange(true) },
                        onFilterClick = { /* TODO: Handle filter/sort */ },
                        onMoreOptionClick = { option ->
                            when (option) {
                                "about" -> onNavigateTo("about")
                                "settings" -> onNavigateTo("settings")
                                else -> viewModel.onMoreOptionClick(option)
                            }
                        },
                        onBackClick = { },
                        onClearSearch = { },
                        onLoginClick = onLoginClick,
                        onProfileClick = {
                            // Navigate to user's own profile
                            onNavigateTo("user_profile")
                        },
                        onAccountsClick = {
                            // Show account switcher
                            showAccountSwitcher = true
                        },
                        onSettingsClick = {
                            // Navigate to settings
                            onNavigateTo("settings")
                        },
                        onRelaysClick = {
                            onNavigateTo("relays")
                        },
                        isGuest = authState.isGuest,
                        userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
                        userAvatarUrl = authState.userProfile?.picture,
                        scrollBehavior = scrollBehavior,
                        currentFeedView = currentFeedView,
                        onFeedViewChange = { newFeedView -> currentFeedView = newFeedView },
                        isFollowingFilter = homeFeedState.isFollowing,
                        onFollowingFilterChange = { enabled ->
                            feedStateViewModel.setHomeFollowingFilter(enabled)
                            scrollToTopTrigger++
                        },
                        onEditFeedClick = { /* TODO: custom feed filter views */ },
                        homeSortOrder = homeFeedState.homeSortOrder,
                        onHomeSortOrderChange = {
                            feedStateViewModel.setHomeSortOrder(it)
                            scrollToTopTrigger++
                        },
                        activeEngagementFilter = engagementFilter,
                        onEngagementFilterChange = { newFilter ->
                            feedStateViewModel.setHomeEngagementFilter(newFilter)
                            scrollToTopTrigger++
                        },
                        onNavigateToTopics = { onNavigateTo("topics") },
                        onNavigateToLive = { onNavigateTo("live_explorer") },
                        hasFollowedLiveActivity = hasFollowedLive
                    )
                }
            },
            floatingActionButton = {
                val fabVisible by remember(topAppBarState) {
                    derivedStateOf { topAppBarState.collapsedFraction < 0.5f }
                }
                AnimatedVisibility(
                    visible = !isSearchMode && fabVisible,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    val draftsList by social.mycelium.android.repository.DraftsRepository.drafts.collectAsState()
                    val kind1RootDrafts = remember(draftsList) { draftsList.filter { it.type == social.mycelium.android.data.DraftType.NOTE } }
                    social.mycelium.android.ui.components.HomeFab(
                        onScrollToTop = {
                            scope.launch { listState.scrollToItem(0) }
                        },
                        onCompose = { onNavigateTo("compose") },
                        onDrafts = { onNavigateTo("drafts") },
                        draftCount = kind1RootDrafts.size,
                        modifier = Modifier.padding(bottom = 80.dp)
                    )
                }
            }
        ) { paddingValues ->
            DashboardFeedContent(
                paddingValues = paddingValues,
                isRefreshing = isRefreshing,
                onRefresh = {
                    scope.launch {
                        isRefreshing = true
                        kotlinx.coroutines.delay(300)
                        viewModel.applyPendingNotes()
                        // Force-refresh follow list so unfollows from other apps take effect
                        currentAccount?.toHexKey()?.let { pubkey ->
                            val cacheUrls = storageManager.loadIndexerRelays(pubkey).map { it.url }
                            val outboxUrls = relayUiState.outboxRelays.map { it.url }
                            val followRelayUrls = (cacheUrls + outboxUrls).distinct()
                            if (followRelayUrls.isNotEmpty()) {
                                viewModel.loadFollowList(pubkey, followRelayUrls, forceRefresh = true)
                            }
                        }
                        social.mycelium.android.relay.RelayConnectionStateMachine.getInstance().requestRetry()
                        isRefreshing = false
                    }
                },
                listState = listState,
                engagementFilteredNotes = engagementFilteredNotes,
                sortedNotes = sortedNotes,
                homeFeedState = homeFeedState,
                uiState = uiState,
                countsByNoteId = countsByNoteId,
                replyCountByNoteId = replyCountByNoteId,
                zapInProgressNoteIds = zapInProgressNoteIds,
                zappedNoteIds = zappedNoteIds,
                zappedAmountByNoteId = zappedAmountByNoteId,
                boostedNoteIds = boostedNoteIds,
                accountNpub = currentAccount?.npub,
                shouldCloseZapMenus = shouldCloseZapMenus,
                feedTimedOut = feedTimedOut,
                hasOutboxRelays = hasOutboxRelays,
                hasAnyConfiguredRelays = hasAnyConfiguredRelays,
                onboardingComplete = onboardingComplete,
                feedSession = feedSession,
                feedCacheChecked = feedCacheChecked,
                perRelayState = perRelayState,
                failedUserRelayCount = failedUserRelayCount,
                viewModel = viewModel,
                accountStateViewModel = accountStateViewModel,
                onThreadClick = onThreadClick,
                onProfileClick = onProfileClick,
                onImageTap = onImageTap,
                onOpenImageViewer = onOpenImageViewer,
                onVideoClick = onVideoClick,
                onRelayClick = onRelayClick,
                onNavigateToRelayList = onNavigateToRelayList,
                onNavigateTo = onNavigateTo,
                mediaPageForNote = { noteId -> mediaPageForNote(noteId) },
                onMediaPageChanged = { noteId, page -> onMediaPageChanged(noteId, page) },
                onShowZapConfig = { onNavigateToZapSettings() },
                onSeeAllReactions = onSeeAllReactions,
                isDashboardVisible = isDashboardVisible,
            )
        }
    }

    // Account switcher bottom sheet
    if (showAccountSwitcher) {
        social.mycelium.android.ui.components.AccountSwitchBottomSheet(
            accountStateViewModel = accountStateViewModel,
            onDismiss = { showAccountSwitcher = false },
            onAddAccount = {
                showAccountSwitcher = false
                onLoginClick?.invoke()
            }
        )
    }

    // Zap configuration: now navigates to zap_settings page via onNavigateToZapSettings

    // Wallet Connect dialog
    if (showWalletConnectDialog) {
        social.mycelium.android.ui.components.WalletConnectDialog(
            onDismiss = { showWalletConnectDialog = false }
        )
    }

}



@Preview(showBackground = true)
@Composable
fun DashboardScreenPreview() {
    MaterialTheme {
        DashboardScreen()
    }
}
