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
import social.mycelium.android.data.Author
import social.mycelium.android.data.RelayCategory
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
    myAuthor: Author? = null,
    onPublish: (title: String, content: String, hashtags: List<String>, relayUrls: Set<String>) -> String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var hashtags by remember { mutableStateOf(initialHashtag ?: "") }
    var showRelayPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(initialHashtag) {
        if (initialHashtag != null && hashtags.isEmpty()) {
            hashtags = initialHashtag
        }
    }

    // Build relay sections for the selection screen
    val sections = remember(myAuthor, outboxRelays, relayCategories) {
        buildRelaySections(
            myAuthor = myAuthor,
            myOutboxRelays = outboxRelays,
            relayCategories = relayCategories ?: emptyList()
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
                    Toast.makeText(context, "Topic published", Toast.LENGTH_SHORT).show()
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
                        IconButton(onClick = onBack) {
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                singleLine = true
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("Content") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
                    .padding(vertical = 12.dp),
                placeholder = { Text("What's this topic about?") },
                minLines = 4,
                maxLines = 20
            )
            OutlinedTextField(
                value = hashtags,
                onValueChange = { hashtags = it },
                label = { Text("Hashtags (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. nostr, relay") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { showRelayPicker = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank()
            ) {
                Text("Publish")
            }
        }
    }
}
