package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.common.cutoutPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import social.mycelium.android.data.Note
import social.mycelium.android.data.ThreadReply
import social.mycelium.android.data.toNote
import social.mycelium.android.repository.TopicNote
import social.mycelium.android.repository.TopicRepliesRepository
import social.mycelium.android.ui.components.note.NoteCard
import social.mycelium.android.ui.components.note.ActionRowSchema
import social.mycelium.android.ui.components.common.ProfilePicture
import social.mycelium.android.viewmodel.AccountStateViewModel
import social.mycelium.android.viewmodel.ThreadRepliesViewModel
import kotlinx.coroutines.launch
import social.mycelium.android.ui.components.note.FabMenuItem

/**
 * TopicThreadScreen - Displays a kind:11 topic with its kind:1 replies
 * 
 * Shows:
 * - Original kind:11 topic (title, content, author, hashtags)
 * - All kind:1 notes that reply to this topic (with matching I tags and e tags)
 * - Threaded conversation view
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicThreadScreen(
    topic: TopicNote,
    onBackClick: () -> Unit = {},
    onReplyKind1111Click: () -> Unit = {}, // Default topic reply
    onReplyKind1Click: () -> Unit = {}, // Mesh network reply with I tags
    onProfileClick: (String) -> Unit = {},
    onImageTap: (Note, List<String>, Int) -> Unit = { _, _, _ -> },
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    accountStateViewModel: AccountStateViewModel = viewModel(),
    threadRepliesViewModel: ThreadRepliesViewModel = viewModel(),
    relayUrls: List<String> = emptyList(),
    cacheRelayUrls: List<String> = emptyList(),
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topAppBarState)
    
    // Load kind:1111 replies via ThreadRepliesViewModel (same pattern as ModernThreadViewScreen)
    val threadRepliesState by threadRepliesViewModel.uiState.collectAsState()
    
    LaunchedEffect(cacheRelayUrls) {
        if (cacheRelayUrls.isNotEmpty()) {
            threadRepliesViewModel.setCacheRelayUrls(cacheRelayUrls)
        }
    }
    
    // Fetch kind:1111 replies when screen opens
    LaunchedEffect(topic.id, relayUrls) {
        if (relayUrls.isNotEmpty()) {
            val topicAsNote = topic.toNote()
            threadRepliesViewModel.loadRepliesForNote(topicAsNote, relayUrls)
        }
    }
    
    // Kind:1111 replies converted to Note for display
    val kind1111Replies = remember(threadRepliesState.replies) {
        threadRepliesState.replies.map { it.toNote() }
    }
    
    // Kind:1 replies from TopicRepliesRepository
    val topicRepliesRepo = remember { TopicRepliesRepository.getInstance() }
    val repliesByTopicId by topicRepliesRepo.repliesByTopicId.collectAsState()
    val kind1Replies = repliesByTopicId[topic.id] ?: emptyList()
    
    // Merge both reply types, deduplicate by ID, sort by timestamp
    val allReplies = remember(kind1111Replies, kind1Replies) {
        val merged = mutableMapOf<String, Note>()
        kind1111Replies.forEach { merged[it.id] = it }
        kind1Replies.forEach { merged.putIfAbsent(it.id, it) }
        merged.values.sortedBy { it.timestamp }
    }
    
    val isLoading = threadRepliesState.isLoading
    
    // Floating action menu state
    var fabExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = {
                        Text(
                            text = topic.title.ifEmpty { "Topic" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        floatingActionButton = {
            val replyItems = listOf(
                social.mycelium.android.ui.components.note.FabMenuItem(
                    label = "Topic",
                    icon = Icons.Default.Comment,
                    onClick = onReplyKind1111Click,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                social.mycelium.android.ui.components.note.FabMenuItem(
                    label = "Global",
                    icon = Icons.Default.Public,
                    onClick = onReplyKind1Click,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            )
            social.mycelium.android.ui.components.note.ThreadFab(
                listState = listState,
                replyItems = replyItems,
                firstReplyIndex = 2,
                totalItems = 2 + allReplies.size
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Original kind:11 topic
            item(key = "topic_${topic.id}") {
                TopicHeaderCard(
                    topic = topic,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            // Loading indicator
            if (isLoading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
            
            // Reply count header
            if (allReplies.isNotEmpty()) {
                item(key = "reply_header") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "${allReplies.size} ${if (allReplies.size == 1) "Reply" else "Replies"}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            
            // All replies (kind:1111 + kind:1 merged)
            items(
                items = allReplies,
                key = { "reply_${it.id}" }
            ) { reply ->
                NoteCard(
                    note = reply,
                    onLike = { },
                    onShare = { },
                    onComment = { },
                    onReact = { _, _ -> },
                    onProfileClick = onProfileClick,
                    onNoteClick = { },
                    onImageTap = onImageTap,
                    onOpenImageViewer = onOpenImageViewer,
                    onVideoClick = onVideoClick,
                    onZap = { _, _ -> },
                    onCustomZapSend = { _, _, _, _ -> },
                    onZapSettings = { },
                    shouldCloseZapMenus = false,
                    onRelayClick = { },
                    accountNpub = null,
                    isZapInProgress = false,
                    isZapped = false,
                    myZappedAmount = null,
                    overrideReplyCount = null,
                    overrideZapCount = null,
                    overrideReactions = null,
                    actionRowSchema = ActionRowSchema.KIND1111_REPLY,
                    showHashtagsSection = true,
                    initialMediaPage = 0,
                    onMediaPageChanged = { },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            // Empty state
            if (allReplies.isEmpty() && !isLoading) {
                item(key = "empty_state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No replies yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Be the first to reply to this topic",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Header card showing the original kind:11 topic
 */
@Composable
private fun TopicHeaderCard(
    topic: TopicNote,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(horizontal = 0.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Author info
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfilePicture(
                    author = topic.author,
                    size = 32.dp,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = topic.author.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = formatTopicTimestamp(topic.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Subject label
            Text(
                text = "SUBJECT",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.2.sp
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Title
            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Content
            if (topic.content.isNotEmpty()) {
                Text(
                    text = topic.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            // Hashtags
            if (topic.hashtags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    topic.hashtags.forEach { hashtag ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                text = "#$hashtag",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Helper function to format timestamp
private fun formatTopicTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> "${diff / 3600_000}h"
        diff < 604800_000 -> "${diff / 86400_000}d"
        else -> "${diff / 604800_000}w"
    }
}
