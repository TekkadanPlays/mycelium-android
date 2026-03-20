package social.mycelium.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import social.mycelium.android.data.DefaultMediaServers
import social.mycelium.android.data.MediaServer
import social.mycelium.android.data.MediaServerType
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayProfile
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.ui.components.ComposeToolbar
import social.mycelium.android.ui.components.MentionSuggestionList
import social.mycelium.android.ui.components.MentionSuggestionState
import social.mycelium.android.utils.MarkdownVisualTransformation
import social.mycelium.android.utils.UnicodeStylizer
import social.mycelium.android.viewmodel.AccountStateViewModel

/**
 * Note composition screen. User types content and taps Publish to open relay selection.
 * Outbox relays are selected by default; after confirming, the kind-1 note is signed and sent.
 * Includes compose toolbar with media server picker, markdown toggle, zapraiser, and schedule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeNoteScreen(
    onBack: () -> Unit,
    accountStateViewModel: AccountStateViewModel,
    relayCategories: List<RelayCategory>? = null,
    relayProfiles: List<RelayProfile> = emptyList(),
    announcementRelays: List<social.mycelium.android.data.UserRelay> = emptyList(),
    blossomServers: List<MediaServer> = DefaultMediaServers.BLOSSOM_SERVERS,
    nip96Servers: List<MediaServer> = DefaultMediaServers.NIP96_SERVERS,
    initialContent: String = "",
    draftId: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
    val loadedDraft = remember(draftId) { draftId?.let { social.mycelium.android.repository.DraftsRepository.getDraft(it) } }
    val initialText = loadedDraft?.content ?: initialContent
    var textFieldValue by remember { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    val content by remember { derivedStateOf { textFieldValue.text } }
    val coroutineScope = rememberCoroutineScope()
    val myPubkeyHex = currentAccount?.toHexKey()
    val mentionState = remember(myPubkeyHex) { MentionSuggestionState(coroutineScope, myPubkeyHex) }
    DisposableEffect(mentionState) { onDispose { mentionState.dispose() } }
    val emojiState = remember { social.mycelium.android.ui.components.EmojiShortcodeSuggestionState(coroutineScope) }
    DisposableEffect(emojiState) { onDispose { emojiState.dispose() } }
    val onBackWithDraft = {
        if (content.isNotBlank() && content != initialContent) {
            social.mycelium.android.repository.DraftsRepository.saveDraft(
                social.mycelium.android.data.Draft(
                    id = loadedDraft?.id ?: java.util.UUID.randomUUID().toString(),
                    type = social.mycelium.android.data.DraftType.NOTE,
                    content = content
                )
            )
        }
        onBack()
    }
    // Intercept system back gesture to save draft before leaving
    androidx.activity.compose.BackHandler(onBack = onBackWithDraft)

    var zapRaiserAmount by remember { mutableStateOf<Long?>(null) }
    var showZapRaiser by remember { mutableStateOf(false) }
    var markdownEnabled by remember { mutableStateOf(false) }
    var selectedMediaServer by remember { mutableStateOf(blossomServers.firstOrNull() ?: nip96Servers.firstOrNull()) }
    val mdLinkColor = MaterialTheme.colorScheme.primary
    val markdownTransformation = remember(mdLinkColor) { MarkdownVisualTransformation(linkColor = mdLinkColor) }
    var isUploading by remember { mutableStateOf(false) }

    // ── Poll mode ──────────────────────────────────────────────────
    var isPollMode by remember { mutableStateOf(false) }
    var pollOptions by remember { mutableStateOf(listOf("", "")) }
    var isMultipleChoice by remember { mutableStateOf(false) }
    var isZapPoll by remember { mutableStateOf(false) }
    var zapPollMinSats by remember { mutableStateOf("") }
    var zapPollMaxSats by remember { mutableStateOf("") }

    // Image picker → EXIF strip → Blossom upload → insert URL
    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val server = selectedMediaServer
        if (server == null || server.type != MediaServerType.BLOSSOM) {
            Toast.makeText(context, "Select a Blossom server first", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val mimeType = context.contentResolver.getType(uri)
        isUploading = true
        accountStateViewModel.uploadMedia(uri, server.baseUrl, mimeType) { url, error ->
            isUploading = false
            if (url != null) {
                val newText = if (content.isBlank()) url else "$content\n$url"
                textFieldValue = TextFieldValue(newText, TextRange(newText.length))
            } else {
                Toast.makeText(context, error ?: "Upload failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var showRelayPicker by remember { mutableStateOf(false) }
    val outboxRelays = remember(currentAccount?.npub) {
        accountStateViewModel.getOutboxRelaysForPublish()
    }

    // Build relay sections for the selection screen
    val myAuthor = remember(myPubkeyHex) {
        myPubkeyHex?.let { ProfileMetadataCache.getInstance().resolveAuthor(it) }
    }
    val sections = remember(myAuthor, outboxRelays, relayCategories, relayProfiles, announcementRelays) {
        buildRelaySections(
            myAuthor = myAuthor,
            myOutboxRelays = outboxRelays,
            relayCategories = relayCategories ?: emptyList(),
            relayProfiles = relayProfiles,
            announcementRelays = announcementRelays
        )
    }

    if (showRelayPicker) {
        RelaySelectionScreen(
            title = if (isPollMode) "Publish poll" else "Publish note",
            sections = sections,
            onConfirm = { selectedUrls ->
                showRelayPicker = false
                val err = if (isPollMode) {
                    val validOptions = pollOptions.filter { it.isNotBlank() }
                    if (isZapPoll) {
                        val optionPairs = validOptions.mapIndexed { i, label -> i to label }
                        accountStateViewModel.publishZapPoll(
                            question = content,
                            options = optionPairs,
                            valueMinimum = zapPollMinSats.toLongOrNull(),
                            valueMaximum = zapPollMaxSats.toLongOrNull(),
                            closedAtEpochSeconds = null,
                            consensusThreshold = null,
                            relayUrls = selectedUrls
                        )
                    } else {
                        val optionPairs = validOptions.mapIndexed { i, label -> i.toString() to label }
                        accountStateViewModel.publishPoll(
                            question = content,
                            options = optionPairs,
                            isMultipleChoice = isMultipleChoice,
                            endsAtEpochSeconds = null,
                            relayUrls = selectedUrls
                        )
                    }
                } else {
                    accountStateViewModel.publishKind1(content, selectedUrls, zapRaiserAmount = zapRaiserAmount)
                }
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
                    title = { Text(if (isPollMode) "New poll" else "New note") },
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
            // ── Scrollable content area ──
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(if (isPollMode) Modifier.verticalScroll(scrollState) else Modifier)
            ) {
                // Text input — always at the top (question for poll mode, content for note mode)
                social.mycelium.android.ui.components.ModernTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        mentionState.onTextChanged(newValue.text, newValue.selection.end)
                        emojiState.onTextChanged(newValue.text, newValue.selection.end)
                    },
                    placeholder = if (isPollMode) "Ask a question…" else "What's on your mind?",
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isPollMode) Modifier.heightIn(min = 56.dp, max = 120.dp)
                            else Modifier.weight(1f)
                        )
                        .padding(vertical = 12.dp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text
                    ),
                    visualTransformation = if (markdownEnabled) markdownTransformation
                        else androidx.compose.ui.text.input.VisualTransformation.None,
                )

                // ── Poll options editor (below the question) ──
                if (isPollMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pollOptions.forEachIndexed { index, option ->
                            OutlinedTextField(
                                value = option,
                                onValueChange = { newValue ->
                                    pollOptions = pollOptions.toMutableList().also { it[index] = newValue }
                                },
                                placeholder = { Text("Option ${index + 1}") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    if (pollOptions.size > 2) {
                                        androidx.compose.material3.IconButton(onClick = {
                                            pollOptions = pollOptions.toMutableList().also { it.removeAt(index) }
                                        }) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = "Remove option",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        if (pollOptions.size < 10) {
                            TextButton(
                                onClick = { pollOptions = pollOptions + "" }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "Add option",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Add option")
                            }
                        }
                        // Poll type toggle: NIP-88 vs Zap Poll
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(
                                text = "Poll type:",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            FilterChip(
                                selected = !isZapPoll,
                                onClick = { isZapPoll = false },
                                label = { Text("Standard") }
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = isZapPoll,
                                onClick = { isZapPoll = true },
                                label = { Text("⚡ Zap Poll") }
                            )
                        }

                        // Multiple choice toggle (NIP-88 only)
                        if (!isZapPoll) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Checkbox(
                                    checked = isMultipleChoice,
                                    onCheckedChange = { isMultipleChoice = it }
                                )
                                Text(
                                    text = "Allow multiple selections",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        // Zap poll sats range (zap poll only)
                        if (isZapPoll) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = zapPollMinSats,
                                    onValueChange = { zapPollMinSats = it.filter { c -> c.isDigit() } },
                                    placeholder = { Text("Min sats") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                                )
                                OutlinedTextField(
                                    value = zapPollMaxSats,
                                    onValueChange = { zapPollMaxSats = it.filter { c -> c.isDigit() } },
                                    placeholder = { Text("Max sats") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                                )
                            }
                        }
                    }
                }

                // Upload progress indicator
                AnimatedVisibility(visible = isUploading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Uploading media…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Zapraiser input (shown when toggled from toolbar)
                AnimatedVisibility(visible = showZapRaiser) {
                    OutlinedTextField(
                        value = zapRaiserAmount?.toString() ?: "",
                        onValueChange = { zapRaiserAmount = it.toLongOrNull() },
                        label = { Text("Zap goal (sats)") },
                        placeholder = { Text("1000") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                }
            }

            // @mention and :emoji: autocomplete suggestions
            MentionSuggestionList(
                mentionState = mentionState,
                currentText = content,
                onTextUpdated = { newText, newCursor ->
                    textFieldValue = TextFieldValue(newText, TextRange(newCursor))
                }
            )
            social.mycelium.android.ui.components.EmojiShortcodeSuggestionList(
                emojiState = emojiState,
                currentText = content,
                onTextUpdated = { newText, newCursor ->
                    textFieldValue = TextFieldValue(newText, TextRange(newCursor))
                }
            )

            // ── Bottom toolbar: icons + send ──
            ComposeToolbar(
                blossomServers = blossomServers,
                nip96Servers = nip96Servers,
                selectedServer = selectedMediaServer,
                onServerSelected = { selectedMediaServer = it },
                onAttachMedia = {
                    mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                markdownEnabled = markdownEnabled,
                onToggleMarkdown = { markdownEnabled = it },
                showZapRaiser = showZapRaiser,
                onToggleZapRaiser = { enabled ->
                    showZapRaiser = enabled
                    if (!enabled) zapRaiserAmount = null
                },
                onApplyUnicodeStyle = { style ->
                    textFieldValue = TextFieldValue(UnicodeStylizer.stylize(content, style))
                },
                onScheduleClick = {
                    Toast.makeText(context, "Note scheduling coming soon", Toast.LENGTH_SHORT).show()
                },
                isPollMode = isPollMode,
                onPollToggle = { isPollMode = !isPollMode },
                publishEnabled = content.isNotBlank() && !isUploading && (!isPollMode || pollOptions.count { it.isNotBlank() } >= 2),
                publishLabel = if (isPollMode) "Publish poll" else "Publish",
                onPublish = { showRelayPicker = true }
            )
        }
    }
}
