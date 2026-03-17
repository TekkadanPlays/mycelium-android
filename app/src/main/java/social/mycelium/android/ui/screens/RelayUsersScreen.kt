package social.mycelium.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import social.mycelium.android.data.Author
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.ui.components.ProfilePicture
import social.mycelium.android.utils.normalizeAuthorIdForCache
import kotlinx.coroutines.flow.filter

/**
 * Dedicated screen showing all followed users who write to or read from a specific relay per NIP-65.
 * Replaces the inline expandable user list in the relay health screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayUsersScreen(
    relayUrl: String,
    outboxUsers: List<String>,
    inboxUsers: List<String>,
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val profileCache = remember { ProfileMetadataCache.getInstance() }
    val allUsers = remember(outboxUsers, inboxUsers) {
        (outboxUsers + inboxUsers).distinct()
    }
    val outboxSet = remember(outboxUsers) { outboxUsers.toSet() }
    val inboxSet = remember(inboxUsers) { inboxUsers.toSet() }

    // Live profile updates
    var profileRevision by remember { mutableIntStateOf(0) }
    val userSet = remember(allUsers) { allUsers.toSet() }
    LaunchedEffect(userSet) {
        if (userSet.isEmpty()) return@LaunchedEffect
        profileCache.profileUpdated
            .filter { it in userSet }
            .collect { profileRevision++ }
    }
    @Suppress("UNUSED_EXPRESSION") profileRevision

    // Tab + pager state
    val tabs = listOf("All (${allUsers.size})", "Write (${outboxUsers.size})", "Read (${inboxUsers.size})")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val usersPerPage = listOf(allUsers, outboxUsers, inboxUsers)

    val displayName = relayUrl.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = displayName,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${allUsers.size} followed user${if (allUsers.size != 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            beyondViewportPageCount = 1
        ) { page ->
            val displayedUsers = usersPerPage[page]
            if (displayedUsers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No users in this category",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(displayedUsers, key = { "${page}_$it" }) { pk ->
                        @Suppress("UNUSED_EXPRESSION") profileRevision
                        val author = remember(pk, profileRevision) {
                            profileCache.getAuthor(normalizeAuthorIdForCache(pk))
                                ?: Author(id = pk, username = pk.take(8) + "…", displayName = pk.take(8) + "…")
                        }
                        val isWriter = pk in outboxSet
                        val isReader = pk in inboxSet

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onProfileClick(pk) },
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProfilePicture(
                                    author = author,
                                    size = 40.dp,
                                    onClick = { onProfileClick(pk) }
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = author.displayName.ifBlank { author.username.ifBlank { pk.take(12) + "…" } },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (author.username.isNotBlank() && author.username != author.displayName) {
                                        Text(
                                            text = "@${author.username}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (isWriter) {
                                        RelayUserBadge(text = "write", color = MaterialTheme.colorScheme.primary)
                                    }
                                    if (isReader) {
                                        RelayUserBadge(text = "read", color = Color(0xFF4CAF50))
                                    }
                                }
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelayUserBadge(text: String, color: Color) {
    Surface(
        shape = RectangleShape,
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
