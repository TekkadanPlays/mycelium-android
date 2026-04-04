@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package social.mycelium.android.ui.screens

import social.mycelium.android.ui.components.note.NoteCardCallbacks
import social.mycelium.android.ui.components.note.NoteCardOverrides
import social.mycelium.android.ui.components.note.NoteCardConfig
import social.mycelium.android.ui.components.note.NoteCardInteractionState
import kotlinx.coroutines.flow.debounce
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
import androidx.compose.material.icons.outlined.Close
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
import social.mycelium.android.ui.components.nav.AdaptiveHeader
import social.mycelium.android.ui.components.nav.BottomNavigationBar
import social.mycelium.android.ui.components.nav.SmartBottomNavigationBar
import social.mycelium.android.ui.components.nav.ScrollAwareBottomNavigationBar
import social.mycelium.android.ui.components.nav.BottomNavDestinations
import social.mycelium.android.ui.components.common.ModernSearchBar
import social.mycelium.android.ui.components.nav.GlobalSidebar
import social.mycelium.android.ui.components.note.NoteCard
import social.mycelium.android.ui.components.common.LoadingAnimation
import social.mycelium.android.ui.components.note.NoteCard
import social.mycelium.android.viewmodel.DashboardViewModel
import social.mycelium.android.viewmodel.AuthViewModel
import social.mycelium.android.viewmodel.RelayManagementViewModel
import social.mycelium.android.viewmodel.FeedStateViewModel
import social.mycelium.android.viewmodel.HomeSortOrder
import social.mycelium.android.viewmodel.ScrollPosition
import social.mycelium.android.data.RelayConnectionStatus
import social.mycelium.android.relay.RelayState
import social.mycelium.android.repository.relay.RelayRepository
import social.mycelium.android.repository.relay.RelayStorageManager
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import social.mycelium.android.repository.LiveActivityRepository
import social.mycelium.android.repository.feed.FeedSessionState
import social.mycelium.android.utils.normalizeRelayUrl
import social.mycelium.android.viewmodel.FeedState
import social.mycelium.android.ui.performance.animatedYOffset
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

import social.mycelium.android.repository.social.NoteCounts
import social.mycelium.android.repository.sync.ZapType
// ✅ PERFORMANCE: Cached date formatter (Thread view pattern)
private val dateFormatter by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }



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
    countsByNoteId: Map<String, social.mycelium.android.repository.social.NoteCounts>,
    replyCountByNoteId: Map<String, Int>,
    zapInProgressNoteIds: Set<String>,
    zappedNoteIds: Set<String>,
    zappedAmountByNoteId: Map<String, Long>,
    boostedNoteIds: Set<String>,
    accountNpub: String?,
    shouldCloseZapMenus: Boolean,

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
        val screenWidthPx = remember { context.resources.displayMetrics.widthPixels }
        // Dedup set so we don't re-enqueue already-prefetched URLs
        val prefetchedUrls = remember { mutableSetOf<String>() }
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
        // Gate: only prefetch images once the feed has reached Live state.
        // During burst ingestion (Loading), the decoder pool is better reserved for
        // on-screen images. Prefetching 15 full-resolution images every 200ms during
        // Loading saturates the decoder and causes the visible feed to stall.
        val feedState by social.mycelium.android.repository.feed.NotesRepository.getInstance()
            .feedSessionState.collectAsState()
        val isFeedLive = feedState == social.mycelium.android.repository.feed.FeedSessionState.Live
        LaunchedEffect(engagementFilteredNotes, isFeedLive) {
            if (!isFeedLive) return@LaunchedEffect
            val prefetchCount = 5.coerceAtMost(engagementFilteredNotes.size)
            for (i in 0 until prefetchCount) {
                engagementFilteredNotes[i].mediaUrls.forEach { url ->
                    if (!social.mycelium.android.utils.UrlDetector.isVideoUrl(url) && prefetchedUrls.add(url)) {
                        imageLoader.enqueue(
                            coil.request.ImageRequest.Builder(context)
                                .data(url)
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                .size(screenWidthPx / 2, screenWidthPx / 2) // half-res for prefetch; full decode on visible
                                .listener(prefetchListener)
                                .build()
                        )
                    }
                }
            }
        }
        // Prefetch profile metadata so display names / @mentions resolve before scrolling into view
        val profileCache = remember { social.mycelium.android.repository.cache.ProfileMetadataCache.getInstance() }
        LaunchedEffect(engagementFilteredNotes, isFeedLive) {
            if (!isFeedLive) return@LaunchedEffect
            val prefetchCount = 20.coerceAtMost(engagementFilteredNotes.size)
            val pubkeys = mutableSetOf<String>()
            for (i in 0 until prefetchCount) {
                val note = engagementFilteredNotes[i]
                pubkeys.add(note.author.id.lowercase())
                pubkeys.addAll(social.mycelium.android.utils.extractPubkeysFromContent(note.content))
            }
            val uncached = pubkeys.filter { profileCache.getAuthor(it) == null }
            if (uncached.isNotEmpty()) {
                profileCache.requestProfiles(uncached, profileCache.getConfiguredRelayUrls())
            }
        }
        // Combined scroll prefetch: one snapshotFlow drives image prefetch, profile
        // prefetch, and visible range reporting. Replaces 3 separate snapshotFlows
        // that each subscribed to Compose's snapshot system independently.
        LaunchedEffect(listState) {
            var lastProfilePrefetchIdx = -1
            var lastImagePrefetchTimeMs = 0L
            snapshotFlow {
                val info = listState.layoutInfo
                val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                first to last
            }.collect { (firstVisible, lastVisible) ->
                // 1. Report visible range to ViewModel for URL preview prefetch
                viewModel.updateVisibleRange(firstVisible, lastVisible)

                // 2. Image prefetch: 4 notes ahead of last visible
                // Throttle: only fire every 500ms to prevent decoder flooding during fast scroll
                val now = System.currentTimeMillis()
                if (now - lastImagePrefetchTimeMs >= 500) {
                    lastImagePrefetchTimeMs = now
                    val prefetchEnd = (lastVisible + 4).coerceAtMost(engagementFilteredNotes.size - 1)
                    for (i in (lastVisible + 1)..prefetchEnd) {
                        val note = engagementFilteredNotes.getOrNull(i) ?: continue
                        note.mediaUrls.forEach { url ->
                            if (!social.mycelium.android.utils.UrlDetector.isVideoUrl(url) && prefetchedUrls.add(url)) {
                                imageLoader.enqueue(
                                    coil.request.ImageRequest.Builder(context)
                                        .data(url)
                                        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                        .size(screenWidthPx / 2, screenWidthPx / 2) // half-res for prefetch
                                        .listener(prefetchListener)
                                        .build()
                                )
                            }
                        }
                    }
                }

                // 3. Profile prefetch: debounce by skipping if position hasn't changed much
                if (lastVisible > lastProfilePrefetchIdx + 3 || lastProfilePrefetchIdx == -1) {
                    lastProfilePrefetchIdx = lastVisible
                    val prefetchEnd = (lastVisible + 10).coerceAtMost(engagementFilteredNotes.size - 1)
                    val pubkeys = mutableSetOf<String>()
                    for (i in (lastVisible + 1)..prefetchEnd) {
                        val note = engagementFilteredNotes.getOrNull(i) ?: continue
                        pubkeys.add(note.author.id.lowercase())
                        // Use pre-computed p-tag pubkeys instead of re-parsing content
                        // with 5 regex patterns on each scroll event (50 regex scans → 0)
                        note.mentionedPubkeys.forEach { pubkeys.add(it.lowercase()) }
                    }
                    val uncached = pubkeys.filter { profileCache.getAuthor(it) == null }
                    if (uncached.isNotEmpty()) {
                        profileCache.requestProfiles(uncached, profileCache.getConfiguredRelayUrls())
                    }
                }
            }
        }
        // Infinite scroll: robust pagination trigger.
        // Multiple signals drive this: scroll position changes AND isLoadingOlder state changes.
        // This ensures pagination fires even when the user is stationary at the bottom waiting
        // for more content, and works regardless of how many items engagement filtering produces.
        val notesRepo = remember { social.mycelium.android.repository.feed.NotesRepository.getInstance() }
        val isLoadingOlder by notesRepo.isLoadingOlder.collectAsState()
        val paginationExhausted by notesRepo.paginationExhausted.collectAsState()

        // Derived state: should we paginate right now?
        // Re-evaluated whenever scroll position, loading state, or exhaustion changes.
        val shouldPaginate by remember {
            derivedStateOf {
                if (isLoadingOlder || paginationExhausted) return@derivedStateOf false
                val info = listState.layoutInfo
                val totalItems = info.totalItemsCount
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (totalItems < 2) return@derivedStateOf false
                // Trigger when the user is within the last 20% of the list OR within
                // 5 items of the bottom — whichever comes first. This handles both
                // large feeds (20% = 100 notes buffer in a 500-item list) and small
                // feeds (5-item threshold ensures pagination fires even with 15 items).
                val threshold = maxOf(5, totalItems / 5)
                lastVisible >= totalItems - threshold
            }
        }
        LaunchedEffect(shouldPaginate) {
            if (shouldPaginate) {
                notesRepo.loadOlderNotes()
            }
        }

        // Report scroll position to NotesRepository so the interest-set window
        // tracks the user's viewport. Debounced to avoid flooding during flings.
        LaunchedEffect(listState) {
            androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
                .collect { index -> notesRepo.updateVisibleScrollPosition(index) }
        }

        // Hoist global preferences once so each NoteCard doesn't subscribe independently
        val compactMedia by social.mycelium.android.ui.theme.ThemePreferences.compactMedia.collectAsState()
        val showSensitiveContent by social.mycelium.android.ui.settings.MediaPreferences.showSensitiveContent.collectAsState()

        // Current user hex key for ownership checks (delete)
        val currentUserHex = remember(accountNpub) {
            accountNpub?.let { npub ->
                try {
                    (com.example.cybin.nip19.Nip19Parser.uriToRoute(npub)?.entity as? com.example.cybin.nip19.NPub)?.hex?.lowercase()
                } catch (_: Exception) {
                    null
                }
            }
        }
        val stableOnDelete = remember<(Note) -> Unit> {
            { n ->
                val err = accountStateViewModel.deleteNote(n)
                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            }
        }
        val stableOnDeleteReaction = remember<(String, String, String) -> Unit> {
            { noteId, reactionEventId, emoji ->
                val err = accountStateViewModel.deleteReaction(noteId, reactionEventId, emoji)
                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            }
        }

        // ── Stable lambdas: allocated once, not per-item ──────────────────────
        val stableOnReact = remember<(Note, String) -> Unit> {
            { reactedNote, emoji ->
                val error = accountStateViewModel.sendReaction(reactedNote, emoji)
                if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            }
        }
        val stableOnBoost = remember<(Note) -> Unit> {
            { n ->
                val noteId = n.originalNoteId ?: n.id.removePrefix("repost:")
                val authorHex = n.author.id
                android.util.Log.d(
                    "BoostDebug",
                    "stableOnBoost called: noteId=$noteId authorHex=${authorHex.take(12)} route=boost_relay_selection/$noteId/$authorHex"
                )
                onNavigateTo("boost_relay_selection/$noteId/$authorHex")
            }
        }
        val stableOnQuote = remember<(Note) -> Unit> {
            { n ->
                val nevent = com.example.cybin.nip19.encodeNevent(n.id, authorHex = n.author.id)
                val encoded = android.net.Uri.encode(android.net.Uri.encode("\nnostr:$nevent\n"))
                onNavigateTo("compose?initialContent=$encoded")
            }
        }
        val stableOnFork = remember<(Note) -> Unit> {
            { n ->
                val encoded = android.net.Uri.encode(android.net.Uri.encode(n.content))
                onNavigateTo("compose?initialContent=$encoded")
            }
        }
        val stableOnPollVote = remember<(String, String, Set<String>, String?) -> Unit> {
            { noteId, authorPk, selections, relayHint ->
                val err = accountStateViewModel.sendPollVote(noteId, authorPk, selections, relayHint)
                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            }
        }
        val stableOnNoteClick = remember<(Note) -> Unit> { { n -> onThreadClick(n, null) } }
        val stableOnLike = remember<(String) -> Unit> { { noteId -> viewModel.toggleLike(noteId) } }
        val stableOnShare = remember<(String) -> Unit> { { _ -> } }
        val stableOnZap = remember<(Note, Long) -> Unit> {
            { n, amount ->
                val err =
                    accountStateViewModel.sendZap(n, amount, social.mycelium.android.repository.sync.ZapType.PUBLIC, "")
                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            }
        }
        val stableOnComment = remember<(Note) -> Unit> { { n -> onThreadClick(n, null) } }
        val stableOnSeeAllReactions = remember<(Note) -> Unit> { { n -> onSeeAllReactions(n) } }
        val stableOnCustomZapSend = remember<(Note, Long, social.mycelium.android.repository.sync.ZapType, String) -> Unit> {
            { n, amount, zapType, msg ->
                val err = accountStateViewModel.sendZap(n, amount, zapType, msg)
                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            }
        }
        val stableOnFollowAuthor = remember<(String) -> Unit> {
            { pubkey ->
                val err = accountStateViewModel.followUser(pubkey)
                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            }
        }
        val stableOnUnfollowAuthor = remember<(String) -> Unit> {
            { pubkey ->
                val err = accountStateViewModel.unfollowUser(pubkey)
                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            }
        }
        val stableOnBlockAuthor = remember<(String) -> Unit> {
            { pubkey ->
                social.mycelium.android.repository.social.MuteListRepository.blockUser(pubkey)
                Toast.makeText(context, "Blocked", Toast.LENGTH_SHORT).show()
            }
        }
        val stableOnMuteAuthor = remember<(String) -> Unit> {
            { pubkey ->
                val signer = accountStateViewModel.getCurrentSigner()
                if (signer != null) {
                    val relays = accountStateViewModel.getOutboxRelayUrlSet()
                    social.mycelium.android.repository.social.MuteListRepository.muteUser(pubkey, signer, relays)
                    Toast.makeText(context, "Muted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Sign in to mute", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val stableOnBookmarkToggle = remember<(String, Boolean) -> Unit> {
            { noteId, isCurrentlyBookmarked ->
                val signer = accountStateViewModel.getCurrentSigner()
                if (signer != null) {
                    val relays = accountStateViewModel.getOutboxRelayUrlSet()
                    if (isCurrentlyBookmarked) {
                        social.mycelium.android.repository.social.BookmarkRepository.removeBookmark(noteId, signer, relays)
                        Toast.makeText(context, "Bookmark removed", Toast.LENGTH_SHORT).show()
                    } else {
                        social.mycelium.android.repository.social.BookmarkRepository.addBookmark(noteId, signer, relays)
                        Toast.makeText(context, "Bookmarked", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Sign in to bookmark", Toast.LENGTH_SHORT).show()
                }
            }
        }

        social.mycelium.android.ui.components.note.SharedProfileRevisionProvider {
        androidx.compose.runtime.CompositionLocalProvider(
            social.mycelium.android.ui.components.note.LocalFeedListState provides listState
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp)
        ) {
            // New notes counter (tap to load).
            // Always present in the LazyColumn so adding/removing it doesn't shift items.
            // Only shown when user is at the top of the feed — expandVertically shifts
            // all content below, which is disruptive when scrolled mid-feed. Users scroll
            // to top or pull-to-refresh to load new notes.
            item(key = "new_notes_counter") {
                val isFollowing = homeFeedState.isFollowing
                val newCount = if (isFollowing) uiState.newNotesCountFollowing else uiState.newNotesCountAll
                val isAtTop by remember { derivedStateOf { listState.firstVisibleItemIndex <= 0 } }
                androidx.compose.animation.AnimatedVisibility(
                    visible = newCount > 0 && isAtTop,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
                ) {
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

            items(
                items = engagementFilteredNotes,
                key = { it.id },
                contentType = { if (it.kind == 30023) "article_card" else "note_card" }
            ) { note ->
                if (note.kind == 30023) {
                    social.mycelium.android.ui.components.note.ArticleCard(
                        note = note,
                        onNoteClick = { onThreadClick(note, null) },
                        onProfileClick = onProfileClick,
                        onBoost = stableOnBoost,
                        onQuote = stableOnQuote,
                        onFork = stableOnFork,
                        onZap = { _, amount -> stableOnZap(note, amount) },
                        onCustomZapSend = stableOnCustomZapSend,
                        onZapSettings = onShowZapConfig,
                        onDelete = if (currentUserHex != null && social.mycelium.android.utils.normalizeAuthorIdForCache(
                                note.author.id
                            ) == currentUserHex
                        ) stableOnDelete else null,
                        isAuthorFollowed = note.author.id.lowercase() in followSetLower,
                        onFollowAuthor = stableOnFollowAuthor,
                        onUnfollowAuthor = stableOnUnfollowAuthor,
                        onBlockAuthor = stableOnBlockAuthor,
                        onMuteAuthor = stableOnMuteAuthor,
                        onBookmarkToggle = stableOnBookmarkToggle,
                        isZapInProgress = note.id in zapInProgressNoteIds,
                        isZapped = note.id in zappedNoteIds || social.mycelium.android.repository.social.NoteCountsRepository.isOwnZap(
                            note.id
                        ),
                        isBoosted = note.id in boostedNoteIds || (note.originalNoteId != null && note.originalNoteId in boostedNoteIds) || social.mycelium.android.repository.social.NoteCountsRepository.isOwnBoost(
                            note.originalNoteId ?: note.id
                        ),
                        shouldCloseZapMenus = shouldCloseZapMenus,
                        onRelayClick = onRelayClick,
                        onNavigateToRelayList = onNavigateToRelayList,
                        modifier = Modifier.fillMaxWidth()
                    )
                    return@items
                }
                val counts = countsByNoteId[note.originalNoteId ?: note.id] ?: countsByNoteId[note.id]
                NoteCard(
                    note = note,
                    callbacks = NoteCardCallbacks(
                        onLike = stableOnLike,
                        onShare = stableOnShare,
                        onComment = { _ -> stableOnComment(note) },
                        onReact = stableOnReact,
                        onBoost = stableOnBoost,
                        onQuote = stableOnQuote,
                        onFork = stableOnFork,
                        onProfileClick = onProfileClick,
                        onNoteClick = stableOnNoteClick,
                        onImageTap = onImageTap,
                        onOpenImageViewer = onOpenImageViewer,
                        onVideoClick = onVideoClick,
                        onZap = { _, amount -> stableOnZap(note, amount) },
                        onCustomZapSend = stableOnCustomZapSend,
                        onZapSettings = onShowZapConfig,
                        onRelayClick = onRelayClick,
                        onNavigateToRelayList = onNavigateToRelayList,
                        onFollowAuthor = stableOnFollowAuthor,
                        onUnfollowAuthor = stableOnUnfollowAuthor,
                        onBlockAuthor = stableOnBlockAuthor,
                        onMuteAuthor = stableOnMuteAuthor,
                        onBookmarkToggle = stableOnBookmarkToggle,
                        onDelete = if (currentUserHex != null && social.mycelium.android.utils.normalizeAuthorIdForCache(
                                note.author.id
                            ) == currentUserHex
                        ) stableOnDelete else null,
                        onMediaPageChanged = { page -> onMediaPageChanged(note.id, page) },
                        onSeeAllReactions = { stableOnSeeAllReactions(note) },
                        onPollVote = stableOnPollVote,
                        onDeleteReaction = stableOnDeleteReaction,
                    ),
                    overrides = NoteCardOverrides(
                        replyCount = replyCountByNoteId[note.id] ?: counts?.replyCount,
                        zapCount = counts?.zapCount,
                        zapTotalSats = counts?.zapTotalSats,
                        reactions = counts?.reactions,
                        reactionAuthors = counts?.reactionAuthors,
                        zapAuthors = counts?.zapAuthors,
                        zapAmountByAuthor = counts?.zapAmountByAuthor,
                        customEmojiUrls = counts?.customEmojiUrls,
                    ),
                    config = NoteCardConfig(
                        showHashtagsSection = false,
                        initialMediaPage = mediaPageForNote(note.id),
                        isVisible = isDashboardVisible && note.id in visibleKeys,
                        compactMedia = compactMedia,
                        showSensitiveContent = showSensitiveContent,
                    ),
                    interaction = NoteCardInteractionState(
                        isZapInProgress = note.id in zapInProgressNoteIds,
                        isZapped = note.id in zappedNoteIds || social.mycelium.android.repository.social.NoteCountsRepository.isOwnZap(
                            note.id
                        ),
                        isBoosted = note.id in boostedNoteIds || (note.originalNoteId != null && note.originalNoteId in boostedNoteIds) || social.mycelium.android.repository.social.NoteCountsRepository.isOwnBoost(
                            note.originalNoteId ?: note.id
                        ),
                        myZappedAmount = zappedAmountByNoteId[note.id],
                        isAuthorFollowed = note.author.id.lowercase() in followSetLower,
                        shouldCloseZapMenus = shouldCloseZapMenus,
                    ),
                    accountNpub = accountNpub,
                    myPubkeyHex = currentUserHex,
                    countsByNoteId = countsByNoteId,
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
        } // end CompositionLocalProvider
        } // end SharedProfileRevisionProvider


        // ═══ BANNER: Relay failure warning (debounced for cold start) ═══
        // Don't show immediately — relays need time to reconnect on cold start.
        var bannerGracePeriodExpired by remember { mutableStateOf(false) }
        var bannerDismissed by remember { mutableStateOf(false) }
        // Reset dismiss state when relay failures change (new failures re-show banner)
        LaunchedEffect(failedUserRelayCount) {
            if (failedUserRelayCount == 0) bannerDismissed = false
        }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(15_000L) // 15s grace period for cold start reconnection
            bannerGracePeriodExpired = true
        }
        // Auto-dismiss after 30s of continuous display
        LaunchedEffect(failedUserRelayCount, bannerGracePeriodExpired) {
            if (failedUserRelayCount > 0 && bannerGracePeriodExpired && !bannerDismissed) {
                kotlinx.coroutines.delay(30_000L)
                bannerDismissed = true
            }
        }

        AnimatedVisibility(
            visible = failedUserRelayCount > 0 && sortedNotes.isNotEmpty()
                    && bannerGracePeriodExpired && !bannerDismissed,
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
                    IconButton(
                        onClick = { bannerDismissed = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Dismiss",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                        )
                    }
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
    relayViewModel: RelayManagementViewModel? = null,
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
    val replyCountByNoteId by social.mycelium.android.repository.cache.ReplyCountCache.replyCountByNoteId.collectAsState()
    val countsByNoteId by social.mycelium.android.repository.social.NoteCountsRepository.countsByNoteId.collectAsState()
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
    // True once feed has been successfully loaded at least once — suppresses overlay on transient disconnections
    val hasEverLoadedFeed by viewModel.hasEverLoadedFeed.collectAsState()

    // Real per-relay connection status from RelayConnectionStateMachine
    // Debounced: perRelayState can emit rapidly during connection bursts.
    // Use derivedStateOf to avoid recomposing DashboardScreen on every relay status change.
    val rawPerRelayState by social.mycelium.android.relay.RelayConnectionStateMachine.getInstance().perRelayState.collectAsState()
    val perRelayState = rawPerRelayState
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

    // Relay management — ViewModel is hoisted to MyceliumNavigation for sharing across screens
    val storageManager = remember { RelayStorageManager(context) }
    val relayUiState = if (relayViewModel != null) {
        relayViewModel.uiState.collectAsState().value
    } else {
        social.mycelium.android.viewmodel.RelayManagementUiState()
    }

    // Sidebar relay count: only count user-configured relays (categories + outbox + inbox),
    // NOT indexer relays or other one-shot connections that appear in perRelayState.
    // Uses derivedStateOf to avoid recomputing on every unrelated recomposition.
    val userRelayUrls by remember(currentAccount, relayUiState) {
        derivedStateOf {
            val pubkey = currentAccount?.toHexKey() ?: return@derivedStateOf emptySet<String>()
            val categoryUrls = relayUiState.relayCategories
                .flatMap { it.relays }.map { normalizeRelayUrl(it.url) }
            val outboxUrls = relayUiState.outboxRelays
                .map { normalizeRelayUrl(it.url) }
            val inboxUrls = relayUiState.inboxRelays
                .map { normalizeRelayUrl(it.url) }
            (categoryUrls + outboxUrls + inboxUrls).toSet()
        }
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

    // Standalone categories (relayCategories) are the single source of truth for
    // kind-30002 data — always use them instead of activeProfile.categories, which
    // can be stale when RelayCategorySyncRepository writes to the standalone store
    // without updating the profile store (dual-store desync bug).
    val relayCategories = relayUiState.relayCategories
    val activeProfile = relayUiState.relayProfiles.firstOrNull { it.isActive }
        ?.copy(categories = relayCategories)

    // Initialize sidebar sections as expanded on first load
    LaunchedEffect(relayCategories) {
        if (relayCategories.isNotEmpty()) {
            feedStateViewModel.initializeExpandedCategories(relayCategories.map { it.id })
        }
    }

    // Async check: does the user have ANY saved relay config?
    // Avoids blocking first frame with synchronous SharedPreferences read.
    var hasSavedRelayConfig by remember { mutableStateOf(false) }
    LaunchedEffect(currentAccount) {
        hasSavedRelayConfig = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            currentAccount?.toHexKey()?.let { pubkey ->
                storageManager.loadCategories(pubkey).flatMap { it.relays }.isNotEmpty()
            } ?: false
        }
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
    // Only subscribed (active) categories contribute to the live subscription.
    // Toggling a category off removes its relays from the subscription set.
    val subscribedCategoryRelayUrls = remember(relayCategories) {
        relayCategories.filter { it.isSubscribed }.flatMap { it.relays }.map { it.url }.distinct()
    }
    val hasAnyConfiguredRelays = allCategoryRelayUrls.isNotEmpty() || hasSavedRelayConfig
    // All known relay URLs: categories + outbox + inbox — used for validity checks
    val allKnownRelayUrls = remember(allCategoryRelayUrls, relayUiState.outboxRelays, relayUiState.inboxRelays) {
        (allCategoryRelayUrls +
                relayUiState.outboxRelays.map { it.url } +
                relayUiState.inboxRelays.map { it.url }
                ).distinct()
    }

    // If the selected relay/category was removed, fall back to Global
    LaunchedEffect(relayCategories, relayUiState.outboxRelays, relayUiState.inboxRelays, homeFeedState) {
        val selectedUrl = homeFeedState.selectedRelayUrl
        val selectedCatId = homeFeedState.selectedCategoryId
        if (selectedUrl != null && selectedUrl !in allKnownRelayUrls) {
            feedStateViewModel.setHomeGlobal()
            feedStateViewModel.setTopicsGlobal()
            val allSubscribed = (subscribedCategoryRelayUrls + relayUiState.outboxRelays.map { it.url }).distinct()
            if (allSubscribed.isNotEmpty()) viewModel.setDisplayFilterOnly(allSubscribed)
        } else if (selectedCatId != null && selectedCatId != "outbox" && relayCategories.none { it.id == selectedCatId }) {
            feedStateViewModel.setHomeGlobal()
            feedStateViewModel.setTopicsGlobal()
            val allSubscribed = (subscribedCategoryRelayUrls + relayUiState.outboxRelays.map { it.url }).distinct()
            if (allSubscribed.isNotEmpty()) viewModel.setDisplayFilterOnly(allSubscribed)
        }
    }

    // Apply feed subscription when relay config or account changes.
    // isDashboardVisible is NOT a key — navigating to live_stream/video_viewer and back
    // should not re-fire the subscription. The subscription stays active in the background.
    // Sidebar relay/category selection is handled by setDisplayFilterOnly in onItemClick — NOT here.
    // Key on allCategoryRelayUrls (stable URL list) instead of relayCategories (object list that
    // changes on every NIP-11 info update, causing cascading re-fires that delay feed loading).
    // Gated on userStateReady from StartupOrchestrator to ensure follow list is
    // applied before the feed renders. Waits for settingsReady up to ~9s so Phase 0
    // outbox-first kind-30078 fetch can apply before the main feed REQ.
    val userStateReady by social.mycelium.android.repository.sync.StartupOrchestrator.userStateReady.collectAsState()
    val settingsReady by social.mycelium.android.repository.sync.StartupOrchestrator.settingsReady.collectAsState()
    LaunchedEffect(
        currentAccount,
        subscribedCategoryRelayUrls,
        relayUiState.outboxRelays,
        homeFeedState.isGlobal,
        onboardingComplete,
        userStateReady,
        settingsReady
    ) {
        if (!onboardingComplete) return@LaunchedEffect
        if (subscribedCategoryRelayUrls.isEmpty() && relayUiState.outboxRelays.isEmpty()) return@LaunchedEffect
        if (!userStateReady) return@LaunchedEffect
        // Wait for Phase 0 (kind-30078) up to maxWaitMs + buffer so outbox-first settings apply before main REQ.
        // Returning users: skipPhase0() leaves settingsReady true immediately.
        if (!settingsReady) {
            val deadline = System.currentTimeMillis() + 9_000L
            while (!social.mycelium.android.repository.sync.StartupOrchestrator.settingsReady.value
                && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(100)
            }
        }
        // Debounce: keys settle in rapid succession (visibility, categories, outbox);
        // wait briefly so we only fire the subscription once.
        kotlinx.coroutines.delay(150)
        val pubkey = currentAccount?.toHexKey()
        // Merge subscribed (active) category relays + outbox relays.
        // Only subscribed categories contribute to the live subscription — disabled
        // categories are inactive and their relays are not connected.
        val outboxUrls = if (pubkey != null) relayUiState.outboxRelays.map { it.url } else emptyList()
        val relayUrlsToUse = (subscribedCategoryRelayUrls + outboxUrls).map { normalizeRelayUrl(it) }.distinct()
        val displayUrls = when {
            homeFeedState.isGlobal -> relayUrlsToUse
            homeFeedState.selectedCategoryId == "outbox" -> outboxUrls.ifEmpty { relayUrlsToUse }
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
            // Signal the startup orchestrator that the feed subscription is live.
            // This gates Phase 3 (enrichment) — notifications, outbox, counts, etc.
            social.mycelium.android.repository.sync.StartupOrchestrator.markFeedStarted()
            social.mycelium.android.repository.cache.QuotedNoteCache.setRelayUrls(relayUrlsToUse)
            social.mycelium.android.repository.cache.ArticleEmbedCache.setRelayUrls(relayUrlsToUse)
            currentAccount?.toHexKey()?.let { pk ->
                social.mycelium.android.repository.cache.QuotedNoteCache.setIndexerRelayUrls(
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

    // Set cache relay URLs, request kind-0 for current user, load follow list, and fetch NIP-65 relay list
    LaunchedEffect(currentAccount, relayUiState.outboxRelays) {
        currentAccount?.toHexKey()?.let { pubkey ->
            val cacheUrls = storageManager.loadIndexerRelays(pubkey).map { it.url }
            val outboxUrls = relayUiState.outboxRelays.map { it.url }
            val followRelayUrls = (cacheUrls + outboxUrls).distinct()
            if (followRelayUrls.isNotEmpty()) {
                viewModel.setCacheRelayUrls(cacheUrls)
                social.mycelium.android.repository.cache.ProfileMetadataCache.getInstance()
                    .requestProfiles(listOf(pubkey), cacheUrls)
                viewModel.loadFollowList(pubkey, followRelayUrls)
                // NIP-65: fetch kind-10002 relay list for outbox model (counts use indexer relays)
                social.mycelium.android.repository.relay.Nip65RelayListRepository.fetchRelayList(pubkey, cacheUrls)
                // NIP-66 is initialized globally in MainActivity — no per-account trigger needed
            }
        }
    }

    // Apply follow filter when Following/Global, follow list, or active people list changes.
    // Always call setFollowFilter — the repository handles the empty-list case safely
    // (drops all notes + uses lastAppliedKind1Filter for subscription). Skipping the call
    // when followList is empty was causing the filter to never be applied on cold start,
    // allowing global notes to bleed into the Following feed.
    val activePeopleListPubkeys by remember(homeFeedState.activeListDTags) {
        derivedStateOf {
            if (homeFeedState.activeListDTags.isNotEmpty()) {
                social.mycelium.android.repository.social.PeopleListRepository.getPubkeysForLists(homeFeedState.activeListDTags)
            } else null
        }
    }
    LaunchedEffect(
        homeFeedState.isFollowing,
        uiState.followList,
        homeFeedState.activeListDTags,
        activePeopleListPubkeys
    ) {
        if (homeFeedState.activeListDTags.isNotEmpty()) {
            // People list(s) active: filter by union of their pubkeys (empty set = blank feed, intentional)
            viewModel.setFollowFilterWithCustomList(activePeopleListPubkeys ?: emptySet())
        } else {
            // Normal Following/Global mode
            viewModel.setFollowFilter(homeFeedState.isFollowing)
        }
    }

    // Update global enrichment (indexer subscriptions for hashtags + list members)
    // when the user changes these filters while in Global mode. This ensures the
    // GlobalFeedManager stays in sync with the dropdown selections.
    val subscribedHashtags by social.mycelium.android.repository.social.PeopleListRepository.subscribedHashtags.collectAsState()
    LaunchedEffect(
        homeFeedState.isFollowing,
        subscribedHashtags,
        homeFeedState.activeListDTags,
        homeFeedState.activeHashtagFilter
    ) {
        // Only relevant when in Global mode (isFollowing == false)
        if (!homeFeedState.isFollowing) {
            // Merge subscribed hashtags with the active hashtag filter (if any)
            val hashtags = buildSet {
                addAll(subscribedHashtags)
                homeFeedState.activeHashtagFilter?.let { add(it) }
            }
            social.mycelium.android.repository.feed.NotesRepository.getInstance().updateGlobalEnrichment(
                hashtags = hashtags,
                listDTags = homeFeedState.activeListDTags
            )
        }
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

    // Account switcher state — auto-dismiss when no accounts remain (e.g. after logout)
    var showAccountSwitcher by remember { mutableStateOf(false) }
    val savedAccounts by accountStateViewModel.savedAccounts.collectAsState()
    if (showAccountSwitcher && savedAccounts.isEmpty()) {
        showAccountSwitcher = false
    }

    // ── Resolved header avatar URL ──
    // authState.userProfile?.picture can be null during initial composition before
    // kind-0 arrives. Fall back to ProfileMetadataCache (populated from Room/SharedPrefs
    // on cold start) and AccountInfo.picture (persisted across sessions).
    // Also observe profileUpdated so the header re-renders when the profile loads.
    val avatarAccount by accountStateViewModel.currentAccount.collectAsState()
    val currentUserHexForAvatar = remember(avatarAccount) {
        avatarAccount?.toHexKey()
    }
    var avatarProfileRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentUserHexForAvatar) {
        val hex = currentUserHexForAvatar ?: return@LaunchedEffect
        social.mycelium.android.repository.cache.ProfileMetadataCache.getInstance().profileUpdated
            .filter { it == hex }
            .collect { avatarProfileRevision++ }
    }
    val resolvedAvatarUrl = remember(
        authState.userProfile?.picture,
        currentUserHexForAvatar,
        avatarProfileRevision
    ) {
        // Priority: authState (latest from StateFlow) → ProfileMetadataCache → AccountInfo
        authState.userProfile?.picture?.takeIf { it.isNotBlank() }
            ?: currentUserHexForAvatar?.let {
                social.mycelium.android.repository.cache.ProfileMetadataCache.getInstance().getAuthor(it)?.avatarUrl
            }?.takeIf { it.isNotBlank() }
            ?: avatarAccount?.picture?.takeIf { it.isNotBlank() }
    }

    // Zap menu state - shared across all note cards
    var shouldCloseZapMenus by remember { mutableStateOf(false) }

    // Zap configuration dialog state
    var showZapConfigDialog by remember { mutableStateOf(false) }
    var showWalletConnectDialog by remember { mutableStateOf(false) }
    // Relay orb tap navigates to relay log page via onRelayClick callback

    // Restore home feed scroll position when returning to dashboard (one-shot; do not re-run on notes.size).
    // Skip restoration when coming fresh from onboarding (hasLoadedRelays is false) to avoid
    // landing in the middle of a stale cached feed.
    // Also skip when the LazyColumn is already at/near the saved position (overlay case: the list
    // was never destroyed, just hidden behind the thread overlay — scrollToItem on an already-
    // positioned list triggers a layout pass that causes a violent shift).
    // On process death resume: notes may still be loading from Room cache — wait for them
    // before attempting restore. feedCacheChecked ensures Room has finished loading.
    val scrollPos = homeFeedState.scrollPosition
    LaunchedEffect(isDashboardVisible, scrollPos.firstVisibleItem, scrollPos.scrollOffset, feedCacheChecked) {
        if (isDashboardVisible && scrollPos.firstVisibleItem > 0 && feedCacheChecked) {
            // Wait briefly for notes to populate from Room cache if they haven't yet
            if (uiState.notes.isEmpty()) {
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    snapshotFlow { uiState.notes.size }
                        .filter { it > 0 }
                        .first()
                }
            }
            if (uiState.notes.isNotEmpty()) {
                val currentIdx = listState.firstVisibleItemIndex
                val drift = kotlin.math.abs(currentIdx - scrollPos.firstVisibleItem)
                // Only restore if the list drifted significantly (e.g. NavHost recreation).
                // For overlay returns, currentIdx ≈ scrollPos.firstVisibleItem already.
                if (drift > 2) {
                    listState.scrollToItem(
                        scrollPos.firstVisibleItem.coerceAtMost(uiState.notes.size - 1),
                        scrollPos.scrollOffset
                    )
                }
            }
            feedStateViewModel.updateHomeFeedState { copy(scrollPosition = ScrollPosition(0, 0)) }
        }
    }

    // On fresh mount (app restart), ensure feed starts at the top.
    // Guard: ViewModel-scoped flag survives LaunchedEffect lifecycle restarts (STOPPED→STARTED
    // during navigation to image_viewer/video_viewer). Without this, the LaunchedEffect re-fires
    // on every pop-back and scrolls the feed to the top.
    // Debounce: wait for the note count to stabilize (300ms with no new notes) before scrolling,
    // so the feed settles before we snap to top — prevents landing mid-feed after relay events stream in.
    // Skip when a saved scroll position exists (Room cache restore after process death) —
    // the scroll-restore LaunchedEffect above handles that case.
    LaunchedEffect(Unit) {
        if (!feedStateViewModel.hasInitializedHomeScroll) {
            // If there's a saved scroll position from a previous session, the user was
            // mid-feed when the process died. Don't override their position with scroll-to-top.
            if (homeFeedState.scrollPosition.firstVisibleItem > 0) {
                feedStateViewModel.hasInitializedHomeScroll = true
            } else {
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                snapshotFlow { uiState.notes.size }
                    .filter { it > 0 }
                    .debounce(300)
                    .first()
                listState.scrollToItem(0)
                feedStateViewModel.hasInitializedHomeScroll = true
            }
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

    // When new notes first appear (0 → >0), expand the top app bar so the counter
    // is visible without requiring the user to pull down. Only triggers once per
    // batch — subsequent count increments while browsing do NOT re-expand the header.
    // Guard: only expand when the user is near the top of the feed where the banner
    // is actually visible. Expanding the header while scrolled mid-feed causes the
    // header + bottom nav to flash in/out (bottom nav derives visibility from
    // collapsedFraction) and disrupts scroll position — especially on app resume
    // when new notes accumulated in the background.
    val newNotesCount = if (homeFeedState.isFollowing) uiState.newNotesCountFollowing else uiState.newNotesCountAll
    var hasExpandedForNewNotes by remember { mutableStateOf(false) }
    LaunchedEffect(newNotesCount) {
        if (newNotesCount == 0) {
            // Reset: next batch of new notes will trigger one expansion
            hasExpandedForNewNotes = false
        } else if (!hasExpandedForNewNotes && topAppBarState.heightOffset < 0f) {
            // Only auto-expand when user is near the top where the banner is visible
            val isNearTop = listState.firstVisibleItemIndex <= 1
            if (isNearTop) {
                topAppBarState.heightOffset = 0f
                hasExpandedForNewNotes = true
            }
        }
    }

    // Engagement filter: null = all, "replies" / "likes" / "zaps" — persisted in FeedStateViewModel
    val engagementFilter = homeFeedState.engagementFilter
    // Keep NoteCountsRepository in sync so it can prioritize the relevant event kind
    LaunchedEffect(engagementFilter) {
        social.mycelium.android.repository.social.NoteCountsRepository.activeEngagementFilter = engagementFilter
    }

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

    // URL previews are passed as a side channel to avoid copying the entire note list
    // every time a single preview resolves. Previews are looked up per-item in the LazyColumn.
    val urlPreviewsByNoteId = uiState.urlPreviewsByNoteId
    val notesList = uiState.notes
    // Home feed sort: Latest (by time) or Popular (cumulative engagement score)
    // distinctBy(id) prevents duplicate-key crashes in LazyColumn when the same note arrives from multiple relays
    // Popular score = total reactions + zap count + reply count (simple cumulative; later becomes WoT-extensible)
    val sortedNotes by remember(notesList, homeFeedState.homeSortOrder) {
        derivedStateOf {
            when (homeFeedState.homeSortOrder) {
                // Latest: data layer guarantees unique IDs (FeedIndex) and descending
                // timestamp order (flushKind1Events, insertRepostNote). Pass through directly.
                HomeSortOrder.Latest -> notesList
                HomeSortOrder.Popular -> notesList.sortedWith(
                    compareByDescending<Note> { note ->
                        val counts = countsByNoteId[note.originalNoteId ?: note.id] ?: countsByNoteId[note.id]
                        val reactionCount = counts?.reactionAuthors?.values?.sumOf { it.size } ?: 0
                        val zapCount = counts?.zapAuthors?.size ?: 0
                        val replyCount = replyCountByNoteId[note.id] ?: 0
                        reactionCount + zapCount + replyCount
                    }.thenByDescending { it.repostTimestamp ?: it.timestamp }
                )
            }
        }
    }

    // Mute/block filtering: hide notes from muted or blocked authors
    val mutedPubkeys by social.mycelium.android.repository.social.MuteListRepository.mutedPubkeys.collectAsState()
    val blockedPubkeys by social.mycelium.android.repository.social.MuteListRepository.blockedPubkeys.collectAsState()
    val mutedWords by social.mycelium.android.repository.social.MuteListRepository.mutedWords.collectAsState()

    // Stage 1: mute/block/hidden filtering — only recomputes when the note list or mute lists change.
    // countsByNoteId is deliberately excluded so frequent count updates don't trigger full refilter.
    val baseFilteredNotes by remember(sortedNotes, hiddenNoteIds, mutedPubkeys, blockedPubkeys, mutedWords) {
        derivedStateOf {
            val hiddenAuthors = mutedPubkeys + blockedPubkeys
            sortedNotes.filter { note ->
                note.id !in hiddenNoteIds &&
                        note.author.id.lowercase() !in hiddenAuthors &&
                        (mutedWords.isEmpty() || mutedWords.none { word ->
                            note.content.contains(
                                word,
                                ignoreCase = true
                            )
                        })
            }
        }
    }
    // Stage 2: engagement sort — only recomputes when filter type or counts change.
    // For the default chronological feed (engagementFilter == null/empty), this is a no-op pass-through.
    val afterEngagementSort by remember(baseFilteredNotes, engagementFilter) {
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
    // Stage 3: hashtag content filter — only notes containing the active hashtag are shown.
    val activeHashtagFilter = homeFeedState.activeHashtagFilter
    val engagementFilteredNotes by remember(afterEngagementSort, activeHashtagFilter) {
        derivedStateOf {
            val filtered = if (activeHashtagFilter == null) afterEngagementSort
            else {
                val tag = activeHashtagFilter.lowercase()
                afterEngagementSort.filter { note ->
                    note.content.contains("#$tag", ignoreCase = true) ||
                            note.hashtags.any { it.equals(tag, ignoreCase = true) }
                }
            }
            // Guard: distinctBy(id) prevents LazyColumn duplicate-key crash.
            // The data layer (FeedIndex) should guarantee unique IDs, but edge cases
            // (note arriving both standalone and inside kind-6 content, relay races)
            // can slip through. First occurrence wins (preserves sort order).
            filtered.distinctBy { it.id }
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

    // Relay health: trouble count for sidebar badge — only flag user-specified relays
    // (outbox/inbox/custom categories), not indexer or system relays.
    val flaggedRelays by social.mycelium.android.relay.RelayHealthTracker.flaggedRelays.collectAsState()
    val blockedRelays by social.mycelium.android.relay.RelayHealthTracker.blockedRelays.collectAsState()
    val troubleRelayCount = remember(flaggedRelays, blockedRelays, userRelayUrls) {
        val trouble = (flaggedRelays + blockedRelays)
        trouble.count {
            it in userRelayUrls || social.mycelium.android.repository.relay.RelayStorageManager.normalizeRelayUrl(
                it
            ) in userRelayUrls
        }
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
        gesturesEnabled = currentAccount != null && !isSearchMode,
        onToggleCategorySubscription = { categoryId ->
            relayViewModel?.toggleCategorySubscription(categoryId)
            // After toggle, recompute the display filter so the feed reflects the change.
            // relayCategories snapshot is stale here (toggle hasn't propagated yet),
            // so invert the current isSubscribed to predict the new state.
            val updatedSubscribedUrls = relayCategories
                .filter { cat ->
                    if (cat.id == categoryId) !cat.isSubscribed else cat.isSubscribed
                }
                .flatMap { it.relays }.map { it.url }.distinct()
            val outboxUrls = relayUiState.outboxRelays.map { it.url }
            val allSubscribed = (updatedSubscribedUrls + outboxUrls).distinct()
            val state = homeFeedState
            when {
                state.isGlobal -> if (allSubscribed.isNotEmpty()) viewModel.setDisplayFilterOnly(allSubscribed)
                state.selectedCategoryId == categoryId -> {
                    // Toggled the currently-selected category — show its relays (or fall back to all if now empty)
                    val catRelays =
                        relayCategories.firstOrNull { it.id == categoryId }?.relays?.map { it.url } ?: emptyList()
                    val wasSubscribed = relayCategories.firstOrNull { it.id == categoryId }?.isSubscribed ?: false
                    if (!wasSubscribed) {
                        // Re-subscribing: show this category's relays
                        if (catRelays.isNotEmpty()) viewModel.setDisplayFilterOnly(catRelays)
                    } else {
                        // Unsubscribing the selected category: fall back to Global
                        feedStateViewModel.setHomeGlobal()
                        feedStateViewModel.setTopicsGlobal()
                        if (allSubscribed.isNotEmpty()) viewModel.setDisplayFilterOnly(allSubscribed)
                    }
                }

                state.selectedCategoryId == "outbox" -> viewModel.setDisplayFilterOnly(outboxUrls)
                state.selectedRelayUrl != null -> viewModel.setDisplayFilterOnly(listOf(state.selectedRelayUrl!!))
                else -> if (allSubscribed.isNotEmpty()) viewModel.setDisplayFilterOnly(allSubscribed)
            }
        },
        onItemClick = { itemId ->
            when {
                itemId == "global" -> {
                    feedStateViewModel.setHomeGlobal()
                    feedStateViewModel.setTopicsGlobal()
                    val allSubscribed =
                        (subscribedCategoryRelayUrls + relayUiState.outboxRelays.map { it.url }).distinct()
                    if (allSubscribed.isNotEmpty()) viewModel.setDisplayFilterOnly(allSubscribed)
                }

                itemId == "outbox" -> {
                    val outboxUrls = relayUiState.outboxRelays.map { it.url }
                    if (outboxUrls.isNotEmpty()) {
                        feedStateViewModel.setHomeSelectedOutbox()
                        feedStateViewModel.setTopicsSelectedOutbox()
                        viewModel.setDisplayFilterOnly(outboxUrls)
                    }
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
                    // Look up relay name from categories, outbox, and inbox
                    val relay = activeProfile?.categories?.flatMap { it.relays }?.firstOrNull { it.url == relayUrl }
                        ?: relayUiState.outboxRelays.firstOrNull { it.url == relayUrl }
                        ?: relayUiState.inboxRelays.firstOrNull { it.url == relayUrl }
                    val displayName = relay?.displayName ?: relay?.info?.name ?: relayUrl
                        .removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
                    feedStateViewModel.setHomeSelectedRelay(relayUrl, displayName)
                    feedStateViewModel.setTopicsSelectedRelay(relayUrl, displayName)
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
                                loginContext.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://github.com/greenart7c3/Amber")
                                    )
                                )
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
                            // Navigate but keep search alive so user can return
                            onThreadClick(note, null)
                        },
                        active = isSearchMode,
                        onActiveChange = { active ->
                            if (!active) {
                                onSearchModeChange(false)
                                searchQuery = ""
                            }
                        },
                        onBackClick = {
                            // Only clear search on explicit close (back button)
                            searchQuery = ""
                            onSearchModeChange(false)
                        },
                        placeholder = { Text("Search notes, users, hashtags...") },
                        onProfileClick = { pubkey ->
                            // Navigate but keep search alive so user can return
                            onProfileClick(pubkey)
                        },
                        onHashtagClick = { tag ->
                            // Navigate but keep search alive so user can return
                            onNavigateTo("hashtag/$tag")
                        },
                        accountPubkey = currentAccount?.toHexKey()
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
                        onListsClick = {
                            onNavigateTo("lists")
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
                        userAvatarUrl = resolvedAvatarUrl,
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
                        peopleLists = remember(social.mycelium.android.repository.social.PeopleListRepository.peopleLists.collectAsState().value) {
                            social.mycelium.android.repository.social.PeopleListRepository.peopleLists.value.map { it.dTag to it.title }
                        },
                        activeListDTag = homeFeedState.activeListDTag,
                        activeListDTags = homeFeedState.activeListDTags,
                        onPeopleListSelected = { dTag, title ->
                            if (dTag != null && title != null) {
                                feedStateViewModel.setHomeActiveList(dTag, title)
                            } else {
                                feedStateViewModel.clearHomeActiveList()
                            }
                            scrollToTopTrigger++
                        },
                        onPeopleListToggled = { dTag, title ->
                            feedStateViewModel.toggleHomeActiveList(dTag, title)
                            scrollToTopTrigger++
                        },
                        subscribedHashtags = subscribedHashtags,
                        activeHashtagFilter = homeFeedState.activeHashtagFilter,
                        onHashtagFilterSelected = { hashtag ->
                            if (hashtag != null) {
                                feedStateViewModel.setHomeHashtagFilter(hashtag)
                            } else {
                                feedStateViewModel.clearHomeHashtagFilter()
                            }
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
                    val kind1RootDrafts =
                        remember(draftsList) { draftsList.filter { it.type == social.mycelium.android.data.DraftType.NOTE } }
                    social.mycelium.android.ui.components.nav.HomeFab(
                        onScrollToTop = {
                            scope.launch { listState.scrollToItem(0) }
                        },
                        onCompose = { onNavigateTo("compose") },
                        onArticle = { onNavigateTo("compose_article") },
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
        social.mycelium.android.ui.components.nav.AccountSwitchBottomSheet(
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
        social.mycelium.android.ui.components.zap.WalletConnectDialog(
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
