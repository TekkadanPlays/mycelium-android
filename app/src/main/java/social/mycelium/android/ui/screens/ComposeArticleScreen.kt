package social.mycelium.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import social.mycelium.android.data.Author
import social.mycelium.android.data.DefaultMediaServers
import social.mycelium.android.data.MediaServer
import social.mycelium.android.data.MediaServerType
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayProfile
import social.mycelium.android.data.UserRelay
import social.mycelium.android.ui.components.ComposeToolbar
import social.mycelium.android.ui.components.MentionSuggestionList
import social.mycelium.android.ui.components.MentionSuggestionState
import social.mycelium.android.utils.MarkdownVisualTransformation
import social.mycelium.android.viewmodel.AccountStateViewModel

/**
 * Compose screen for creating NIP-23 long-form content (kind 30023) articles.
 * Fields: title, summary (optional), cover image URL (optional), markdown body, hashtags.
 * Publish button opens relay selection screen before signing and sending.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeArticleScreen(
    outboxRelays: List<UserRelay> = emptyList(),
    myAuthor: Author? = null,
    relayCategories: List<RelayCategory>? = null,
    relayProfiles: List<RelayProfile> = emptyList(),
    blossomServers: List<MediaServer> = DefaultMediaServers.BLOSSOM_SERVERS,
    nip96Servers: List<MediaServer> = DefaultMediaServers.NIP96_SERVERS,
    accountStateViewModel: AccountStateViewModel? = null,
    onPublish: (title: String, content: String, summary: String?, imageUrl: String?, hashtags: List<String>, relayUrls: Set<String>) -> String?,
    onBack: () -> Unit,
    draftId: String? = null,
    modifier: Modifier = Modifier
) {
    val loadedDraft = remember(draftId) { draftId?.let { social.mycelium.android.repository.DraftsRepository.getDraft(it) } }
    var title by remember { mutableStateOf(loadedDraft?.title ?: "") }
    var summary by remember { mutableStateOf("") }
    var coverImageUrl by remember { mutableStateOf("") }
    val initialText = loadedDraft?.content ?: ""
    var textFieldValue by remember { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    val content by remember { derivedStateOf { textFieldValue.text } }
    var lastSavedContent by remember { mutableStateOf(initialText) }
    var lastSavedTitle by remember { mutableStateOf(loadedDraft?.title ?: "") }
    var hashtags by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val mentionState = remember(myAuthor?.id) { MentionSuggestionState(coroutineScope, myAuthor?.id) }
    DisposableEffect(mentionState) { onDispose { mentionState.dispose() } }
    val emojiState = remember { social.mycelium.android.ui.components.EmojiShortcodeSuggestionState(coroutineScope) }
    DisposableEffect(emojiState) { onDispose { emojiState.dispose() } }

    val onBackWithDraft = {
        if (title.isBlank() && content.isBlank()) {
            // User erased everything — delete any existing draft
            loadedDraft?.let { social.mycelium.android.repository.DraftsRepository.deleteDraft(it.id) }
        } else if (content != lastSavedContent || title != lastSavedTitle) {
            social.mycelium.android.repository.DraftsRepository.saveDraft(
                social.mycelium.android.data.Draft(
                    id = loadedDraft?.id ?: java.util.UUID.randomUUID().toString(),
                    type = social.mycelium.android.data.DraftType.ARTICLE,
                    content = content,
                    title = title
                )
            )
        }
        onBack()
    }
    androidx.activity.compose.BackHandler(onBack = onBackWithDraft)

    // Auto-save draft every 30 seconds while editing (if enabled in settings)
    val autoSaveEnabled by social.mycelium.android.ui.settings.FeedPreferences.autoSaveDrafts.collectAsState()
    val draftIdForAutoSave = remember { loadedDraft?.id ?: java.util.UUID.randomUUID().toString() }
    LaunchedEffect(autoSaveEnabled) {
        if (!autoSaveEnabled) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            if ((content.isNotBlank() || title.isNotBlank()) && (content != lastSavedContent || title != lastSavedTitle)) {
                social.mycelium.android.repository.DraftsRepository.saveDraft(
                    social.mycelium.android.data.Draft(
                        id = draftIdForAutoSave,
                        type = social.mycelium.android.data.DraftType.ARTICLE,
                        content = content,
                        title = title
                    )
                )
                lastSavedContent = content
                lastSavedTitle = title
            }
        }
    }

    var showRelayPicker by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    val mdLinkColor = MaterialTheme.colorScheme.primary
    val markdownTransformation = remember(mdLinkColor) { MarkdownVisualTransformation(linkColor = mdLinkColor) }
    val context = LocalContext.current

    // Media server state
    val allServers = remember(blossomServers, nip96Servers) { blossomServers + nip96Servers }
    var selectedMediaServer by remember { mutableStateOf(allServers.firstOrNull() ?: MediaServer("", "", MediaServerType.NIP96)) }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val server = selectedMediaServer
        if (accountStateViewModel == null) {
            Toast.makeText(context, "Sign in to upload media", Toast.LENGTH_SHORT).show()
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

    // Build relay sections for publish
    val sections = remember(myAuthor, outboxRelays, relayCategories, relayProfiles) {
        buildRelaySections(
            myAuthor = myAuthor,
            myOutboxRelays = outboxRelays,
            relayCategories = relayCategories ?: emptyList(),
            relayProfiles = relayProfiles
        )
    }

    if (showRelayPicker) {
        RelaySelectionScreen(
            title = "Publish article",
            sections = sections,
            onConfirm = { selectedUrls ->
                showRelayPicker = false
                val hashtagList = hashtags.split(",").map { it.trim().trimStart('#') }.filter { it.isNotBlank() }
                val err = onPublish(
                    title,
                    content,
                    summary.takeIf { it.isNotBlank() },
                    coverImageUrl.takeIf { it.isNotBlank() },
                    hashtagList,
                    selectedUrls
                )
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
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Article,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "Write Article",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackWithDraft) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                if (title.isNotBlank() && content.isNotBlank()) {
                                    showRelayPicker = true
                                }
                            },
                            enabled = title.isNotBlank() && content.isNotBlank()
                        ) {
                            Text("Publish")
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
                .padding(horizontal = 16.dp)
        ) {
            // Title
            social.mycelium.android.ui.components.ModernTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = "Article Title",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                singleLine = true
            )

            // Summary (optional)
            social.mycelium.android.ui.components.ModernTextField(
                value = summary,
                onValueChange = { summary = it },
                placeholder = "Summary (optional — shown in feed preview)",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                singleLine = false
            )

            // Cover image URL (optional)
            social.mycelium.android.ui.components.ModernTextField(
                value = coverImageUrl,
                onValueChange = { coverImageUrl = it },
                placeholder = "Cover image URL (optional)",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                singleLine = true
            )

            // Article body (markdown)
            social.mycelium.android.ui.components.ModernTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    textFieldValue = newValue
                    mentionState.onTextChanged(newValue.text, newValue.selection.end)
                    emojiState.onTextChanged(newValue.text, newValue.selection.end)
                },
                placeholder = "Write your article in Markdown...",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 8.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text
                ),
                visualTransformation = markdownTransformation,
            )

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

            // Hashtags
            social.mycelium.android.ui.components.ModernTextField(
                value = hashtags,
                onValueChange = { hashtags = it },
                placeholder = "Hashtags (comma-separated)",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Upload progress indicator
            AnimatedVisibility(visible = isUploading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Uploading media...", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Compose toolbar (media upload + publish)
            ComposeToolbar(
                blossomServers = blossomServers,
                nip96Servers = nip96Servers,
                selectedServer = selectedMediaServer,
                onServerSelected = { selectedMediaServer = it },
                onAttachMedia = {
                    mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                markdownEnabled = true,
                onToggleMarkdown = { /* always markdown for articles */ },
                showZapRaiser = false,
                onToggleZapRaiser = { },
                onApplyUnicodeStyle = { /* no unicode styling for articles */ },
                onScheduleClick = {
                    Toast.makeText(context, "Article scheduling coming soon", Toast.LENGTH_SHORT).show()
                },
                publishEnabled = title.isNotBlank() && content.isNotBlank(),
                publishLabel = "Publish article",
                onPublish = { showRelayPicker = true }
            )
        }
    }
}
