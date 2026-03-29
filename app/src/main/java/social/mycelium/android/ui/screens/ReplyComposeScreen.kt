package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.common.cutoutPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.mycelium.android.data.Author
import social.mycelium.android.data.Note
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayProfile
import social.mycelium.android.data.UserRelay
import social.mycelium.android.repository.relay.Nip65RelayListRepository
import social.mycelium.android.repository.cache.ProfileMetadataCache
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import social.mycelium.android.ui.components.compose.MentionSuggestionList
import social.mycelium.android.ui.components.compose.MentionSuggestionState
import social.mycelium.android.ui.components.common.ModernTextField

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
    onPublish: (rootId: String, rootPubkey: String, parentId: String?, parentPubkey: String?, content: String, relayUrls: Set<String>, taggedPubkeys: List<String>) -> String?,
    onBack: () -> Unit,
    myAuthor: Author? = null,
    myOutboxRelays: List<UserRelay> = emptyList(),
    relayCategories: List<RelayCategory>? = null,
    relayProfiles: List<RelayProfile> = emptyList(),
    draftId: String? = null,
    modifier: Modifier = Modifier
) {
    val loadedDraft = remember(draftId) { draftId?.let { social.mycelium.android.repository.DraftsRepository.getDraft(it) } }
    val initialText = loadedDraft?.content ?: ""
    var textFieldValue by remember { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    val content by remember { derivedStateOf { textFieldValue.text } }
    var lastSavedContent by remember { mutableStateOf(initialText) }
    val coroutineScope = rememberCoroutineScope()
    val mentionState = remember(myAuthor?.id) { MentionSuggestionState(coroutineScope, myAuthor?.id) }
    DisposableEffect(mentionState) { onDispose { mentionState.dispose() } }
    val emojiState = remember { social.mycelium.android.ui.components.emoji.EmojiShortcodeSuggestionState(coroutineScope) }
    DisposableEffect(emojiState) { onDispose { emojiState.dispose() } }
    val onBackWithDraft = {
        if (content.isBlank()) {
            // User erased everything — delete any existing draft
            loadedDraft?.let { social.mycelium.android.repository.DraftsRepository.deleteDraft(it.id) }
        } else if (content != lastSavedContent) {
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
    // Intercept system back gesture to save draft before leaving
    androidx.activity.compose.BackHandler(onBack = onBackWithDraft)




    var showRelayPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val profileCache = remember { ProfileMetadataCache.getInstance() }
    val myPubkey = myAuthor?.id

    // ── Amethyst-style reply chain tagging ──
    // Collect p-tags from parent event + parent author (like Amethyst ShortNotePostViewModel)
    val initialTaggedPubkeys = remember(replyToNote, rootPubkey, parentPubkey) {
        val parentMentions = replyToNote?.mentionedPubkeys ?: emptyList()
        val parentAuthorPubkey = parentPubkey ?: rootPubkey
        // Amethyst logic: forward parent's p-tags, ensure parent author is included
        val combined = if (parentMentions.contains(parentAuthorPubkey)) {
            parentMentions
        } else {
            parentMentions + parentAuthorPubkey
        }
        // Also ensure root author is included if different
        val withRoot = if (rootPubkey !in combined) combined + rootPubkey else combined
        // Remove our own pubkey — don't tag yourself
        withRoot.filter { it != myPubkey }.distinct()
    }
    var taggedPubkeys by remember { mutableStateOf(initialTaggedPubkeys) }

    // Resolve display names for tagged pubkeys
    val taggedAuthors by remember {
        derivedStateOf {
            taggedPubkeys.map { pk -> profileCache.resolveAuthor(pk) }
        }
    }

    // ── Inbox relay fetching for all tagged users ──
    // Map of pubkey -> inbox relay URLs (fetched from NIP-65)
    var taggedInboxRelays by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    // Fetch NIP-65 inbox relays for all tagged pubkeys
    LaunchedEffect(taggedPubkeys) {
        if (taggedPubkeys.isEmpty()) {
            taggedInboxRelays = emptyMap()
            return@LaunchedEffect
        }
        val discoveryRelays = listOf("wss://purplepag.es", "wss://user.kindpag.es", "wss://indexer.coracle.social", "wss://directory.yabu.me") +
            (Nip65RelayListRepository.readRelays.value.takeIf { it.isNotEmpty() } ?: emptyList())
        // Batch fetch for any pubkeys not yet cached
        val uncached = taggedPubkeys.filter { Nip65RelayListRepository.getCachedAuthorRelays(it) == null }
        if (uncached.isNotEmpty()) {
            Nip65RelayListRepository.batchFetchRelayLists(uncached, discoveryRelays)
            // Poll for cache population
            kotlinx.coroutines.withTimeoutOrNull(5000) {
                while (true) {
                    kotlinx.coroutines.delay(300)
                    val allCached = uncached.all { Nip65RelayListRepository.getCachedAuthorRelays(it) != null }
                    if (allCached) break
                }
            }
        }
        // Build map from cache
        taggedInboxRelays = taggedPubkeys.associateWith { pk ->
            Nip65RelayListRepository.getCachedAuthorRelays(pk)?.readRelays ?: emptyList()
        }
    }

    // Also request kind-0 profiles for tagged pubkeys so display names resolve
    LaunchedEffect(taggedPubkeys) {
        val unknownPubkeys = taggedPubkeys.filter { profileCache.getAuthor(it) == null }
        if (unknownPubkeys.isNotEmpty()) {
            val discoveryRelays = listOf("wss://purplepag.es", "wss://user.kindpag.es") +
                (Nip65RelayListRepository.readRelays.value.takeIf { it.isNotEmpty() } ?: emptyList())
            profileCache.requestProfiles(unknownPubkeys, discoveryRelays)
        }
    }

    // Build per-user inbox list for relay selection (each tagged person gets own section)
    val taggedUserInboxes = remember(taggedAuthors, taggedInboxRelays) {
        taggedAuthors.mapIndexed { index, author ->
            val pk = taggedPubkeys.getOrNull(index) ?: author.id
            author to (taggedInboxRelays[pk] ?: emptyList())
        }
    }

    // Build relay sections with per-user inbox sections + our profile
    val sections = remember(taggedUserInboxes, myAuthor, myOutboxRelays, relayCategories, relayProfiles, replyToNote) {
        buildRelaySections(
            myAuthor = myAuthor,
            myOutboxRelays = myOutboxRelays,
            relayCategories = relayCategories ?: emptyList(),
            relayProfiles = relayProfiles,
            noteRelayUrls = replyToNote?.relayUrls ?: emptyList(),
            taggedUserInboxes = taggedUserInboxes
        )
    }

    if (showRelayPicker) {
        RelaySelectionScreen(
            title = "Publish reply",
            sections = sections,
            onConfirm = { selectedUrls ->
                showRelayPicker = false
                val err = onPublish(rootId, rootPubkey, parentId?.takeIf { it.isNotBlank() }, parentPubkey?.takeIf { it.isNotBlank() }, content, selectedUrls, taggedPubkeys)
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

            // ── Tagged users (removable chips) ──
            if (taggedPubkeys.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tagging",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        taggedAuthors.forEachIndexed { index, author ->
                            InputChip(
                                selected = true,
                                onClick = {
                                    // Remove this tagged user
                                    taggedPubkeys = taggedPubkeys.filterIndexed { i, _ -> i != index }
                                },
                                label = {
                                    Text(
                                        text = author.displayName.ifBlank { author.username }.ifBlank { author.id.take(8) + "…" },
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }
            }
            social.mycelium.android.ui.components.common.ModernTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    textFieldValue = newValue
                    mentionState.onTextChanged(newValue.text, newValue.selection.end)
                    emojiState.onTextChanged(newValue.text, newValue.selection.end)
                },
                placeholder = "Write your reply...",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 12.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text
                ),
            )
            MentionSuggestionList(
                mentionState = mentionState,
                currentText = content,
                onTextUpdated = { newText, newCursor ->
                    textFieldValue = TextFieldValue(newText, TextRange(newCursor))
                }
            )
            social.mycelium.android.ui.components.emoji.EmojiShortcodeSuggestionList(
                emojiState = emojiState,
                currentText = content,
                onTextUpdated = { newText, newCursor ->
                    textFieldValue = TextFieldValue(newText, TextRange(newCursor))
                }
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
