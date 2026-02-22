package social.mycelium.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import social.mycelium.android.ui.components.ClickableNoteContent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlin.math.abs
import coil.compose.AsyncImage
import social.mycelium.android.ui.components.InlineVideoPlayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.data.Note
import social.mycelium.android.data.QuotedNoteMeta
import social.mycelium.android.repository.ZapType
import social.mycelium.android.data.SampleData
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.utils.UrlDetector
import social.mycelium.android.utils.normalizeAuthorIdForCache
import kotlinx.coroutines.flow.filter
import social.mycelium.android.repository.QuotedNoteCache
import social.mycelium.android.ui.theme.NoteBodyTextStyle
import social.mycelium.android.utils.NoteContentBlock
import social.mycelium.android.utils.buildNoteContentWithInlinePreviews
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ✅ CRITICAL PERFORMANCE FIX: Cache SimpleDateFormat (creating it is VERY expensive)
// SimpleDateFormat creation can take 50-100ms, causing visible lag in lists
private val dateFormatter by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

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
 * Modern Note Card following Material3 best practices.
 *
 * Features:
 * - Interactive chip hashtags
 * - Proper elevation and theming
 * - Smooth animations
 * - Modern action buttons
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModernNoteCard(
    note: Note,
    onLike: (String) -> Unit = {},
    onDislike: (String) -> Unit = {},
    onBookmark: (String) -> Unit = {},
    onZap: (String, Long) -> Unit = { _, _ -> },
    onCustomZap: (String) -> Unit = {},
    onCustomZapSend: ((Note, Long, ZapType, String) -> Unit)? = null,
    onZapSettings: () -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNoteClick: (Note) -> Unit = {},
    /** Called when user taps the image (not the magnifier): (note, urls, index). E.g. feed = open thread, thread = open viewer. */
    onImageTap: (Note, List<String>, Int) -> Unit = { _, _, _ -> },
    /** Called when user taps the magnifier on an image: open full viewer. */
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    /** Called when user taps a video: (urls, initialIndex). */
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    onHashtagClick: (String) -> Unit = {},
    onRelayClick: (relayUrl: String) -> Unit = {},
    isZapInProgress: Boolean = false,
    isZapped: Boolean = false,
    myZappedAmount: Long? = null,
    overrideReplyCount: Int? = null,
    overrideZapCount: Int? = null,
    overrideReactions: List<String>? = null,
    /** When true (e.g. thread view), link embed shows expanded description. */
    expandLinkPreviewInThread: Boolean = false,
    modifier: Modifier = Modifier
) {
    NoteCardContent(
        note = note,
        onLike = onLike,
        onDislike = onDislike,
        onBookmark = onBookmark,
        onZap = onZap,
        onCustomZap = onCustomZap,
        onCustomZapSend = onCustomZapSend,
        onZapSettings = onZapSettings,
        onComment = onComment,
        onProfileClick = onProfileClick,
        onNoteClick = onNoteClick,
        onImageTap = onImageTap,
        onOpenImageViewer = onOpenImageViewer,
        onVideoClick = onVideoClick,
        onHashtagClick = onHashtagClick,
        onRelayClick = onRelayClick,
        isZapInProgress = isZapInProgress,
        isZapped = isZapped,
        myZappedAmount = myZappedAmount,
        overrideReplyCount = overrideReplyCount,
        overrideZapCount = overrideZapCount,
        overrideReactions = overrideReactions,
        expandLinkPreviewInThread = expandLinkPreviewInThread,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteCardContent(
    note: Note,
    onLike: (String) -> Unit,
    onDislike: (String) -> Unit,
    onBookmark: (String) -> Unit,
    onZap: (String, Long) -> Unit,
    onCustomZap: (String) -> Unit,
    onCustomZapSend: ((Note, Long, ZapType, String) -> Unit)?,
    onZapSettings: () -> Unit,
    onComment: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onNoteClick: (Note) -> Unit,
    onImageTap: (Note, List<String>, Int) -> Unit,
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    onVideoClick: (List<String>, Int) -> Unit,
    onHashtagClick: (String) -> Unit,
    onRelayClick: (relayUrl: String) -> Unit,
    isZapInProgress: Boolean = false,
    isZapped: Boolean = false,
    myZappedAmount: Long? = null,
    overrideReplyCount: Int? = null,
    overrideZapCount: Int? = null,
    overrideReactions: List<String>? = null,
    expandLinkPreviewInThread: Boolean = false,
    modifier: Modifier = Modifier
) {
    var isZapMenuExpanded by remember { mutableStateOf(false) }
    var showCustomZapDialog by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onNoteClick(note) },
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 1.dp,
            pressedElevation = 4.dp,
            hoveredElevation = 3.dp
        ),
        shape = RectangleShape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Author from note (repository updates list when profiles load); no per-card profileUpdated collector
            val displayAuthor = note.author
            val profileCache = ProfileMetadataCache.getInstance()
            // Author info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfilePicture(
                    author = displayAuthor,
                    size = 40.dp,
                    onClick = { onProfileClick(note.author.id) }
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = authorDisplayLabel(displayAuthor),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                        if (displayAuthor.isVerified) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "Verified",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    val formattedTime = remember(note.timestamp) {
                        formatTimestamp(note.timestamp)
                    }
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

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

            // HTML embed at top: edge-to-edge in feed, padded in thread view
            val firstPreview = note.urlPreviews.firstOrNull()
            if (firstPreview != null) {
                Kind1LinkEmbedBlock(
                    previewInfo = firstPreview,
                    expandDescriptionInThread = expandLinkPreviewInThread,
                    inThreadView = expandLinkPreviewInThread,
                    onUrlClick = { url -> uriHandler.openUri(url) },
                    modifier = if (expandLinkPreviewInThread) Modifier.padding(horizontal = 16.dp) else Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Counts row: between embed and body
            val replyCountVal = (overrideReplyCount ?: note.comments).coerceAtLeast(0)
            val zapCount = (overrideZapCount ?: note.zapCount).coerceAtLeast(0)
            val reactionCount = (overrideReactions ?: note.reactions).size.coerceAtLeast(0)
            val countParts = buildList {
                add("$replyCountVal repl${if (replyCountVal == 1) "y" else "ies"}")
                add("$reactionCount reaction${if (reactionCount == 1) "" else "s"}")
                add("$zapCount zap${if (zapCount == 1) "" else "s"}")
                if (isZapped && (myZappedAmount ?: 0L) > 0L) add("You zapped ${social.mycelium.android.utils.ZapUtils.formatZapAmount(myZappedAmount!!)}")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = countParts.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Body zone: only when there is text or quoted notes; otherwise embed/media only (no highlight box)
            val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary)
            val contentIsMarkdown = remember(note.content) { isMarkdown(note.content) }
            val contentBlocks = remember(note.content, note.mediaUrls, note.urlPreviews) {
                buildNoteContentWithInlinePreviews(
                    note.content,
                    note.mediaUrls.toSet(),
                    note.urlPreviews,
                    linkStyle,
                    profileCache
                )
            }
            if (hasBodyText) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RectangleShape,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    contentBlocks.forEach { block ->
                        when (block) {
                            is NoteContentBlock.Content -> {
                                val annotated = block.annotated
                                if (annotated.isNotEmpty()) {
                                    if (contentIsMarkdown) {
                                        MarkdownNoteContent(
                                            content = annotated.text,
                                            style = NoteBodyTextStyle.copy(
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            onProfileClick = onProfileClick,
                                            onNoteClick = { onNoteClick(note) },
                                            onUrlClick = { url -> uriHandler.openUri(url) },
                                            onHashtagClick = onHashtagClick
                                        )
                                    } else {
                                        ClickableNoteContent(
                                            text = annotated,
                                            style = NoteBodyTextStyle.copy(
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
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
                            is NoteContentBlock.Preview -> {
                                if (firstPreview == null) {
                                    UrlPreviewCard(
                                        previewInfo = block.previewInfo,
                                        onUrlClick = { url -> uriHandler.openUri(url) },
                                        onUrlLongClick = { _ -> }
                                    )
                                }
                            }
                            is NoteContentBlock.MediaGroup -> {
                                // Media groups handled by the bottom carousel in thread view
                            }
                            is NoteContentBlock.QuotedNote -> {
                                // Inline quoted notes handled by standalone section below
                            }
                            is NoteContentBlock.LiveEventReference -> {
                                // Live event references handled like quoted notes
                            }
                        }
                    }
                    if (note.quotedEventIds.isNotEmpty()) {
                        val profileCache = ProfileMetadataCache.getInstance()
                        val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary)
                        val uriHandler = LocalUriHandler.current
                        var quotedMetas by remember(note.id) { mutableStateOf<Map<String, QuotedNoteMeta>>(emptyMap()) }
                        LaunchedEffect(note.quotedEventIds) {
                            note.quotedEventIds.forEach { id ->
                                if (id !in quotedMetas) {
                                    val meta = QuotedNoteCache.get(id)
                                    if (meta != null) quotedMetas = quotedMetas + (id to meta)
                                }
                            }
                        }
                        if (quotedMetas.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                quotedMetas.values.forEach { meta ->
                                    val quotedAuthor = remember(meta.authorId) { profileCache.resolveAuthor(meta.authorId) }
                                    val quotedExpanded = QuotedNoteExpandedState.isExpanded(meta.eventId)
                                    val hasMore = meta.fullContent.length > meta.contentSnippet.length

                                    // Rich content blocks
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

                                    // Outer wrapper: matches body text background
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        shape = androidx.compose.ui.graphics.RectangleShape
                                    ) {
                                    // Inner quote surface
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
                                            // ── Header: author (left) ──
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
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))

                                            // ── Rich content body ──
                                            quotedContentBlocks.forEach { qBlock ->
                                                when (qBlock) {
                                                    is NoteContentBlock.Content -> {
                                                        val qAnnotated = qBlock.annotated
                                                        if (qAnnotated.isNotEmpty()) {
                                                            if (quotedIsMarkdown) {
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
                                                                    maxLines = if (quotedExpanded) Int.MAX_VALUE else 3,
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
                                                                            isVisible = true, // Thread view: card always visible
                                                                            onFullscreenClick = { onVideoClick(qMediaList, qMediaList.indexOf(url)) }
                                                                        )
                                                                    }
                                                                } else {
                                                                    coil.compose.AsyncImage(
                                                                        model = url,
                                                                        contentDescription = null,
                                                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
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
                                                        Text(
                                                            text = "Quoted note: ${qBlock.eventId.take(8)}…",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.padding(vertical = 2.dp)
                                                        )
                                                    }
                                                    is NoteContentBlock.LiveEventReference -> {
                                                        Text(
                                                            text = "Live event: ${qBlock.eventId.take(8)}…",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.padding(vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            if (hasMore) {
                                                Text(
                                                    text = if (quotedExpanded) "Show less" else "Read more",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .padding(top = 2.dp)
                                                        .clickable(
                                                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                            indication = null
                                                        ) { QuotedNoteExpandedState.toggle(meta.eventId) }
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
                    }
                }
            }
            }
            if (note.mediaUrls.isNotEmpty()) {
                // Media only (no body text): edge-to-edge
                val mediaList = note.mediaUrls.take(10)
                val pagerState = rememberPagerState(
                    pageCount = { mediaList.size },
                    initialPage = 0
                )
                // Stable container ratio: use the tallest (smallest ratio) across ALL
                // media so the container never resizes when swiping between pages.
                var mediaContainerRatio by remember(mediaList) {
                    val ratios = mediaList.map { url ->
                        social.mycelium.android.utils.MediaAspectRatioCache.get(url)
                            ?: if (UrlDetector.isVideoUrl(url)) 16f / 9f else null
                    }
                    val known = ratios.filterNotNull()
                    mutableStateOf(if (known.isNotEmpty()) known.min() else null)
                }
                val mediaContainerModifier = if (mediaContainerRatio != null) {
                    Modifier.fillMaxWidth().aspectRatio(mediaContainerRatio!!.coerceIn(0.3f, 3.0f))
                } else {
                    Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 480.dp)
                }
                Box(modifier = mediaContainerModifier) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        pageSpacing = 0.dp
                    ) { page ->
                        val offsetFromCenter = page - pagerState.currentPage - pagerState.currentPageOffsetFraction
                        val scale = 1f - 0.15f * abs(offsetFromCenter).coerceIn(0f, 1f)
                        val alpha = 1f - 0.25f * abs(offsetFromCenter).coerceIn(0f, 1f)
                        val url = mediaList[page]
                        val isVideo = UrlDetector.isVideoUrl(url)
                        val isCurrentPage = pagerState.currentPage == page
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                }
                                .then(
                                    if (!isVideo) Modifier.clickable {
                                        onImageTap(note, mediaList, page)
                                    } else Modifier
                                )
                        ) {
                            if (isVideo) {
                                InlineVideoPlayer(
                                    url = url,
                                    modifier = Modifier.fillMaxSize(),
                                    isVisible = isCurrentPage,
                                    onFullscreenClick = { onVideoClick(mediaList, page) }
                                )
                            } else {
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                    onSuccess = { state ->
                                        val drawable = state.result.drawable
                                        social.mycelium.android.utils.MediaAspectRatioCache.add(url, drawable.intrinsicWidth, drawable.intrinsicHeight)
                                        val newRatio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.toFloat()
                                        val current = mediaContainerRatio
                                        if (current == null || newRatio < current) {
                                            mediaContainerRatio = newRatio
                                        }
                                    }
                                )
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

            // Hashtags as chips
            if (note.hashtags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    note.hashtags.take(3).forEach { hashtag ->
                        SuggestionChip(
                            onClick = { onHashtagClick(hashtag) },
                            label = {
                                Text(
                                    text = "#$hashtag",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                    if (note.hashtags.size > 3) {
                        SuggestionChip(
                            onClick = { /* Show all hashtags */ },
                            label = {
                                Text(
                                    text = "+${note.hashtags.size - 3}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onLike(note.id) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowUpward,
                        contentDescription = "Upvote",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { onDislike(note.id) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowDownward,
                        contentDescription = "Downvote",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { onBookmark(note.id) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bookmark,
                        contentDescription = "Bookmark",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { onComment(note.id) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubble,
                        contentDescription = "Reply",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Zap button - tap to expand menu, long-press for custom zap dialog; loading + zapped state
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .combinedClickable(
                            onClick = { isZapMenuExpanded = !isZapMenuExpanded },
                            onLongClick = { showCustomZapDialog = true }
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

                // Isolate menu state to prevent card recomposition
                NoteMoreOptionsMenu(
                    onShare = { onNoteClick(note) },
                    onReport = { /* Handle report */ }
                )
            }

            // Zap menu - Custom chip opens custom zap dialog
            ZapMenuRow(
                isExpanded = isZapMenuExpanded,
                onExpandedChange = { isZapMenuExpanded = it },
                onZap = { amount -> onZap(note.id, amount) },
                onCustomZap = { showCustomZapDialog = true; onCustomZap(note.id) },
                onSettingsClick = onZapSettings
            )

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
        }
    }
}

@Composable
private fun NoteMoreOptionsMenu(
    onShare: () -> Unit,
    onReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { showMenu = true },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Share") },
                onClick = {
                    showMenu = false
                    onShare()
                },
                leadingIcon = {
                    Icon(Icons.Filled.Send, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text("Report") },
                onClick = {
                    showMenu = false
                    onReport()
                },
                leadingIcon = {
                    Icon(Icons.Filled.Flag, contentDescription = null)
                }
            )
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
        else -> dateFormatter.format(Date(timestamp)) // ✅ Use cached formatter
    }
}

@Preview(showBackground = true)
@Composable
fun ModernNoteCardPreview() {
    MaterialTheme {
        ModernNoteCard(note = SampleData.sampleNotes[0])
    }
}
