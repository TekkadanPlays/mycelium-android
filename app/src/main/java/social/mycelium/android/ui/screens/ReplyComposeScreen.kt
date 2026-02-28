package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.mycelium.android.data.Author
import social.mycelium.android.data.Note
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayProfile
import social.mycelium.android.data.UserRelay
import social.mycelium.android.repository.Nip65RelayListRepository
import android.widget.Toast

/**
 * Dedicated screen for replying to a comment. Shows the note being replied to at the top,
 * then a content field and Publish → relay selection → sign and send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyComposeScreen(
    replyToNote: Note?,
    rootId: String,
    rootPubkey: String,
    parentId: String?,
    parentPubkey: String?,
    onPublish: (rootId: String, rootPubkey: String, parentId: String?, parentPubkey: String?, content: String, relayUrls: Set<String>) -> String?,
    onBack: () -> Unit,
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
                    type = social.mycelium.android.data.DraftType.REPLY_KIND1,
                    content = content,
                    rootId = rootId,
                    rootPubkey = rootPubkey,
                    parentId = parentId,
                    parentPubkey = parentPubkey
                )
            )
        }
        onBack()
    }
    var showRelayPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Resolve target user's inbox relays from NIP-65 cache (updated reactively after fetch)
    val targetPubkey = parentPubkey ?: rootPubkey
    val targetAuthor = replyToNote?.author
    var targetInboxRelays by remember(targetPubkey) {
        mutableStateOf(
            Nip65RelayListRepository.getCachedAuthorRelays(targetPubkey)?.readRelays ?: emptyList()
        )
    }

    // Fetch NIP-65 relay list for target if not cached
    LaunchedEffect(targetPubkey) {
        if (targetInboxRelays.isEmpty() && targetPubkey.isNotBlank()) {
            val discoveryRelays = listOf("wss://purplepag.es", "wss://user.kindpag.es", "wss://indexer.coracle.social", "wss://directory.yabu.me") +
                (Nip65RelayListRepository.readRelays.value.takeIf { it.isNotEmpty() } ?: emptyList())
            Nip65RelayListRepository.batchFetchRelayLists(listOf(targetPubkey), discoveryRelays)
            // Poll for cache population (batchFetchRelayLists is async)
            kotlinx.coroutines.withTimeoutOrNull(5000) {
                while (true) {
                    kotlinx.coroutines.delay(300)
                    val cached = Nip65RelayListRepository.getCachedAuthorRelays(targetPubkey)
                    if (cached != null && cached.readRelays.isNotEmpty()) {
                        targetInboxRelays = cached.readRelays
                        break
                    }
                }
            }
        }
    }

    // Build relay sections with target user + our profile
    val sections = remember(targetAuthor, targetInboxRelays, myAuthor, myOutboxRelays, relayCategories, relayProfiles, replyToNote) {
        buildRelaySections(
            targetAuthor = targetAuthor,
            targetInboxRelays = targetInboxRelays,
            myAuthor = myAuthor,
            myOutboxRelays = myOutboxRelays,
            relayCategories = relayCategories ?: emptyList(),
            relayProfiles = relayProfiles,
            noteRelayUrls = replyToNote?.relayUrls ?: emptyList()
        )
    }

    if (showRelayPicker) {
        RelaySelectionScreen(
            title = "Publish reply",
            sections = sections,
            onConfirm = { selectedUrls ->
                showRelayPicker = false
                val err = onPublish(rootId, rootPubkey, parentId?.takeIf { it.isNotBlank() }, parentPubkey?.takeIf { it.isNotBlank() }, content, selectedUrls)
                if (err != null) {
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                } else {
                    loadedDraft?.let { social.mycelium.android.repository.DraftsRepository.deleteDraft(it.id) }
                    onBack()
                }
            },
            onBack = { showRelayPicker = false }
        )
        return
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text(if (replyToNote != null) "Reply" else "Reply to thread") },
                    navigationIcon = {
                        IconButton(onClick = onBackWithDraft) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    windowInsets = WindowInsets(0)
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            if (replyToNote != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = replyToNote.author.displayName.ifBlank { replyToNote.author.username }.ifBlank { replyToNote.author.id.take(12) },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = replyToNote.content,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("Your reply") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 12.dp),
                placeholder = { Text("Write your reply...") },
            )
            Button(
                onClick = { showRelayPicker = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                enabled = content.isNotBlank()
            ) {
                Text("Publish")
            }
        }
    }
}
