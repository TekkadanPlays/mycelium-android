package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import social.mycelium.android.data.Author
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayProfile
import social.mycelium.android.data.UserRelay
import social.mycelium.android.repository.Nip65RelayListRepository
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.repository.TopicNote
import social.mycelium.android.viewmodel.AccountStateViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * ComposeTopicReplyScreen - Compose a kind:1 reply to a kind:11 topic
 * 
 * Creates a kind:1 note with:
 * - I tags matching the topic's hashtags (for anchor-based threading)
 * - e tag pointing to the topic (NIP-10 root)
 * 
 * This enables mesh network visibility - the reply spreads across relays
 * as a regular note while maintaining topic context through anchors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeTopicReplyScreen(
    topic: TopicNote,
    onBack: () -> Unit = {},
    accountStateViewModel: AccountStateViewModel = viewModel(),
    myAuthor: Author? = null,
    myOutboxRelays: List<UserRelay> = emptyList(),
    relayCategories: List<RelayCategory>? = null,
    relayProfiles: List<RelayProfile> = emptyList(),
    draftId: String? = null,
    modifier: Modifier = Modifier
) {
    val loadedDraft = remember(draftId) { draftId?.let { social.mycelium.android.repository.DraftsRepository.getDraft(it) } }
    var content by remember { mutableStateOf(loadedDraft?.content ?: "") }
    val onBackWithDraft = {
        if (content.isNotBlank()) {
            social.mycelium.android.repository.DraftsRepository.saveDraft(
                social.mycelium.android.data.Draft(
                    id = loadedDraft?.id ?: java.util.UUID.randomUUID().toString(),
                    type = social.mycelium.android.data.DraftType.TOPIC_REPLY,
                    content = content,
                    rootId = topic.id,
                    rootPubkey = topic.author.id
                )
            )
        }
        onBack()
    }
    // Intercept system back gesture to save draft before leaving
    androidx.activity.compose.BackHandler(onBack = onBackWithDraft)

    var isPublishing by remember { mutableStateOf(false) }
    var showRelayPicker by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Resolve target user's inbox relays from NIP-65 cache
    val targetInboxRelays = remember(topic.author.id) {
        val authorRelays = Nip65RelayListRepository.getCachedAuthorRelays(topic.author.id)
        authorRelays?.readRelays ?: emptyList()
    }

    // Build relay sections with target user + our profile
    val sections = remember(topic, myAuthor, myOutboxRelays, relayCategories, relayProfiles, targetInboxRelays) {
        buildRelaySections(
            targetAuthor = topic.author,
            targetInboxRelays = targetInboxRelays,
            myAuthor = myAuthor,
            myOutboxRelays = myOutboxRelays,
            relayCategories = relayCategories ?: emptyList(),
            relayProfiles = relayProfiles,
            noteRelayUrls = topic.relayUrls
        )
    }

    if (showRelayPicker) {
        RelaySelectionScreen(
            title = "Publish reply",
            sections = sections,
            onConfirm = { selectedUrls ->
                showRelayPicker = false
                isPublishing = true
                accountStateViewModel.publishTopicReply(
                    content = content,
                    topicId = topic.id,
                    topicAuthorPubkey = topic.author.id,
                    hashtags = topic.hashtags,
                    relayUrls = selectedUrls
                )
                loadedDraft?.let { social.mycelium.android.repository.DraftsRepository.deleteDraft(it.id) }
                onBack()
            },
            onBack = { showRelayPicker = false }
        )
        return
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                title = {
                    Text(
                        text = "Reply to Topic",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackWithDraft) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (content.isNotBlank() && !isPublishing) {
                                showRelayPicker = true
                            }
                        },
                        enabled = content.isNotBlank() && !isPublishing
                    ) {
                        if (isPublishing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Publish"
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Post")
                        }
                    }
                },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Topic context card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Replying to:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = topic.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (topic.hashtags.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            topic.hashtags.take(3).forEach { hashtag ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.height(20.dp)
                                ) {
                                    Text(
                                        text = "#$hashtag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Info card explaining mesh network reply
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Global Reply",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = "This reply will appear in your regular feed and spread across the network while staying linked to this topic.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Content input
            social.mycelium.android.ui.components.ModernTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = "Share your thoughts...",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text
                ),
            )
        }
    }
}
