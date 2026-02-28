package social.mycelium.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import androidx.compose.ui.unit.offset
import androidx.compose.ui.graphics.Color
import social.mycelium.android.ui.components.ClickableNoteContent
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.repository.ReactionsRepository
import com.example.cybin.nip19.toNpub
import com.example.cybin.nip19.encodeNevent
import social.mycelium.android.utils.NoteContentBlock
import social.mycelium.android.utils.normalizeAuthorIdForCache
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import social.mycelium.android.ui.theme.NoteBodyTextStyle
import social.mycelium.android.utils.buildNoteContentWithInlinePreviews
import social.mycelium.android.utils.UrlDetector
import coil.compose.AsyncImage
import coil.request.ImageRequest
import social.mycelium.android.utils.MediaAspectRatioCache
import social.mycelium.android.ui.components.InlineVideoPlayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.data.Note
import social.mycelium.android.data.PublishState
import social.mycelium.android.data.QuotedNoteMeta
import social.mycelium.android.repository.ZapType
import social.mycelium.android.data.SampleData
import social.mycelium.android.repository.QuotedNoteCache
import social.mycelium.android.ui.icons.ArrowDownward
import social.mycelium.android.ui.icons.ArrowUpward
import social.mycelium.android.ui.icons.Bolt
import social.mycelium.android.ui.icons.Bookmark
import social.mycelium.android.ui.icons.ChatBubble
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Persistent expanded state for quoted notes — survives LazyColumn recycling and
 * fullscreen navigation (e.g. video player back gesture).
 * Keyed by quoted event ID; bounded to avoid unbounded growth.
 */
object QuotedNoteExpandedState {
    private const val MAX_ENTRIES = 500
    private val map = mutableStateMapOf<String, Boolean>()

    fun isExpanded(eventId: String): Boolean = map[eventId] ?: false

    fun toggle(eventId: String) {
        map[eventId] = !(map[eventId] ?: false)
        if (map.size > MAX_ENTRIES) {
            val keysToRemove = map.keys.take(map.size - MAX_ENTRIES)
            keysToRemove.forEach { map.remove(it) }
        }
    }
}

/**
 * Defines which buttons appear in the NoteCard action row.
 * All controls are right-aligned with consistent sizing.
 *
 * - [KIND1_FEED]: Lightning | Boost | Likes | ReactionsCaret
 * - [KIND11_FEED]: Upvote | Downvote | Boost | Lightning | Likes | ReactionsCaret
 * - [KIND1111_REPLY]: Upvote | Downvote | Lightning | Likes | Reply | ReactionsCaret
 */
enum class ActionRowSchema {
    /** Kind-1 home feed (no reply button). */
    KIND1_FEED,
    /** Kind-1 thread root note (shows reply button). */
    KIND1_REPLY,
    /** Kind-11 topics feed, kind-11 thread root. */
    KIND11_FEED,
    /** Kind-1111 replies to kind-11 threads. */
    KIND1111_REPLY,
}

// ✅ CRITICAL PERFORMANCE FIX: Cache SimpleDateFormat
private val dateFormatter by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

/**
 * Extracted quoted note body — content blocks + read more toggle.
 * Splits the inner Surface lambda of QuotedNoteContent to reduce 6.8MB JIT.
 */
@Composable
private fun QuotedNoteBody(
    contentBlocks: List<NoteContentBlock>,
    isMarkdown: Boolean,
    isExpanded: Boolean,
    hasMore: Boolean,
    meta: QuotedNoteMeta,
    quotedAuthor: social.mycelium.android.data.Author,
    isVisible: Boolean,
    onExpandToggle: () -> Unit,
    onProfileClick: (String) -> Unit,
    onNoteClick: (Note) -> Unit,
    onVideoClick: (List<String>, Int) -> Unit,
    onOpenImageViewer: (List<String>, Int) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    contentBlocks.forEach { qBlock ->
        when (qBlock) {
            is NoteContentBlock.Content -> {
                val qAnnotated = qBlock.annotated
                if (qAnnotated.isNotEmpty()) {
                    if (isMarkdown) {
                        MarkdownNoteContent(
                            content = qAnnotated.text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            onProfileClick = onProfileClick,
                            onNoteClick = { },
                            onUrlClick = { url -> uriHandler.openUri(url) }
                        )
                    } else {
                        ClickableNoteContent(
                            text = qAnnotated,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                            onClick = { offset ->
                                val profile = qAnnotated.getStringAnnotations(tag = "PROFILE", start = offset, end = offset).firstOrNull()
                                val url = qAnnotated.getStringAnnotations(tag = "URL", start = offset, end = offset).firstOrNull()
                                when {
                                    profile != null -> onProfileClick(profile.item)
                                    url != null -> uriHandler.openUri(url.item)
                                    else -> {
                                        val quotedNote = Note(
                                            id = meta.eventId,
                                            author = quotedAuthor,
                                            content = meta.fullContent,
                                            timestamp = meta.createdAt,
                                            likes = 0, shares = 0, comments = 0,
                                            isLiked = false, hashtags = emptyList(),
                                            mediaUrls = emptyList(), isReply = false,
                                            relayUrl = meta.relayUrl,
                                            relayUrls = listOfNotNull(meta.relayUrl)
                                        )
                                        onNoteClick(quotedNote)
                                    }
                                }
                            }
                        )
                    }
                }
            }
            is NoteContentBlock.MediaGroup -> {
                val qMediaList = qBlock.urls.take(4)
                if (qMediaList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    qMediaList.forEach { url ->
                        if (social.mycelium.android.utils.UrlDetector.isVideoUrl(url)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            ) {
                                InlineVideoPlayer(
                                    url = url,
                                    modifier = Modifier.fillMaxWidth(),
                                    isVisible = isVisible,
                                    onFullscreenClick = { onVideoClick(qMediaList, qMediaList.indexOf(url)) }
                                )
                            }
                        } else {
                            coil.compose.AsyncImage(
                                model = url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onOpenImageViewer(qMediaList, qMediaList.indexOf(url)) }
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
            is NoteContentBlock.Preview -> {
                UrlPreviewCard(
                    previewInfo = qBlock.previewInfo,
                    onUrlClick = { url -> uriHandler.openUri(url) },
                    onUrlLongClick = { }
                )
            }
            is NoteContentBlock.QuotedNote -> {
                // Depth-limited: nested quotes render as clickable links (not inline cards).
                // Tapping navigates to the quoted note's thread for infinite exploration.
                Row(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            val profileCache = social.mycelium.android.repository.ProfileMetadataCache.getInstance()
                            val qAuthor = profileCache.resolveAuthor(qBlock.eventId)
                            onNoteClick(Note(
                                id = qBlock.eventId,
                                author = qAuthor,
                                content = "",
                                timestamp = 0L,
                                likes = 0, shares = 0, comments = 0,
                                isLiked = false, hashtags = emptyList(),
                                mediaUrls = emptyList(), isReply = false,
                            ))
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.FormatQuote,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "View quoted note \u203A",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            is NoteContentBlock.LiveEventReference -> {
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.PlayCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "Live event: ${qBlock.eventId.take(8)}\u2026",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    if (hasMore) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandToggle() }
                .padding(top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = if (isExpanded) "Show less" else "Read more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Extracted quoted note composable — reduces NoteCard lambda nesting from 5 levels
 * which was causing 6.8MB JIT compilation for the quoted note path.
 */
@Composable
private fun QuotedNoteContent(
    meta: QuotedNoteMeta,
    quotedAuthor: social.mycelium.android.data.Author,
    quotedCounts: social.mycelium.android.repository.NoteCounts?,
    linkStyle: SpanStyle,
    profileCache: social.mycelium.android.repository.ProfileMetadataCache,
    isVisible: Boolean,
    onProfileClick: (String) -> Unit,
    onNoteClick: (Note) -> Unit,
    onVideoClick: (List<String>, Int) -> Unit,
    onOpenImageViewer: (List<String>, Int) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val quotedExpanded = QuotedNoteExpandedState.isExpanded(meta.eventId)
    val hasMore = meta.fullContent.length > meta.contentSnippet.length

    val quotedDisplayContent = if (quotedExpanded) meta.fullContent else meta.contentSnippet
    val quotedMediaUrls = remember(meta.fullContent) {
        social.mycelium.android.utils.UrlDetector.findUrls(meta.fullContent)
            .filter { social.mycelium.android.utils.UrlDetector.isImageUrl(it) || social.mycelium.android.utils.UrlDetector.isVideoUrl(it) }
            .toSet()
    }
    val quotedIsMarkdown = remember(quotedDisplayContent) { isMarkdown(quotedDisplayContent) }
    val quotedContentBlocks = remember(quotedDisplayContent, quotedMediaUrls) {
        buildNoteContentWithInlinePreviews(
            quotedDisplayContent,
            quotedMediaUrls,
            emptyList(),
            linkStyle,
            profileCache
        )
    }

    // Outer surface: same background as body text for visual continuity
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RectangleShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
    // Inner surface: slightly elevated for quote distinction
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable {
                val quotedNote = Note(
                    id = meta.eventId,
                    author = quotedAuthor,
                    content = meta.fullContent,
                    timestamp = meta.createdAt,
                    likes = 0, shares = 0, comments = 0,
                    isLiked = false, hashtags = emptyList(),
                    mediaUrls = emptyList(), isReply = false,
                    relayUrl = meta.relayUrl,
                    relayUrls = listOfNotNull(meta.relayUrl)
                )
                onNoteClick(quotedNote)
            },
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        shape = MaterialTheme.shapes.small,
        shadowElevation = 0.dp
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            )
        Column(modifier = Modifier.padding(10.dp).weight(1f)) {
            // ── Header row: author (left) + counters & emojis (right) ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                ProfilePicture(author = quotedAuthor, size = 20.dp, onClick = { onProfileClick(meta.authorId) })
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = quotedAuthor.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (quotedCounts != null) {
                    val qReplyCount = quotedCounts.replyCount
                    val qReactionsList = quotedCounts.reactions
                    val qReactionCount = quotedCounts.reactionAuthors.values.sumOf { it.size }
                    val qZapSats = quotedCounts.zapTotalSats
                    val qEmojiUrls = quotedCounts.customEmojiUrls
                    val cStyle = MaterialTheme.typography.labelSmall
                    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    val zapColor = Color(0xFFFFD700)
                    val reactColor = Color(0xFFE57373)
                    val replyColor = Color(0xFF8FBC8F)
                    if (qReactionCount > 0 || qReplyCount > 0 || qZapSats > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (qReactionCount > 0) {
                                qReactionsList.distinct().take(3).forEach { emoji ->
                                    ReactionEmoji(emoji = emoji, customEmojiUrls = qEmojiUrls, fontSize = 11.sp, imageSize = 12.dp)
                                }
                                if (qReactionCount > 1) {
                                    Text(" $qReactionCount", style = cStyle, color = reactColor)
                                }
                            }
                            if (qReactionCount > 0 && (qReplyCount > 0 || qZapSats > 0)) {
                                Text(" · ", style = cStyle, color = mutedColor)
                            }
                            if (qReplyCount > 0) {
                                Icon(Icons.Outlined.ChatBubbleOutline, null, modifier = Modifier.size(12.dp), tint = replyColor)
                                Text(" $qReplyCount", style = cStyle, color = replyColor)
                            }
                            if (qReplyCount > 0 && qZapSats > 0) {
                                Text(" · ", style = cStyle, color = mutedColor)
                            }
                            if (qZapSats > 0) {
                                Icon(Icons.Filled.ElectricBolt, null, modifier = Modifier.size(12.dp), tint = zapColor)
                                Text(" ${formatSats(qZapSats)}", style = cStyle, color = zapColor)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            // ── Rich content body ──
            QuotedNoteBody(
                contentBlocks = quotedContentBlocks,
                isMarkdown = quotedIsMarkdown,
                isExpanded = quotedExpanded,
                hasMore = hasMore,
                meta = meta,
                quotedAuthor = quotedAuthor,
                isVisible = isVisible,
                onExpandToggle = { QuotedNoteExpandedState.toggle(meta.eventId) },
                onProfileClick = onProfileClick,
                onNoteClick = onNoteClick,
                onVideoClick = onVideoClick,
                onOpenImageViewer = onOpenImageViewer,
            )
        }
        }
    }
    } // outer Surface
}

/**
 * Extracted media carousel composable — reduces NoteCard lambda nesting depth
 * which was causing 6.8MB JIT compilation on first scroll.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteMediaCarousel(
    mediaList: List<String>,
    allMediaUrls: List<String>,
    groupStartIndex: Int,
    initialMediaPage: Int,
    isVisible: Boolean,
    onMediaPageChanged: (Int) -> Unit,
    onImageTap: (List<String>, Int) -> Unit,
    onOpenImageViewer: (List<String>, Int) -> Unit,
    onVideoClick: (List<String>, Int) -> Unit,
) {
    val pagerState = rememberPagerState(
        pageCount = { mediaList.size },
        initialPage = initialMediaPage.coerceIn(0, (mediaList.size - 1).coerceAtLeast(0))
    )
    // Sync pager when returning from fullscreen viewer (initialMediaPage changed externally)
    LaunchedEffect(initialMediaPage) {
        val target = initialMediaPage.coerceIn(0, (mediaList.size - 1).coerceAtLeast(0))
        if (pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }
    // Report page changes back so album position persists
    LaunchedEffect(pagerState.currentPage) {
        onMediaPageChanged(pagerState.currentPage)
    }
    // Stable container ratio: LOCKED after first composition to prevent layout shift.
    // Uses cached aspect ratios if available; otherwise commits to 16:9 default.
    // The real ratio is still written to MediaAspectRatioCache on load so NEXT
    // time this card appears it uses the correct size from the start.
    val containerRatio = remember(mediaList) {
        val ratios = mediaList.map { url ->
            MediaAspectRatioCache.get(url)
                ?: if (UrlDetector.isVideoUrl(url)) 16f / 9f else null
        }
        val known = ratios.filterNotNull()
        if (known.isNotEmpty()) known.min() else (16f / 9f)
    }
    // Pass vertical scroll through to parent LazyColumn so the HorizontalPager
    // doesn't steal vertical gestures. Only single-image carousels disable paging entirely.
    val verticalPassthrough = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Let the parent consume all vertical scroll — pager only gets horizontal
                return if (abs(available.y) > abs(available.x)) available.copy(x = 0f) else Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                return if (abs(available.y) > abs(available.x)) available.copy(x = 0f) else Velocity.Zero
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio((containerRatio ?: (16f / 9f)).coerceIn(0.3f, 3.0f))
            .clipToBounds()
            .nestedScroll(verticalPassthrough)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 0.dp,
            beyondViewportPageCount = 1,
            userScrollEnabled = mediaList.size > 1
        ) { page ->
            val url = mediaList[page]
            val isVideo = UrlDetector.isVideoUrl(url)
            val isCurrentPage = pagerState.currentPage == page
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (!isVideo) Modifier.clickable {
                            onImageTap(allMediaUrls, groupStartIndex + page)
                        } else Modifier
                    )
            ) {
                if (isVideo) {
                    InlineVideoPlayer(
                        url = url,
                        modifier = Modifier.fillMaxSize(),
                        isVisible = isCurrentPage && isVisible,
                        onFullscreenClick = {
                            onVideoClick(allMediaUrls, groupStartIndex + page)
                        }
                    )
                } else {
                    val imageContext = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(imageContext)
                            .data(url)
                            .crossfade(true)
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                        onSuccess = { state ->
                            // Cache the real ratio for future renders — does NOT
                            // resize the current container (ratio is locked).
                            val drawable = state.result.drawable
                            MediaAspectRatioCache.add(url, drawable.intrinsicWidth, drawable.intrinsicHeight)
                        }
                    )
                    // Fullscreen magnifier icon — one per image
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) {
                                onOpenImageViewer(allMediaUrls, groupStartIndex + page)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Fullscreen,
                            contentDescription = "View fullscreen",
                            modifier = Modifier.size(20.dp),
                            tint = Color.White
                        )
                    }
                }
            }
        }
        if (mediaList.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(mediaList.size) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (pagerState.currentPage == index) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (pagerState.currentPage == index)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                    )
                }
            }
        }
    }
}

/**
 * Extracted expandable details panel — boosts, reactions, zaps breakdown.
 * Splits the inner AnimatedVisibility of NoteCard to reduce ART JIT instruction count.
 */
@Composable
private fun NoteDetailsPanel(
    note: Note,
    isDetailsExpanded: Boolean,
    overrideReactions: List<String>?,
    overrideReactionAuthors: Map<String, List<String>>?,
    overrideZapCount: Int?,
    overrideZapTotalSats: Long?,
    overrideZapAuthors: List<String>?,
    overrideZapAmountByAuthor: Map<String, Long>?,
    overrideCustomEmojiUrls: Map<String, String>?,
    overrideRepostAuthors: List<String>? = null,
    isZapped: Boolean,
    myZappedAmount: Long?,
    onProfileClick: (String) -> Unit,
    onSeeAllReactions: (() -> Unit)?,
) {
    AnimatedVisibility(
        visible = isDetailsExpanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        val profileCache = remember { ProfileMetadataCache.getInstance() }
        val detailReactions = overrideReactions ?: note.reactions
        val detailZapCount = (overrideZapCount ?: note.zapCount).coerceAtLeast(0)
        // Merge repost authors from note + NoteCountsRepository
        val boostAuthors = remember(note.repostedByAuthors, overrideRepostAuthors) {
            if (overrideRepostAuthors.isNullOrEmpty()) {
                note.repostedByAuthors
            } else {
                val existingIds = note.repostedByAuthors.map { it.id }.toSet()
                val extra = overrideRepostAuthors.filter { it !in existingIds }.map { profileCache.resolveAuthor(it) }
                (note.repostedByAuthors + extra).distinctBy { it.id }
            }
        }
        val hasBoosts = boostAuthors.isNotEmpty()
        val hasReactions = detailReactions.isNotEmpty()
        val hasZaps = detailZapCount > 0

        var boostsExpanded by remember { mutableStateOf(false) }
        var reactionsExpanded by remember { mutableStateOf(false) }
        var zapsExpanded by remember { mutableStateOf(false) }
        if (hasBoosts || hasReactions || hasZaps) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* consume clicks — dead zone */ }
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                    // Boosts summary row (tappable)
                    if (hasBoosts) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { boostsExpanded = !boostsExpanded }
                                .padding(vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Repeat,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFF4CAF50)
                            )
                            Spacer(Modifier.width(8.dp))
                            val boostAvatars = boostAuthors.take(5)
                            Box(modifier = Modifier.width((20 + (boostAvatars.size - 1).coerceAtLeast(0) * 12).dp)) {
                                boostAvatars.forEachIndexed { i, author ->
                                    Box(modifier = Modifier.offset(x = (i * 12).dp)) {
                                        ProfilePicture(author = author, size = 20.dp, onClick = { onProfileClick(author.id) })
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${boostAuthors.size} boost${if (boostAuthors.size != 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (boostsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        // Expanded: 3 latest boosters
                        AnimatedVisibility(visible = boostsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            Column(modifier = Modifier.padding(start = 22.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                boostAuthors.take(3).forEach { author ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                    ) {
                                        ProfilePicture(author = author, size = 20.dp, onClick = { onProfileClick(author.id) })
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = authorDisplayLabel(author),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.clickable { onProfileClick(author.id) }
                                        )
                                    }
                                }
                                if (boostAuthors.size > 3 && onSeeAllReactions != null) {
                                    Text(
                                        text = "see all ${boostAuthors.size} boosts",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clickable { onSeeAllReactions() }
                                            .padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Reactions summary row (tappable)
                    if (hasReactions) {
                        val detailReactionAuthors = overrideReactionAuthors ?: emptyMap()
                        // Sort emojis by actual popularity (number of authors), not just presence
                        val grouped = remember(detailReactions, detailReactionAuthors) {
                            detailReactions.map { emoji ->
                                emoji to (detailReactionAuthors[emoji]?.size ?: 1)
                            }.sortedByDescending { it.second }
                        }
                        val totalReactionCount = if (detailReactionAuthors.isNotEmpty()) {
                            detailReactionAuthors.values.sumOf { it.size }
                        } else detailReactions.size
                        val detailEmojiUrls = overrideCustomEmojiUrls ?: emptyMap()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { reactionsExpanded = !reactionsExpanded }
                                .padding(vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFFE91E63)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Reactions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            // Show top 5 emoji chips inline sorted by popularity
                            val showcased = grouped.take(5)
                            showcased.forEach { (emoji, count) ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ReactionEmoji(emoji = emoji, customEmojiUrls = detailEmojiUrls, fontSize = 13.sp, imageSize = 14.dp)
                                        if (count > 1) {
                                            Spacer(Modifier.width(2.dp))
                                            Text(text = "$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            if (totalReactionCount > 0) {
                                Text(
                                    text = "$totalReactionCount",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFE57373)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Icon(
                                imageVector = if (reactionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        // Expanded: 3 latest individual reactions across all emojis
                        AnimatedVisibility(visible = reactionsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            val rxProfileCache = remember { ProfileMetadataCache.getInstance() }
                            // Build flat list of (emoji, pubkey) pairs — latest 3
                            val latestReactions = remember(overrideReactionAuthors) {
                                val flat = mutableListOf<Pair<String, String>>()
                                overrideReactionAuthors?.forEach { (emoji, authors) ->
                                    authors.forEach { pubkey -> flat.add(emoji to pubkey) }
                                }
                                flat.takeLast(3).reversed() // latest first
                            }
                            val rxPubkeys = remember(latestReactions) { latestReactions.map { it.second }.distinct() }
                            var rxProfileRevision by remember { mutableIntStateOf(0) }
                            LaunchedEffect(rxPubkeys) {
                                val uncached = rxPubkeys.filter { rxProfileCache.getAuthor(it) == null }
                                if (uncached.isNotEmpty()) rxProfileCache.requestProfiles(uncached, rxProfileCache.getConfiguredRelayUrls())
                            }
                            LaunchedEffect(Unit) { rxProfileCache.profileUpdated.collect { pk -> if (pk in rxPubkeys) rxProfileRevision++ } }
                            @Suppress("UNUSED_EXPRESSION") rxProfileRevision
                            Column(modifier = Modifier.padding(start = 22.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                latestReactions.forEach { (emoji, pubkey) ->
                                    val author = rxProfileCache.resolveAuthor(pubkey)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                    ) {
                                        ReactionEmoji(emoji = emoji, customEmojiUrls = detailEmojiUrls, fontSize = 14.sp, imageSize = 16.dp)
                                        Spacer(Modifier.width(6.dp))
                                        ProfilePicture(author = author, size = 20.dp, onClick = { onProfileClick(author.id) })
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = authorDisplayLabel(author),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.clickable { onProfileClick(author.id) }
                                        )
                                    }
                                }
                                if (totalReactionCount > 3 && onSeeAllReactions != null) {
                                    Text(
                                        text = "see all $totalReactionCount reactions",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clickable { onSeeAllReactions() }
                                            .padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Zaps summary row (tappable)
                    if (hasZaps) {
                        val detailZapTotalSats = (overrideZapTotalSats ?: 0L).coerceAtLeast(0L)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { zapsExpanded = !zapsExpanded }
                                .padding(vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFFF59E0B)
                            )
                            Spacer(Modifier.width(8.dp))
                            if (detailZapTotalSats > 0) {
                                Text(
                                    text = "${social.mycelium.android.utils.ZapUtils.formatZapAmount(detailZapTotalSats)} sats",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFF59E0B)
                                )
                                Text(
                                    text = " ($detailZapCount zap${if (detailZapCount != 1) "s" else ""})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Text(
                                    text = "$detailZapCount zap${if (detailZapCount != 1) "s" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Icon(
                                imageVector = if (zapsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        // Expanded: 3 latest zappers
                        AnimatedVisibility(visible = zapsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            val zapProfileCache = remember { ProfileMetadataCache.getInstance() }
                            val allZapPubkeys = remember(overrideZapAuthors) { overrideZapAuthors?.distinct() ?: emptyList() }
                            var zapProfileRevision by remember { mutableIntStateOf(0) }
                            LaunchedEffect(allZapPubkeys) {
                                val uncached = allZapPubkeys.filter { zapProfileCache.getAuthor(it) == null }
                                if (uncached.isNotEmpty()) zapProfileCache.requestProfiles(uncached, zapProfileCache.getConfiguredRelayUrls())
                            }
                            LaunchedEffect(Unit) { zapProfileCache.profileUpdated.collect { pk -> if (pk in allZapPubkeys) zapProfileRevision++ } }
                            @Suppress("UNUSED_EXPRESSION") zapProfileRevision
                            Column(modifier = Modifier.padding(start = 22.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (isZapped && myZappedAmount != null && myZappedAmount > 0) {
                                    Text(
                                        text = "You zapped $myZappedAmount sats",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFF59E0B)
                                    )
                                }
                                if (!overrideZapAuthors.isNullOrEmpty()) {
                                    val sortedZapAuthors = remember(overrideZapAuthors, overrideZapAmountByAuthor) {
                                        overrideZapAuthors.sortedByDescending { overrideZapAmountByAuthor?.get(it) ?: 0L }
                                    }
                                    sortedZapAuthors.take(3).forEach { pubkey ->
                                        val author = zapProfileCache.resolveAuthor(pubkey)
                                        val zapSats = overrideZapAmountByAuthor?.get(pubkey) ?: 0L
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp)
                                        ) {
                                            ProfilePicture(author = author, size = 20.dp, onClick = { onProfileClick(author.id) })
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = authorDisplayLabel(author),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false).clickable { onProfileClick(author.id) }
                                            )
                                            if (zapSats > 0) {
                                                Text(
                                                    text = " ⚡ ${social.mycelium.android.utils.ZapUtils.formatZapAmount(zapSats)} sats",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFFF59E0B)
                                                )
                                            }
                                        }
                                    }
                                    if (allZapPubkeys.size > 3 && onSeeAllReactions != null) {
                                        Text(
                                            text = "see all ${allZapPubkeys.size} zaps",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clickable { onSeeAllReactions() }
                                                .padding(vertical = 2.dp)
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "$detailZapCount total zap${if (detailZapCount != 1) "s" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // "see all" link to open full ReactionsScreen
                    if (onSeeAllReactions != null) {
                        Text(
                            text = "see all",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSeeAllReactions() }
                                .padding(vertical = 4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RectangleShape
            ) {
                Text(
                    text = "No reactions yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * Extracted action row + 3-dot menu + zap menu.
 * Splits the deeply nested DropdownMenu lambdas out of NoteCard to reduce ART JIT instruction count.
 */
@OptIn(ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
private fun NoteActionRow(
    note: Note,
    actionRowSchema: ActionRowSchema,
    isZapInProgress: Boolean,
    isZapped: Boolean,
    reactionEmoji: String?,
    isDetailsExpanded: Boolean,
    onDetailsToggle: () -> Unit,
    isZapMenuExpanded: Boolean,
    onZapMenuToggle: () -> Unit,
    onShowCustomZapDialog: () -> Unit,
    onShowReactionPicker: () -> Unit,
    onComment: (String) -> Unit,
    onBoost: ((Note) -> Unit)?,
    onQuote: ((Note) -> Unit)?,
    onFork: ((Note) -> Unit)?,
    onZap: (String, Long) -> Unit,
    onCustomZap: (String) -> Unit,
    onZapSettings: () -> Unit,
    onProfileClick: (String) -> Unit,
    isAuthorFollowed: Boolean,
    onFollowAuthor: ((String) -> Unit)?,
    onUnfollowAuthor: ((String) -> Unit)?,
    onMessageAuthor: ((String) -> Unit)?,
    onBlockAuthor: ((String) -> Unit)?,
    onMuteAuthor: ((String) -> Unit)?,
    onBookmarkToggle: ((String, Boolean) -> Unit)?,
    extraMoreMenuItems: List<Pair<String, () -> Unit>>,
    translationResult: social.mycelium.android.repository.TranslationService.TranslationResult?,
    isTranslating: Boolean,
    showOriginal: Boolean,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
    onShowTranslation: () -> Unit,
) {
    var showRepostMenu by remember { mutableStateOf(false) }
    var showHeaderMenu by remember { mutableStateOf(false) }
    var menuLevel by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    // Menu content and helpers declared before Row so DropdownMenu inside Box can reference them
    val authorLabel = remember(note.author) { authorDisplayLabel(note.author) }
    val closeMenu = { showHeaderMenu = false; menuLevel = 0 }

    @Composable
    fun MoreMenuContent() {
        when (menuLevel) {
            // ═══ ROOT LEVEL ═══
            0 -> {
                // Follow / Unfollow
                if (isAuthorFollowed) {
                    onUnfollowAuthor?.let { unfollow ->
                        DropdownMenuItem(
                            text = { Text("Unfollow $authorLabel") },
                            leadingIcon = { Icon(Icons.Default.PersonRemove, null, modifier = Modifier.size(18.dp)) },
                            onClick = { closeMenu(); unfollow(note.author.id) }
                        )
                    }
                } else {
                    onFollowAuthor?.let { follow ->
                        DropdownMenuItem(
                            text = { Text("Follow $authorLabel") },
                            leadingIcon = { Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(18.dp)) },
                            onClick = { closeMenu(); follow(note.author.id) }
                        )
                    }
                }
                // Message author
                onMessageAuthor?.let { msg ->
                    DropdownMenuItem(
                        text = { Text("Message $authorLabel") },
                        leadingIcon = { Icon(Icons.Default.Send, null, modifier = Modifier.size(18.dp)) },
                        onClick = { closeMenu(); msg(note.author.id) }
                    )
                }
                // Copy → sub-menu
                DropdownMenuItem(
                    text = { Text("Copy\u2026") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowRight, null, modifier = Modifier.size(18.dp)) },
                    onClick = { menuLevel = 1 }
                )
                // Bookmarks → sub-menu
                DropdownMenuItem(
                    text = { Text("Bookmarks") },
                    leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowRight, null, modifier = Modifier.size(18.dp)) },
                    onClick = { menuLevel = 2 }
                )
                // Share (direct action)
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        closeMenu()
                        val nevent = com.example.cybin.nip19.encodeNevent(
                            eventIdHex = note.id,
                            relays = note.relayUrls.take(2),
                            authorHex = note.author.id
                        )
                        val sendIntent = android.content.Intent().apply {
                            action = android.content.Intent.ACTION_SEND
                            putExtra(android.content.Intent.EXTRA_TEXT, "https://njump.me/$nevent")
                            type = "text/plain"
                        }
                        context.startActivity(android.content.Intent.createChooser(sendIntent, "Share note"))
                    }
                )
                // Translate / Show Original
                if (translationResult != null && !showOriginal) {
                    DropdownMenuItem(
                        text = { Text("Show original") },
                        leadingIcon = { Icon(Icons.Outlined.Translate, null, modifier = Modifier.size(18.dp)) },
                        onClick = { onShowOriginal(); closeMenu() }
                    )
                } else {
                    DropdownMenuItem(
                        text = {
                            if (isTranslating) Text("Translating\u2026")
                            else if (translationResult != null) Text("Show translation")
                            else Text("Translate")
                        },
                        leadingIcon = {
                            if (isTranslating) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 1.5.dp)
                            else Icon(Icons.Outlined.Translate, null, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            closeMenu()
                            if (translationResult != null) {
                                onShowTranslation()
                            } else {
                                onTranslate()
                            }
                        }
                    )
                }
                // Filters & Blocks → sub-menu
                if (onBlockAuthor != null || onMuteAuthor != null) {
                    DropdownMenuItem(
                        text = { Text("Filters & Blocks") },
                        leadingIcon = { Icon(Icons.Outlined.Block, null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = { Icon(Icons.Default.KeyboardArrowRight, null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuLevel = 3 }
                    )
                }
                // Report
                DropdownMenuItem(
                    text = { Text("Report") },
                    leadingIcon = { Icon(Icons.Default.Report, null, modifier = Modifier.size(18.dp)) },
                    onClick = { closeMenu() }
                )
                // Extra items from caller
                extraMoreMenuItems.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { action(); closeMenu() }
                    )
                }
            }
            // ═══ COPY SUB-MENU ═══
            1 -> {
                DropdownMenuItem(
                    text = { Text("Copy text") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        closeMenu()
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Note text", note.content))
                    }
                )
                DropdownMenuItem(
                    text = { Text("Copy author npub") },
                    leadingIcon = { Icon(Icons.Outlined.Person, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        closeMenu()
                        val npub = note.author.id.toNpub()
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("npub", npub))
                    }
                )
                DropdownMenuItem(
                    text = { Text("Copy nevent") },
                    leadingIcon = { Icon(Icons.Outlined.Link, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        closeMenu()
                        val nevent = com.example.cybin.nip19.encodeNevent(
                            eventIdHex = note.id,
                            relays = note.relayUrls.take(2),
                            authorHex = note.author.id
                        )
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("nevent", nevent))
                    }
                )
                DropdownMenuItem(
                    text = { Text("← Back") },
                    leadingIcon = { Icon(Icons.Default.KeyboardArrowLeft, null, modifier = Modifier.size(18.dp)) },
                    onClick = { menuLevel = 0 }
                )
            }
            // ═══ BOOKMARKS SUB-MENU ═══
            2 -> {
                val bookmarkedIds by social.mycelium.android.repository.BookmarkRepository.bookmarkedNoteIds.collectAsState()
                val isBookmarked = note.id in bookmarkedIds
                DropdownMenuItem(
                    text = { Text(if (isBookmarked) "Remove bookmark" else "Add to public") },
                    leadingIcon = {
                        Icon(
                            if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        closeMenu()
                        onBookmarkToggle?.invoke(note.id, isBookmarked)
                    }
                )
                DropdownMenuItem(
                    text = { Text("← Back") },
                    leadingIcon = { Icon(Icons.Default.KeyboardArrowLeft, null, modifier = Modifier.size(18.dp)) },
                    onClick = { menuLevel = 0 }
                )
            }
            // ═══ FILTERS & BLOCKS SUB-MENU ═══
            3 -> {
                onBlockAuthor?.let { block ->
                    DropdownMenuItem(
                        text = { Text("Block ${authorDisplayLabel(note.author)}") },
                        leadingIcon = { Icon(Icons.Outlined.Block, null, modifier = Modifier.size(18.dp)) },
                        onClick = { closeMenu(); block(note.author.id) }
                    )
                }
                onMuteAuthor?.let { mute ->
                    DropdownMenuItem(
                        text = { Text("Mute ${authorDisplayLabel(note.author)}") },
                        leadingIcon = { Icon(Icons.Outlined.VolumeOff, null, modifier = Modifier.size(18.dp)) },
                        onClick = { closeMenu(); mute(note.author.id) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("← Back") },
                    leadingIcon = { Icon(Icons.Default.KeyboardArrowLeft, null, modifier = Modifier.size(18.dp)) },
                    onClick = { menuLevel = 0 }
                )
            }
        }
    }

    // Action row — layout driven by actionRowSchema, right-aligned with consistent sizing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val showVoting = actionRowSchema != ActionRowSchema.KIND1_FEED && actionRowSchema != ActionRowSchema.KIND1_REPLY
        val showBoost = actionRowSchema != ActionRowSchema.KIND1111_REPLY
        val showReply = actionRowSchema == ActionRowSchema.KIND1111_REPLY || actionRowSchema == ActionRowSchema.KIND1_REPLY

        // Upvote / Downvote — kind-11 feed + kind-1111 replies
        if (showVoting) {
            ActionButton(
                icon = Icons.Outlined.ArrowUpward,
                contentDescription = "Upvote",
                onClick = { /* TODO: Upvote */ }
            )
            ActionButton(
                icon = Icons.Outlined.ArrowDownward,
                contentDescription = "Downvote",
                onClick = { /* TODO: Downvote */ }
            )
        }

        // Boost (Repost / Quote / Fork) — kind-1 feed + kind-11 feed
        if (showBoost) {
            ActionButton(
                icon = Icons.Outlined.Repeat,
                contentDescription = "Repost",
                onClick = { showRepostMenu = true }
            )
        }

        // Lightning (Zap) — all schemas
        Box(
            modifier = Modifier
                .size(40.dp)
                .combinedClickable(
                    onClick = onZapMenuToggle,
                    onLongClick = onShowCustomZapDialog
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                isZapInProgress -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                else -> Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = "Zap",
                    tint = if (isZapped) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Likes / React button (NIP-25) — all schemas
        ReactionButton(
            emoji = reactionEmoji,
            onClick = onShowReactionPicker
        )

        // Reply — kind-1111 replies only
        if (showReply) {
            ActionButton(
                icon = Icons.Outlined.ChatBubbleOutline,
                contentDescription = "Reply",
                onClick = { onComment(note.id) }
            )
        }

        // Reactions caret — all schemas
        ActionButton(
            icon = if (isDetailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = "Details",
            onClick = onDetailsToggle
        )

        // 3-dot More menu
        ActionButton(
            icon = Icons.Default.MoreVert,
            contentDescription = "More",
            onClick = { showHeaderMenu = true }
        )
    }

    // ── Boost dialog — centered floating menu ──
    if (showRepostMenu) {
        Dialog(onDismissRequest = { showRepostMenu = false }) {
            @OptIn(ExperimentalMaterial3Api::class)
            CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration()) {
                Surface(
                    shape = RectangleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth(0.75f)
                ) {
                    Column {
                        DropdownMenuItem(
                            text = { Text("Boost") },
                            leadingIcon = { Icon(Icons.Outlined.Repeat, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = { showRepostMenu = false; onBoost?.invoke(note) }
                        )
                        DropdownMenuItem(
                            text = { Text("Quote") },
                            leadingIcon = { Icon(Icons.Outlined.FormatQuote, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = { showRepostMenu = false; onQuote?.invoke(note) }
                        )
                        DropdownMenuItem(
                            text = { Text("Fork") },
                            leadingIcon = { Icon(Icons.Outlined.ForkRight, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = { showRepostMenu = false; onFork?.invoke(note) }
                        )
                    }
                }
            }
        }
    }

    // ── More menu dialog — centered floating menu ──
    if (showHeaderMenu) {
        Dialog(onDismissRequest = { showHeaderMenu = false; menuLevel = 0 }) {
            @OptIn(ExperimentalMaterial3Api::class)
            CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration()) {
                Surface(
                    shape = RectangleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Column {
                        MoreMenuContent()
                    }
                }
            }
        }
    }

    // Zap menu - completely separate, appears below action buttons; Custom chip opens dialog
    ZapMenuRow(
        isExpanded = isZapMenuExpanded,
        onExpandedChange = { /* controlled by parent */ },
        onZap = { amount -> onZap(note.id, amount) },
        onCustomZap = { onShowCustomZapDialog(); onCustomZap(note.id) },
        onSettingsClick = onZapSettings
    )
}

/** Display name for author: prefer displayName, fall back to username, then short pubkey. */
private fun authorDisplayLabel(author: social.mycelium.android.data.Author): String {
    val d = author.displayName
    // Only treat as placeholder if it looks like a truncated hex pubkey (8 hex chars + "...")
    val isPlaceholder = d.length == 11 && d.endsWith("...") && d.substring(0, 8).all { it in '0'..'9' || it in 'a'..'f' }
    if (!isPlaceholder) return d
    // displayName was a placeholder; try username instead
    val u = author.username
    val uIsPlaceholder = u.length == 11 && u.endsWith("...") && u.substring(0, 8).all { it in '0'..'9' || it in 'a'..'f' }
    return if (!uIsPlaceholder) u else author.id.take(8) + "..."
}


/**
 * Thin animated progress line at the top of a NoteCard showing publish lifecycle.
 * - Sending: animated indeterminate shimmer (primary color)
 * - Confirmed: full-width green line that fades out
 * - Failed: full-width red line
 */
@Composable
private fun PublishProgressLine(state: PublishState) {
    val height = 3.dp
    when (state) {
        PublishState.Sending -> {
            val infiniteTransition = rememberInfiniteTransition(label = "publish_shimmer")
            val offset by infiniteTransition.animateFloat(
                initialValue = -0.3f,
                targetValue = 1.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "shimmer_offset"
            )
            val color = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(
                                color.copy(alpha = 0f),
                                color.copy(alpha = 0.8f),
                                color.copy(alpha = 0f)
                            ),
                            startX = offset * 1000f,
                            endX = (offset + 0.3f) * 1000f
                        )
                    )
            )
        }
        PublishState.Confirmed -> {
            // Green line expands from center to edges, then fades
            val expandFraction by animateFloatAsState(
                targetValue = 1f,
                animationSpec = tween(400, easing = FastOutSlowInEasing),
                label = "confirmed_expand"
            )
            val fadeAlpha by animateFloatAsState(
                targetValue = 0f,
                animationSpec = tween(1500, delayMillis = 600),
                label = "confirmed_fade"
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = expandFraction)
                        .height(height)
                        .background(Color(0xFF4CAF50).copy(alpha = 0.7f * (1f - fadeAlpha * 0.7f)))
                )
            }
        }
        PublishState.Failed -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun NoteCard(
    note: Note,
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onReact: (Note, String) -> Unit = { _, _ -> },
    /** Boost (kind-6 repost): republish the note as-is. */
    onBoost: ((Note) -> Unit)? = null,
    /** Quote: open compose with this note quoted (nostr:nevent1…). */
    onQuote: ((Note) -> Unit)? = null,
    /** Fork: open compose pre-filled with this note's content for editing. */
    onFork: ((Note) -> Unit)? = null,
    onProfileClick: (String) -> Unit = {},
    onNoteClick: (Note) -> Unit = {},
    /** Called when user taps the image (not the magnifier): (note, urls, index). E.g. feed = open thread, thread = open viewer. */
    onImageTap: (Note, List<String>, Int) -> Unit = { _, _, _ -> },
    /** Called when user taps the magnifier on an image: open full viewer. */
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    /** Called when user taps a video: (urls, initialIndex). */
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    onZap: (String, Long) -> Unit = { _, _ -> },
    onCustomZap: (String) -> Unit = {},
    onCustomZapSend: ((Note, Long, ZapType, String) -> Unit)? = null,
    onZapSettings: () -> Unit = {},
    shouldCloseZapMenus: Boolean = false,
    /** Called when user taps a relay orb to show relay info. */
    onRelayClick: (relayUrl: String) -> Unit = {},
    /** When set, tapping relay orbs navigates to a dedicated relay list screen. */
    onNavigateToRelayList: ((List<String>) -> Unit)? = null,
    /** Extra items for the 3-dot More menu (e.g. "Copy text" on thread view). */
    extraMoreMenuItems: List<Pair<String, () -> Unit>> = emptyList(),
    /** Whether the current user follows the note's author. */
    isAuthorFollowed: Boolean = false,
    /** Follow the note's author. */
    onFollowAuthor: ((String) -> Unit)? = null,
    /** Unfollow the note's author. */
    onUnfollowAuthor: ((String) -> Unit)? = null,
    /** Open a DM chat with the note's author. */
    onMessageAuthor: ((String) -> Unit)? = null,
    /** Block the note's author. */
    onBlockAuthor: ((String) -> Unit)? = null,
    /** Mute the note's author. */
    onMuteAuthor: ((String) -> Unit)? = null,
    /** Toggle bookmark for a note: (noteId, isCurrentlyBookmarked) -> Unit. */
    onBookmarkToggle: ((String, Boolean) -> Unit)? = null,
    /** Current account npub for per-account recent emoji list. */
    accountNpub: String? = null,
    /** True while a zap is being sent for this note (shows loading on bolt). */
    isZapInProgress: Boolean = false,
    /** True if current user has zapped this note (bolt turns yellow). */
    isZapped: Boolean = false,
    /** Amount (sats) the current user zapped this note; shown as "You zapped X sats" when isZapped. */
    myZappedAmount: Long? = null,
    /** Override comment count (e.g. from ReplyCountCache when thread was loaded); used for counts row when non-null. */
    overrideReplyCount: Int? = null,
    /** Override zap count (e.g. from NoteCountsRepository kind-9735). */
    overrideZapCount: Int? = null,
    /** Total sats zapped (from NoteCountsRepository bolt11 parsing). */
    overrideZapTotalSats: Long? = null,
    /** Override reaction emojis (e.g. from NoteCountsRepository kind-7). */
    overrideReactions: List<String>? = null,
    /** Override reaction authors keyed by emoji (from NoteCountsRepository). */
    overrideReactionAuthors: Map<String, List<String>>? = null,
    /** Override zap author pubkeys (from NoteCountsRepository). */
    overrideZapAuthors: List<String>? = null,
    /** Per-author zap amounts in sats (from NoteCountsRepository). */
    overrideZapAmountByAuthor: Map<String, Long>? = null,
    /** NIP-30 custom emoji URLs: maps ":shortcode:" to image URL for rendering custom emoji reactions. */
    overrideCustomEmojiUrls: Map<String, String>? = null,
    /** When false, hides counts row and action row (like/reply/zap/more); used for compact reply in thread. */
    showActionRow: Boolean = true,
    /** Controls which buttons appear in the action row (kind-1 feed, kind-11 feed, kind-1111 reply). */
    actionRowSchema: ActionRowSchema = ActionRowSchema.KIND1_FEED,
    /** When set (e.g. thread view), author matching this id gets OP highlight and "OP" label; score • time shown on author line. */
    rootAuthorId: String? = null,
    /** When true (e.g. thread view), link embed shows expanded description. */
    expandLinkPreviewInThread: Boolean = false,
    /** When false, hides hashtags section below body (used in feed contexts). */
    showHashtagsSection: Boolean = true,
    /** Initial page index for the media album (shared state from AppViewModel). */
    initialMediaPage: Int = 0,
    /** Called when user swipes the media album to a different page. */
    onMediaPageChanged: (Int) -> Unit = {},
    /** Whether this card is currently visible on screen; off-screen cards pause video playback. */
    isVisible: Boolean = true,
    /** Aggregated counts for all notes (feed + quoted); used to show counters on quoted note previews. */
    countsByNoteId: Map<String, social.mycelium.android.repository.NoteCounts> = emptyMap(),
    /** Called when user taps "See all" in the expanded reaction details panel. */
    onSeeAllReactions: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isZapMenuExpanded by remember { mutableStateOf(false) }
    var showReactionPicker by remember { mutableStateOf(false) }
    var showCustomZapDialog by remember { mutableStateOf(false) }
    var isDetailsExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var reactionEmoji by remember(note.id) { mutableStateOf(ReactionsRepository.getLastReaction(note.id)) }
    var recentEmojis by remember(accountNpub) { mutableStateOf(ReactionsRepository.getRecentEmojis(context, accountNpub)) }

    // On-demand translation state
    var translationResult by remember(note.id) { mutableStateOf(social.mycelium.android.repository.TranslationService.getCached(note.id)) }
    var isTranslating by remember(note.id) { mutableStateOf(false) }
    var showOriginal by remember(note.id) { mutableStateOf(false) }
    val translationScope = rememberCoroutineScope()

    // Close zap menu when feed scrolls
    LaunchedEffect(shouldCloseZapMenus) {
        if (shouldCloseZapMenus) {
            isZapMenuExpanded = false
        }
    }
    // Observe profileUpdated directly so profile always renders correctly even if repository timing is off
    val profileCache = ProfileMetadataCache.getInstance()
    var profileRevision by remember { mutableIntStateOf(0) }
    val authorPubkey = remember(note.author.id) { normalizeAuthorIdForCache(note.author.id) }
    LaunchedEffect(authorPubkey) {
        profileCache.profileUpdated
            .filter { it == authorPubkey }
            .debounce(500)
            .collect { profileRevision++ }
    }
    // Snapshot read of diskCacheRestored avoids per-card flow collector; value only flips once at startup
    val diskCacheReady = profileCache.diskCacheRestored.value
    val displayAuthor = remember(note.author.id, profileRevision, diskCacheReady) {
        profileCache.resolveAuthor(note.author.id)
    }
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onNoteClick(note) },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RectangleShape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Publish progress line ──────────────────────────────────────
            if (note.publishState != null) {
                PublishProgressLine(state = note.publishState)
            }
            // Author info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar with shared element support
                ProfilePicture(
                    author = displayAuthor,
                    size = 40.dp,
                    onClick = { onProfileClick(note.author.id) }
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                            val isOp = rootAuthorId != null && normalizeAuthorIdForCache(note.author.id) == normalizeAuthorIdForCache(rootAuthorId)
                            if (isOp) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = Color(0xFF8E30EB),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = authorDisplayLabel(displayAuthor),
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "OP",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    text = authorDisplayLabel(displayAuthor),
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (displayAuthor.isVerified) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "Verified",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        if (rootAuthorId != null) {
                            val formattedTime = remember(note.timestamp) { formatTimestamp(note.timestamp) }
                            val score = note.likes
                            Text(
                                text = "$score • $formattedTime",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
                val relayUrlsForOrbs = remember(note.relayUrls, note.relayUrl) { note.displayRelayUrls() }
                RelayOrbs(relayUrls = relayUrlsForOrbs, onRelayClick = onRelayClick, onNavigateToRelayList = onNavigateToRelayList)
            }

            val uriHandler = LocalUriHandler.current
            val hasBodyText = note.content.isNotBlank() || note.quotedEventIds.isNotEmpty()

            // Optional SUBJECT/TOPIC row (kind-11 / kind-1111)
            val topicTitle = note.topicTitle
            if (!topicTitle.isNullOrEmpty()) {
                Text(
                    text = topicTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            val firstPreview = note.urlPreviews.firstOrNull()

            // Counts row: above embed and body (when action row is shown)
            if (showActionRow) {
                val replyCountVal = (overrideReplyCount ?: note.comments).coerceAtLeast(0)
                val zapTotalSats = (overrideZapTotalSats ?: 0L).coerceAtLeast(0L)
                val zapCount = (overrideZapCount ?: note.zapCount).coerceAtLeast(0)
                val reactionsList = overrideReactions ?: note.reactions
                val reactionAuthorsMap = overrideReactionAuthors ?: emptyMap()
                val reactionCount = if (reactionAuthorsMap.isNotEmpty()) {
                    reactionAuthorsMap.values.sumOf { it.size }
                } else {
                    reactionsList.size
                }.coerceAtLeast(0)
                val formattedTime = remember(note.timestamp) { formatTimestamp(note.timestamp) }
                // Colors for count numbers
                val MyceliumGreen = Color(0xFF8FBC8F)
                val pastelRed = Color(0xFFE57373)
                val zapYellow = Color(0xFFFFD700)
                val mutedText = MaterialTheme.colorScheme.onSurfaceVariant
                val countStyle = MaterialTheme.typography.bodySmall
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left side: timestamp • replies
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = formattedTime, style = countStyle, color = mutedText)
                        if (replyCountVal > 0) {
                            Text(text = " • ", style = countStyle, color = mutedText)
                            Text(text = "$replyCountVal", style = countStyle, color = MyceliumGreen)
                            Text(text = " repl${if (replyCountVal == 1) "y" else "ies"}", style = countStyle, color = mutedText)
                        }
                    }
                    // Right side: reaction emojis • zaps
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (reactionCount > 0) {
                            // Show actual emoji characters (up to 5 unique), then count
                            val uniqueEmojis = reactionsList.distinct().take(5)
                            val emojiUrls = overrideCustomEmojiUrls ?: emptyMap()
                            uniqueEmojis.forEach { emoji ->
                                ReactionEmoji(
                                    emoji = emoji,
                                    customEmojiUrls = emojiUrls,
                                    fontSize = 13.sp,
                                    imageSize = 14.dp
                                )
                            }
                            if (reactionCount > 1) {
                                Text(text = " $reactionCount", style = countStyle, color = pastelRed)
                            }
                        }
                        if (reactionCount > 0 && (zapTotalSats > 0 || zapCount > 0)) {
                            Text(text = " • ", style = countStyle, color = mutedText)
                        }
                        if (zapTotalSats > 0) {
                            Text(text = social.mycelium.android.utils.ZapUtils.formatZapAmount(zapTotalSats), style = countStyle, color = zapYellow)
                            Text(text = " sats", style = countStyle, color = mutedText)
                        } else if (zapCount > 0) {
                            Text(text = "$zapCount", style = countStyle, color = zapYellow)
                            Text(text = " zap${if (zapCount == 1) "" else "s"}", style = countStyle, color = mutedText)
                        }
                    }
                }

                // ── Zapraiser progress bar (NIP-TBD) ──
                val zapraiserGoal = remember(note.tags) {
                    note.tags.firstOrNull { it.firstOrNull() == "zapraiser" }
                        ?.getOrNull(1)?.toLongOrNull()
                }
                if (zapraiserGoal != null && zapraiserGoal > 0) {
                    val raised = zapTotalSats
                    val progress = (raised.toFloat() / zapraiserGoal).coerceIn(0f, 1f)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${social.mycelium.android.utils.ZapUtils.formatZapAmount(raised)} raised",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFF59E0B)
                            )
                            Text(
                                text = "Goal: ${social.mycelium.android.utils.ZapUtils.formatZapAmount(zapraiserGoal)} sats",
                                style = MaterialTheme.typography.labelSmall,
                                color = mutedText.copy(alpha = 0.6f)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)),
                            color = Color(0xFFF59E0B),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        if (raised >= zapraiserGoal) {
                            Text(
                                text = "Goal reached!",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF66DDAA),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }

            // HTML embed: below counts, above body text
            if (firstPreview != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Kind1LinkEmbedBlock(
                    previewInfo = firstPreview,
                    expandDescriptionInThread = expandLinkPreviewInThread,
                    inThreadView = expandLinkPreviewInThread,
                    onUrlClick = { url -> uriHandler.openUri(url) },
                    onNoteClick = { onNoteClick(note) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            // Body zone: only when there is text or quoted notes; otherwise embed/media only
            val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
            // When firstPreview is shown as the top-level embed, pass its URL as "consumed"
            // so the text builder hides it from body text but does NOT add it to media groups
            // (the preview URL is a webpage, not an image — adding it to mediaUrls creates blank album entries)
            val consumedUrls = remember(firstPreview) {
                if (firstPreview != null) setOf(firstPreview.url) else emptySet()
            }
            // Track mentioned pubkeys so content rebuilds when their profiles load (npub→@displayName)
            val mentionedPubkeys = remember(note.content) {
                social.mycelium.android.utils.extractPubkeysFromContent(note.content)
            }
            var mentionProfileVersion by remember { mutableStateOf(0) }
            if (mentionedPubkeys.isNotEmpty()) {
                LaunchedEffect(mentionedPubkeys) {
                    val pubkeySet = mentionedPubkeys.toSet()
                    profileCache.profileUpdated
                        .filter { it in pubkeySet }
                        .debounce(600)
                        .collect { mentionProfileVersion++ }
                }
            }
            // Quoted note fetch state (hoisted so inline QuotedNote blocks can access it)
            var quotedMetas by remember(note.id) { mutableStateOf<Map<String, QuotedNoteMeta>>(emptyMap()) }
            var quotedFailedIds by remember(note.id) { mutableStateOf<Set<String>>(emptySet()) }
            var quotedLoading by remember(note.id) { mutableStateOf(note.quotedEventIds.isNotEmpty()) }
            var quotedProfileRevision by remember { mutableIntStateOf(0) }
            if (note.quotedEventIds.isNotEmpty()) {
                LaunchedEffect(note.quotedEventIds) {
                    quotedLoading = true
                    val newFailed = mutableSetOf<String>()
                    val fetched = note.quotedEventIds.mapNotNull { id ->
                        if (id in quotedMetas) null
                        else {
                            val meta = QuotedNoteCache.get(id)
                            if (meta != null) id to meta
                            else { newFailed.add(id); null }
                        }
                    }
                    if (fetched.isNotEmpty()) quotedMetas = quotedMetas + fetched.toMap()
                    if (newFailed.isNotEmpty()) quotedFailedIds = quotedFailedIds + newFailed
                    quotedLoading = false
                }
                // Observe profile updates for quoted note authors
                val quotedAuthorPubkeys = remember(quotedMetas) {
                    quotedMetas.values.map { normalizeAuthorIdForCache(it.authorId) }.toSet()
                }
                LaunchedEffect(quotedAuthorPubkeys) {
                    if (quotedAuthorPubkeys.isNotEmpty()) {
                        profileCache.profileUpdated
                            .filter { it in quotedAuthorPubkeys }
                            .debounce(600)
                            .collect { quotedProfileRevision++ }
                    }
                }
            }

            // Use translated text when available and user hasn't toggled to original
            val displayContent = if (translationResult != null && !showOriginal) translationResult!!.translatedText else note.content
            val contentIsMarkdown = remember(displayContent) { isMarkdown(displayContent) }
            val contentBlocks = remember(displayContent, note.mediaUrls, note.urlPreviews, consumedUrls, mentionProfileVersion, diskCacheReady) {
                buildNoteContentWithInlinePreviews(
                    displayContent,
                    note.mediaUrls.toSet(),
                    note.urlPreviews,
                    linkStyle,
                    profileCache,
                    consumedUrls
                )
            }
            // Collect all media URLs across all MediaGroup blocks for fullscreen viewer
            val allMediaUrls = remember(contentBlocks) {
                contentBlocks.filterIsInstance<NoteContentBlock.MediaGroup>().flatMap { it.urls }
                    .ifEmpty { note.mediaUrls }
            }

            // ═══ Sensitive content detection (NIP-36 content-warning tag or #nsfw hashtag) ═══
            val contentWarningReason = remember(note.tags) {
                note.tags.firstOrNull { it.firstOrNull() == "content-warning" }?.getOrNull(1)
            }
            val isSensitive = remember(note.tags, note.hashtags) {
                contentWarningReason != null || note.hashtags.any { it.equals("nsfw", ignoreCase = true) }
            }
            val showSensitivePref by social.mycelium.android.ui.settings.MediaPreferences.showSensitiveContent.collectAsState()
            var sensitiveRevealed by remember(note.id) { mutableStateOf(false) }
            val shouldBlur = isSensitive && !showSensitivePref && !sensitiveRevealed

            if (shouldBlur) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .clickable { sensitiveRevealed = true }
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.VisibilityOff,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = contentWarningReason?.let { "Sensitive: $it" } ?: "Sensitive content",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Tap to reveal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Render interleaved content blocks: text surfaces, inline media carousels, and previews
            if (!shouldBlur) contentBlocks.forEach { block ->
                when (block) {
                    is NoteContentBlock.Content -> {
                        val annotated = block.annotated
                        if (annotated.isNotEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RectangleShape,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp
                            ) {
                                if (contentIsMarkdown) {
                                    MarkdownNoteContent(
                                        content = annotated.text,
                                        style = NoteBodyTextStyle.copy(
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                        onProfileClick = onProfileClick,
                                        onNoteClick = { onNoteClick(note) },
                                        onUrlClick = { url -> uriHandler.openUri(url) },
                                        onHashtagClick = { /* TODO: hashtag navigation */ }
                                    )
                                } else {
                                    ClickableNoteContent(
                                        text = annotated,
                                        style = NoteBodyTextStyle.copy(
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                        onClick = { offset ->
                                            val profile = annotated.getStringAnnotations(tag = "PROFILE", start = offset, end = offset).firstOrNull()
                                            val url = annotated.getStringAnnotations(tag = "URL", start = offset, end = offset).firstOrNull()
                                            val naddr = annotated.getStringAnnotations(tag = "NADDR", start = offset, end = offset).firstOrNull()
                                            when {
                                                profile != null -> onProfileClick(profile.item)
                                                url != null -> uriHandler.openUri(url.item)
                                                naddr != null -> uriHandler.openUri(naddr.item)
                                                else -> onNoteClick(note)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    is NoteContentBlock.MediaGroup -> {
                        val mediaList = block.urls.take(10)
                        val groupStartIndex = remember(allMediaUrls, mediaList) {
                            allMediaUrls.indexOf(mediaList.first()).coerceAtLeast(0)
                        }
                        NoteMediaCarousel(
                            mediaList = mediaList,
                            allMediaUrls = allMediaUrls,
                            groupStartIndex = groupStartIndex,
                            initialMediaPage = initialMediaPage,
                            isVisible = isVisible,
                            onMediaPageChanged = onMediaPageChanged,
                            onImageTap = { urls, index -> onImageTap(note, urls, index) },
                            onOpenImageViewer = onOpenImageViewer,
                            onVideoClick = onVideoClick,
                        )
                    }
                    is NoteContentBlock.Preview -> {
                        // Inline preview block skipped when we show top-right thumbnail
                        if (firstPreview == null) {
                            UrlPreviewCard(
                                previewInfo = block.previewInfo,
                                onUrlClick = { url -> uriHandler.openUri(url) },
                                onUrlLongClick = { _ -> }
                            )
                        }
                    }
                    is NoteContentBlock.LiveEventReference -> {
                        // NIP-53 live event referenced via nevent (kind=30311)
                        val liveRepo = social.mycelium.android.repository.LiveActivityRepository.getInstance()
                        val allActivities by liveRepo.allActivities.collectAsState()
                        val activity = allActivities.firstOrNull { it.id == block.eventId }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RectangleShape
                        ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable {
                                    onNoteClick(Note(
                                        id = block.eventId,
                                        author = social.mycelium.android.data.Author(id = block.author ?: "", username = "", displayName = "Live Event"),
                                        content = "",
                                        timestamp = 0L,
                                        likes = 0, shares = 0, comments = 0,
                                        isLiked = false, hashtags = emptyList(),
                                        mediaUrls = emptyList(), isReply = false,
                                        relayUrl = block.relays.firstOrNull(),
                                        relayUrls = block.relays
                                    ))
                                },
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Red live dot or generic icon
                                if (activity?.status == social.mycelium.android.data.LiveActivityStatus.LIVE) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(androidx.compose.ui.graphics.Color(0xFFEF4444), androidx.compose.foundation.shape.CircleShape)
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
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    val subtitle = when (activity?.status) {
                                        social.mycelium.android.data.LiveActivityStatus.LIVE -> "LIVE" + (activity.currentParticipants?.let { " • $it viewers" } ?: "")
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
                    }
                    is NoteContentBlock.QuotedNote -> {
                        val eventId = block.eventId
                        val meta = quotedMetas[eventId]
                        if (meta != null) {
                            val quotedAuthor = remember(meta.authorId, quotedProfileRevision) {
                                profileCache.resolveAuthor(meta.authorId)
                            }
                            QuotedNoteContent(
                                meta = meta,
                                quotedAuthor = quotedAuthor,
                                quotedCounts = countsByNoteId[eventId],
                                linkStyle = linkStyle,
                                profileCache = profileCache,
                                isVisible = isVisible,
                                onProfileClick = onProfileClick,
                                onNoteClick = onNoteClick,
                                onVideoClick = onVideoClick,
                                onOpenImageViewer = onOpenImageViewer,
                            )
                        } else if (eventId in quotedFailedIds) {
                            // Outer wrapper matches body text background
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RectangleShape
                            ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Outlined.Info, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                    Text("Quoted event not found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                }
                            }
                            }
                        } else if (quotedLoading) {
                            // Outer wrapper matches body text background
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RectangleShape
                            ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                    Text("Loading quoted note\u2026", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                }
                            }
                            }
                        }
                    }
                }
            }

            // Translation label (subtle, below content)
            if (translationResult != null && !showOriginal) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Translate,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Text(
                        text = "Translated from ${java.util.Locale(translationResult!!.sourceLang).displayLanguage}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }

            // Hashtags
            if (showHashtagsSection && note.hashtags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    text = note.hashtags.joinToString(" ") { "#$it" },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // Repost label (kind-6) — below body, above controls
            if (note.repostedByAuthors.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color(0xFF4CAF50).copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(4.dp))
                    // Stacked avatars (up to 3)
                    val displayAuthors = note.repostedByAuthors.take(3)
                    Box(modifier = Modifier.width(if (displayAuthors.size > 1) (16 + (displayAuthors.size - 1) * 10).dp else 16.dp)) {
                        displayAuthors.forEachIndexed { i, author ->
                            Box(modifier = Modifier.offset(x = (i * 10).dp)) {
                                ProfilePicture(
                                    author = author,
                                    size = 16.dp,
                                    onClick = { onProfileClick(author.id) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    val repostTimeText = note.repostTimestamp?.let { " • ${formatTimestamp(it)}" } ?: ""
                    val boosterCount = note.repostedByAuthors.size
                    val firstName = authorDisplayLabel(note.repostedByAuthors.first())
                    val boostText = when {
                        boosterCount == 1 -> "$firstName boosted"
                        boosterCount == 2 -> "$firstName & ${authorDisplayLabel(note.repostedByAuthors[1])} boosted"
                        else -> "$firstName & ${boosterCount - 1} others boosted"
                    }
                    Text(
                        text = "$boostText$repostTimeText",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (showActionRow) {
            NoteActionRow(
                note = note,
                actionRowSchema = actionRowSchema,
                isZapInProgress = isZapInProgress,
                isZapped = isZapped,
                reactionEmoji = reactionEmoji,
                isDetailsExpanded = isDetailsExpanded,
                onDetailsToggle = { isDetailsExpanded = !isDetailsExpanded },
                isZapMenuExpanded = isZapMenuExpanded,
                onZapMenuToggle = { isZapMenuExpanded = !isZapMenuExpanded },
                onShowCustomZapDialog = { showCustomZapDialog = true },
                onShowReactionPicker = { showReactionPicker = true },
                onComment = onComment,
                onBoost = onBoost,
                onQuote = onQuote,
                onFork = onFork,
                onZap = onZap,
                onCustomZap = onCustomZap,
                onZapSettings = onZapSettings,
                onProfileClick = onProfileClick,
                isAuthorFollowed = isAuthorFollowed,
                onFollowAuthor = onFollowAuthor,
                onUnfollowAuthor = onUnfollowAuthor,
                onMessageAuthor = onMessageAuthor,
                onBlockAuthor = onBlockAuthor,
                onMuteAuthor = onMuteAuthor,
                onBookmarkToggle = onBookmarkToggle,
                extraMoreMenuItems = extraMoreMenuItems,
                translationResult = translationResult,
                isTranslating = isTranslating,
                showOriginal = showOriginal,
                onTranslate = {
                    if (!isTranslating) {
                        isTranslating = true
                        translationScope.launch {
                            val result = social.mycelium.android.repository.TranslationService.translate(note.id, note.content)
                            translationResult = result
                            isTranslating = false
                            if (result != null) showOriginal = false
                        }
                    }
                },
                onShowOriginal = { showOriginal = true },
                onShowTranslation = { showOriginal = false },
            )

            NoteDetailsPanel(
                note = note,
                isDetailsExpanded = isDetailsExpanded,
                overrideReactions = overrideReactions,
                overrideReactionAuthors = overrideReactionAuthors,
                overrideZapCount = overrideZapCount,
                overrideZapTotalSats = overrideZapTotalSats,
                overrideZapAuthors = overrideZapAuthors,
                overrideZapAmountByAuthor = overrideZapAmountByAuthor,
                overrideCustomEmojiUrls = overrideCustomEmojiUrls,
                overrideRepostAuthors = countsByNoteId[note.originalNoteId ?: note.id]?.repostAuthors,
                isZapped = isZapped,
                myZappedAmount = myZappedAmount,
                onProfileClick = onProfileClick,
                onSeeAllReactions = onSeeAllReactions,
            )
            }

            if (showCustomZapDialog) {
                ZapCustomDialog(
                    onDismiss = { showCustomZapDialog = false },
                    onSendZap = { amount, zapType, message ->
                        showCustomZapDialog = false
                        onCustomZapSend?.invoke(note, amount, zapType, message)
                    },
                    onZapSettings = onZapSettings
                )
            }

            if (showReactionPicker) {
                EmojiPickerDialog(
                    recentEmojis = recentEmojis,
                    onDismiss = { showReactionPicker = false },
                    onEmojiSelected = { emoji ->
                        reactionEmoji = emoji
                        showReactionPicker = false
                        ReactionsRepository.recordEmoji(context, accountNpub, emoji)
                        recentEmojis = ReactionsRepository.getRecentEmojis(context, accountNpub)
                        onReact(note, emoji)
                    },
                    onSaveDefaultEmoji = { emoji ->
                        // Save as a default (adds to front of recent list)
                        ReactionsRepository.recordEmoji(context, accountNpub, emoji)
                        recentEmojis = ReactionsRepository.getRecentEmojis(context, accountNpub)
                    }
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ReactionButton(
    emoji: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (emoji.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Outlined.FavoriteBorder,
                contentDescription = "React",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(
                text = emoji,
                fontSize = 18.sp
            )
        }
    }
}

/** Returns the first grapheme (code point) of the string, or null if empty/blank. */
internal fun firstGrapheme(s: String): String? {
    val t = s.trim()
    if (t.isEmpty()) return null
    val end = Character.offsetByCodePoints(t, 0, 1).coerceIn(1, t.length)
    return t.substring(0, end)
}

@Composable
private fun ReactionPickerDialog(
    recentEmojis: List<String>,
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    onSaveDefaultEmoji: ((String) -> Unit)? = null
) {
    var customEmoji by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                // Header
                Text(
                    text = "React",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Choose a reaction or enter a custom emoji.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Recent emojis (wrap when many)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    recentEmojis.forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 28.sp,
                            modifier = Modifier.clickable { onEmojiSelected(emoji) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Custom emoji input with + save button (type freely; we use first character when sending/saving)
                OutlinedTextField(
                    value = customEmoji,
                    onValueChange = { customEmoji = it },
                    label = { Text("Custom emoji") },
                    placeholder = { Text("\uD83D\uDE48") }, // 🙈
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (firstGrapheme(customEmoji) != null && onSaveDefaultEmoji != null) {
                            IconButton(
                                onClick = { firstGrapheme(customEmoji)?.let { onSaveDefaultEmoji(it) } }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "Save as default",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            firstGrapheme(customEmoji)?.let { onEmojiSelected(it) }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = firstGrapheme(customEmoji) != null
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h"
        else -> dateFormatter.format(Date(timestamp))
    }
}

private fun formatSats(sats: Long): String = when {
    sats >= 1_000_000 -> "${sats / 1_000_000}.${(sats % 1_000_000) / 100_000}M"
    sats >= 1_000 -> "${sats / 1_000}.${(sats % 1_000) / 100}K"
    else -> "$sats"
}

@Preview(showBackground = true)
@Composable
fun NoteCardPreview() {
    NoteCard(note = SampleData.sampleNotes[0])
}
