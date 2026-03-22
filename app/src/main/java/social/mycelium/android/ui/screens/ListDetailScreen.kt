package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
 * Detail screen for a single NIP-51 people list (kind 30000).
 * Shows all members (public + private) with their profile info,
 * and allows adding/removing members.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailScreen(
    dTag: String,
    onBackClick: () -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    getSigner: () -> com.example.cybin.signer.NostrSigner? = { null },
    getOutboxRelays: () -> Set<String> = { emptySet() },
) {
    val peopleLists by PeopleListRepository.peopleLists.collectAsState()
    val list = peopleLists.firstOrNull { it.dTag == dTag }

    var showAddMemberDialog by remember { mutableStateOf(false) }
    var memberToRemove by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = list?.title ?: "List",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (list != null) {
                            Text(
                                text = "${list.pubkeys.size} members",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddMemberDialog = true }) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = "Add member")
                    }
                }
            )
        }
    ) { padding ->
        if (list == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "List not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        val profileCache = remember { ProfileMetadataCache.getInstance() }
        // Request profiles for all members
        LaunchedEffect(list.pubkeys) {
            val uncached = list.pubkeys.filter { profileCache.getAuthor(it) == null }
            if (uncached.isNotEmpty()) {
                profileCache.requestProfiles(uncached, profileCache.getConfiguredRelayUrls())
            }
        }

        // Sort: resolved profiles first, then by display name
        val sortedMembers = remember(list.pubkeys) {
            list.pubkeys.toList().sortedBy { pk ->
                val author = profileCache.getAuthor(pk)
                author?.displayName?.lowercase() ?: "zzz_${pk.take(8)}"
            }
        }

        if (sortedMembers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.People,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No members yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tap + to add people to this list",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                // Description
                if (!list.description.isNullOrBlank()) {
                    item {
                        Text(
                            text = list.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }

                // Public members header
                if (list.publicPubkeys.isNotEmpty()) {
                    item {
                        SectionLabel("Public Members", list.publicPubkeys.size)
                    }
                    items(
                        sortedMembers.filter { it in list.publicPubkeys },
                        key = { "pub_$it" }
                    ) { pubkey ->
                        MemberRow(
                            pubkey = pubkey,
                            isPrivate = false,
                            profileCache = profileCache,
                            onClick = { onProfileClick(pubkey) },
                            onRemoveClick = { memberToRemove = pubkey }
                        )
                    }
                }

                // Private members header
                if (list.privatePubkeys.isNotEmpty()) {
                    item {
                        SectionLabel("Private Members", list.privatePubkeys.size)
                    }
                    items(
                        sortedMembers.filter { it in list.privatePubkeys },
                        key = { "priv_$it" }
                    ) { pubkey ->
                        MemberRow(
                            pubkey = pubkey,
                            isPrivate = true,
                            profileCache = profileCache,
                            onClick = { onProfileClick(pubkey) },
                            onRemoveClick = { memberToRemove = pubkey }
                        )
                    }
                }
            }
        }
    }

    // ── Add Member Dialog ─────────────────────────────────────────────────
    if (showAddMemberDialog) {
        AddMemberDialog(
            onDismiss = { showAddMemberDialog = false },
            onAdd = { npubOrHex, isPrivate ->
                val signer = getSigner() ?: return@AddMemberDialog
                val relays = getOutboxRelays()
                val hex = resolveToHex(npubOrHex)
                if (hex != null) {
                    PeopleListRepository.addToPeopleList(dTag, hex, isPrivate, signer, relays)
                }
                showAddMemberDialog = false
            }
        )
    }

    // ── Remove Member Confirmation ────────────────────────────────────────
    memberToRemove?.let { pubkey ->
        val author = remember(pubkey) { ProfileMetadataCache.getInstance().getAuthor(pubkey) }
        val displayName = author?.displayName ?: pubkey.take(12) + "..."
        AlertDialog(
            onDismissRequest = { memberToRemove = null },
            title = { Text("Remove Member") },
            text = { Text("Remove $displayName from this list?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val signer = getSigner()
                        val relays = getOutboxRelays()
                        if (signer != null) {
                            PeopleListRepository.removeFromPeopleList(dTag, pubkey, signer, relays)
                        }
                        memberToRemove = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { memberToRemove = null }) { Text("Cancel") }
            }
        )
    }
}

// ── Components ────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "($count)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MemberRow(
    pubkey: String,
    isPrivate: Boolean,
    profileCache: ProfileMetadataCache,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    val author = remember(pubkey) { profileCache.getAuthor(pubkey) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (author?.avatarUrl != null) {
                AsyncImage(
                    model = author.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp).align(Alignment.Center)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = author?.displayName ?: pubkey.take(12) + "...",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (author?.username != null && author.username != author.displayName) {
                    Text(
                        text = "@${author.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (isPrivate) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = "Private",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        IconButton(onClick = onRemoveClick, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AddMemberDialog(
    onDismiss: () -> Unit,
    onAdd: (npubOrHex: String, isPrivate: Boolean) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Member") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("npub or hex pubkey") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isPrivate = !isPrivate }
                ) {
                    Checkbox(checked = isPrivate, onCheckedChange = { isPrivate = it })
                    Spacer(Modifier.width(4.dp))
                    Text("Add as private (encrypted)", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(input.trim(), isPrivate) },
                enabled = input.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Resolve an npub1 or hex string to a 64-char hex pubkey.
 * Returns null if parsing fails.
 */
private fun resolveToHex(input: String): String? {
    val trimmed = input.trim().removePrefix("nostr:")
    if (trimmed.length == 64 && trimmed.all { it in '0'..'9' || it in 'a'..'f' }) {
        return trimmed.lowercase()
    }
    if (trimmed.startsWith("npub1")) {
        return try {
            val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute("nostr:$trimmed")
            (parsed?.entity as? com.example.cybin.nip19.NPub)?.hex?.lowercase()
        } catch (_: Exception) { null }
    }
    if (trimmed.startsWith("nprofile1")) {
        return try {
            val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute("nostr:$trimmed")
            (parsed?.entity as? com.example.cybin.nip19.NProfile)?.hex?.lowercase()
        } catch (_: Exception) { null }
    }
    return null
}
