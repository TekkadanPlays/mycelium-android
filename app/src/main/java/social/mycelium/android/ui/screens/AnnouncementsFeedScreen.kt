package social.mycelium.android.ui.screens

import social.mycelium.android.ui.components.note.NoteCardCallbacks
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import social.mycelium.android.data.Note
import social.mycelium.android.ui.components.nav.AdaptiveHeader
import social.mycelium.android.ui.components.nav.HomeFab
import social.mycelium.android.ui.components.note.NoteCard
import social.mycelium.android.viewmodel.AnnouncementsUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementsFeedScreen(
    uiState: AnnouncementsUiState,
    onRefresh: () -> Unit,
    onNoteClick: (Note) -> Unit,
    onProfileClick: (String) -> Unit,
    onConfigureRelays: () -> Unit,
    onEffectsLab: () -> Unit = {},
    topAppBarState: TopAppBarState,
    // AdaptiveHeader callbacks
    onMenuClick: () -> Unit = {},
    isGuest: Boolean = true,
    userDisplayName: String? = null,
    userAvatarUrl: String? = null,
    onUserProfileClick: () -> Unit = {},
    onAccountsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onRelaysClick: () -> Unit = {},
    onLoginClick: (() -> Unit)? = null,
    onNavigateToTopics: (() -> Unit)? = null,
    onNavigateToHome: (() -> Unit)? = null,
    onNavigateToLive: (() -> Unit)? = null,
    hasFollowedLiveActivity: Boolean = false,
    // HomeFab callbacks
    onCompose: () -> Unit = {},
    draftCount: Int = 0,
    onDrafts: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            AdaptiveHeader(
                title = "news",
                onMenuClick = onMenuClick,
                isGuest = isGuest,
                userDisplayName = userDisplayName,
                userAvatarUrl = userAvatarUrl,
                onProfileClick = onUserProfileClick,
                onAccountsClick = onAccountsClick,
                onSettingsClick = onSettingsClick,
                onRelaysClick = onRelaysClick,
                onLoginClick = onLoginClick,
                scrollBehavior = scrollBehavior,
                onNavigateToTopics = onNavigateToTopics,
                onNavigateToHome = onNavigateToHome,
                onNavigateToLive = onNavigateToLive,
                hasFollowedLiveActivity = hasFollowedLiveActivity
            )
        },
        floatingActionButton = {
            HomeFab(
                onScrollToTop = {
                    scope.launch { listState.animateScrollToItem(0) }
                },
                onCompose = onCompose,
                draftCount = draftCount,
                onDrafts = onDrafts,
                modifier = Modifier.padding(bottom = 80.dp)
            )
        },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { paddingValues ->
        if (!uiState.hasRelays) {
            // Empty state — no announcement relays configured
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Outlined.Campaign,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No announcement relays configured",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Add announcement relays in Relay Manager to see project news and updates here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    FilledTonalButton(onClick = onConfigureRelays) {
                        Text("Configure Relays")
                    }
                }
            }
        } else {
            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = onRefresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (uiState.notes.isEmpty() && !uiState.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No announcements yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = uiState.notes,
                            key = { it.id }
                        ) { note ->
                            NoteCard(
                                note = note,
                                callbacks = NoteCardCallbacks(
                                    onNoteClick = { onNoteClick(note) },
                                    onProfileClick = { onProfileClick(note.author.id) },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
