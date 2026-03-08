package social.mycelium.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import social.mycelium.android.data.DefaultMediaServers
import social.mycelium.android.data.MediaServer
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayProfile
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.ui.components.ComposeToolbar
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
    var content by remember { mutableStateOf(loadedDraft?.content ?: initialContent) }
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

    var showRelayPicker by remember { mutableStateOf(false) }
    val outboxRelays = remember(currentAccount?.npub) {
        accountStateViewModel.getOutboxRelaysForPublish()
    }

    // Build relay sections for the selection screen
    val myPubkeyHex = currentAccount?.toHexKey()
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
            title = "Publish note",
            sections = sections,
            onConfirm = { selectedUrls ->
                showRelayPicker = false
                val err = accountStateViewModel.publishKind1(content, selectedUrls, zapRaiserAmount = zapRaiserAmount)
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
                    title = { Text("New note") },
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
            social.mycelium.android.ui.components.ModernTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = "What's on your mind?",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 16.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text
                ),
            )
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
            // Compose toolbar with media server picker, markdown toggle, zapraiser, schedule
            ComposeToolbar(
                blossomServers = blossomServers,
                nip96Servers = nip96Servers,
                selectedServer = selectedMediaServer,
                onServerSelected = { selectedMediaServer = it },
                onAttachMedia = {
                    // TODO: Launch image picker → strip metadata → upload via BlossomClient → insert URL
                    Toast.makeText(context, "Media upload coming soon", Toast.LENGTH_SHORT).show()
                },
                markdownEnabled = markdownEnabled,
                onToggleMarkdown = { markdownEnabled = it },
                showZapRaiser = showZapRaiser,
                onToggleZapRaiser = { enabled ->
                    showZapRaiser = enabled
                    if (!enabled) zapRaiserAmount = null
                },
                onScheduleClick = {
                    // TODO: Show date/time picker → sign event → store in draft → schedule alarm
                    Toast.makeText(context, "Note scheduling coming soon", Toast.LENGTH_SHORT).show()
                }
            )
            Button(
                onClick = { showRelayPicker = true },
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 16.dp),
                enabled = content.isNotBlank()
            ) {
                Text("Publish")
            }
        }
    }
}
