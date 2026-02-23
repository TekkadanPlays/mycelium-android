package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.viewmodel.AccountStateViewModel

/**
 * Note composition screen. User types content and taps Publish to open relay selection.
 * Outbox relays are selected by default; after confirming, the kind-1 note is signed and sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeNoteScreen(
    onBack: () -> Unit,
    accountStateViewModel: AccountStateViewModel,
    relayCategories: List<RelayCategory>? = null,
    initialContent: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
    var content by remember { mutableStateOf(initialContent) }
    var showRelayPicker by remember { mutableStateOf(false) }
    val outboxRelays = remember(currentAccount?.npub) {
        accountStateViewModel.getOutboxRelaysForPublish()
    }

    // Build relay sections for the selection screen
    val myPubkeyHex = currentAccount?.toHexKey()
    val myAuthor = remember(myPubkeyHex) {
        myPubkeyHex?.let { ProfileMetadataCache.getInstance().resolveAuthor(it) }
    }
    val sections = remember(myAuthor, outboxRelays, relayCategories) {
        buildRelaySections(
            myAuthor = myAuthor,
            myOutboxRelays = outboxRelays,
            relayCategories = relayCategories ?: emptyList()
        )
    }

    if (showRelayPicker) {
        RelaySelectionScreen(
            title = "Publish note",
            sections = sections,
            onConfirm = { selectedUrls ->
                showRelayPicker = false
                val err = accountStateViewModel.publishKind1(content, selectedUrls)
                if (err != null) {
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                } else {
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
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
                    .padding(vertical = 16.dp),
                placeholder = { Text("What's on your mind?") },
                minLines = 6,
                maxLines = 20
            )
            Button(
                onClick = { showRelayPicker = true },
                modifier = Modifier.padding(top = 8.dp),
                enabled = content.isNotBlank()
            ) {
                Text("Publish")
            }
        }
    }
}
