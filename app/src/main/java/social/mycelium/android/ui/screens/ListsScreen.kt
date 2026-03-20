package social.mycelium.android.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import social.mycelium.android.repository.PeopleListRepository
import social.mycelium.android.repository.ProfileMetadataCache

/**
 * Lists management screen — shows the user's NIP-51 people lists (kind 30000)
 * and subscribed hashtags (kind 10015). Supports viewing, creating, editing,
 * and deleting lists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    onBackClick: () -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNavigateToListDetail: (String) -> Unit = {},
    getSigner: () -> com.example.cybin.signer.NostrSigner? = { null },
    getOutboxRelays: () -> Set<String> = { emptySet() },
) {
    val peopleLists by PeopleListRepository.peopleLists.collectAsState()
    val subscribedHashtags by PeopleListRepository.subscribedHashtags.collectAsState()

    var showCreateListDialog by remember { mutableStateOf(false) }
    var showAddHashtagDialog by remember { mutableStateOf(false) }
    var listToDelete by remember { mutableStateOf<PeopleListRepository.PeopleList?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lists") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateListDialog = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Create list")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // ── People Lists Section ─────────────────────────────────────
            item {
                SectionHeader(
                    title = "People Lists",
                    subtitle = "${peopleLists.size} lists",
                    icon = Icons.Outlined.People
                )
            }

            if (peopleLists.isEmpty()) {
                item {
                    EmptyState(
                        message = "No people lists yet",
                        hint = "Create a list to organize the people you follow into groups"
                    )
                }
            } else {
                items(peopleLists, key = { it.dTag }) { list ->
                    PeopleListRow(
                        list = list,
                        onClick = { onNavigateToListDetail(list.dTag) },
                        onDeleteClick = { listToDelete = list }
                    )
                }
            }

            // ── Hashtag Subscriptions Section ────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader(
                    title = "Followed Hashtags",
                    subtitle = "${subscribedHashtags.size} hashtags",
                    icon = Icons.Outlined.Tag,
                    actionIcon = Icons.Outlined.Add,
                    onActionClick = { showAddHashtagDialog = true }
                )
            }

            if (subscribedHashtags.isEmpty()) {
                item {
                    EmptyState(
                        message = "No followed hashtags",
                        hint = "Follow hashtags to see them in your home feed"
                    )
                }
            } else {
                items(subscribedHashtags.toList().sorted(), key = { it }) { hashtag ->
                    HashtagRow(
                        hashtag = hashtag,
                        onUnfollow = {
                            val signer = getSigner() ?: return@HashtagRow
                            val relays = getOutboxRelays()
                            PeopleListRepository.unfollowHashtag(hashtag, signer, relays)
                        }
                    )
                }
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────

    if (showCreateListDialog) {
        CreateListDialog(
            onDismiss = { showCreateListDialog = false },
            onCreate = { title, description ->
                val signer = getSigner() ?: return@CreateListDialog
                val relays = getOutboxRelays()
                PeopleListRepository.createPeopleList(
                    title = title,
                    description = description.takeIf { it.isNotBlank() },
                    signer = signer,
                    relayUrls = relays,
                )
                showCreateListDialog = false
            }
        )
    }

    if (showAddHashtagDialog) {
        AddHashtagDialog(
            onDismiss = { showAddHashtagDialog = false },
            onAdd = { hashtag ->
                val signer = getSigner() ?: return@AddHashtagDialog
                val relays = getOutboxRelays()
                PeopleListRepository.followHashtag(hashtag, signer, relays)
                showAddHashtagDialog = false
            }
        )
    }

    listToDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { listToDelete = null },
            title = { Text("Delete List") },
            text = { Text("Delete \"${list.title}\"? This will remove the list from all your relays.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val signer = getSigner()
                        val relays = getOutboxRelays()
                        if (signer != null) {
                            PeopleListRepository.deletePeopleList(list.dTag, signer, relays)
                        }
                        listToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { listToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

// ── Components ───────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionIcon != null && onActionClick != null) {
            IconButton(onClick = onActionClick, modifier = Modifier.size(32.dp)) {
                Icon(actionIcon, contentDescription = "Add", modifier = Modifier.size(18.dp))
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}

@Composable
private fun PeopleListRow(
    list: PeopleListRepository.PeopleList,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // List icon or image
        if (list.image != null) {
            AsyncImage(
                model = list.image,
                contentDescription = list.title,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.People,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = list.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${list.pubkeys.size} members",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (list.description != null) {
                Text(
                    text = list.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Member avatar stack (first 3)
        MemberAvatarStack(list.pubkeys.take(3).toList())

        IconButton(onClick = onDeleteClick, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun MemberAvatarStack(pubkeys: List<String>) {
    if (pubkeys.isEmpty()) return
    Row(modifier = Modifier.padding(end = 4.dp)) {
        pubkeys.forEachIndexed { index, pubkey ->
            val author = remember(pubkey) {
                ProfileMetadataCache.getInstance().getAuthor(pubkey)
            }
            val avatarUrl = author?.avatarUrl
            Box(
                modifier = Modifier
                    .offset(x = (-8 * index).dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (avatarUrl != null) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Outlined.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@Composable
private fun HashtagRow(
    hashtag: String,
    onUnfollow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Tag,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "#$hashtag",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onUnfollow, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Unfollow",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(message: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

// ── Dialogs ──────────────────────────────────────────────────────────────

@Composable
private fun CreateListDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, description: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create List") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("List Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title.trim(), description.trim()) },
                enabled = title.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddHashtagDialog(
    onDismiss: () -> Unit,
    onAdd: (hashtag: String) -> Unit,
) {
    var hashtag by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Follow Hashtag") },
        text = {
            OutlinedTextField(
                value = hashtag,
                onValueChange = { hashtag = it.removePrefix("#") },
                label = { Text("Hashtag") },
                prefix = { Text("#") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(hashtag.trim()) },
                enabled = hashtag.isNotBlank()
            ) { Text("Follow") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
