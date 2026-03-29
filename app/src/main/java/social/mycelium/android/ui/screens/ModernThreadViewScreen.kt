package social.mycelium.android.ui.screens

import androidx.compose.runtime.collectAsState
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntSize
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.animateFloatAsState
import social.mycelium.android.ui.components.note.isMarkdown
import social.mycelium.android.ui.components.note.EmbeddedArticlePreview
import social.mycelium.android.ui.components.note.FabMenuItem

import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import social.mycelium.android.data.Author
import social.mycelium.android.data.Comment
import social.mycelium.android.data.Note
import social.mycelium.android.viewmodel.Kind1RepliesViewModel
import social.mycelium.android.viewmodel.ThreadRepliesUiState
import social.mycelium.android.data.ThreadReply
import social.mycelium.android.data.ThreadedReply
import social.mycelium.android.data.toThreadReplyForThread
import social.mycelium.android.data.toNote
import social.mycelium.android.data.SampleData
import social.mycelium.android.repository.relay.RelayStorageManager
import social.mycelium.android.ui.components.nav.AdaptiveHeader
import social.mycelium.android.ui.components.nav.BottomNavigationBar
import social.mycelium.android.repository.sync.ZapType
import social.mycelium.android.ui.components.note.NoteCard
import social.mycelium.android.ui.components.common.ProfilePicture
import social.mycelium.android.ui.components.relay.RelayOrbs
import social.mycelium.android.ui.components.relay.SingleRelayOrb
import androidx.compose.foundation.lazy.LazyRow
import social.mycelium.android.ui.components.zap.ZapButtonWithMenu
import social.mycelium.android.ui.components.zap.ZapMenuRow
import social.mycelium.android.ui.icons.ArrowDownward
import social.mycelium.android.ui.icons.ArrowUpward
import social.mycelium.android.ui.icons.Bolt
import social.mycelium.android.ui.icons.Bookmark
import social.mycelium.android.viewmodel.ThreadRepliesViewModel
import social.mycelium.android.viewmodel.Kind1ReplySortOrder
import social.mycelium.android.viewmodel.ReplySortOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

import social.mycelium.android.repository.social.NoteCounts
// ✅ PERFORMANCE: Cached date formatter
private val dateFormatter by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

// ✅ PERFORMANCE: Consistent animation specs
private val standardAnimation = tween<IntSize>(durationMillis = 200, easing = FastOutSlowInEasing)
private val fastAnimation = tween<IntSize>(durationMillis = 150, easing = FastOutSlowInEasing)

/** Fallback Thread/topic reply separator line color (used where depth is unknown). */

/** Fallback Thread/topic reply separator line color (used where depth is unknown). */
private val ThreadLineColor = Color(0xFF8888A0)

/** Max indent depth; beyond this show "Read N more replies" and open sub-thread on tap. */
private const val MAX_THREAD_DEPTH = 4

/** Stable key for a reply so optimistic→real replacement doesn't change list key (avoids UI jump).
 *  Use the event id when available (unique per nostr event). Only fall back to content-based key
 *  for optimistic replies whose id is a synthetic UUID (starts with "optimistic-"). */
private fun logicalReplyKey(reply: ThreadReply): String =
    if (reply.id.startsWith("optimistic-")) {
        "logical:${reply.author.id}:${reply.content.take(80)}:${reply.replyToId}"
    } else {
        "reply:${reply.id}"
    }

/** Find a ThreadedReply node by reply id in the tree. */
private fun findThreadedReplyById(tree: List<ThreadedReply>, id: String): ThreadedReply? =
    tree.firstOrNull { it.reply.id == id } ?: tree.firstNotNullOfOrNull { findThreadedReplyById(it.children, id) }

/** Path of reply ids from root to target (inclusive); null if target not in tree. Used to expand and scroll to a reply. */
private fun findPathToReplyId(roots: List<ThreadedReply>, targetId: String): List<String>? {
    for (node in roots) {
        if (node.reply.id == targetId) return listOf(targetId)
        findPathToReplyId(node.children, targetId)?.let { sub -> return listOf(node.reply.id) + sub }
    }
    return null
}

/** Subtree under the given reply as the new root: same nesting (indent/lines), only that ROOT and its children. */
private fun subtreeWithStructure(tree: List<ThreadedReply>, focusReplyId: String): List<ThreadedReply>? {
    val node = findThreadedReplyById(tree, focusReplyId) ?: return null
    fun relevel(n: ThreadedReply, newLevel: Int): ThreadedReply = ThreadedReply(
        reply = n.reply,
        children = n.children.map { relevel(it, newLevel + 1) },
        level = newLevel
    )
    return listOf(relevel(node, 0))
}

/**
 * Find the index in [displayList] of the top-level item whose subtree contains [targetId].
 * Returns -1 if the target is not found. This is needed because the LazyColumn only has
 * top-level items — children are rendered recursively inside each ThreadedReplyCard.
 */
private fun findContainingDisplayIndex(displayList: List<ThreadedReply>, targetId: String): Int {
    for (i in displayList.indices) {
        if (displayList[i].reply.id == targetId) return i
        if (findThreadedReplyById(displayList[i].children, targetId) != null) return i
    }
    return -1
}

/**
 * Compute the sub-thread drill-down stack needed to make a deeply nested reply
 * visible in the display list. The display list only has top-level items;
 * children are rendered recursively inside ThreadedReplyCard. To ensure the
 * target reply is visible:
 *  - We need the target to be at a low enough level that it's rendered inline
 *    (not behind "Read N more replies", which fires at MAX_THREAD_DEPTH).
 *  - We also want it shallow enough that scrolling to its top-level ancestor
 *    in the LazyColumn actually brings it on-screen.
 *
 * Strategy: drill down so the target's **grandparent** (or parent for short
 * chains) becomes the sub-thread root. This places the target at level ≤ 2,
 * guaranteeing it's visible without any further user interaction.
 *
 * Returns the list of reply IDs to push onto [rootReplyIdStack], or empty if
 * the reply is visible without drilling down.
 */
private fun computeDrillDownStack(tree: List<ThreadedReply>, targetId: String): List<String> {
    val path = findPathToReplyId(tree, targetId) ?: return emptyList()
    // path = [root-child, ..., grandparent, parent, target]
    // path.size = 1 means the target is a level-0 reply → visible, no drill needed
    if (path.size <= 2) return emptyList() // target at level 0 or 1 → visible

    // We want the target at level ≤ 2 in the sub-thread.
    // If we pivot on path[pivotIdx], that node becomes level 0, and the target
    // (which is the last element) is at level (path.size - 1 - pivotIdx).
    // Solve: path.size - 1 - pivotIdx <= 2  →  pivotIdx >= path.size - 3
    // Pivot on the grandparent of the target (or parent if path is short)
    val pivotIdx = (path.size - 3).coerceAtLeast(0)

    // Build pivot stack — we need intermediate pivots every MAX_THREAD_DEPTH
    // levels so the drill-down UI shows each "Back" step correctly.
    val pivots = mutableListOf<String>()
    var currentOffset = 0
    for (i in 0..pivotIdx) {
        val effectiveLevel = i - currentOffset
        if (effectiveLevel >= MAX_THREAD_DEPTH) {
            pivots.add(path[i - 1])
            currentOffset = i - 1
        }
    }
    // Final pivot: the grandparent
    pivots.add(path[pivotIdx])
    return pivots
}

/** Filter threaded replies by relay URLs. Shows entire subtree when a node matches;
 *  shows non-matching parents as context when only a descendant matches. */
private fun filterThreadedByRelays(
    replies: List<ThreadedReply>,
    relayFilter: Set<String>
): List<ThreadedReply> {
    if (relayFilter.isEmpty()) return replies
    return replies.mapNotNull { node ->
        val selfMatches = node.reply.relayUrls.any {
            social.mycelium.android.utils.normalizeRelayUrl(it) in relayFilter
        }
        if (selfMatches) {
            node // entire subtree shown
        } else {
            val filteredChildren = filterThreadedByRelays(node.children, relayFilter)
            if (filteredChildren.isNotEmpty()) {
                node.copy(children = filteredChildren)
            } else null
        }
    }
}

// Data classes previously in ThreadViewScreen.kt — moved here after cleanup
@Immutable
data class CommentState(
    val isExpanded: Boolean = true,
    val isCollapsed: Boolean = false,
    val showControls: Boolean = false
)

data class CommentThread(
    val comment: Comment,
    val replies: List<CommentThread> = emptyList()
)

fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h"
        else -> dateFormatter.format(Date(timestamp))
    }
}

/**
 * Modern, performant Thread View Screen following Material Design 3 principles
 *
 * Key Performance Improvements:
 * - Single animation spec for consistency
 * - Simplified state management
 * - Reduced recompositions
 * - Clean visual hierarchy
 * - Smooth animations without conflicts
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernThreadViewScreen(
    note: Note,
    comments: List<CommentThread>,
    listState: LazyListState = rememberLazyListState(),
    commentStates: MutableMap<String, CommentState> = remember { mutableStateMapOf() },
    expandedControlsCommentId: String? = null,
    onExpandedControlsChange: (String?) -> Unit = {},
    /** Reply ID whose controls (like/reply/zap) are shown; null = all compact. Tap reply to expand. */
    expandedControlsReplyId: String? = null,
    onExpandedControlsReplyChange: (String?) -> Unit = {},
    topAppBarState: TopAppBarState = rememberTopAppBarState(),
    replyKind: Int = 1111, // 1 = Kind 1 replies (home feed), 1111 = Kind 1111 replies (topics)
    /** When set (e.g. from notification), expand path to this reply and scroll to it. */
    highlightReplyId: String? = null,
    threadRepliesViewModel: ThreadRepliesViewModel = viewModel(),
    kind1RepliesViewModel: Kind1RepliesViewModel = viewModel(),
    relayUrls: List<String> = emptyList(),
    cacheRelayUrls: List<String> = emptyList(),
    onBackClick: () -> Unit = {},
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    /** Navigate to a different note's thread (e.g. tapping a quoted note). */
    onNoteClick: (Note) -> Unit = {},
    onImageTap: (Note, List<String>, Int) -> Unit = { _, _, _ -> },
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    onReact: (Note, String) -> Unit = { _, _ -> },
    /** Boost (kind-6 repost): republish the note as-is. */
    onBoost: ((Note) -> Unit)? = null,
    /** Quote: open compose with this note quoted (nostr:nevent1…). */
    onQuote: ((Note) -> Unit)? = null,
    /** Fork: open compose pre-filled with this note's content for editing. */
    onFork: ((Note) -> Unit)? = null,
    onCustomZapSend: ((Note, Long, ZapType, String) -> Unit)? = null,
    /** When user taps a zap amount chip; (noteId, amount). */
    onZap: (String, Long) -> Unit = { _, _ -> },
    /** When non-null, used to resolve (noteId, amount) to sendZap(Note, amount) for root and replies. */
    onSendZap: ((Note, Long) -> Unit)? = null,
    /** Note IDs currently sending a zap (for loading indicator). */
    zapInProgressNoteIds: Set<String> = emptySet(),
    /** Note IDs the current user has zapped (bolt turns yellow). */
    zappedNoteIds: Set<String> = emptySet(),
    /** Amount (sats) the current user zapped per note ID; for "You zapped X sats". */
    myZappedAmountByNoteId: Map<String, Long> = emptyMap(),
    /** Note IDs the current user has boosted (repost icon turns green). */
    boostedNoteIds: Set<String> = emptySet(),
    /** Kind-30011 vote: (noteId, authorPubkey, direction +1/-1). */
    onVote: ((String, String, Int) -> Unit)? = null,
    onCommentLike: (String) -> Unit = {},
    onCommentReply: (String) -> Unit = {},
    /** When non-null and replyKind==1111, enables kind-1111 reply dialog and publish. Returns error message or null on success. */
    onPublishThreadReply: ((rootId: String, rootPubkey: String, parentId: String?, parentPubkey: String?, content: String) -> String?)? = null,
    /** When set, opens reply in a dedicated screen instead of in-dialog (replyToNote shown at top). */
    onOpenReplyCompose: ((rootId: String, rootPubkey: String, parentId: String?, parentPubkey: String?, replyToNote: Note?) -> Unit)? = null,
    onLoginClick: (() -> Unit)? = null,
    isGuest: Boolean = true,
    userDisplayName: String? = null,
    userAvatarUrl: String? = null,
    onHeaderProfileClick: () -> Unit = {},
    onHeaderAccountsClick: () -> Unit = {},
    onHeaderQrCodeClick: () -> Unit = {},
    onHeaderSettingsClick: () -> Unit = {},
    accountNpub: String? = null,
    /** Current user Author for optimistic reply (kind-1111); when set, reply appears immediately. */
    currentUserAuthor: Author? = null,
    /** Retrieve the shared media album page for a note (from AppViewModel). */
    mediaPageForNote: (String) -> Int = { 0 },
    /** Store the media album page when user swipes (to AppViewModel). */
    onMediaPageChanged: (String, Int) -> Unit = { _, _ -> },
    onRelayNavigate: (String) -> Unit = {},
    /** When set, tapping relay orbs navigates to a dedicated relay list screen instead of opening a popup. */
    onNavigateToRelayList: ((List<String>) -> Unit)? = null,
    /** Called when user taps "See all" in the reaction details panel. */
    onSeeAllReactions: (String) -> Unit = {},
    /** Navigate to the full-page zap settings screen. */
    onNavigateToZapSettings: () -> Unit = {},
    /** Reply drafts belonging to this thread; injected inline as placeholder cards. */
    threadDrafts: List<social.mycelium.android.data.Draft> = emptyList(),
    onEditDraft: (social.mycelium.android.data.Draft) -> Unit = {},
    onDeleteDraft: (String) -> Unit = {},
    /** Delete this note (hybrid NIP-86 + NIP-09). Only pass for the current user's own notes. */
    onDeleteNote: ((Note) -> Unit)? = null,
    /** NIP-88 poll vote callback: (noteId, authorPubkey, selectedOptions, relayHint). */
    onPollVote: ((String, String, Set<String>, String?) -> Unit)? = null,
    /** Current user hex pubkey for detecting own poll votes. */
    myPubkeyHex: String? = null,
    /** Delete a reaction (kind-5 deletion): (noteId, reactionEventId, emoji). */
    onDeleteReaction: ((noteId: String, reactionEventId: String, emoji: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val compactMedia by social.mycelium.android.ui.theme.ThemePreferences.compactMedia.collectAsState()
    val currentUserHex = remember(accountNpub) {
        accountNpub?.let { npub ->
            try {
                (com.example.cybin.nip19.Nip19Parser.uriToRoute(npub)?.entity as? com.example.cybin.nip19.NPub)?.hex?.lowercase()
            } catch (_: Exception) {
                null
            }
        }
    }
    var isRefreshing by remember { mutableStateOf(false) }

    /** Stack of reply ids for sub-thread drill-down; back gesture pops one. Empty = full thread. */
    var rootReplyIdStack by remember { mutableStateOf<List<String>>(emptyList()) }
    val currentRootReplyId = rootReplyIdStack.lastOrNull()

    /** Saved scroll positions per stack depth — restored when popping back from sub-thread. */
    val savedScrollByDepth = remember { mutableMapOf<Int, Pair<Int, Int>>() }

    /** Root-only mode: when true, only show level-0 replies with descendant count badges. */
    var showRootOnly by remember { mutableStateOf(false) }

    /** Sort order: false = oldest first (chronological), true = newest first. */
    var isNewestFirst by remember { mutableStateOf(false) }
    /** Selected relay URLs for filtering thread replies. Empty = show all (no filter active). */
    var selectedRelayFilters by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Select appropriate ViewModel based on reply kind
    val repliesState = when (replyKind) {
        1 -> {
            // Kind 1 replies for home feed (NIP-10 threaded when root/reply data present)
            val kind1State by kind1RepliesViewModel.uiState.collectAsState()
            ThreadRepliesUiState(
                note = kind1State.note,
                replies = kind1State.replies.map { it.toThreadReplyForThread() },
                threadedReplies = kind1State.threadedReplies,
                isLoading = kind1State.isLoading,
                error = kind1State.error,
                totalReplyCount = kind1State.totalReplyCount
            )
        }

        else -> {
            // Kind 1111 replies for topics
            threadRepliesViewModel.uiState.collectAsState().value
        }
    }

    // Set cache relay URLs for kind-0 profile fetches in reply ViewModels
    LaunchedEffect(cacheRelayUrls) {
        if (cacheRelayUrls.isNotEmpty()) {
            kind1RepliesViewModel.setCacheRelayUrls(cacheRelayUrls)
            threadRepliesViewModel.setCacheRelayUrls(cacheRelayUrls)
        }
    }

    // Prefetch quoted notes for the root note + preload outbox relays for quoted note authors
    LaunchedEffect(note.id) {
        // Ensure quoted notes are in cache (may already be from feed prefetch; if not, fetch now)
        if (note.quotedEventIds.isNotEmpty()) {
            val uncached =
                note.quotedEventIds.filter { social.mycelium.android.repository.cache.QuotedNoteCache.getCached(it) == null }
            if (uncached.isNotEmpty()) {
                social.mycelium.android.repository.cache.QuotedNoteCache.prefetchForNotes(listOf(note))
            }
        }
        val discoveryRelays = cacheRelayUrls
        note.quotedEventIds.forEach { quotedId ->
            val quotedMeta = social.mycelium.android.repository.cache.QuotedNoteCache.getCached(quotedId)
            if (quotedMeta != null && quotedMeta.authorId.isNotBlank()) {
                social.mycelium.android.repository.relay.Nip65RelayListRepository.fetchOutboxRelaysForAuthor(
                    quotedMeta.authorId, discoveryRelays
                )
            }
        }
    }

    // Load replies when screen opens; clear other kind's state so we don't show stale replies.
    // Guard: skip clear+reload if the active ViewModel already has replies for this note
    // (e.g. returning from reply_compose — don't wipe existing replies).
    val relayUrlsKey = remember(relayUrls) { relayUrls.sorted().joinToString(",") }

    // Defensive: immediately clear stale replies when note changes so old thread's
    // comments never flash on screen. This runs synchronously during composition,
    // before the LaunchedEffect fires. Also handles shared ViewModel instances
    // (e.g. overlay threads on dashboard reuse the same VM across open/close cycles).
    val lastLoadedNoteId = remember { mutableStateOf<String?>(null) }
    if (lastLoadedNoteId.value != note.id) {
        // Clear the previous note's replies from both VMs
        val prev = lastLoadedNoteId.value
        if (prev != null) {
            kind1RepliesViewModel.clearRepliesForNote(prev)
            threadRepliesViewModel.clearRepliesForNote(prev)
        }
        // Also force-clear the active VM if it has a different note loaded (shared VM reuse)
        val activeNoteId = when (replyKind) {
            1 -> kind1RepliesViewModel.uiState.value.note?.id
            else -> threadRepliesViewModel.uiState.value.note?.id
        }
        if (activeNoteId != null && activeNoteId != note.id) {
            kind1RepliesViewModel.clearRepliesForNote(activeNoteId)
            threadRepliesViewModel.clearRepliesForNote(activeNoteId)
        }
        lastLoadedNoteId.value = note.id
    }

    LaunchedEffect(note.id, relayUrlsKey, replyKind) {
        // Suspend until relay URLs are available (rather than skip + re-fire on recomposition)
        val resolvedUrls = snapshotFlow { relayUrls }
            .first { it.isNotEmpty() }

        val alreadyLoaded = when (replyKind) {
            1 -> kind1RepliesViewModel.uiState.value.note?.id == note.id && kind1RepliesViewModel.uiState.value.replies.isNotEmpty()
            else -> threadRepliesViewModel.uiState.value.note?.id == note.id && threadRepliesViewModel.uiState.value.replies.isNotEmpty()
        }
        android.util.Log.d(
            "ThreadView",
            "note=${note.id.take(8)} replyKind=$replyKind relays=${resolvedUrls.size} alreadyLoaded=$alreadyLoaded relayUrls=${
                resolvedUrls.take(3)
            }"
        )
        if (!alreadyLoaded) {
            // Clear the other kind's VM so stale replies from a previous thread don't linger
            when (replyKind) {
                1 -> threadRepliesViewModel.clearRepliesForNote(note.id)
                else -> kind1RepliesViewModel.clearRepliesForNote(note.id)
            }
            android.util.Log.d(
                "ThreadView",
                "LOADING replies: note=${note.id.take(8)} replyKind=$replyKind via ${if (replyKind == 1) "kind1RepliesVM" else "threadRepliesVM"}"
            )
            when (replyKind) {
                1 -> kind1RepliesViewModel.loadRepliesForNote(note, resolvedUrls)
                else -> threadRepliesViewModel.loadRepliesForNote(note, resolvedUrls)
            }
        }
    }

    // Compute unique relay URLs and counts across all replies (for relay filter bar).
    // Sorted by descending count so the most common relays appear first.
    val replyRelayData = remember(repliesState.replies) {
        val counts = mutableMapOf<String, Int>()
        repliesState.replies.forEach { reply ->
            reply.relayUrls.map { social.mycelium.android.utils.normalizeRelayUrl(it) }.toSet().forEach { url ->
                counts[url] = (counts[url] ?: 0) + 1
            }
        }
        counts.entries
            .sortedByDescending { it.value }
            .map { (url, count) -> url to count }
    }

    // Subscribe to kind-7/9735 counts for root + reply note IDs so reactions/zaps show on thread replies
    // Key on totalReplyCount (not list ref) to avoid recomputing when the same replies re-emit
    val threadNoteRelays = remember(repliesState.totalReplyCount, note.id) {
        val map = mutableMapOf<String, List<String>>()
        val rootRelays = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
        map[note.id] = rootRelays
        // Include quoted event IDs from the root note so their counts render
        note.quotedEventIds.forEach { qid ->
            if (qid !in map) {
                val cached = social.mycelium.android.repository.cache.QuotedNoteCache.getCached(qid)
                map[qid] = listOfNotNull(cached?.relayUrl).ifEmpty { rootRelays }
            }
        }
        repliesState.replies.forEach { reply ->
            map[reply.id] = reply.relayUrls
            // Extract quoted event IDs from reply content
            social.mycelium.android.utils.Nip19QuoteParser.extractQuotedEventIds(reply.content).forEach { qid ->
                if (qid !in map) {
                    val cached = social.mycelium.android.repository.cache.QuotedNoteCache.getCached(qid)
                    map[qid] = listOfNotNull(cached?.relayUrl).ifEmpty { reply.relayUrls }
                }
            }
        }
        map.toMap()
    }
    LaunchedEffect(threadNoteRelays) {
        social.mycelium.android.repository.social.NoteCountsRepository.setThreadNoteIdsOfInterest(threadNoteRelays)
    }
    DisposableEffect(Unit) {
        onDispose { social.mycelium.android.repository.social.NoteCountsRepository.setThreadNoteIdsOfInterest(emptyMap()) }
    }

    val noteCountsByNoteId by social.mycelium.android.repository.social.NoteCountsRepository.countsByNoteId.collectAsState()

    // ✅ ZAP MENU AWARENESS: Global state for zap menu closure (like feed cards)
    var shouldCloseZapMenus by remember { mutableStateOf(false) }
    var expandedZapMenuCommentId by remember { mutableStateOf<String?>(null) }

    var showWalletConnectDialog by remember { mutableStateOf(false) }
    var showCopyTextDialog by remember { mutableStateOf(false) }
    var copyTextContent by remember { mutableStateOf("") }
    val clipboardManager =
        remember { context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    // Kind-1111 reply dialog (topic thread reply)
    var showReplyDialog by remember { mutableStateOf(false) }
    var parentReplyId by remember { mutableStateOf<String?>(null) }
    val effectiveOnComment: (String) -> Unit = if (onOpenReplyCompose != null || onPublishThreadReply != null) {
        { id ->
            if (id == note.id) {
                if (onOpenReplyCompose != null) {
                    onOpenReplyCompose(note.id, note.author.id, null, null, null)
                } else {
                    parentReplyId = null
                    showReplyDialog = true
                }
            } else onComment(id)
        }
    } else {
        onComment
    }
    val effectiveOnCommentReply: (String) -> Unit = if (onOpenReplyCompose != null || onPublishThreadReply != null) {
        { replyId ->
            if (onOpenReplyCompose != null) {
                val parent = repliesState.replies.find { it.id == replyId }
                onOpenReplyCompose(note.id, note.author.id, replyId, parent?.author?.id, parent?.toNote())
            } else {
                parentReplyId = replyId
                showReplyDialog = true
            }
        }
    } else {
        onCommentReply
    }
    val effectiveOnZap: (String, Long) -> Unit = if (onSendZap != null) {
        { nId, amount ->
            if (nId == note.id) onSendZap(note, amount)
            else repliesState.replies.find { it.id == nId }?.toNote()?.let { onSendZap(it, amount) }
        }
    } else {
        onZap
    }

    // ✅ ZAP MENU AWARENESS: Close zap menus when scrolling starts (like feed cards)
    var wasScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !wasScrolling) {
            // Scroll just started - close zap menus immediately
            shouldCloseZapMenus = true
            expandedZapMenuCommentId = null
            kotlinx.coroutines.delay(100)
            shouldCloseZapMenus = false
        }
        wasScrolling = listState.isScrollInProgress
    }

    // Predictive back: from sub-thread pop one level; from full thread exit screen
    BackHandler(enabled = rootReplyIdStack.isNotEmpty()) {
        val depth = rootReplyIdStack.size
        rootReplyIdStack = rootReplyIdStack.dropLast(1)
        // Restore scroll position from before the drill-down
        val saved = savedScrollByDepth.remove(depth - 1)
        if (saved != null) {
            scope.launch { listState.scrollToItem(saved.first, saved.second) }
        }
    }
    BackHandler(enabled = rootReplyIdStack.isEmpty()) {
        onBackClick()
    }

    // Persistent highlight: stays visible until user leaves the thread view entirely.
    // Set once when we scroll to the target reply; never auto-cleared.
    var highlightedReplyId by remember { mutableStateOf<String?>(null) }
    var highlightedPathIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var haveScrolledToHighlight by remember(highlightReplyId) { mutableStateOf(false) }

    // When opened from notification / reply tap with highlightReplyId, expand path to
    // that reply, drill into sub-threads if needed, and scroll to it.
    // Triggers a targeted fetch for the specific reply + ancestors to avoid relying
    // solely on generic deep-fetch rounds (which may not discover deeply nested replies).
    LaunchedEffect(highlightReplyId) {
        if (highlightReplyId == null) return@LaunchedEffect
        android.util.Log.d("ThreadHighlight", "Attempting to scroll to reply $highlightReplyId")

        // Proactively fetch the target reply and its ancestor chain.
        // This runs concurrently with the polling loop below — whichever path
        // makes the reply appear in the threaded list first wins.
        if (replyKind == 1) {
            kind1RepliesViewModel.fetchSpecificReply(note.id, highlightReplyId!!, relayUrls)
        }

        // Wait for replies to load (retry up to 12s — extra 2s for targeted fetch latency)
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            val threaded = if (repliesState.threadedReplies.isNotEmpty()) {
                repliesState.threadedReplies
            } else {
                repliesState.replies.map { ThreadedReply(reply = it, children = emptyList(), level = 0) }
            }
            val path = findPathToReplyId(threaded, highlightReplyId!!)
            if (path != null) {
                android.util.Log.d("ThreadHighlight", "Found path to reply (${path.size} ancestors)")
                // Expand all ancestors so they're not collapsed
                path.forEach { id -> commentStates[id] = CommentState(isCollapsed = false, isExpanded = true) }

                // If reply is deeply nested, drill into sub-threads to reach it
                val drillStack = computeDrillDownStack(threaded, highlightReplyId!!)
                if (drillStack.isNotEmpty()) {
                    android.util.Log.d("ThreadHighlight", "Drilling into sub-thread (stack=${drillStack.size})")
                    rootReplyIdStack = drillStack
                }

                // Brief pause for layout to settle after drill-down + expansion
                kotlinx.coroutines.delay(400)

                // Compute the display list after drill-down
                val activeThreaded = if (repliesState.threadedReplies.isNotEmpty()) {
                    repliesState.threadedReplies
                } else {
                    repliesState.replies.map { ThreadedReply(reply = it, children = emptyList(), level = 0) }
                }
                val activeRoot = rootReplyIdStack.lastOrNull()
                val activeDisplayList = when {
                    activeRoot != null -> subtreeWithStructure(activeThreaded, activeRoot) ?: activeThreaded
                    else -> activeThreaded
                }
                // The displayList only has top-level items; nested replies are
                // rendered inside each ThreadedReplyCard. Use findContainingDisplayIndex
                // to locate the top-level ancestor that contains our target.
                val listIndex = findContainingDisplayIndex(activeDisplayList, highlightReplyId!!)
                if (listIndex >= 0) {
                    // LazyColumn: item(main_note)=0, item(replies_section)=1, then itemsIndexed(displayList)
                    // When in sub-thread mode, item(back_to_subthread)=2 shifts indices by 1
                    val headerItems = if (activeRoot != null) 3 else 2
                    val scrollIndex = headerItems + listIndex
                    android.util.Log.d("ThreadHighlight", "Scrolling to index $scrollIndex (listIndex=$listIndex, headers=$headerItems)")
                    listState.animateScrollToItem(scrollIndex)
                    highlightedReplyId = highlightReplyId
                    highlightedPathIds = path.toSet()
                    haveScrolledToHighlight = true

                    // Secondary scroll: the LazyColumn may compose new items
                    // after the first scroll, shifting indices. Retry once
                    // after a brief layout settle.
                    kotlinx.coroutines.delay(300)
                    val retryIndex = findContainingDisplayIndex(activeDisplayList, highlightReplyId!!)
                    if (retryIndex >= 0) {
                        val retryScroll = headerItems + retryIndex
                        if (retryScroll != scrollIndex || !listState.isScrollInProgress) {
                            android.util.Log.d("ThreadHighlight", "Secondary scroll to $retryScroll")
                            listState.animateScrollToItem(retryScroll)
                        }
                    }
                    break
                } else {
                    android.util.Log.d("ThreadHighlight", "Reply found in tree but not in display list (activeRoot=$activeRoot, displayListSize=${activeDisplayList.size})")
                }
            }
            // Reply not in tree yet — wait for replies to load and retry
            kotlinx.coroutines.delay(500)
        }
        if (!haveScrolledToHighlight) {
            android.util.Log.w("ThreadHighlight", "Failed to scroll to reply $highlightReplyId after 12s")
        }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AdaptiveHeader(
                title = "thread",
                showBackArrow = true,
                onBackClick = onBackClick,
                onLoginClick = onLoginClick,
                onProfileClick = onHeaderProfileClick,
                onAccountsClick = onHeaderAccountsClick,
                onQrCodeClick = onHeaderQrCodeClick,
                onSettingsClick = onHeaderSettingsClick,
                scrollBehavior = scrollBehavior,
                isGuest = isGuest,
                userDisplayName = userDisplayName,
                userAvatarUrl = userAvatarUrl
            )
        },
        floatingActionButton = {
            val replyItems = buildList {
                if (onOpenReplyCompose != null || onPublishThreadReply != null) {
                    add(
                        social.mycelium.android.ui.components.note.FabMenuItem(
                            label = "Reply",
                            icon = Icons.Outlined.Reply,
                            onClick = {
                                if (onOpenReplyCompose != null) {
                                    onOpenReplyCompose(note.id, note.author.id, null, null, null)
                                } else {
                                    parentReplyId = null
                                    showReplyDialog = true
                                }
                            }
                        ))
                }
            }
            // Show ThreadFab when there are reply actions OR when there are replies to navigate
            if (replyItems.isNotEmpty() || repliesState.replies.isNotEmpty()) {
                val hasNestedReplies = repliesState.threadedReplies.any { it.children.isNotEmpty() }
                social.mycelium.android.ui.components.note.ThreadFab(
                    listState = listState,
                    replyItems = replyItems,
                    firstReplyIndex = 2,
                    totalItems = 2 + repliesState.totalReplyCount,
                    showRootOnly = showRootOnly,
                    onToggleRootOnly = { showRootOnly = !showRootOnly },
                    rootOnlyAvailable = hasNestedReplies && currentRootReplyId == null,
                    isNewestFirst = isNewestFirst,
                    onToggleSortOrder = {
                        isNewestFirst = !isNewestFirst
                        when (replyKind) {
                            1 -> kind1RepliesViewModel.setSortOrder(
                                if (isNewestFirst) Kind1ReplySortOrder.REVERSE_CHRONOLOGICAL
                                else Kind1ReplySortOrder.CHRONOLOGICAL
                            )

                            else -> threadRepliesViewModel.setSortOrder(
                                if (isNewestFirst) ReplySortOrder.REVERSE_CHRONOLOGICAL
                                else ReplySortOrder.CHRONOLOGICAL
                            )
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                scope.launch {
                    if (replyKind == 1) {
                        // TODO: Kind1RepliesViewModel pending support
                    } else {
                        threadRepliesViewModel.refreshReplies(relayUrls)
                    }
                    delay(300)
                    isRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                state = listState,
                modifier = modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp)
            ) {
                // Main note card - pull to refresh loads pending replies
                item(key = "main_note") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        NoteCard(
                            note = note,
                            onLike = onLike,
                            onShare = onShare,
                            onComment = effectiveOnComment,
                            onReact = onReact,
                            onBoost = onBoost,
                            onQuote = onQuote,
                            onFork = onFork,
                            onProfileClick = onProfileClick,
                            onNoteClick = { clickedNote ->
                                // Prevent opening a duplicate thread of the current root note
                                if (clickedNote.id != note.id) onNoteClick(clickedNote)
                            },
                            onImageTap = onImageTap,
                            onOpenImageViewer = onOpenImageViewer,
                            onVideoClick = onVideoClick,
                            onCustomZapSend = onCustomZapSend,
                            onZap = effectiveOnZap,
                            isZapInProgress = note.id in zapInProgressNoteIds,
                            isZapped = note.id in zappedNoteIds || social.mycelium.android.repository.social.NoteCountsRepository.isOwnZap(
                                note.id
                            ),
                            isBoosted = note.id in boostedNoteIds || (note.originalNoteId != null && note.originalNoteId in boostedNoteIds) || social.mycelium.android.repository.social.NoteCountsRepository.isOwnBoost(
                                note.originalNoteId ?: note.id
                            ),
                            onVote = onVote,
                            ownVoteValue = social.mycelium.android.repository.social.VoteRepository.getOwnVote(note.id),
                            voteScore = social.mycelium.android.repository.social.VoteRepository.getScore(note.id),
                            myZappedAmount = myZappedAmountByNoteId[note.id],
                            overrideReplyCount = repliesState.totalReplyCount,
                            overrideZapCount = noteCountsByNoteId[note.id]?.zapCount,
                            overrideZapTotalSats = noteCountsByNoteId[note.id]?.zapTotalSats,
                            overrideReactions = noteCountsByNoteId[note.id]?.reactions,
                            overrideReactionAuthors = noteCountsByNoteId[note.id]?.reactionAuthors,
                            overrideZapAuthors = noteCountsByNoteId[note.id]?.zapAuthors,
                            overrideZapAmountByAuthor = noteCountsByNoteId[note.id]?.zapAmountByAuthor,
                            overrideCustomEmojiUrls = noteCountsByNoteId[note.id]?.customEmojiUrls,
                            onRelayClick = onRelayNavigate,
                            onNavigateToRelayList = onNavigateToRelayList,
                            shouldCloseZapMenus = shouldCloseZapMenus,
                            accountNpub = accountNpub,
                            onDelete = if (onDeleteNote != null && currentUserHex != null && social.mycelium.android.utils.normalizeAuthorIdForCache(
                                    note.author.id
                                ) == currentUserHex
                            ) onDeleteNote else null,
                            expandLinkPreviewInThread = true,
                            showHashtagsSection = false,
                            initialMediaPage = mediaPageForNote(note.id),
                            onMediaPageChanged = { page -> onMediaPageChanged(note.id, page) },
                            actionRowSchema = if (replyKind == 1) social.mycelium.android.ui.components.note.ActionRowSchema.KIND1_FEED
                            else social.mycelium.android.ui.components.note.ActionRowSchema.KIND11_FEED,
                            onSeeAllReactions = { onSeeAllReactions(note.id) },
                            compactMedia = compactMedia,
                            onPollVote = onPollVote,
                            myPubkeyHex = myPubkeyHex,
                            onDeleteReaction = onDeleteReaction,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Modern divider
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Threaded replies section with loading state
                item(key = "replies_section") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(4.dp))

                        // "x new replies" banner (tappable to load pending replies)
                        val newRootCount = repliesState.newRepliesByParent[note.id] ?: 0
                        val totalNewCount = repliesState.newReplyCount
                        if (totalNewCount > 0) {
                            Surface(
                                onClick = {
                                    if (replyKind == 1) {
                                        // TODO: Kind1RepliesViewModel pending support
                                    } else {
                                        threadRepliesViewModel.refreshReplies(relayUrls)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "$totalNewCount new ${if (totalNewCount == 1) "reply" else "replies"}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        // Reply count header (no loader; fallback message for no replies only)
                        if (repliesState.totalReplyCount > 0 || totalNewCount > 0) {
                            val filterActive = selectedRelayFilters.isNotEmpty()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left: reply count text
                                val directCount = repliesState.replies.size
                                val totalCount = repliesState.totalReplyCount
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = when {
                                            totalCount <= 1 -> if (totalCount == 1) "1 reply" else ""
                                            directCount == totalCount -> "$totalCount replies"
                                            else -> "$directCount replies, $totalCount total"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (totalNewCount > 0) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "($totalNewCount new)",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                // Middle: relay filter orbs (scrollable, fills available space)
                                // Debug-only: relay filter is experimental; hidden in release builds
                                if (social.mycelium.android.BuildConfig.RELAY_FILTER_DEV_MODE &&
                                    replyRelayData.size > 1 && repliesState.replies.isNotEmpty()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Clear button
                                        if (filterActive) {
                                            Box(
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.errorContainer)
                                                    .clickable { selectedRelayFilters = emptySet() },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Clear relay filter",
                                                    modifier = Modifier.size(12.dp),
                                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                            }
                                        }
                                        replyRelayData.forEach { (relayUrl, _) ->
                                            val isSelected = relayUrl in selectedRelayFilters
                                            val orbAlpha = when {
                                                !filterActive -> 1f
                                                isSelected -> 1f
                                                else -> 0.35f
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .then(
                                                        if (isSelected) Modifier.border(
                                                            2.dp,
                                                            MaterialTheme.colorScheme.primary,
                                                            CircleShape
                                                        )
                                                        else Modifier
                                                    )
                                                    .clip(CircleShape)
                                                    .clickable {
                                                        selectedRelayFilters = if (isSelected) {
                                                            selectedRelayFilters - relayUrl
                                                        } else {
                                                            selectedRelayFilters + relayUrl
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                SingleRelayOrb(
                                                    relayUrl = relayUrl,
                                                    size = 22.dp,
                                                    modifier = Modifier.graphicsLayer { alpha = orbAlpha }
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // No relay data or feature gated — spacer to push sort button right
                                    Spacer(modifier = Modifier.weight(1f))
                                }

                                // Right: sort button
                                if (repliesState.totalReplyCount > 0) {
                                    var showSortMenu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(
                                            onClick = { showSortMenu = true },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Sort,
                                                contentDescription = "Sort",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = showSortMenu,
                                            onDismissRequest = { showSortMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Oldest first") },
                                                onClick = {
                                                    if (replyKind == 1) kind1RepliesViewModel.setSortOrder(
                                                        Kind1ReplySortOrder.CHRONOLOGICAL
                                                    )
                                                    else threadRepliesViewModel.setSortOrder(ReplySortOrder.CHRONOLOGICAL)
                                                    showSortMenu = false
                                                    scope.launch { listState.animateScrollToItem(1) }
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Newest first") },
                                                onClick = {
                                                    if (replyKind == 1) kind1RepliesViewModel.setSortOrder(
                                                        Kind1ReplySortOrder.REVERSE_CHRONOLOGICAL
                                                    )
                                                    else threadRepliesViewModel.setSortOrder(ReplySortOrder.REVERSE_CHRONOLOGICAL)
                                                    showSortMenu = false
                                                    scope.launch { listState.animateScrollToItem(1) }
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Most liked") },
                                                onClick = {
                                                    if (replyKind == 1) kind1RepliesViewModel.setSortOrder(
                                                        Kind1ReplySortOrder.MOST_LIKED
                                                    )
                                                    else threadRepliesViewModel.setSortOrder(ReplySortOrder.MOST_LIKED)
                                                    showSortMenu = false
                                                    scope.launch { listState.animateScrollToItem(1) }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Empty state: no replies (no loader)
                        if (repliesState.replies.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No replies yet. Be the first to reply!",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // Display replies — always use threaded path (wraps flat replies as level-0 when threadedReplies empty).
                // Avoids structural flip between flat/threaded itemsIndexed which corrupts LazyColumn draws.
                run {
                    val displayThreaded = if (repliesState.threadedReplies.isNotEmpty()) {
                        repliesState.threadedReplies
                    } else {
                        repliesState.replies.map { ThreadedReply(reply = it, children = emptyList(), level = 0) }
                    }

                    // Compute descendant counts per root reply for root-only badges
                    fun countDescendants(replies: List<ThreadedReply>): Int =
                        replies.sumOf { 1 + countDescendants(it.children) }

                    val descendantCountByReplyId: Map<String, Int> =
                        if (showRootOnly) {
                            displayThreaded.filter { it.level == 0 && it.children.isNotEmpty() }
                                .associate { it.reply.id to countDescendants(it.children) }
                        } else emptyMap()
                    val preFilterList = when {
                        currentRootReplyId != null -> subtreeWithStructure(displayThreaded, currentRootReplyId!!)
                            ?: displayThreaded

                        showRootOnly -> displayThreaded.filter { it.level == 0 }
                        else -> displayThreaded
                    }
                    val displayList = filterThreadedByRelays(preFilterList, selectedRelayFilters)
                    if (currentRootReplyId != null) {
                        item(key = "back_to_subthread") {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        rootReplyIdStack = rootReplyIdStack.dropLast(1)
                                    },
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowBack,
                                        contentDescription = "Back",
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (rootReplyIdStack.size == 1) "Back to full thread" else "Back",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    itemsIndexed(
                        items = displayList,
                        key = { _, it -> logicalReplyKey(it.reply) }
                    ) { index, threadedReply ->
                        ThreadedReplyCard(
                            threadedReply = threadedReply,
                            isLastRootReply = index == displayList.size - 1,
                            rootAuthorId = note.author.id,
                            parentAuthorId = note.author.id,
                            replyKind = replyKind,
                            commentStates = commentStates,
                            noteCountsByNoteId = noteCountsByNoteId,
                            highlightedReplyId = highlightedReplyId,
                            highlightedPathIds = highlightedPathIds,
                            onLike = { replyId ->
                                if (replyKind == 1) kind1RepliesViewModel.likeReply(replyId)
                                else threadRepliesViewModel.likeReply(replyId)
                            },
                            onReply = effectiveOnCommentReply,
                            onProfileClick = onProfileClick,
                            onRelayClick = onRelayNavigate,
                            onNavigateToRelayList = onNavigateToRelayList,
                            shouldCloseZapMenus = shouldCloseZapMenus,
                            expandedZapMenuReplyId = expandedZapMenuCommentId,
                            onExpandZapMenu = { replyId ->
                                expandedZapMenuCommentId =
                                    if (expandedZapMenuCommentId == replyId) null else replyId
                            },
                            onZap = effectiveOnZap,
                            onZapSettings = { onNavigateToZapSettings() },
                            expandedControlsReplyId = expandedControlsReplyId,
                            onExpandedControlsReplyChange = onExpandedControlsReplyChange,
                            onReadMoreReplies = { replyId ->
                                // Save current scroll position before drilling down
                                savedScrollByDepth[rootReplyIdStack.size] = Pair(
                                    listState.firstVisibleItemIndex,
                                    listState.firstVisibleItemScrollOffset
                                )
                                rootReplyIdStack = rootReplyIdStack + replyId
                                scope.launch { listState.scrollToItem(0) }
                            },
                            onNoteClick = { clickedNote -> if (clickedNote.id != note.id) onNoteClick(clickedNote) },
                            onReact = onReact,
                            onVote = onVote,
                            onImageTap = { urls, idx -> onImageTap(note, urls, idx) },
                            onVideoClick = onVideoClick,
                            collapsedChildCount = if (showRootOnly) descendantCountByReplyId[threadedReply.reply.id] else null,
                            isScrolling = listState.isScrollInProgress,
                            compactMedia = compactMedia,
                            accountNpub = accountNpub,
                            onDeleteReaction = onDeleteReaction,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Per-reply "x new replies" inline indicator
                        val pendingForThisReply = repliesState.newRepliesByParent[threadedReply.reply.id] ?: 0
                        if (pendingForThisReply > 0) {
                            Surface(
                                onClick = {
                                    if (replyKind != 1) {
                                        threadRepliesViewModel.applyPendingRepliesForParent(threadedReply.reply.id)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = (16 + (threadedReply.level * 12)).dp,
                                        end = 16.dp,
                                        top = 2.dp,
                                        bottom = 2.dp
                                    ),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SubdirectoryArrowRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "$pendingForThisReply new ${if (pendingForThisReply == 1) "reply" else "replies"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }

                        if (index < displayList.size - 1) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    // ── Inline draft reply placeholders ──
                    if (threadDrafts.isNotEmpty()) {
                        val replyLevelMap = displayList.associate { it.reply.id to it.level }
                        items(
                            items = threadDrafts,
                            key = { "draft_${it.id}" }
                        ) { draft ->
                            val draftLevel = when {
                                draft.parentId != null && replyLevelMap.containsKey(draft.parentId) ->
                                    (replyLevelMap[draft.parentId] ?: 0) + 1

                                draft.parentId == note.id || draft.rootId == note.id -> 0
                                else -> 0
                            }
                            social.mycelium.android.ui.components.note.DraftReplyCard(
                                draft = draft,
                                level = draftLevel,
                                onEditClick = onEditDraft,
                                onDeleteClick = onDeleteDraft
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        } // PullToRefreshBox
    }

    // Zap configuration: now navigates to zap_settings page via onNavigateToZapSettings

    if (showWalletConnectDialog) {
        social.mycelium.android.ui.components.zap.WalletConnectDialog(
            onDismiss = { showWalletConnectDialog = false }
        )
    }

    // Copy text dialog: raw note body in a popup; user can select/copy part or all
    if (showCopyTextDialog) {
        val scrollState = rememberScrollState()
        AlertDialog(
            onDismissRequest = { showCopyTextDialog = false },
            title = { Text("Note text") },
            text = {
                Box(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(scrollState)
                ) {
                    SelectionContainer {
                        Text(
                            text = copyTextContent.ifEmpty { " " },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboardManager.setPrimaryClip(
                            android.content.ClipData.newPlainText("note", copyTextContent)
                        )
                        showCopyTextDialog = false
                    }
                ) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCopyTextDialog = false }) {
                    Text("Dismiss")
                }
            }
        )
    }

    // Kind-1111 reply dialog: publish thread reply (topic thread); show root note so author remembers context
    if (showReplyDialog && onPublishThreadReply != null) {
        var replyContent by remember { mutableStateOf("") }
        val parentPubkey = parentReplyId?.let { pid ->
            repliesState.replies.find { it.id == pid }?.author?.id
        }
        AlertDialog(
            onDismissRequest = { showReplyDialog = false },
            title = { Text(if (parentReplyId == null) "Reply to topic" else "Reply") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "Replying to",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ProfilePicture(
                                    author = note.author,
                                    size = 32.dp,
                                    onClick = { }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        social.mycelium.android.ui.components.common.Nip05Icon(
                                            pubkeyHex = social.mycelium.android.utils.normalizeAuthorIdForCache(note.author.id)
                                        )
                                        Text(
                                            text = note.author.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = note.content.take(200)
                                            .let { if (note.content.length > 200) "$it…" else it },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = replyContent,
                        onValueChange = { replyContent = it },
                        label = { Text("Your reply") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (replyKind == 1111 && currentUserAuthor != null) {
                            threadRepliesViewModel.addOptimisticReply(
                                rootId = note.id,
                                parentId = parentReplyId,
                                content = replyContent,
                                currentUserAuthor = currentUserAuthor
                            )
                        }
                        val err =
                            onPublishThreadReply(note.id, note.author.id, parentReplyId, parentPubkey, replyContent)
                        if (err != null) {
                            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            showReplyDialog = false
                            replyContent = ""
                            android.widget.Toast.makeText(context, "Reply sent", android.widget.Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                ) {
                    Text("Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ModernCommentThreadItem(
    commentThread: CommentThread,
    onLike: (String) -> Unit,
    onReply: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onZap: (String, Long) -> Unit = { _, _ -> },
    onCustomZap: (String) -> Unit = {},
    onZapSettings: () -> Unit = {},
    depth: Int,
    commentStates: MutableMap<String, CommentState>,
    expandedControlsCommentId: String?,
    onExpandControls: (String) -> Unit,
    // ✅ ZAP MENU AWARENESS: Add zap menu state parameters
    shouldCloseZapMenus: Boolean = false,
    expandedZapMenuCommentId: String? = null,
    onExpandZapMenu: (String) -> Unit = {},
    isLastComment: Boolean = false,
    modifier: Modifier = Modifier
) {
    val commentId = commentThread.comment.id
    val state = commentStates.getOrPut(commentId) { CommentState() }
    val isControlsExpanded = expandedControlsCommentId == commentId

    // ✅ ULTRA COMPACT INDENTATION: Very tight spacing for child comments
    val indentPadding = (depth * 1.5).dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min) // Critical for proper vertical lines
            .padding(start = indentPadding)
    ) {
        // Vertical thread line - like original but cleaner
        if (depth > 0) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight() // Full height for proper thread navigation
                    .background(ThreadLineColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Comment content - no individual animation to prevent staggering
        Column(
            modifier = Modifier
                .weight(1f)
        ) {
            ModernCommentCard(
                comment = commentThread.comment,
                onLike = onLike,
                onReply = onReply,
                onProfileClick = onProfileClick,
                onZap = onZap,
                onCustomZap = onCustomZap,
                onZapSettings = onZapSettings,
                isControlsExpanded = isControlsExpanded,
                onToggleControls = { onExpandControls(commentId) },
                isCollapsed = state.isCollapsed,
                onCollapsedChange = { collapsed ->
                    commentStates[commentId] = state.copy(
                        isCollapsed = collapsed,
                        isExpanded = !collapsed
                    )
                },
                // ✅ ZAP MENU AWARENESS: Pass zap menu state to ModernCommentCard
                shouldCloseZapMenus = shouldCloseZapMenus,
                expandedZapMenuCommentId = expandedZapMenuCommentId,
                onExpandZapMenu = { onExpandZapMenu(commentId) },
                modifier = Modifier.fillMaxWidth()
            )

            // Replies - all animated together
            if (state.isExpanded && !state.isCollapsed && commentThread.replies.isNotEmpty()) {
                commentThread.replies.forEachIndexed { index, reply ->
                    ModernCommentThreadItem(
                        commentThread = reply,
                        onLike = onLike,
                        onReply = onReply,
                        onProfileClick = onProfileClick,
                        onZap = onZap,
                        onCustomZap = onCustomZap,
                        onZapSettings = onZapSettings,
                        depth = depth + 1,
                        commentStates = commentStates,
                        expandedControlsCommentId = expandedControlsCommentId,
                        onExpandControls = onExpandControls,
                        // ✅ ZAP MENU AWARENESS: Pass zap menu state to nested replies
                        shouldCloseZapMenus = shouldCloseZapMenus,
                        expandedZapMenuCommentId = expandedZapMenuCommentId,
                        onExpandZapMenu = onExpandZapMenu,
                        isLastComment = index == commentThread.replies.size - 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Minimal separator for top-level comments (but not the last one)
            if (depth == 0 && !isLastComment) {
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModernCommentCard(
    comment: Comment,
    onLike: (String) -> Unit,
    onReply: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onZap: (String, Long) -> Unit = { _, _ -> },
    onCustomZap: (String) -> Unit = {},
    onZapSettings: () -> Unit = {},
    isControlsExpanded: Boolean,
    onToggleControls: () -> Unit,
    isCollapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    // ✅ ZAP MENU AWARENESS: Add zap menu state parameters
    shouldCloseZapMenus: Boolean = false,
    expandedZapMenuCommentId: String? = null,
    onExpandZapMenu: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val commentId = comment.id
    val isZapMenuExpanded = expandedZapMenuCommentId == commentId

    // ✅ ZAP MENU AWARENESS: Close zap menu when shouldCloseZapMenus is true (like feed cards)
    LaunchedEffect(shouldCloseZapMenus) {
        if (shouldCloseZapMenus && isZapMenuExpanded) {
            onExpandZapMenu(commentId) // This will close the menu
        }
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isCollapsed) {
                        onCollapsedChange(false)
                    } else {
                        onToggleControls()
                    }
                },
                onLongClick = {
                    if (!isCollapsed) {
                        onCollapsedChange(true)
                    }
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isCollapsed) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RectangleShape, // Sharp, edge-to-edge
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        if (!isCollapsed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Modern author info with better spacing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfilePicture(
                        author = comment.author,
                        size = 36.dp,
                        onClick = { onProfileClick(comment.author.id) }
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            social.mycelium.android.ui.components.common.Nip05Icon(
                                pubkeyHex = social.mycelium.android.utils.normalizeAuthorIdForCache(comment.author.id)
                            )
                            Text(
                                text = comment.author.displayName,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = formatTimestamp(comment.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Content with better typography
                Text(
                    text = comment.content,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp
                )

                // Embedded media parsed from comment content
                val commentMediaUrls = remember(comment.content) {
                    social.mycelium.android.utils.UrlDetector.findUrls(comment.content)
                        .filter {
                            social.mycelium.android.utils.UrlDetector.isImageUrl(it) || social.mycelium.android.utils.UrlDetector.isVideoUrl(
                                it
                            )
                        }
                        .distinct()
                }
                if (commentMediaUrls.isNotEmpty()) {
                    val commentImageUrls =
                        commentMediaUrls.filter { social.mycelium.android.utils.UrlDetector.isImageUrl(it) }
                    val commentVideoUrls =
                        commentMediaUrls.filter { social.mycelium.android.utils.UrlDetector.isVideoUrl(it) }
                    Spacer(modifier = Modifier.height(6.dp))
                    if (commentImageUrls.size == 1 && commentVideoUrls.isEmpty()) {
                        val commentMediaRatio = remember(commentImageUrls[0]) {
                            social.mycelium.android.utils.MediaAspectRatioCache.get(commentImageUrls[0])
                                ?: (16f / 9f)
                        }
                        AsyncImage(
                            model = commentImageUrls[0],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(commentMediaRatio.coerceIn(0.5f, 2.5f))
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            commentImageUrls.take(3).forEach { url ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            }
                            commentVideoUrls.take(2).forEach { _ ->
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Video",
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Optimized controls - only show/hide, no complex animations
                if (isControlsExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            )
                            .padding(vertical = 4.dp, horizontal = 4.dp)
                    ) {

                        // ✅ COMPACT CONTROLS: Right-aligned with consistent spacing
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompactModernButton(
                                icon = Icons.Outlined.ArrowUpward,
                                contentDescription = "Upvote",
                                isActive = comment.isLiked,
                                onClick = { onLike(comment.id) }
                            )

                            CompactModernButton(
                                icon = Icons.Outlined.ArrowDownward,
                                contentDescription = "Downvote",
                                isActive = false,
                                onClick = { /* Handle downvote */ }
                            )

                            CompactModernButton(
                                icon = Icons.Outlined.Bookmark,
                                contentDescription = "Bookmark",
                                isActive = false,
                                onClick = { /* Handle bookmark */ }
                            )

                            CompactModernButton(
                                icon = Icons.Outlined.Reply,
                                contentDescription = "Reply",
                                isActive = false,
                                onClick = { onReply(comment.id) }
                            )

                            // Zap button - opens ZapDrawer popup
                            CompactModernButton(
                                icon = Icons.Filled.Bolt,
                                contentDescription = "Zap",
                                isActive = false,
                                onClick = { onExpandZapMenu(commentId) }
                            )

                            // More options
                            var showMenu by remember { mutableStateOf(false) }
                            Box {
                                CompactModernButton(
                                    icon = Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    isActive = false,
                                    onClick = { showMenu = true }
                                )

                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Share") },
                                        onClick = { showMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Report") },
                                        onClick = { showMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Report, contentDescription = null) }
                                    )
                                }
                            }
                        }
                    }

                    // Zap drawer — ModalBottomSheet for smooth animate-in/out
                    if (isZapMenuExpanded) {
                        social.mycelium.android.ui.components.zap.ZapBottomSheet(
                            onDismiss = { onExpandZapMenu(commentId) },
                            onZap = { amount ->
                                onExpandZapMenu(commentId)
                                onZap(comment.id, amount)
                            },
                            onCustomZapSend = { amount, zapType, message ->
                                onExpandZapMenu(commentId)
                                onCustomZap(comment.id)
                            },
                            onSettingsClick = {
                                onExpandZapMenu(commentId)
                                onZapSettings()
                            }
                        )
                    }
                }
            }
        } else {
            // Compact collapsed state
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Expand thread",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                social.mycelium.android.ui.components.common.Nip05Icon(
                    pubkeyHex = social.mycelium.android.utils.normalizeAuthorIdForCache(comment.author.id),
                    size = 12.dp
                )
                Text(
                    text = comment.author.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onProfileClick(comment.author.id) }
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "· tap to expand",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ModernActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ✅ CONSISTENT: Match main card ActionButton pattern
    Box(
        modifier = modifier
            .height(48.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp) // ✅ CONSISTENT: Match main card icon size
        )
    }
}

@Composable
private fun CompactModernButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(36.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint
                ?: if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun formatReplyTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> "${diff / 86400_000}d ago"
    }
}

/**
 * Extracted reply header: profile picture, OP badge, display name, reaction/zap counts,
 * timestamp, 3-dot menu, relay orbs.
 * Splits ~130 lines from ThreadedReplyCard to reduce 9MB inner lambda JIT.
 */
@Composable
private fun ReplyHeader(
    reply: ThreadReply,
    displayAuthor: Author,
    rootAuthorId: String?,
    noteCountsByNoteId: Map<String, social.mycelium.android.repository.social.NoteCounts>,
    onProfileClick: (String) -> Unit,
    onRelayClick: (String) -> Unit,
    onNavigateToRelayList: ((List<String>) -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        ProfilePicture(
            author = displayAuthor,
            size = 28.dp,
            onClick = { onProfileClick(reply.author.id) }
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    val isOp =
                        rootAuthorId != null && social.mycelium.android.utils.normalizeAuthorIdForCache(reply.author.id) == social.mycelium.android.utils.normalizeAuthorIdForCache(
                            rootAuthorId
                        )
                    val replyAuthorPubkey = social.mycelium.android.utils.normalizeAuthorIdForCache(reply.author.id)
                    if (isOp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = Color(0xFF8E30EB),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    social.mycelium.android.ui.components.common.Nip05Icon(
                                        pubkeyHex = replyAuthorPubkey,
                                        size = 14.dp,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                    Text(
                                        text = displayAuthor.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "OP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        social.mycelium.android.ui.components.common.Nip05Icon(
                            pubkeyHex = replyAuthorPubkey
                        )
                        Text(
                            text = displayAuthor.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                val headerCounts = noteCountsByNoteId[reply.id]
                val hcReactions = headerCounts?.reactions ?: emptyList()
                val hcZapSats = headerCounts?.zapTotalSats ?: 0L
                val hcEmojiUrls = headerCounts?.customEmojiUrls ?: emptyMap()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (hcReactions.isNotEmpty()) {
                        val uniqueEmojis = hcReactions.distinct().take(3)
                        uniqueEmojis.forEach { emoji ->
                            social.mycelium.android.ui.components.emoji.ReactionEmoji(
                                emoji = emoji,
                                customEmojiUrls = hcEmojiUrls,
                                fontSize = 12.sp,
                                imageSize = 14.dp
                            )
                        }
                        if (hcReactions.size > 1) {
                            Text(
                                text = "${hcReactions.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE57373)
                            )
                        }
                    }
                    if (hcZapSats > 0) {
                        Text(
                            text = "⚡${social.mycelium.android.utils.ZapUtils.formatZapAmount(hcZapSats)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFD700)
                        )
                    }
                    Text(
                        text = formatReplyTimestamp(reply.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    // 3-dot menu in header
                    var showMore by remember { mutableStateOf(false) }
                    Box {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More",
                            modifier = Modifier
                                .size(18.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) { showMore = true },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DropdownMenu(
                            expanded = showMore,
                            onDismissRequest = { showMore = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = { showMore = false },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Report") },
                                onClick = { showMore = false },
                                leadingIcon = { Icon(Icons.Default.Report, contentDescription = null) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Extracted reply content body: rich text blocks, inline media, URL previews, quoted notes.
 * Splits ~200 lines from ThreadedReplyCard to reduce 18MB inner lambda JIT.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun ReplyContentBody(
    reply: ThreadReply,
    onProfileClick: (String) -> Unit,
    onNoteClick: (Note) -> Unit,
    onImageTap: (List<String>, Int) -> Unit,
    onVideoClick: (List<String>, Int) -> Unit,
    onToggleControls: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    noteCountsByNoteId: Map<String, social.mycelium.android.repository.social.NoteCounts> = emptyMap(),
    compactMedia: Boolean = false,
    myPubkey: String? = null,
    onPollVote: ((String, String, Set<String>, String?) -> Unit)? = null,
) {
    val profileCache = social.mycelium.android.repository.cache.ProfileMetadataCache.getInstance()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val linkStyle = androidx.compose.ui.text.SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
    )
    val replyMediaUrls = remember(reply.content) {
        social.mycelium.android.utils.UrlDetector.findUrls(reply.content)
            .filter {
                social.mycelium.android.utils.UrlDetector.isImageUrl(it) || social.mycelium.android.utils.UrlDetector.isVideoUrl(
                    it
                )
            }
            .toSet()
    }
    val replyIsMarkdown = remember(reply.content) { social.mycelium.android.ui.components.note.isMarkdown(reply.content) }
    val replyMentionedPubkeys = remember(reply.content) {
        social.mycelium.android.utils.extractPubkeysFromContent(reply.content)
    }
    var replyMentionVersion by remember(reply.id) { androidx.compose.runtime.mutableIntStateOf(0) }
    val replyDiskCacheReady by profileCache.diskCacheRestored.collectAsState()
    if (replyMentionedPubkeys.isNotEmpty()) {
        LaunchedEffect(replyMentionedPubkeys) {
            val pubkeySet = replyMentionedPubkeys.toSet()
            val uncached = pubkeySet.filter { profileCache.getAuthor(it) == null }
            if (uncached.isNotEmpty()) {
                profileCache.requestProfiles(uncached, profileCache.getConfiguredRelayUrls())
            }
            val nowResolved = pubkeySet.count { profileCache.getAuthor(it) != null }
            val initiallyResolved = pubkeySet.size - uncached.size
            if (nowResolved > initiallyResolved) {
                replyMentionVersion++
            }
            profileCache.profileUpdated
                .filter { it in pubkeySet }
                .debounce(50)
                .collect { replyMentionVersion++ }
        }
        LaunchedEffect(replyMentionedPubkeys, replyDiskCacheReady) {
            if (!replyDiskCacheReady) return@LaunchedEffect
            val pubkeySet = replyMentionedPubkeys.toSet()
            val hasNewlyResolved = pubkeySet.any { pk ->
                val author = profileCache.getAuthor(pk)
                author != null && author.displayName != pk.take(8) + "..."
            }
            if (hasNewlyResolved) replyMentionVersion++
        }
    }
    val replyCacheKey = remember(reply.content, replyMediaUrls) {
        social.mycelium.android.utils.ContentBlockCache.key(
            reply.content,
            replyMediaUrls
        )
    }
    val replyContentBlocks by androidx.compose.runtime.produceState(
        initialValue = social.mycelium.android.utils.ContentBlockCache.get(replyCacheKey) ?: emptyList(),
        replyCacheKey, replyMentionVersion
    ) {
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            social.mycelium.android.utils.buildNoteContentWithInlinePreviews(
                reply.content,
                replyMediaUrls,
                emptyList(),
                linkStyle,
                profileCache,
                emptySet(),
                emptyMap()
            )
        }
        social.mycelium.android.utils.ContentBlockCache.put(replyCacheKey, result)
        value = result
    }

    replyContentBlocks.forEach { block ->
        when (block) {
            is social.mycelium.android.utils.NoteContentBlock.Content -> {
                val annotated = block.annotated
                if (annotated.isNotEmpty()) {
                    if (replyIsMarkdown) {
                        social.mycelium.android.ui.components.note.MarkdownNoteContent(
                            content = annotated.text,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                            onProfileClick = onProfileClick,
                            onNoteClick = { },
                            onUrlClick = { url -> uriHandler.openUri(url) }
                        )
                    } else {
                        social.mycelium.android.ui.components.note.ClickableNoteContent(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                            emojiUrls = block.emojiUrls,
                            onClick = { offset ->
                                val profile =
                                    annotated.getStringAnnotations(tag = "PROFILE", start = offset, end = offset)
                                        .firstOrNull()
                                val url = annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                    .firstOrNull()
                                when {
                                    profile != null -> onProfileClick(profile.item)
                                    url != null -> uriHandler.openUri(url.item)
                                    else -> onToggleControls()
                                }
                            },
                            onLongPress = onLongPress
                        )
                    }
                }
            }

            is social.mycelium.android.utils.NoteContentBlock.MediaGroup -> {
                val mediaList = block.urls.take(10)
                if (mediaList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    social.mycelium.android.ui.components.note.NoteMediaCarousel(
                        mediaList = mediaList,
                        allMediaUrls = mediaList,
                        groupStartIndex = 0,
                        initialMediaPage = 0,
                        isVisible = true,
                        mediaMeta = reply.mediaMeta,
                        compactMedia = compactMedia,
                        onMediaPageChanged = { },
                        onImageTap = onImageTap,
                        onOpenImageViewer = onImageTap,
                        onVideoClick = onVideoClick,
                    )
                }
            }

            is social.mycelium.android.utils.NoteContentBlock.Preview -> {
                social.mycelium.android.ui.components.preview.UrlPreviewCard(
                    previewInfo = block.previewInfo,
                    onUrlClick = { url -> uriHandler.openUri(url) },
                    onUrlLongClick = { }
                )
            }

            is social.mycelium.android.utils.NoteContentBlock.LiveEventReference -> {
                // NIP-53 live event embed — matches feed card style
                val liveRepo = remember { social.mycelium.android.repository.LiveActivityRepository.getInstance() }
                val activity =
                    remember(block.eventId) { liveRepo.allActivities.value.firstOrNull { it.id == block.eventId } }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            onNoteClick(
                                Note(
                                    id = block.eventId,
                                    author = social.mycelium.android.data.Author(
                                        id = block.author ?: "",
                                        username = "",
                                        displayName = "Live Event"
                                    ),
                                    content = "", timestamp = 0L,
                                    likes = 0, shares = 0, comments = 0,
                                    isLiked = false, hashtags = emptyList(),
                                    mediaUrls = emptyList(), isReply = false,
                                    relayUrl = block.relays.firstOrNull(),
                                    relayUrls = block.relays
                                )
                            )
                        },
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        0.5.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    ),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (activity?.status == social.mycelium.android.data.LiveActivityStatus.LIVE) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        androidx.compose.ui.graphics.Color(0xFFEF4444),
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.PlayCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activity?.title ?: "Live Event",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subtitle = when (activity?.status) {
                                social.mycelium.android.data.LiveActivityStatus.LIVE -> "LIVE" + (activity.currentParticipants?.let { " \u00B7 $it viewers" }
                                    ?: "")

                                social.mycelium.android.data.LiveActivityStatus.PLANNED -> "Planned"
                                social.mycelium.android.data.LiveActivityStatus.ENDED -> "Ended"
                                null -> "Live Event"
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (activity?.status == social.mycelium.android.data.LiveActivityStatus.LIVE)
                                    androidx.compose.ui.graphics.Color(0xFFEF4444)
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            is social.mycelium.android.utils.NoteContentBlock.EmojiPack -> {
                social.mycelium.android.ui.components.emoji.EmojiPackGrid(
                    author = block.author,
                    dTag = block.dTag,
                    relayHints = block.relayHints
                )
            }

            is social.mycelium.android.utils.NoteContentBlock.Article -> {
                social.mycelium.android.ui.components.note.EmbeddedArticlePreview(
                    author = block.author,
                    dTag = block.dTag,
                    relayHints = block.relayHints,
                    onNoteClick = onNoteClick
                )
            }

            is social.mycelium.android.utils.NoteContentBlock.QuotedNote -> {
                val qProfileCache = social.mycelium.android.repository.cache.ProfileMetadataCache.getInstance()
                val qLinkStyle = androidx.compose.ui.text.SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                )
                var qMeta by remember(block.eventId) {
                    mutableStateOf(
                        social.mycelium.android.repository.cache.QuotedNoteCache.getCached(
                            block.eventId
                        )
                    )
                }
                LaunchedEffect(block.eventId) {
                    if (qMeta == null) {
                        qMeta = social.mycelium.android.repository.cache.QuotedNoteCache.get(block.eventId)
                    }
                }
                val meta = qMeta
                if (meta != null) {
                    val qAuthor = remember(meta.authorId) { qProfileCache.resolveAuthor(meta.authorId) }
                    social.mycelium.android.ui.components.note.QuotedNoteContent(
                        parentNoteId = reply.id,
                        meta = meta,
                        quotedAuthor = qAuthor,
                        quotedCounts = noteCountsByNoteId[block.eventId],
                        linkStyle = qLinkStyle,
                        profileCache = qProfileCache,
                        isVisible = true,
                        onProfileClick = onProfileClick,
                        onNoteClick = onNoteClick,
                        onVideoClick = onVideoClick,
                        onOpenImageViewer = onImageTap,
                        depth = 1,
                        myPubkey = myPubkey,
                        onPollVote = onPollVote,
                    )
                } else {
                    // Loading placeholder — matches feed style
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            "Loading quoted note\u2026",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Extracted reply controls: action buttons (upvote, downvote, react, zap, reply, details)
 * + expandable reaction/zap detail panel + zap amount chips.
 * Splits ~400 lines from ThreadedReplyCard to reduce 19MB JIT.
 */
@Composable
private fun ReplyControlsPanel(
    reply: ThreadReply,
    /** 1 = kind-1 replies (no voting), 1111 = kind-1111 replies (with voting). */
    replyKind: Int = 1111,
    isControlsExpanded: Boolean,
    isZapMenuExpanded: Boolean,
    noteCountsByNoteId: Map<String, social.mycelium.android.repository.social.NoteCounts>,
    onReply: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onExpandZapMenu: (String) -> Unit,
    onZap: (String, Long) -> Unit,
    onZapSettings: () -> Unit,
    onReact: (Note, String) -> Unit,
    onVote: ((String, String, Int) -> Unit)? = null,
    onRelayClick: (String) -> Unit = {},
    onNavigateToRelayList: ((List<String>) -> Unit)? = null,
    isScrolling: Boolean = false,
    accountNpub: String? = null,
    /** Called when user confirms removal of a reaction: (noteId, reactionEventId, emoji). */
    onDeleteReaction: ((String, String, String) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isDetailsExpanded by remember { mutableStateOf(false) }
    val reactiveOwnVotes by social.mycelium.android.repository.social.VoteRepository.ownVotes.collectAsState()
    val reactiveUpvotes by social.mycelium.android.repository.social.VoteRepository.upvoteCounts.collectAsState()
    val reactiveDownvotes by social.mycelium.android.repository.social.VoteRepository.downvoteCounts.collectAsState()
    val replyOwnVote = reactiveOwnVotes[reply.id] ?: 0
    val replyVoteScore = (reactiveUpvotes[reply.id] ?: 0) - (reactiveDownvotes[reply.id] ?: 0)
    // Derive liked state from NoteCountsRepository (reactive) so heart updates after user reacts
    val replyCounts = noteCountsByNoteId[reply.id]
    val myPubkey = social.mycelium.android.repository.social.NoteCountsRepository.currentUserPubkey
    val isLikedFromCounts = remember(replyCounts?.reactionAuthors, myPubkey) {
        if (myPubkey == null) false
        else replyCounts?.reactionAuthors?.values?.any { myPubkey in it } == true
    }
    val replyIsLiked = isLikedFromCounts || reply.isLiked

    AnimatedVisibility(
        visible = isControlsExpanded,
        enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150))
    ) {
        Column {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Relay orbs — tucked into the expandable drawer
                val replyRelayUrls = remember(reply.relayUrls) {
                    val seen = mutableSetOf<String>()
                    reply.relayUrls.filter { url -> seen.add(social.mycelium.android.utils.normalizeRelayUrl(url)) }
                        .take(6)
                }
                if (replyRelayUrls.isNotEmpty()) {
                    RelayOrbs(
                        relayUrls = replyRelayUrls,
                        onRelayClick = onRelayClick,
                        onNavigateToRelayList = onNavigateToRelayList
                    )
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                Row(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                        .padding(vertical = 2.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Upvote / Downvote — kind-1111 replies only (not kind-1)
                    if (replyKind != 1) {
                        val replyUpCount = reactiveUpvotes[reply.id] ?: 0
                        val replyDownCount = reactiveDownvotes[reply.id] ?: 0
                        CompactModernButton(
                            icon = Icons.Outlined.ArrowUpward,
                            contentDescription = "Upvote",
                            isActive = replyOwnVote > 0,
                            tint = if (replyOwnVote > 0) Color(0xFF8FBC8F) else null,
                            onClick = { onVote?.invoke(reply.id, reply.author.id, 1) }
                        )
                        if (replyUpCount > 0) {
                            Text(
                                text = "$replyUpCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8FBC8F),
                                modifier = Modifier.padding(horizontal = 1.dp)
                            )
                        }
                        CompactModernButton(
                            icon = Icons.Outlined.ArrowDownward,
                            contentDescription = "Downvote",
                            isActive = replyOwnVote < 0,
                            tint = if (replyOwnVote < 0) Color(0xFFE57373) else null,
                            onClick = { onVote?.invoke(reply.id, reply.author.id, -1) }
                        )
                        if (replyDownCount > 0) {
                            Text(
                                text = "$replyDownCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE57373),
                                modifier = Modifier.padding(horizontal = 1.dp)
                            )
                        }
                    }
                    // Lightning (Zap)
                    CompactModernButton(
                        icon = Icons.Filled.Bolt,
                        contentDescription = "Zap",
                        isActive = false,
                        onClick = { onExpandZapMenu(reply.id) }
                    )
                    // Likes / React button — shows actual emoji when user has reacted
                    Box {
                        var showReactionMenu by remember { mutableStateOf(false) }
                        var showFullPicker by remember { mutableStateOf(false) }
                        var selectedEmoji by remember(reply.id) {
                            mutableStateOf(social.mycelium.android.repository.social.ReactionsRepository.getLastReaction(reply.id))
                        }
                        val hasReacted = replyIsLiked || selectedEmoji != null
                        // Close reaction menu on scroll
                        LaunchedEffect(isScrolling) {
                            if (isScrolling && showReactionMenu) showReactionMenu = false
                        }
                        if (selectedEmoji != null) {
                            val isCustomEmoji =
                                selectedEmoji!!.startsWith(":") && selectedEmoji!!.endsWith(":") && selectedEmoji!!.length > 2
                            if (isCustomEmoji) {
                                // NIP-30 custom emoji — resolve URL from counts or saved packs
                                val allSaved by social.mycelium.android.repository.EmojiPackSelectionRepository.allSavedEmojis.collectAsState()
                                val countsUrl = replyCounts?.customEmojiUrls?.get(selectedEmoji!!)
                                val savedUrl = allSaved[selectedEmoji!!]
                                val emojiUrl = countsUrl ?: savedUrl
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clickable { showReactionMenu = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (emojiUrl != null) {
                                        coil.compose.AsyncImage(
                                            model = emojiUrl,
                                            contentDescription = selectedEmoji!!.removeSurrounding(":"),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.Favorite,
                                            contentDescription = "Reacted",
                                            tint = Color(0xFFE91E63),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            } else {
                                // Regular emoji — show first grapheme
                                val displayEmoji = remember(selectedEmoji) {
                                    val breaker = java.text.BreakIterator.getCharacterInstance()
                                    breaker.setText(selectedEmoji!!)
                                    val start = breaker.first()
                                    val end = breaker.next()
                                    if (end != java.text.BreakIterator.DONE) selectedEmoji!!.substring(
                                        start,
                                        end
                                    ) else selectedEmoji!!
                                }
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clickable { showReactionMenu = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = displayEmoji,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        } else {
                            CompactModernButton(
                                icon = if (hasReacted) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "React",
                                isActive = hasReacted,
                                onClick = { showReactionMenu = true },
                                tint = if (hasReacted) Color.Red else null
                            )
                        }
                        DropdownMenu(
                            expanded = showReactionMenu,
                            onDismissRequest = { showReactionMenu = false },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
                        ) {
                            val recentEmojis = remember {
                                social.mycelium.android.repository.social.ReactionsRepository.getRecentEmojis(
                                    context,
                                    accountNpub
                                )
                            }
                            // Own reactions for this reply (for removal support)
                            val replyCountsForBar = noteCountsByNoteId[reply.id]
                            val replyOwnReactions = remember(replyCountsForBar?.reactionAuthors, myPubkey) {
                                if (myPubkey == null || replyCountsForBar == null) emptyList()
                                else replyCountsForBar.reactionAuthors.flatMap { (emoji, authors) ->
                                    if (myPubkey in authors) listOf(reply.id to emoji) else emptyList()
                                }
                            }
                            social.mycelium.android.ui.components.emoji.ReactionFavoritesBar(
                                recentEmojis = recentEmojis,
                                onEmojiSelected = { emoji ->
                                    showReactionMenu = false
                                    selectedEmoji = emoji
                                    onReact(reply.toNote(), emoji)
                                },
                                onCustomEmojiSelected = { shortcode, url ->
                                    showReactionMenu = false
                                    val emojiKey = ":$shortcode:"
                                    selectedEmoji = emojiKey
                                    onReact(reply.toNote(), emojiKey)
                                },
                                onOpenFullPicker = {
                                    showReactionMenu = false
                                    showFullPicker = true
                                },
                                ownReactions = replyOwnReactions,
                                customEmojiUrls = replyCountsForBar?.customEmojiUrls ?: emptyMap(),
                                onRemoveReaction = if (onDeleteReaction != null) { eventId, emoji ->
                                    showReactionMenu = false
                                    onDeleteReaction(reply.id, eventId, emoji)
                                } else null
                            )
                        }
                        if (showFullPicker) {
                            social.mycelium.android.ui.components.emoji.EmojiDrawer(
                                accountNpub = accountNpub,
                                onDismiss = { showFullPicker = false },
                                onEmojiSelected = { emoji ->
                                    showFullPicker = false
                                    selectedEmoji = emoji
                                    onReact(reply.toNote(), emoji)
                                },
                                onCustomEmojiSelected = { shortcode, url ->
                                    showFullPicker = false
                                    val emojiKey = ":$shortcode:"
                                    selectedEmoji = emojiKey
                                    onReact(reply.toNote(), emojiKey)
                                }
                            )
                        }
                    }
                    // Reply
                    CompactModernButton(
                        icon = Icons.Outlined.Reply,
                        contentDescription = "Reply",
                        isActive = false,
                        onClick = { onReply(reply.id) }
                    )
                    // Reactions caret — expand/collapse reaction & zap breakdown
                    CompactModernButton(
                        icon = if (isDetailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Details",
                        isActive = isDetailsExpanded,
                        onClick = { isDetailsExpanded = !isDetailsExpanded }
                    )
                }
            }

            // ── Expandable details panel: replies, reactions, zaps ──
            ReplyDetailsPanel(
                replyId = reply.id,
                isDetailsExpanded = isDetailsExpanded,
                noteCountsByNoteId = noteCountsByNoteId,
                onProfileClick = onProfileClick,
            )
        }
    }

    // Zap drawer — ModalBottomSheet for smooth animate-in/out
    if (isControlsExpanded && isZapMenuExpanded) {
        social.mycelium.android.ui.components.zap.ZapBottomSheet(
            onDismiss = { onExpandZapMenu(reply.id) },
            onZap = { amount ->
                onExpandZapMenu(reply.id)
                onZap(reply.id, amount)
            },
            onCustomZapSend = null,
            onSettingsClick = {
                onExpandZapMenu(reply.id)
                onZapSettings()
            }
        )
    }
}

/**
 * Extracted reaction/zap detail panel for a reply.
 * Shows per-author reaction lines and zap amounts when expanded.
 */
@Composable
private fun ReplyDetailsPanel(
    replyId: String,
    isDetailsExpanded: Boolean,
    noteCountsByNoteId: Map<String, social.mycelium.android.repository.social.NoteCounts>,
    onProfileClick: (String) -> Unit,
) {
    val detailCounts = noteCountsByNoteId[replyId]
    val detailReactions = detailCounts?.reactions ?: emptyList()
    val detailReactionAuthors = detailCounts?.reactionAuthors ?: emptyMap()
    val detailZapCount = detailCounts?.zapCount ?: 0
    val detailZapTotalSats = detailCounts?.zapTotalSats ?: 0L
    val detailZapAuthors = detailCounts?.zapAuthors ?: emptyList()
    val detailZapAmountByAuthor = detailCounts?.zapAmountByAuthor ?: emptyMap()
    val detailEmojiUrls = detailCounts?.customEmojiUrls ?: emptyMap()
    val hasReactions = detailReactions.isNotEmpty()
    val hasZaps = detailZapCount > 0

    AnimatedVisibility(
        visible = isDetailsExpanded,
        enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(150))
    ) {
        val profileCache = remember { social.mycelium.android.repository.cache.ProfileMetadataCache.getInstance() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { /* consume clicks — dead zone */ }
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!hasReactions && !hasZaps) {
                Text(
                    text = "No reactions or zaps yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            // Reactions section
            if (hasReactions) {
                var reactionsExpanded by remember { mutableStateOf(false) }
                val grouped = remember(detailReactions, detailReactionAuthors) {
                    detailReactions.map { emoji ->
                        emoji to (detailReactionAuthors[emoji]?.size ?: 1)
                    }.sortedByDescending { it.second }
                }
                val totalReactionCount = if (detailReactionAuthors.isNotEmpty()) {
                    detailReactionAuthors.values.sumOf { it.size }
                } else detailReactions.size
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { reactionsExpanded = !reactionsExpanded }
                        .padding(vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Favorite, null, Modifier.size(14.dp), tint = Color(0xFFE91E63))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Reactions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    grouped.take(5).forEach { (emoji, count) ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                social.mycelium.android.ui.components.emoji.ReactionEmoji(
                                    emoji = emoji,
                                    customEmojiUrls = detailEmojiUrls,
                                    fontSize = 13.sp,
                                    imageSize = 14.dp
                                )
                                if (count > 1) {
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        "$count",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (totalReactionCount > 0) {
                        Text(
                            "$totalReactionCount",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE57373)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(
                        if (reactionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                // Expanded: 3 latest individual reactions
                AnimatedVisibility(visible = reactionsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    val latestReactions = remember(detailReactionAuthors) {
                        val flat = mutableListOf<Pair<String, String>>()
                        detailReactionAuthors.forEach { (emoji, authors) ->
                            authors.forEach { pubkey -> flat.add(emoji to pubkey) }
                        }
                        flat.takeLast(3).reversed()
                    }
                    val rxPubkeys = remember(latestReactions) { latestReactions.map { it.second }.distinct() }
                    var profileRevision by remember { mutableIntStateOf(0) }
                    LaunchedEffect(rxPubkeys) {
                        val uncached = rxPubkeys.filter { profileCache.getAuthor(it) == null }
                        if (uncached.isNotEmpty()) profileCache.requestProfiles(
                            uncached,
                            profileCache.getConfiguredRelayUrls()
                        )
                    }
                    LaunchedEffect(Unit) { profileCache.profileUpdated.collect { pk -> if (pk in rxPubkeys) profileRevision++ } }
                    @Suppress("UNUSED_EXPRESSION") profileRevision
                    Column(
                        modifier = Modifier.padding(start = 22.dp, top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        latestReactions.forEach { (emoji, pubkey) ->
                            val author = profileCache.resolveAuthor(pubkey)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                social.mycelium.android.ui.components.emoji.ReactionEmoji(
                                    emoji = emoji,
                                    customEmojiUrls = detailEmojiUrls,
                                    fontSize = 14.sp,
                                    imageSize = 16.dp
                                )
                                Spacer(Modifier.width(6.dp))
                                social.mycelium.android.ui.components.common.ProfilePicture(
                                    author = author,
                                    size = 20.dp,
                                    onClick = { onProfileClick(author.id) })
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = author.displayName.ifBlank { author.id.take(8) + "..." },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { onProfileClick(author.id) }
                                )
                            }
                        }
                    }
                }
            }

            // Zaps section
            if (hasZaps) {
                var zapsExpanded by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { zapsExpanded = !zapsExpanded }
                        .padding(vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Bolt, null, Modifier.size(14.dp), tint = Color(0xFFF59E0B))
                    Spacer(Modifier.width(8.dp))
                    if (detailZapTotalSats > 0) {
                        Text(
                            "${social.mycelium.android.utils.ZapUtils.formatZapAmount(detailZapTotalSats)} sats",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFFF59E0B)
                        )
                        Text(
                            " ($detailZapCount zap${if (detailZapCount != 1) "s" else ""})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Text(
                            "$detailZapCount zap${if (detailZapCount != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Icon(
                        if (zapsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                // Expanded: 3 latest zappers
                AnimatedVisibility(visible = zapsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    val allZapPubkeys = remember(detailZapAuthors) { detailZapAuthors.distinct() }
                    var zapProfileRevision by remember { mutableIntStateOf(0) }
                    LaunchedEffect(allZapPubkeys) {
                        val uncached = allZapPubkeys.filter { profileCache.getAuthor(it) == null }
                        if (uncached.isNotEmpty()) profileCache.requestProfiles(
                            uncached,
                            profileCache.getConfiguredRelayUrls()
                        )
                    }
                    LaunchedEffect(Unit) { profileCache.profileUpdated.collect { pk -> if (pk in allZapPubkeys) zapProfileRevision++ } }
                    @Suppress("UNUSED_EXPRESSION") zapProfileRevision
                    Column(
                        modifier = Modifier.padding(start = 22.dp, top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val sortedZapAuthors = remember(detailZapAuthors, detailZapAmountByAuthor) {
                            detailZapAuthors.sortedByDescending { detailZapAmountByAuthor[it] ?: 0L }
                        }
                        sortedZapAuthors.take(3).forEach { pubkey ->
                            val author = profileCache.resolveAuthor(pubkey)
                            val zapSats = detailZapAmountByAuthor[pubkey] ?: 0L
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                social.mycelium.android.ui.components.common.ProfilePicture(
                                    author = author,
                                    size = 20.dp,
                                    onClick = { onProfileClick(author.id) })
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = author.displayName.ifBlank { author.id.take(8) + "..." },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false).clickable { onProfileClick(author.id) }
                                )
                                if (zapSats > 0) {
                                    Text(
                                        " ⚡ ${social.mycelium.android.utils.ZapUtils.formatZapAmount(zapSats)} sats",
                                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFF59E0B)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Threaded reply card: thin line along the left edge of the card, stacking horizontally per level
 * for coherent conversation view. Condensed layout.
 */
@Composable
private fun ThreadedReplyCard(
    threadedReply: ThreadedReply,
    isLastRootReply: Boolean = true,
    /** Root note author id; when reply.author matches, show OP highlight and "OP" label. */
    rootAuthorId: String? = null,
    /** Parent card's author id — used for "replying to @name" context label. */
    parentAuthorId: String? = null,
    /** 1 = kind-1 thread (no voting on replies), 1111 = kind-1111 thread (voting on replies). */
    replyKind: Int = 1111,
    commentStates: MutableMap<String, CommentState>,
    /** Counts (reactions, zaps, replies) per note ID from NoteCountsRepository. */
    noteCountsByNoteId: Map<String, social.mycelium.android.repository.social.NoteCounts> = emptyMap(),
    /** Reply ID to persistently highlight (accent bar + background wash). Threaded through children. */
    highlightedReplyId: String? = null,
    highlightedPathIds: Set<String> = emptySet(),
    onLike: (String) -> Unit,
    onReply: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onRelayClick: (String) -> Unit = {},
    onNavigateToRelayList: ((List<String>) -> Unit)? = null,
    shouldCloseZapMenus: Boolean = false,
    expandedZapMenuReplyId: String? = null,
    onExpandZapMenu: (String) -> Unit = {},
    onZap: (String, Long) -> Unit = { _, _ -> },
    onZapSettings: () -> Unit = {},
    /** Which reply ID has controls expanded; null = all compact. */
    expandedControlsReplyId: String? = null,
    onExpandedControlsReplyChange: (String?) -> Unit = {},
    /** When level >= MAX_THREAD_DEPTH and there are children, tap "Read N more replies" opens sub-thread. */
    onReadMoreReplies: (String) -> Unit = {},
    /** Navigate to a quoted note's thread. */
    onNoteClick: (Note) -> Unit = {},
    /** Send a reaction (emoji) to a reply. */
    onReact: (Note, String) -> Unit = { _, _ -> },
    /** Kind-30011 vote: (noteId, authorPubkey, direction +1/-1). */
    onVote: ((String, String, Int) -> Unit)? = null,
    onImageTap: (List<String>, Int) -> Unit = { _, _ -> },
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    /** When non-null and > 0, shows "N replies in thread" badge (root-only mode). */
    collapsedChildCount: Int? = null,
    /** When true, suppress click/longClick to prevent accidental triggers during fast fling. */
    isScrolling: Boolean = false,
    compactMedia: Boolean = false,
    accountNpub: String? = null,
    myPubkey: String? = null,
    onPollVote: ((String, String, Set<String>, String?) -> Unit)? = null,
    /** Called when user confirms removal of a reaction: (noteId, reactionEventId, emoji). */
    onDeleteReaction: ((String, String, String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val reply = threadedReply.reply
    val replyKey = logicalReplyKey(reply)
    val isControlsExpanded = expandedControlsReplyId == reply.id
    val onToggleControls: () -> Unit =
        { onExpandedControlsReplyChange(if (expandedControlsReplyId == reply.id) null else reply.id) }
    // onDeleteReaction is threaded through from ModernThreadViewScreen via ThreadedReplyCard params
    val level = threadedReply.level
    val state = commentStates.getOrPut(replyKey) { CommentState() }
    val canCollapse = true // allow collapsing single/leaf replies as well as branches
    val threadLineWidth = 1.5.dp
    // Fixed indent step per nesting level. Since children are rendered recursively
    // inside the parent's Column, each level only needs ONE step of indent — not
    // level * N (which would compound and grow quadratically).
    val singleIndentStep = 4.dp
    val isHighlightedTarget = highlightedReplyId == reply.id
    val isInHighlightPath = reply.id in highlightedPathIds
    val isHighlighted = isHighlightedTarget || isInHighlightPath
    val railColor = ThreadLineColor
    val isZapMenuExpanded = expandedZapMenuReplyId == reply.id

    // Resolve author from profile cache so display name/avatar update when profiles load
    val profileCache = social.mycelium.android.repository.cache.ProfileMetadataCache.getInstance()
    // Snapshot read avoids per-reply flow collector; value only flips once at startup
    val diskCacheReady = profileCache.diskCacheRestored.value
    val authorPubkey =
        remember(reply.author.id) { social.mycelium.android.utils.normalizeAuthorIdForCache(reply.author.id) }
    var profileRevision by remember(reply.id) { mutableIntStateOf(0) }
    if (profileRevision == 0) {
        LaunchedEffect(authorPubkey) {
            profileCache.profileUpdated
                .filter { it == authorPubkey }
                .debounce(1500)
                .collect { profileRevision = 1 }
        }
    }
    val displayAuthor = remember(reply.author.id, profileRevision, diskCacheReady) {
        profileCache.resolveAuthor(reply.author.id)
    }
    // Trigger profile fetch if author is unknown — use reply source relays as hints
    LaunchedEffect(authorPubkey, diskCacheReady) {
        if (profileCache.getAuthor(authorPubkey) == null) {
            val cacheRelays = profileCache.getConfiguredRelayUrls()
            val hintRelays = reply.relayUrls + profileCache.getOutboxRelays(authorPubkey)
            if (cacheRelays.isNotEmpty() || hintRelays.isNotEmpty()) {
                profileCache.requestProfileWithHints(listOf(authorPubkey), cacheRelays, hintRelays.distinct())
            }
        }
    }

    LaunchedEffect(shouldCloseZapMenus) {
        if (shouldCloseZapMenus && isZapMenuExpanded) onExpandZapMenu(reply.id)
    }

    // Content shifts right by one indent step (parent already indented for prior levels).
    // Level 0 = no indent (top-level reply). Level >= 1 = one fixed step.
    val contentStartPad = if (level > 0) singleIndentStep else 0.dp
    val lineX = contentStartPad
    
    val highlightColor = MaterialTheme.colorScheme.primary

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Spacer(modifier = Modifier.width(contentStartPad + threadLineWidth + 3.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Show "event not found" indicator for orphan replies whose parent wasn't fetched
                if (threadedReply.isOrphan) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LinkOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "Replying to an event not found",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            drawContent()
                            if (isHighlightedTarget) {
                                drawRect(color = highlightColor, alpha = 0.12f)
                            } else if (isInHighlightPath) {
                                drawRect(color = highlightColor, alpha = 0.05f)
                            }
                        }
                        .combinedClickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (isScrolling) return@combinedClickable
                                if (state.isCollapsed) {
                                    commentStates[replyKey] = state.copy(isCollapsed = false, isExpanded = true)
                                    // Also expand controls so uncollapse + controls happens in one tap
                                    onExpandedControlsReplyChange(reply.id)
                                } else {
                                    onToggleControls()
                                }
                            },
                            onLongClick = {
                                if (isScrolling) return@combinedClickable
                                if (canCollapse && !state.isCollapsed) {
                                    commentStates[replyKey] = state.copy(isCollapsed = true, isExpanded = false)
                                }
                            }
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RectangleShape,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    if (state.isCollapsed) {
                        val childCount = threadedReply.totalReplies
                        val label = when {
                            childCount == 0 -> "1 reply"
                            childCount == 1 -> "view 1 more reply"
                            else -> "view $childCount more replies"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "[+]",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                // ── Header — extracted into ReplyHeader ──
                                ReplyHeader(
                                    reply = reply,
                                    displayAuthor = displayAuthor,
                                    rootAuthorId = rootAuthorId,
                                    noteCountsByNoteId = noteCountsByNoteId,
                                    onProfileClick = onProfileClick,
                                    onRelayClick = onRelayClick,
                                    onNavigateToRelayList = onNavigateToRelayList,
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                // ── Rich content — extracted into ReplyContentBody ──
                                ReplyContentBody(
                                    reply = reply,
                                    onProfileClick = onProfileClick,
                                    onNoteClick = onNoteClick,
                                    onImageTap = onImageTap,
                                    onVideoClick = onVideoClick,
                                    onToggleControls = onToggleControls,
                                    onLongPress = {
                                        if (canCollapse && !state.isCollapsed) {
                                            commentStates[replyKey] = state.copy(isCollapsed = true, isExpanded = false)
                                        }
                                    },
                                    noteCountsByNoteId = noteCountsByNoteId,
                                    compactMedia = compactMedia,
                                    myPubkey = myPubkey,
                                    onPollVote = onPollVote,
                                )

                                // Reactions and zaps — extracted into ReplyControlsPanel
                                ReplyControlsPanel(
                                    reply = reply,
                                    replyKind = replyKind,
                                    isControlsExpanded = isControlsExpanded,
                                    isZapMenuExpanded = isZapMenuExpanded,
                                    noteCountsByNoteId = noteCountsByNoteId,
                                    onReply = onReply,
                                    onProfileClick = onProfileClick,
                                    onExpandZapMenu = onExpandZapMenu,
                                    onZap = onZap,
                                    onZapSettings = onZapSettings,
                                    onReact = onReact,
                                    onVote = onVote,
                                    onRelayClick = onRelayClick,
                                    onNavigateToRelayList = onNavigateToRelayList,
                                    isScrolling = isScrolling,
                                    accountNpub = accountNpub,
                                    onDeleteReaction = onDeleteReaction,
                                )
                            }  // Column (weight 1f, padding)

                        }  // Row (fillMaxWidth, Top)
                    }  // else (not collapsed)
                }  // Card
                // Children rendering extracted to reduce JIT size
                ThreadedReplyChildren(
                    threadedReply = threadedReply,
                    parentAuthorId = reply.author.id,
                    isCollapsed = state.isCollapsed,
                    collapsedChildCount = collapsedChildCount,
                    rootAuthorId = rootAuthorId,
                    replyKind = replyKind,
                    commentStates = commentStates,
                    noteCountsByNoteId = noteCountsByNoteId,
                    highlightedReplyId = highlightedReplyId,
                    highlightedPathIds = highlightedPathIds,
                    onLike = onLike,
                    onReply = onReply,
                    onProfileClick = onProfileClick,
                    onRelayClick = onRelayClick,
                    onNavigateToRelayList = onNavigateToRelayList,
                    shouldCloseZapMenus = shouldCloseZapMenus,
                    expandedZapMenuReplyId = expandedZapMenuReplyId,
                    onExpandZapMenu = onExpandZapMenu,
                    onZap = onZap,
                    onZapSettings = onZapSettings,
                    expandedControlsReplyId = expandedControlsReplyId,
                    onExpandedControlsReplyChange = onExpandedControlsReplyChange,
                    onReadMoreReplies = onReadMoreReplies,
                    onNoteClick = onNoteClick,
                    onReact = onReact,
                    onVote = onVote,
                    onImageTap = onImageTap,
                    onVideoClick = onVideoClick,
                    isScrolling = isScrolling,
                    compactMedia = compactMedia,
                    accountNpub = accountNpub,
                    myPubkey = myPubkey,
                    onPollVote = onPollVote,
                    onDeleteReaction = onDeleteReaction,
                )

            }
        }
        // Thread rail: full height, color-coded by depth level (Reddit-style)
        Box(
            modifier = Modifier
                .matchParentSize()
                .align(Alignment.CenterStart)
        ) {
            Box(
                modifier = Modifier
                    .width(threadLineWidth)
                    .offset(x = lineX)
                    .fillMaxHeight()
                    .background(railColor, RoundedCornerShape(1.dp))
            )
        }
    }
}

/**
 * Extracted children rendering from ThreadedReplyCard — recursive child cards,
 * collapsed child badge, and "Read N more replies". Splits the 7.6MB JIT.
 */
@Composable
private fun ThreadedReplyChildren(
    threadedReply: ThreadedReply,
    /** This card's parent author id — passed to child ThreadedReplyCards for "replying to" label. */
    parentAuthorId: String? = null,
    isCollapsed: Boolean,
    collapsedChildCount: Int?,
    rootAuthorId: String?,
    replyKind: Int,
    commentStates: MutableMap<String, CommentState>,
    noteCountsByNoteId: Map<String, social.mycelium.android.repository.social.NoteCounts>,
    highlightedReplyId: String? = null,
    highlightedPathIds: Set<String> = emptySet(),
    onLike: (String) -> Unit,
    onReply: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onRelayClick: (String) -> Unit,
    onNavigateToRelayList: ((List<String>) -> Unit)?,
    shouldCloseZapMenus: Boolean,
    expandedZapMenuReplyId: String?,
    onExpandZapMenu: (String) -> Unit,
    onZap: (String, Long) -> Unit,
    onZapSettings: () -> Unit,
    expandedControlsReplyId: String?,
    onExpandedControlsReplyChange: (String?) -> Unit,
    onReadMoreReplies: (String) -> Unit,
    onNoteClick: (Note) -> Unit,
    onReact: (Note, String) -> Unit,
    onVote: ((String, String, Int) -> Unit)?,
    onImageTap: (List<String>, Int) -> Unit,
    onVideoClick: (List<String>, Int) -> Unit,
    isScrolling: Boolean,
    compactMedia: Boolean = false,
    accountNpub: String? = null,
    myPubkey: String? = null,
    onPollVote: ((String, String, Set<String>, String?) -> Unit)? = null,
    /** Called when user confirms removal of a reaction: (noteId, reactionEventId, emoji). */
    onDeleteReaction: ((String, String, String) -> Unit)? = null,
) {
    val reply = threadedReply.reply
    val level = threadedReply.level

    // Root-only mode: show collapsed child count badge
    if (collapsedChildCount != null && collapsedChildCount > 0) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onReadMoreReplies(reply.id) },
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.UnfoldMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (collapsedChildCount == 1) "1 reply in thread" else "$collapsedChildCount replies in thread",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            }
        }
    }
    if (!isCollapsed && collapsedChildCount == null) {
        if (level >= MAX_THREAD_DEPTH && threadedReply.children.isNotEmpty()) {
            val n = threadedReply.totalReplies
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onReadMoreReplies(reply.id) },
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Read $n more replies",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            threadedReply.children.forEach { childReply ->
                ThreadedReplyCard(
                    threadedReply = childReply,
                    isLastRootReply = true,
                    rootAuthorId = rootAuthorId,
                    parentAuthorId = threadedReply.reply.author.id,
                    replyKind = replyKind,
                    commentStates = commentStates,
                    noteCountsByNoteId = noteCountsByNoteId,
                    highlightedReplyId = highlightedReplyId,
                    highlightedPathIds = highlightedPathIds,
                    onLike = onLike,
                    onReply = onReply,
                    onProfileClick = onProfileClick,
                    onRelayClick = onRelayClick,
                    onNavigateToRelayList = onNavigateToRelayList,
                    shouldCloseZapMenus = shouldCloseZapMenus,
                    expandedZapMenuReplyId = expandedZapMenuReplyId,
                    onExpandZapMenu = onExpandZapMenu,
                    onZap = onZap,
                    onZapSettings = onZapSettings,
                    expandedControlsReplyId = expandedControlsReplyId,
                    onExpandedControlsReplyChange = onExpandedControlsReplyChange,
                    onReadMoreReplies = onReadMoreReplies,
                    onNoteClick = onNoteClick,
                    onReact = onReact,
                    onVote = onVote,
                    onImageTap = onImageTap,
                    onVideoClick = onVideoClick,
                    isScrolling = isScrolling,
                    compactMedia = compactMedia,
                    accountNpub = accountNpub,
                    myPubkey = myPubkey,
                    onPollVote = onPollVote,
                    onDeleteReaction = onDeleteReaction,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

fun createSampleCommentThreads(): List<CommentThread> {
    return listOf(
        CommentThread(
            comment = SampleData.sampleComments[0],
            replies = listOf(
                CommentThread(
                    comment = SampleData.sampleComments[1],
                    replies = listOf(
                        CommentThread(
                            comment = SampleData.sampleComments[6],
                            replies = listOf(
                                CommentThread(comment = SampleData.sampleComments[11])
                            )
                        ),
                        CommentThread(
                            comment = SampleData.sampleComments[7],
                            replies = listOf(
                                CommentThread(
                                    comment = SampleData.sampleComments[10],
                                    replies = listOf(
                                        CommentThread(comment = SampleData.sampleComments[12])
                                    )
                                )
                            )
                        )
                    )
                ),
                CommentThread(comment = SampleData.sampleComments[5])
            )
        ),
        CommentThread(
            comment = SampleData.sampleComments[2],
            replies = listOf(
                CommentThread(
                    comment = SampleData.sampleComments[3],
                    replies = listOf(
                        CommentThread(
                            comment = SampleData.sampleComments[8],
                            replies = listOf(
                                CommentThread(comment = SampleData.sampleComments[9])
                            )
                        )
                    )
                )
            )
        ),
        CommentThread(comment = SampleData.sampleComments[4])
    )
}

@Preview(showBackground = true)
@Composable
fun ModernThreadViewScreenPreview() {
    MaterialTheme {
        ModernThreadViewScreen(
            note = SampleData.sampleNotes.first(),
            comments = createSampleCommentThreads()
        )
    }
}
