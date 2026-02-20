package social.mycelium.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
import social.mycelium.android.utils.MediaAspectRatioCache
import social.mycelium.android.ui.components.InlineVideoPlayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.data.Note
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

// ✅ CRITICAL PERFORMANCE FIX: Cache SimpleDateFormat
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


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun NoteCard(
    note: Note,
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onReact: (Note, String) -> Unit = { _, _ -> },
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
    /** Extra items for the 3-dot More menu (e.g. "Copy text" on thread view). */
    extraMoreMenuItems: List<Pair<String, () -> Unit>> = emptyList(),
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
                RelayOrbs(relayUrls = note.displayRelayUrls(), onRelayClick = onRelayClick)
                Spacer(modifier = Modifier.width(8.dp))
                var showHeaderMenu by remember { mutableStateOf(false) }
                Box {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More",
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { showHeaderMenu = true },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DropdownMenu(
                        expanded = showHeaderMenu,
                        onDismissRequest = { showHeaderMenu = false }
                    ) {
                        // Translate / Show Original
                        if (translationResult != null && !showOriginal) {
                            DropdownMenuItem(
                                text = { Text("Show original") },
                                leadingIcon = { Icon(Icons.Outlined.Translate, null, modifier = Modifier.size(18.dp)) },
                                onClick = { showOriginal = true; showHeaderMenu = false }
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
                                    showHeaderMenu = false
                                    if (translationResult != null) {
                                        showOriginal = false
                                    } else if (!isTranslating) {
                                        isTranslating = true
                                        translationScope.launch {
                                            val result = social.mycelium.android.repository.TranslationService.translate(note.id, note.content)
                                            translationResult = result
                                            isTranslating = false
                                            if (result != null) showOriginal = false
                                        }
                                    }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = { showHeaderMenu = false },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Report") },
                            onClick = { showHeaderMenu = false },
                            leadingIcon = { Icon(Icons.Default.Report, contentDescription = null) }
                        )
                        // Extra items from caller (e.g. "Copy text" on thread view)
                        extraMoreMenuItems.forEach { (label, action) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    action()
                                    showHeaderMenu = false
                                }
                            )
                        }
                    }
                }
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
                        .debounce(300)
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

            // Render interleaved content blocks: text surfaces, inline media carousels, and previews
            contentBlocks.forEach { block ->
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
                        // Inline album carousel at the correct position in the text flow
                        val mediaList = block.urls.take(10)
                        // Compute the starting index of this group within allMediaUrls for fullscreen viewer
                        val groupStartIndex = remember(allMediaUrls, mediaList) {
                            allMediaUrls.indexOf(mediaList.first()).coerceAtLeast(0)
                        }
                        val pagerState = rememberPagerState(
                            pageCount = { mediaList.size },
                            initialPage = initialMediaPage.coerceIn(0, (mediaList.size - 1).coerceAtLeast(0))
                        )
                        // Report page changes back so album position persists
                        LaunchedEffect(pagerState.currentPage) {
                            onMediaPageChanged(pagerState.currentPage)
                        }
                        // Stable container ratio: use the tallest (smallest ratio) across ALL
                        // media in the group so the container never resizes when swiping.
                        // For uncached items, default videos to 16:9 and images to null.
                        var containerRatio by remember(mediaList) {
                            val ratios = mediaList.map { url ->
                                MediaAspectRatioCache.get(url)
                                    ?: if (UrlDetector.isVideoUrl(url)) 16f / 9f else null
                            }
                            val known = ratios.filterNotNull()
                            // Tallest = smallest ratio (most portrait)
                            mutableStateOf(if (known.isNotEmpty()) known.min() else null)
                        }
                        val containerModifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio((containerRatio ?: (16f / 9f)).coerceIn(0.3f, 3.0f))
                            .clipToBounds()
                            .background(androidx.compose.ui.graphics.Color.Black)
                        Box(
                            modifier = containerModifier,
                            contentAlignment = Alignment.Center
                        ) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                                pageSpacing = 0.dp
                            ) { page ->
                                val offsetFromCenter = page - pagerState.currentPage - pagerState.currentPageOffsetFraction
                                val scale = 1f - 0.15f * abs(offsetFromCenter).coerceIn(0f, 1f)
                                val alphaVal = 1f - 0.25f * abs(offsetFromCenter).coerceIn(0f, 1f)
                                val url = mediaList[page]
                                val isVideo = UrlDetector.isVideoUrl(url)
                                val isCurrentPage = pagerState.currentPage == page
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                            this.alpha = alphaVal
                                        }
                                        .then(
                                            // Videos handle fullscreen via InlineVideoPlayer's pill (preserves player);
                                            // images use direct clickable
                                            if (!isVideo) Modifier.clickable {
                                                val fullIndex = groupStartIndex + page
                                                onImageTap(note, allMediaUrls, fullIndex)
                                            } else Modifier
                                        )
                                ) {
                                    if (isVideo) {
                                        InlineVideoPlayer(
                                            url = url,
                                            modifier = Modifier.fillMaxSize(),
                                            isVisible = isCurrentPage && isVisible,
                                            onFullscreenClick = {
                                                val fullIndex = groupStartIndex + page
                                                onVideoClick(allMediaUrls, fullIndex)
                                            }
                                        )
                                    } else {
                                        AsyncImage(
                                            model = url,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize(),
                                            onSuccess = { state ->
                                                val drawable = state.result.drawable
                                                MediaAspectRatioCache.add(url, drawable.intrinsicWidth, drawable.intrinsicHeight)
                                                val newRatio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.toFloat()
                                                val current = containerRatio
                                                if (current == null || newRatio < current) {
                                                    containerRatio = newRatio
                                                }
                                            }
                                        )
                                        IconButton(
                                            onClick = {
                                                val fullIndex = groupStartIndex + page
                                                onOpenImageViewer(allMediaUrls, fullIndex)
                                            },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(8.dp)
                                                .size(36.dp),
                                            colors = IconButtonDefaults.iconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                                contentColor = MaterialTheme.colorScheme.onSurface
                                            )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = "Open in viewer"
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
                    is NoteContentBlock.QuotedNote -> {
                        val eventId = block.eventId
                        val meta = quotedMetas[eventId]
                        if (meta != null) {
                            val quotedAuthor = remember(meta.authorId, quotedProfileRevision) {
                                profileCache.resolveAuthor(meta.authorId)
                            }
                            var quotedExpanded by remember(eventId) { mutableStateOf(false) }
                            val hasMore = meta.fullContent.length > meta.contentSnippet.length
                            val quotedCounts = countsByNoteId[eventId]

                            // Build rich content blocks for the quoted note body
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
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(0.dp, Color.Transparent),
                                shape = RectangleShape
                            ) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    // Left accent bar
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .fillMaxHeight()
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                                    )
                                Column(modifier = Modifier.padding(10.dp).weight(1f).animateContentSize(animationSpec = tween(durationMillis = 300))) {
                                    // ── Header row: author (left) + counters & emojis (right) ──
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        // Left: profile pic + name
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
                                        // Right: emoji reactions • reply count • zap sats
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
                                                    // Emoji reactions from the right
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
                                                            // Compact video thumbnail
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
                                                // Nested quotes: just show a minimal label
                                                Text(
                                                    text = "Quoted note: ${qBlock.eventId.take(8)}…",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    // "Read more" / "Show less" toggle
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
                                                ) { quotedExpanded = !quotedExpanded }
                                        )
                                    }
                                }
                                }
                            }
                        } else if (eventId in quotedFailedIds) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Outlined.Info, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                    Text("Quoted event not found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                }
                            }
                        } else if (quotedLoading) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(8.dp)
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
            // Action buttons - 6 icons total with expanded hitboxes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Upvote button - expanded hitbox
                ActionButton(
                    icon = Icons.Outlined.ArrowUpward,
                    contentDescription = "Upvote",
                    onClick = { /* Test button */ },
                    modifier = Modifier.weight(1f)
                )

                // Downvote button - expanded hitbox
                ActionButton(
                    icon = Icons.Outlined.ArrowDownward,
                    contentDescription = "Downvote",
                    onClick = { /* Test button */ },
                    modifier = Modifier.weight(1f)
                )

                // Bookmark button - expanded hitbox
                ActionButton(
                    icon = Icons.Outlined.Bookmark,
                    contentDescription = "Bookmark",
                    onClick = { /* Test button */ },
                    modifier = Modifier.weight(1f)
                )

                // React button (NIP-25) - shows emoji if reacted
                ReactionButton(
                    emoji = reactionEmoji,
                    onClick = { showReactionPicker = true },
                    modifier = Modifier.weight(1f)
                )

                // Zap button - tap to expand menu, long-press for custom zap dialog; loading + zapped state
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .combinedClickable(
                            onClick = { isZapMenuExpanded = !isZapMenuExpanded },
                            onLongClick = { showCustomZapDialog = true }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isZapInProgress -> CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        else -> Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = "Zap",
                            tint = if (isZapped) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Details caret - expandable panel for boosts, reactions, zaps
                ActionButton(
                    icon = if (isDetailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Details",
                    onClick = { isDetailsExpanded = !isDetailsExpanded },
                    modifier = Modifier.weight(1f)
                )
            }

            // Zap menu - completely separate, appears below action buttons; Custom chip opens dialog
            ZapMenuRow(
                isExpanded = isZapMenuExpanded,
                onExpandedChange = { isZapMenuExpanded = it },
                onZap = { amount -> onZap(note.id, amount) },
                onCustomZap = { showCustomZapDialog = true; onCustomZap(note.id) },
                onSettingsClick = onZapSettings
            )

            // Expandable details panel — boosts, reactions, zaps (tappable to reveal individuals)
            AnimatedVisibility(
                visible = isDetailsExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                val detailReactions = overrideReactions ?: note.reactions
                val detailZapCount = (overrideZapCount ?: note.zapCount).coerceAtLeast(0)
                val hasBoosts = note.repostedByAuthors.isNotEmpty()
                val hasReactions = detailReactions.isNotEmpty()
                val hasZaps = detailZapCount > 0

                var boostsExpanded by remember { mutableStateOf(false) }
                var reactionsExpanded by remember { mutableStateOf(false) }
                var zapsExpanded by remember { mutableStateOf(false) }

                if (hasBoosts || hasReactions || hasZaps) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = RectangleShape
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
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
                                    val boostAuthors = note.repostedByAuthors.take(5)
                                    Box(modifier = Modifier.width((20 + (boostAuthors.size - 1).coerceAtLeast(0) * 12).dp)) {
                                        boostAuthors.forEachIndexed { i, author ->
                                            Box(modifier = Modifier.offset(x = (i * 12).dp)) {
                                                ProfilePicture(author = author, size = 20.dp, onClick = { onProfileClick(author.id) })
                                            }
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "${note.repostedByAuthors.size} boost${if (note.repostedByAuthors.size != 1) "s" else ""}",
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
                                // Expanded: individual boosters
                                AnimatedVisibility(visible = boostsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                                    Column(modifier = Modifier.padding(start = 22.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        note.repostedByAuthors.forEach { author ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { onProfileClick(author.id) }
                                                    .padding(vertical = 2.dp)
                                            ) {
                                                ProfilePicture(author = author, size = 20.dp, onClick = { onProfileClick(author.id) })
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = authorDisplayLabel(author),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
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
                                // Expanded: full reaction breakdown with author names
                                AnimatedVisibility(visible = reactionsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                                    val profileCache = remember { ProfileMetadataCache.getInstance() }
                                    // Fetch uncached profiles for reaction authors and recompose when they arrive
                                    var profileRevision by remember { mutableIntStateOf(0) }
                                    val allReactionPubkeys = remember(overrideReactionAuthors) {
                                        overrideReactionAuthors?.values?.flatten()?.distinct() ?: emptyList()
                                    }
                                    LaunchedEffect(allReactionPubkeys) {
                                        val uncached = allReactionPubkeys.filter { profileCache.getAuthor(it) == null }
                                        if (uncached.isNotEmpty()) {
                                            val relays = profileCache.getConfiguredRelayUrls()
                                            profileCache.requestProfiles(uncached, relays)
                                        }
                                    }
                                    LaunchedEffect(Unit) {
                                        profileCache.profileUpdated.collect { pubkey ->
                                            if (pubkey in allReactionPubkeys) profileRevision++
                                        }
                                    }
                                    @Suppress("UNUSED_EXPRESSION") profileRevision // force recomposition
                                    Column(modifier = Modifier.padding(start = 22.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        grouped.forEach { (emoji, count) ->
                                            val emojiAuthors = overrideReactionAuthors?.get(emoji)
                                            if (!emojiAuthors.isNullOrEmpty()) {
                                                emojiAuthors.take(10).forEach { pubkey ->
                                                    val author = profileCache.resolveAuthor(pubkey)
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { onProfileClick(author.id) }
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
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = " reacted",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                if (emojiAuthors.size > 10) {
                                                    Text(
                                                        text = "  +${emojiAuthors.size - 10} more",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            } else {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                                                    Text(text = emoji, fontSize = 16.sp)
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        text = "$count reaction${if (count != 1) "s" else ""}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
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
                                // Expanded: zap details with author names
                                AnimatedVisibility(visible = zapsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                                    val profileCache = remember { ProfileMetadataCache.getInstance() }
                                    // Fetch uncached profiles for zap authors and recompose when they arrive
                                    var zapProfileRevision by remember { mutableIntStateOf(0) }
                                    val allZapPubkeys = remember(overrideZapAuthors) {
                                        overrideZapAuthors?.distinct() ?: emptyList()
                                    }
                                    LaunchedEffect(allZapPubkeys) {
                                        val uncached = allZapPubkeys.filter { profileCache.getAuthor(it) == null }
                                        if (uncached.isNotEmpty()) {
                                            val relays = profileCache.getConfiguredRelayUrls()
                                            profileCache.requestProfiles(uncached, relays)
                                        }
                                    }
                                    LaunchedEffect(Unit) {
                                        profileCache.profileUpdated.collect { pubkey ->
                                            if (pubkey in allZapPubkeys) zapProfileRevision++
                                        }
                                    }
                                    @Suppress("UNUSED_EXPRESSION") zapProfileRevision // force recomposition
                                    Column(modifier = Modifier.padding(start = 22.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        if (isZapped && myZappedAmount != null && myZappedAmount > 0) {
                                            Text(
                                                text = "You zapped $myZappedAmount sats",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFFF59E0B)
                                            )
                                        }
                                        if (!overrideZapAuthors.isNullOrEmpty()) {
                                            overrideZapAuthors.take(10).forEach { pubkey ->
                                                val author = profileCache.resolveAuthor(pubkey)
                                                val zapSats = overrideZapAmountByAuthor?.get(pubkey) ?: 0L
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { onProfileClick(author.id) }
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
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                    if (zapSats > 0) {
                                                        Text(
                                                            text = " ⚡ ${social.mycelium.android.utils.ZapUtils.formatZapAmount(zapSats)} sats",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = Color(0xFFF59E0B)
                                                        )
                                                    } else {
                                                        Text(
                                                            text = " zapped",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                            if (overrideZapAuthors.size > 10) {
                                                Text(
                                                    text = "+${overrideZapAuthors.size - 10} more",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

            if (showCustomZapDialog) {
                ZapCustomDialog(
                    onDismiss = { showCustomZapDialog = false },
                    onSendZap = { amount, zapType, message ->
                        showCustomZapDialog = false
                        onCustomZapSend?.invoke(note, amount, zapType, message)
                    }
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
    // ✅ EXPANDED HITBOX: Wider touch target to prevent accidental card activation
    Box(
        modifier = modifier
            .height(48.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
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
            .height(48.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (emoji.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Outlined.FavoriteBorder,
                contentDescription = "React",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Text(
                text = emoji,
                fontSize = 20.sp
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
