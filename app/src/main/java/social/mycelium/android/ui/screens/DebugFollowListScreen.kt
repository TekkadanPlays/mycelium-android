package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.common.cutoutPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import social.mycelium.android.data.Author
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.repository.social.ContactListRepository
import social.mycelium.android.repository.feed.NotesRepository
import social.mycelium.android.repository.cache.ProfileMetadataCache
import social.mycelium.android.repository.relay.RelayStorageManager
import social.mycelium.android.utils.normalizeAuthorIdForCache
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugFollowListScreen(
    currentAccountPubkey: String?,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var followList by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    val storageManager = remember(context) { RelayStorageManager(context) }
    val cacheRelayUrls = remember(currentAccountPubkey) {
        currentAccountPubkey?.let { storageManager.loadIndexerRelays(it).map { r -> r.url } } ?: emptyList()
    }
    val relayCategories = remember(currentAccountPubkey) {
        currentAccountPubkey?.let { storageManager.loadCategories(it) } ?: emptyList()
    }

    val profileCache = ProfileMetadataCache.getInstance()
    LaunchedEffect(Unit) {
        profileCache.profileUpdated.collect { refreshTrigger++ }
    }

    LaunchedEffect(currentAccountPubkey, cacheRelayUrls) {
        if (currentAccountPubkey == null || cacheRelayUrls.isEmpty()) {
            error = "No account or cache relays"
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        error = null
        followList = try {
            ContactListRepository.fetchFollowList(currentAccountPubkey, cacheRelayUrls, forceRefresh = true).sorted()
        } catch (e: Exception) {
            error = e.message ?: "Failed to load"
            emptyList()
        }
        isLoading = false
    }

    val cachedCount = remember(followList, refreshTrigger) {
        followList.count { profileCache.getAuthor(normalizeAuthorIdForCache(it)) != null }
    }

    // For "Fetch all": use cache relays + subscription (feed) relays for better coverage; cache uses longer timeout when uncached.size > 50
    val profileRelayUrlsForBulk = remember(cacheRelayUrls, relayCategories) {
        val subscriptionUrls = relayCategories.flatMap { it.relays }.map { it.url }.distinct()
        (cacheRelayUrls + subscriptionUrls).distinct().filter { it.isNotBlank() }
    }

    fun fetchUncached() {
        val uncached = followList.filter { profileCache.getAuthor(normalizeAuthorIdForCache(it)) == null }
        val relayUrls = if (profileRelayUrlsForBulk.isNotEmpty()) profileRelayUrlsForBulk else cacheRelayUrls
        if (uncached.isEmpty() || relayUrls.isEmpty()) return
        scope.launch {
            profileCache.requestProfiles(uncached, relayUrls)
            // Push cache into home feed so new profiles show there (profileUpdated can drop when many emit at once)
            NotesRepository.getInstance().refreshAuthorsFromCache()
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text("Following & profiles") },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        ) {
            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item(key = "relays") {
                            DebugRelaySection(
                                cacheRelayUrls = cacheRelayUrls,
                                relayCategories = relayCategories
                            )
                        }
                        item(key = "summary") {
                            DebugSummaryRow(
                                cachedCount = cachedCount,
                                totalCount = followList.size,
                                onFetchAll = ::fetchUncached
                            )
                        }
                        items(followList, key = { it }) { pubkey ->
                            DebugFollowRow(
                                pubkey = pubkey,
                                author = profileCache.getAuthor(normalizeAuthorIdForCache(pubkey)),
                                cacheRelayUrls = cacheRelayUrls,
                                onFetch = {
                                    scope.launch { profileCache.requestProfiles(listOf(pubkey), cacheRelayUrls) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugRelaySection(
    cacheRelayUrls: List<String>,
    relayCategories: List<RelayCategory>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Relays (this account)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text("Cache (kind-0 / kind-3):", style = MaterialTheme.typography.labelMedium)
            cacheRelayUrls.forEach { url ->
                Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            if (relayCategories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Categories:", style = MaterialTheme.typography.labelMedium)
                relayCategories.forEach { cat ->
                    Text(
                        "${cat.name}: ${cat.relays.size} relay(s)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugSummaryRow(
    cachedCount: Int,
    totalCount: Int,
    onFetchAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Profiles cached: $cachedCount / $totalCount",
            style = MaterialTheme.typography.bodyMedium
        )
        if (cachedCount < totalCount) {
            Button(
                onClick = onFetchAll,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.heightIn(min = 32.dp)
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Fetch all", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DebugFollowRow(
    pubkey: String,
    author: Author?,
    cacheRelayUrls: List<String>,
    onFetch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shortKey = if (pubkey.length > 16) "${pubkey.take(8)}…${pubkey.takeLast(4)}" else pubkey
    val isCached = author != null

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (author?.avatarUrl != null) {
                    AsyncImage(
                        model = author.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = (author?.displayName?.firstOrNull() ?: pubkey.firstOrNull())?.uppercaseChar()?.toString() ?: "?",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = author?.displayName?.takeIf { d -> !d.endsWith("...") && d != author.username } ?: shortKey,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (author != null && author.username != author.displayName) {
                    Text(
                        text = "@${author.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                author?.about?.takeIf { it.isNotBlank() }?.let { about ->
                    Text(
                        text = about.take(80) + if (about.length > 80) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                author?.nip05?.takeIf { it.isNotBlank() }?.let { nip05 ->
                    Text("NIP-05: $nip05", style = MaterialTheme.typography.labelSmall)
                }
                author?.website?.takeIf { it.isNotBlank() }?.let { url ->
                    Text("Web: $url", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    text = pubkey,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isCached) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (isCached) "Cached" else "Missing",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (!isCached && cacheRelayUrls.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onFetch) {
                        Text("Fetch", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
