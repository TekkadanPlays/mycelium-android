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
        AddMemberBottomSheet(
            existingPubkeys = list?.pubkeys ?: emptySet(),
            onDismiss = { showAddMemberDialog = false },
            onAdd = { hex, isPrivate ->
                val signer = getSigner() ?: return@AddMemberBottomSheet
                val relays = getOutboxRelays()
                PeopleListRepository.addToPeopleList(dTag, hex, isPrivate, signer, relays)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemberBottomSheet(
    existingPubkeys: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (hex: String, isPrivate: Boolean) -> Unit,
) {
    val profileCache = remember { ProfileMetadataCache.getInstance() }
    var query by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Search results: filter cached profiles by display name, username, or npub
    val allCached = remember { profileCache.getAllCached() }
    val searchResults = remember(query, allCached) {
        if (query.isBlank()) {
            // Show most recently cached profiles when no search query
            allCached.entries
                .filter { it.key !in existingPubkeys }
                .take(50)
                .map { it.key to it.value }
        } else {
            val lowerQuery = query.lowercase().trim()
            // Check if query is a valid NIP-19 identifier or hex pubkey
            val resolvedHex = resolveToHex(query)
            val results = allCached.entries
                .filter { (pubkey, author) ->
                    pubkey !in existingPubkeys && (
                        author.displayName.lowercase().contains(lowerQuery) ||
                        (author.username?.lowercase()?.contains(lowerQuery) == true) ||
                        pubkey.startsWith(lowerQuery) ||
                        (author.nip05?.lowercase()?.contains(lowerQuery) == true)
                    )
                }
                .sortedBy { (_, author) ->
                    // Exact prefix matches first, then contains matches
                    val name = author.displayName.lowercase()
                    when {
                        name.startsWith(lowerQuery) -> 0
                        author.username?.lowercase()?.startsWith(lowerQuery) == true -> 1
                        else -> 2
                    }
                }
                .take(50)
                .map { it.key to it.value }
                .toMutableList()

            // If the resolved hex isn't in the results, add a placeholder entry
            if (resolvedHex != null && results.none { it.first == resolvedHex }) {
                val author = profileCache.getAuthor(resolvedHex)
                    ?: social.mycelium.android.data.Author(
                        id = resolvedHex,
                        username = resolvedHex.take(8) + "...",
                        displayName = resolvedHex.take(12) + "...",
                        avatarUrl = null,
                        isVerified = false
                    )
                results.add(0, resolvedHex to author)
            }
            results
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = "Add Member",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            // Search field
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search by name, username, or paste npub/hex") },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear")
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // Private toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { isPrivate = !isPrivate }
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Checkbox(checked = isPrivate, onCheckedChange = { isPrivate = it })
                Spacer(Modifier.width(4.dp))
                Text("Add as private (encrypted)", style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            // Results list
            if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.PersonSearch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (query.isBlank()) "Search for a user" else "No results found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (query.isNotBlank()) {
                            Text(
                                text = "Try pasting an npub or hex pubkey",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(searchResults, key = { it.first }) { (pubkey, author) ->
                        SearchResultRow(
                            pubkey = pubkey,
                            author = author,
                            onClick = {
                                onAdd(pubkey, isPrivate)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    pubkey: String,
    author: social.mycelium.android.data.Author,
    onClick: () -> Unit,
) {
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
            if (author.avatarUrl != null) {
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
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = author.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row {
                if (author.username != null && author.username != author.displayName) {
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
                if (author.nip05 != null) {
                    Text(
                        text = author.nip05!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Add button hint
        Icon(
            Icons.Outlined.PersonAdd,
            contentDescription = "Add",
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Resolve an npub1, nprofile1, or hex string to a 64-char hex pubkey.
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

