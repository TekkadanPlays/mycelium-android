package social.mycelium.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cybin.signer.NostrSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.mycelium.android.data.Author
import social.mycelium.android.repository.cache.ProfileMetadataCache
import social.mycelium.android.services.Nip86Client
import social.mycelium.android.ui.components.common.ProfilePicture
import com.example.cybin.nip19.toNpub
import java.text.Normalizer

/**
 * Full-screen NIP-86 relay user management.
 * Shows the allow list for a relay with per-user controls (add / remove).
 * Includes user search via ProfileMetadataCache fuzzy matching + npub/hex resolution.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayUserManagementScreen(
    relayUrl: String,
    signer: NostrSigner,
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val client = remember(signer) { Nip86Client(signer) }
    val profileCache = remember { ProfileMetadataCache.getInstance() }

    var supportedMethods by remember { mutableStateOf<List<String>?>(null) }
    var allowedPubkeys by remember { mutableStateOf<List<Nip86Client.AllowedEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var removingPubkey by remember { mutableStateOf<String?>(null) }

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Author>>(emptyList()) }
    var showSearchResults by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val canList = supportedMethods?.let { "listallowedpubkeys" in it } ?: false
    val canAdd = supportedMethods?.let { "allowpubkey" in it } ?: false
    val canRemove = supportedMethods?.let { "disallowpubkey" in it } ?: false

    val allowedPubkeySet = remember(allowedPubkeys) {
        allowedPubkeys.map { it.pubkey.lowercase() }.toSet()
    }

    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackbarMessage = null
        }
    }

    // Probe NIP-86 methods then load allow list
    LaunchedEffect(Unit) {
        isLoading = true
        when (val methodResult = client.supportedMethods(relayUrl)) {
            is Nip86Client.Nip86Result.Success -> {
                supportedMethods = methodResult.data
                if ("listallowedpubkeys" in methodResult.data) {
                    when (val listResult = client.listAllowedPubkeys(relayUrl)) {
                        is Nip86Client.Nip86Result.Success -> {
                            allowedPubkeys = listResult.data
                            error = null
                        }
                        is Nip86Client.Nip86Result.Error -> { error = listResult.message }
                    }
                }
            }
            is Nip86Client.Nip86Result.Error -> {
                supportedMethods = emptyList()
                error = methodResult.message
            }
        }
        isLoading = false
    }

    // Search when query changes
    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 2) {
            searchResults = emptyList()
            showSearchResults = false
            return@LaunchedEffect
        }
        val query = searchQuery.trim()
        val results = withContext(Dispatchers.Default) {
            // Direct npub/hex resolution
            val directHex = resolveToHex(query)
            val directAuthor = if (directHex != null) {
                listOf(profileCache.resolveAuthor(directHex))
            } else emptyList()

            // Fuzzy search across cached profiles
            val allProfiles = profileCache.getAllCached()
            val normalizedQuery = normalizeForSearch(query.lowercase())
            val fuzzyResults = allProfiles.values
                .filter { author ->
                    val id = author.id.lowercase()
                    // Skip if already in allow list
                    if (id in allowedPubkeySet) return@filter false
                    // Skip direct matches (shown separately)
                    if (directHex != null && id == directHex.lowercase()) return@filter false
                    val dn = normalizeForSearch(author.displayName.lowercase())
                    val un = normalizeForSearch(author.username.lowercase())
                    dn.contains(normalizedQuery) || un.contains(normalizedQuery)
                }
                .sortedByDescending { author ->
                    val dn = normalizeForSearch(author.displayName.lowercase())
                    val un = normalizeForSearch(author.username.lowercase())
                    when {
                        dn.startsWith(normalizedQuery) -> 100f
                        un.startsWith(normalizedQuery) -> 90f
                        dn.contains(normalizedQuery) -> 50f
                        else -> 30f
                    } + if (author.avatarUrl != null) 5f else 0f
                }
                .take(15)

            (directAuthor + fuzzyResults).distinctBy { it.id.lowercase() }
        }
        searchResults = results
        showSearchResults = results.isNotEmpty()
    }

    // Helper: add a user by hex pubkey
    fun addUser(hex: String) {
        if (isAdding) return
        isAdding = true
        scope.launch {
            when (val r = client.allowPubkey(relayUrl, hex)) {
                is Nip86Client.Nip86Result.Success -> {
                    val author = profileCache.resolveAuthor(hex)
                    val name = author.displayName.takeIf { it.isNotBlank() } ?: hex.take(8) + "\u2026"
                    snackbarMessage = "Added $name"
                    searchQuery = ""
                    showSearchResults = false
                    // Refresh list
                    if (canList) {
                        when (val lr = client.listAllowedPubkeys(relayUrl)) {
                            is Nip86Client.Nip86Result.Success -> allowedPubkeys = lr.data
                            is Nip86Client.Nip86Result.Error -> {}
                        }
                    } else {
                        allowedPubkeys = allowedPubkeys + Nip86Client.AllowedEntry(hex, null)
                    }
                }
                is Nip86Client.Nip86Result.Error -> snackbarMessage = "Failed: ${r.message}"
            }
            isAdding = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Allowed Users",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            relayUrl.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Search / Add user bar ──
            if (canAdd) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search users or paste npub / hex") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search, null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = ""; showSearchResults = false }) {
                                    Icon(Icons.Default.Clear, "Clear", modifier = Modifier.size(20.dp))
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .focusRequester(focusRequester),
                        enabled = !isAdding,
                        shape = RectangleShape
                    )

                    // Search results dropdown
                    AnimatedVisibility(visible = showSearchResults && searchResults.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(searchResults, key = { it.id }) { author ->
                                SearchResultRow(
                                    author = author,
                                    isAdding = isAdding,
                                    onAdd = { addUser(author.id) },
                                    onProfileClick = { onProfileClick(author.id) }
                                )
                            }
                        }
                    }

                    // Direct add button for raw npub/hex input
                    val directHex = remember(searchQuery) { resolveToHex(searchQuery.trim()) }
                    if (directHex != null && directHex.lowercase() !in allowedPubkeySet && !showSearchResults) {
                        val directAuthor = remember(directHex) { profileCache.resolveAuthor(directHex) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                                .clickable(enabled = !isAdding) { addUser(directHex) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProfilePicture(author = directAuthor, size = 36.dp, onClick = {})
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                val name = directAuthor.displayName.takeIf { it.isNotBlank() }
                                    ?: directAuthor.username.takeIf { it.isNotBlank() }
                                Text(
                                    name ?: directHex.take(16) + "\u2026",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "Tap to add to allow list",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (isAdding) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Outlined.PersonAdd, "Add",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                }
            }

            // ── Content ──
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Loading\u2026",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Text(
                                "Error",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                error ?: "Unknown error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                allowedPubkeys.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.PersonOff, null,
                                Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (canList) "No allowed users" else "Allow list not queryable",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            if (canAdd) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Search above to add users",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
                else -> {
                    // User count header
                    Text(
                        "${allowedPubkeys.size} user${if (allowedPubkeys.size != 1) "s" else ""} allowed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(allowedPubkeys, key = { it.pubkey }) { entry ->
                            AllowedUserRow(
                                entry = entry,
                                profileCache = profileCache,
                                canRemove = canRemove,
                                isRemoving = removingPubkey == entry.pubkey,
                                onRemove = {
                                    removingPubkey = entry.pubkey
                                    scope.launch {
                                        when (val r = client.disallowPubkey(relayUrl, entry.pubkey)) {
                                            is Nip86Client.Nip86Result.Success -> {
                                                allowedPubkeys = allowedPubkeys.filter { it.pubkey != entry.pubkey }
                                                snackbarMessage = "User removed"
                                            }
                                            is Nip86Client.Nip86Result.Error -> snackbarMessage = "Failed: ${r.message}"
                                        }
                                        removingPubkey = null
                                    }
                                },
                                onProfileClick = { onProfileClick(entry.pubkey) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Allowed user row with card styling ──

@Composable
private fun AllowedUserRow(
    entry: Nip86Client.AllowedEntry,
    profileCache: ProfileMetadataCache,
    canRemove: Boolean,
    isRemoving: Boolean,
    onRemove: () -> Unit,
    onProfileClick: () -> Unit
) {
    val author = remember(entry.pubkey) { profileCache.resolveAuthor(entry.pubkey) }
    val displayName = author.displayName.takeIf { it.isNotBlank() }
        ?: author.username.takeIf { it.isNotBlank() }
    val npub = remember(entry.pubkey) { entry.pubkey.toNpub() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
            ProfilePicture(
                author = author,
                size = 40.dp,
                onClick = onProfileClick
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName ?: npub.take(16) + "\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (displayName != null) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = npub.take(20) + "\u2026" + npub.takeLast(6),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                if (entry.reason != null) {
                    Text(
                        text = entry.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        if (canRemove) {
            IconButton(onClick = onRemove, enabled = !isRemoving) {
                if (isRemoving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Outlined.PersonRemove, "Remove user",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// ── Search result row ──

@Composable
private fun SearchResultRow(
    author: Author,
    isAdding: Boolean,
    onAdd: () -> Unit,
    onProfileClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isAdding) { onAdd() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfilePicture(author = author, size = 36.dp, onClick = onProfileClick)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            val name = author.displayName.takeIf { it.isNotBlank() }
                ?: author.username.takeIf { it.isNotBlank() }
            Text(
                text = name ?: author.id.take(12) + "\u2026",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val npub = remember(author.id) { author.id.toNpub() }
            Text(
                text = npub.take(20) + "\u2026" + npub.takeLast(6),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        if (isAdding) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Outlined.PersonAdd, "Add user",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ── Helpers ──

private fun resolveToHex(input: String): String? {
    if (input.isBlank()) return null
    // Try npub/nprofile
    if (input.startsWith("npub1") || input.startsWith("nprofile1")) {
        return try {
            val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute(input)
            when (val entity = parsed?.entity) {
                is com.example.cybin.nip19.NPub -> entity.hex
                is com.example.cybin.nip19.NProfile -> entity.hex
                else -> null
            }
        } catch (_: Exception) { null }
    }
    // Try 64-char hex
    if (input.length == 64 && input.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        return input.lowercase()
    }
    return null
}

private fun normalizeForSearch(input: String): String {
    val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
    return decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()
}

