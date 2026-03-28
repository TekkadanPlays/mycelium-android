package social.mycelium.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import coil.compose.AsyncImage
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
import social.mycelium.android.utils.ComposeVisualTransformation
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
    var lastSavedContent by remember { mutableStateOf(initialText) }
    val coroutineScope = rememberCoroutineScope()
    val myPubkeyHex = currentAccount?.toHexKey()
    val mentionState = remember(myPubkeyHex) { MentionSuggestionState(coroutineScope, myPubkeyHex) }
    DisposableEffect(mentionState) { onDispose { mentionState.dispose() } }
    val emojiState = remember { social.mycelium.android.ui.components.EmojiShortcodeSuggestionState(coroutineScope) }
    DisposableEffect(emojiState) { onDispose { emojiState.dispose() } }
    val onBackWithDraft = {
        val draftIdForSave = loadedDraft?.id ?: draftId ?: java.util.UUID.randomUUID().toString()
        if (content.isBlank()) {
            // User erased everything — delete any existing draft
            loadedDraft?.let { social.mycelium.android.repository.DraftsRepository.deleteDraft(it.id) }
        } else if (content != lastSavedContent && content != initialContent) {
            social.mycelium.android.repository.DraftsRepository.saveDraft(
                social.mycelium.android.data.Draft(
                    id = draftIdForSave,
                    type = social.mycelium.android.data.DraftType.NOTE,
                    content = content
                )
            )
        }
        onBack()
    }
    // Intercept system back gesture to save draft before leaving
    androidx.activity.compose.BackHandler(onBack = onBackWithDraft)

    // Auto-save draft every 30 seconds while editing (if enabled in settings)
    val autoSaveEnabled by social.mycelium.android.ui.settings.FeedPreferences.autoSaveDrafts.collectAsState()
    val draftIdForAutoSave = remember { loadedDraft?.id ?: java.util.UUID.randomUUID().toString() }
    LaunchedEffect(autoSaveEnabled) {
        if (!autoSaveEnabled) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            if (content.isNotBlank() && content != lastSavedContent) {
                social.mycelium.android.repository.DraftsRepository.saveDraft(
                    social.mycelium.android.data.Draft(
                        id = draftIdForAutoSave,
                        type = social.mycelium.android.data.DraftType.NOTE,
                        content = content
                    )
                )
                lastSavedContent = content
            }
        }
    }

    var zapRaiserAmount by remember { mutableStateOf<Long?>(null) }
    var showZapRaiser by remember { mutableStateOf(false) }
    var markdownEnabled by remember { mutableStateOf(false) }
    var selectedMediaServer by remember { mutableStateOf(blossomServers.firstOrNull() ?: nip96Servers.firstOrNull()) }
    val mdLinkColor = MaterialTheme.colorScheme.primary
    val markdownTransformation = remember(mdLinkColor) { MarkdownVisualTransformation(linkColor = mdLinkColor) }
    val composeTransformation = remember { ComposeVisualTransformation() }
    var isUploading by remember { mutableStateOf(false) }
    // Track uploaded media URLs for inline previews
    val uploadedMediaUrls = remember { mutableStateListOf<String>() }

    // ── Poll mode ──────────────────────────────────────────────────
    var isPollMode by remember { mutableStateOf(false) }
    var pollOptions by remember { mutableStateOf(listOf("", "")) }
    var isMultipleChoice by remember { mutableStateOf(false) }
    var showResultsBeforeVoting by remember { mutableStateOf(false) }
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
                uploadedMediaUrls.add(url)
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
                            relayUrls = selectedUrls,
                            showResults = showResultsBeforeVoting
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
                        else composeTransformation,
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = showResultsBeforeVoting,
                                    onCheckedChange = { showResultsBeforeVoting = it }
                                )
                                Text(
                                    text = "Show results before voting",
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

                // ── Inline media previews ──
                if (uploadedMediaUrls.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uploadedMediaUrls.size) { index ->
                            val url = uploadedMediaUrls[index]
                            val isVideo = url.let { u -> u.endsWith(".mp4", true) || u.endsWith(".mov", true) || u.endsWith(".webm", true) || u.endsWith(".avi", true) }
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                if (isVideo) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = "Video",
                                            modifier = Modifier.size(32.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    AsyncImage(
                                        model = url,
                                        contentDescription = "Uploaded media",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                // Remove button
                                IconButton(
                                    onClick = {
                                        uploadedMediaUrls.removeAt(index)
                                        // Also remove URL from text content
                                        val currentText = textFieldValue.text
                                        val cleaned = currentText.replace(url, "").replace(Regex("\n{2,}"), "\n").trim()
                                        textFieldValue = TextFieldValue(cleaned, TextRange(cleaned.length))
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(24.dp)
                                        .padding(2.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Inline quoted note previews ──
                ComposeQuotedNotePreviews(content = content)

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
                    val normalized = UnicodeStylizer.normalize(content)
                    val styled = UnicodeStylizer.stylize(normalized, style)
                    textFieldValue = TextFieldValue(styled, TextRange(styled.length))
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

/**
 * Extracts nostr:nevent1.../nostr:note1... references from content and displays
 * compact inline preview cards for each. Fetches metadata on the fly via QuotedNoteCache.
 */
@Composable
private fun ComposeQuotedNotePreviews(content: String) {
    val quoteRegex = remember { Regex("""nostr:(nevent1[a-z0-9]{58,}|note1[a-z0-9]{58,})""") }
    val quotedIds = remember(content) {
        quoteRegex.findAll(content).mapNotNull { match ->
            try {
                val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute(match.value)
                when (val entity = parsed?.entity) {
                    is com.example.cybin.nip19.NEvent -> entity.hex
                    is com.example.cybin.nip19.NNote -> entity.hex
                    else -> null
                }
            } catch (_: Exception) { null }
        }.distinct().toList()
    }

    if (quotedIds.isEmpty()) return

    val profileCache = remember { ProfileMetadataCache.getInstance() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        quotedIds.forEach { eventId ->
            var meta by remember(eventId) {
                mutableStateOf(social.mycelium.android.repository.QuotedNoteCache.getCached(eventId))
            }
            LaunchedEffect(eventId) {
                if (meta == null) {
                    meta = social.mycelium.android.repository.QuotedNoteCache.get(eventId)
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (meta != null) {
                    val m = meta!!
                    val author = remember(m.authorId) { profileCache.resolveAuthor(m.authorId) }
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Author avatar
                        if (!author.avatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = author.avatarUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = (author.displayName.firstOrNull() ?: '?').toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = author.displayName.ifBlank { author.username.ifBlank { m.authorId.take(8) + "…" } },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = m.contentSnippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    // Loading placeholder
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Loading quoted note…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
