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
import social.mycelium.android.data.Note
import android.widget.Toast

/**
 * Dedicated screen for replying to a comment. Shows the note being replied to at the top,
 * then a content field and Publish (like compose from home feed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyComposeScreen(
    replyToNote: Note?,
    rootId: String,
    rootPubkey: String,
    parentId: String?,
    parentPubkey: String?,
    onPublish: (rootId: String, rootPubkey: String, parentId: String?, parentPubkey: String?, content: String) -> String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var content by remember { mutableStateOf("") }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text(if (replyToNote != null) "Reply" else "Reply to thread") },
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
                    .heightIn(min = 160.dp)
                    .padding(vertical = 12.dp),
                placeholder = { Text("Write your reply...") },
                minLines = 4,
                maxLines = 20
            )
            Button(
                onClick = {
                    val err = onPublish(rootId, rootPubkey, parentId?.takeIf { it.isNotBlank() }, parentPubkey?.takeIf { it.isNotBlank() }, content)
                    if (err != null) {
                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Reply sent", Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = content.isNotBlank()
            ) {
                Text("Publish")
            }
        }
    }
}
