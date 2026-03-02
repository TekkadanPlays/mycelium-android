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
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import social.mycelium.android.data.Author
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayProfile
import social.mycelium.android.data.UserRelay

/**
 * Dedicated screen for creating a Kind 11 topic (like compose for home feed).
 * Title, content, and comma-separated hashtags; optional initial hashtag prefill.
 * Publish button opens relay selection screen before signing and sending.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeTopicScreen(
    initialHashtag: String? = null,
    outboxRelays: List<UserRelay> = emptyList(),
    relayCategories: List<RelayCategory>? = null,
    relayProfiles: List<RelayProfile> = emptyList(),
    myAuthor: Author? = null,
    onPublish: (title: String, content: String, hashtags: List<String>, relayUrls: Set<String>) -> String?,
    onBack: () -> Unit,
    draftId: String? = null,
    modifier: Modifier = Modifier
) {
    val loadedDraft = remember(draftId) { draftId?.let { social.mycelium.android.repository.DraftsRepository.getDraft(it) } }
    var title by remember { mutableStateOf(loadedDraft?.title ?: "") }
    var content by remember { mutableStateOf(loadedDraft?.content ?: "") }
    val onBackWithDraft = {
        if (title.isNotBlank() || content.isNotBlank()) {
            social.mycelium.android.repository.DraftsRepository.saveDraft(
                social.mycelium.android.data.Draft(
                    id = loadedDraft?.id ?: java.util.UUID.randomUUID().toString(),
                    type = social.mycelium.android.data.DraftType.TOPIC,
                    content = content,
                    title = title
                )
            )
        }
        onBack()
    }
    // Intercept system back gesture to save draft before leaving
    androidx.activity.compose.BackHandler(onBack = onBackWithDraft)

    var hashtags by remember { mutableStateOf(initialHashtag ?: "") }
    var showRelayPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(initialHashtag) {
        if (initialHashtag != null && hashtags.isEmpty()) {
            hashtags = initialHashtag
        }
    }

    // Build relay sections for the selection screen
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
            title = "Publish topic",
            sections = sections,
            onConfirm = { selectedUrls ->
                showRelayPicker = false
                val tagList = hashtags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val err = onPublish(title, content, tagList, selectedUrls)
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
                    title = { Text("Create topic") },
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
                value = title,
                onValueChange = { title = it },
                placeholder = "Title",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                singleLine = true
            )
            social.mycelium.android.ui.components.ModernTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = "What's this topic about?",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 12.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text
                ),
            )
            social.mycelium.android.ui.components.ModernTextField(
                value = hashtags,
                onValueChange = { hashtags = it },
                placeholder = "Hashtags (comma-separated)",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { showRelayPicker = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                enabled = title.isNotBlank()
            ) {
                Text("Publish")
            }
        }
    }
}
