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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.UserRelay
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
    var content by remember { mutableStateOf("") }
    var showRelayPicker by remember { mutableStateOf(false) }
    val outboxRelays = remember(currentAccount?.npub) {
        accountStateViewModel.getOutboxRelaysForPublish()
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

    if (showRelayPicker) {
        RelayPickerDialog(
            relays = outboxRelays,
            relayCategories = relayCategories,
            initialSelectedUrls = outboxRelays.map { it.url }.toSet(),
            onDismiss = { showRelayPicker = false },
            onConfirm = { selectedUrls ->
                showRelayPicker = false
                val err = accountStateViewModel.publishKind1(content, selectedUrls)
                if (err != null) {
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                } else {
                    onBack()
                }
            }
        )
    }
}

@Composable
private fun RelayPickerDialog(
    relays: List<UserRelay>,
    relayCategories: List<RelayCategory>?,
    initialSelectedUrls: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var selectedUrls by remember(relays) { mutableStateOf(initialSelectedUrls) }
    val outboxUrls = relays.map { it.url }.toSet()

    // Group by category: each category shows only relays that are in outbox; uncategorized outbox go under "Home relays"
    val sections: List<Pair<String, List<UserRelay>>> = if (!relayCategories.isNullOrEmpty()) {
        val categorized = relayCategories.map { cat ->
            cat.name to cat.relays.filter { it.url in outboxUrls }
        }.filter { (_, r) -> r.isNotEmpty() }
        val uncategorized = relays.filter { r -> relayCategories.none { cat -> cat.relays.any { it.url == r.url } } }
        if (uncategorized.isEmpty()) {
            categorized
        } else {
            val homeName = relayCategories.firstOrNull()?.name ?: "Home relays"
            val (firstName, firstRelays) = categorized.firstOrNull() ?: (homeName to emptyList())
            listOf(firstName to (firstRelays + uncategorized)) + categorized.drop(1)
        }
    } else {
        listOf("Relays" to relays)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Publish to relays",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            if (relays.isEmpty()) {
                Text("Add outbox relays in Settings to publish notes.")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    sections.forEach { (categoryName, categoryRelays) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = categoryName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    categoryRelays.forEach { relay ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = selectedUrls.contains(relay.url),
                                                onCheckedChange = { checked ->
                                                    selectedUrls = if (checked) {
                                                        selectedUrls + relay.url
                                                    } else {
                                                        selectedUrls - relay.url
                                                    }
                                                }
                                            )
                                            Text(
                                                text = relay.displayName.ifBlank { relay.url },
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (relays.isEmpty()) {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            } else {
                Button(
                    onClick = { onConfirm(selectedUrls) },
                    enabled = selectedUrls.isNotEmpty()
                ) {
                    Text("Publish")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
