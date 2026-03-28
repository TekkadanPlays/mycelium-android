package social.mycelium.android.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import social.mycelium.android.data.Author
import social.mycelium.android.data.Note
import social.mycelium.android.viewmodel.SearchResultItem
import social.mycelium.android.viewmodel.SearchTab
import social.mycelium.android.viewmodel.SearchUiState
import social.mycelium.android.viewmodel.SearchViewModel
import social.mycelium.android.ui.components.note.ClickableNoteContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    searchResults: List<Note>,
    onResultClick: (Note) -> Unit,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = { Text("Search notes...") },
    onProfileClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    accountPubkey: String? = null,
) {
    val context = LocalContext.current
    val searchViewModel: SearchViewModel = viewModel()
    LaunchedEffect(Unit) { searchViewModel.init(context, accountPubkey) }
    LaunchedEffect(query) {
        if (query != searchViewModel.queryText.value) searchViewModel.updateQuery(query)
    }
    val searchState by searchViewModel.uiState.collectAsState()
    val currentQuery by searchViewModel.queryText.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(active) {
        if (active) { focusRequester.requestFocus(); keyboardController?.show() }
        else keyboardController?.hide()
    }

    SearchBar(
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester),
        inputField = {
            SearchBarDefaults.InputField(
                query = currentQuery,
                onQueryChange = { onQueryChange(it); searchViewModel.updateQuery(it) },
                onSearch = { searchViewModel.onSearchSubmit(currentQuery) },
                expanded = active,
                onExpandedChange = onActiveChange,
                placeholder = { Text("Search people, notes, hashtags...") },
                leadingIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                trailingIcon = {
                    if (currentQuery.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange(""); searchViewModel.clearQuery() }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                }
            )
        },
        expanded = active,
        onExpandedChange = onActiveChange,
    ) {
        if (currentQuery.isBlank()) {
            RecentSearchesPanel(
                recentSearches = searchState.recentSearches,
                onRecentClick = { onQueryChange(it); searchViewModel.updateQuery(it) },
                onRemoveRecent = { searchViewModel.removeRecentSearch(it) },
                onClearAll = { searchViewModel.clearRecentSearches() }
            )
        } else {
            SearchResultsPanel(
                state = searchState,
                query = currentQuery,
                onTabChange = { searchViewModel.selectTab(it) },
                onUserClick = { keyboardController?.hide(); onProfileClick(it) },
                onNoteClick = { keyboardController?.hide(); onResultClick(it) },
                onHashtagClick = { keyboardController?.hide(); onHashtagClick(it) },
                onDirectUserClick = { keyboardController?.hide(); onProfileClick(it) },
                onDirectNoteClick = { noteId ->
                    keyboardController?.hide()
                    onResultClick(Note(id = noteId, author = Author(id = "", username = "", displayName = ""), content = "", timestamp = 0))
                }
            )
        }
    }
}

@Composable
private fun RecentSearchesPanel(
    recentSearches: List<String>,
    onRecentClick: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearAll: () -> Unit
) {
    if (recentSearches.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Search, null, Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(12.dp))
                Text("Search for people, notes, or hashtags",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                Spacer(Modifier.height(4.dp))
                Text("Paste npub, note, nevent, or hex to go directly",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Recent", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onClearAll) { Text("Clear all", style = MaterialTheme.typography.labelSmall) }
        }
        recentSearches.forEach { search ->
            ListItem(
                headlineContent = { Text(search, style = MaterialTheme.typography.bodyMedium) },
                leadingContent = { Icon(Icons.Default.History, null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                trailingContent = {
                    IconButton(onClick = { onRemoveRecent(search) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onRecentClick(search) }
            )
        }
    }
}

@Composable
private fun SearchResultsPanel(
    state: SearchUiState,
    query: String = "",
    onTabChange: (SearchTab) -> Unit,
    onUserClick: (String) -> Unit,
    onNoteClick: (Note) -> Unit,
    onHashtagClick: (String) -> Unit,
    onDirectUserClick: (String) -> Unit,
    onDirectNoteClick: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(visible = state.isLoadingRelay, enter = fadeIn(), exit = fadeOut()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), trackColor = Color.Transparent)
        }
        if (state.directMatches.isNotEmpty()) {
            state.directMatches.forEach { match ->
                when (match) {
                    is SearchResultItem.DirectUserMatch -> DirectUserMatchRow(match) { onDirectUserClick(match.pubkeyHex) }
                    is SearchResultItem.DirectNoteMatch -> DirectNoteMatchRow(match) { onDirectNoteClick(match.noteIdHex) }
                    else -> {}
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
        val tabList = listOf(SearchTab.PEOPLE, SearchTab.NOTES, SearchTab.HASHTAGS)
        val tabLabels = listOf(
            "People" + if (state.userCount > 0) " (${state.userCount})" else "",
            "Notes" + if (state.noteCount > 0) " (${state.noteCount})" else "",
            "Tags" + if (state.hashtagCount > 0) " (${state.hashtagCount})" else ""
        )
        val pagerState = rememberPagerState(
            initialPage = tabList.indexOf(state.selectedTab).coerceAtLeast(0),
            pageCount = { tabList.size }
        )
        // Sync: ViewModel tab change → pager
        LaunchedEffect(state.selectedTab) {
            val targetPage = tabList.indexOf(state.selectedTab).coerceAtLeast(0)
            if (pagerState.currentPage != targetPage) pagerState.animateScrollToPage(targetPage)
        }
        // Sync: pager swipe → ViewModel
        LaunchedEffect(pagerState.currentPage) {
            val swipedTab = tabList.getOrNull(pagerState.currentPage) ?: SearchTab.PEOPLE
            if (state.selectedTab != swipedTab) onTabChange(swipedTab)
        }
        TabRow(selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.primary, divider = {}) {
            tabLabels.forEachIndexed { index, label ->
                Tab(selected = pagerState.currentPage == index,
                    onClick = { onTabChange(tabList[index]) },
                    text = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) })
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                when (tabList[page]) {
                    SearchTab.PEOPLE -> {
                        if (state.userResults.isEmpty() && !state.isLoadingRelay) item { EmptyTabMsg("No people found") }
                        items(state.userResults, key = { it.author.id }) { user ->
                            UserResultRow(user, query) { onUserClick(user.author.id) }
                        }
                    }
                    SearchTab.NOTES -> {
                        if (state.noteResults.isEmpty() && !state.isLoadingRelay) item { EmptyTabMsg("No notes found") }
                        items(state.noteResults, key = { it.note.id }) { noteItem ->
                            NoteResultRow(noteItem, query) { onNoteClick(noteItem.note) }
                        }
                    }
                    SearchTab.HASHTAGS -> {
                        if (state.hashtagResults.isEmpty()) item { EmptyTabMsg("No hashtags found") }
                        items(state.hashtagResults, key = { it.hashtag }) { hashtagItem ->
                            HashtagResultRow(hashtagItem) { onHashtagClick(hashtagItem.hashtag) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTabMsg(msg: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun UserResultRow(user: SearchResultItem.UserItem, query: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(highlightText(user.author.displayName, query),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (user.isFollowed) {
                    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(start = 2.dp)) {
                        Text("Following", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        },
        supportingContent = {
            Column {
                val subtitle = user.author.nip05 ?: if (user.author.username != user.author.displayName) "@${user.author.username}" else null
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (user.matchSource == "about" && user.author.about != null) {
                    Text(user.author.about.take(80) + if (user.author.about.length > 80) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        },
        leadingContent = {
            if (user.author.avatarUrl != null) {
                coil.compose.AsyncImage(model = user.author.avatarUrl, contentDescription = user.author.displayName,
                    modifier = Modifier.size(42.dp).clip(CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            } else {
                Surface(modifier = Modifier.size(42.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(user.author.displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer))
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onClick() }.fillMaxWidth()
    )
}

@Composable
private fun NoteResultRow(noteItem: SearchResultItem.NoteItem, query: String, onClick: () -> Unit) {
    val note = noteItem.note
    val profileCache = remember { social.mycelium.android.repository.cache.ProfileMetadataCache.getInstance() }
    val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary)
    val mediaUrls = remember(note.id) { note.mediaUrls.toSet() }

    // Build rich annotated content with NIP-19 mentions, hashtags, URLs resolved
    val snippet = noteItem.snippetHighlight.ifBlank { note.content.take(300) }
    val annotatedContent = remember(snippet, query) {
        social.mycelium.android.utils.buildNoteContentAnnotatedString(
            content = snippet,
            mediaUrls = mediaUrls,
            linkStyle = linkStyle,
            profileCache = profileCache
        )
    }

    // Highlight query matches in the annotated string
    val highlightedContent = remember(annotatedContent, query) {
        if (query.isBlank()) annotatedContent
        else {
            val builder = androidx.compose.ui.text.AnnotatedString.Builder(annotatedContent)
            val lowerText = annotatedContent.text.lowercase()
            val lowerQuery = query.lowercase()
            var idx = lowerText.indexOf(lowerQuery)
            while (idx >= 0) {
                builder.addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x33FFEB3B)),
                    idx, (idx + query.length).coerceAtMost(annotatedContent.length)
                )
                idx = lowerText.indexOf(lowerQuery, idx + 1)
            }
            builder.toAnnotatedString()
        }
    }

    val firstMediaUrl = note.mediaUrls.firstOrNull()

    Column(Modifier.clickable { onClick() }.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // Author row
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (note.author.avatarUrl != null) {
                coil.compose.AsyncImage(model = note.author.avatarUrl, contentDescription = null,
                    modifier = Modifier.size(20.dp).clip(CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            } else {
                Surface(Modifier.size(20.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(note.author.displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer))
                    }
                }
            }
            Text(note.author.displayName,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (note.author.nip05 != null) {
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelSmall)
            }
            Text(formatTimestamp(note.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }

        Spacer(Modifier.height(4.dp))

        // Content + optional media thumbnail
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Rich text content
            ClickableNoteContent(
                text = highlightedContent,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.weight(1f).padding(top = 2.dp),
                maxLines = 3,
                onClick = { _ -> onClick() }
            )

            // Media thumbnail
            if (firstMediaUrl != null) {
                val isVideo = social.mycelium.android.utils.UrlDetector.isVideoUrl(firstMediaUrl)
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    coil.compose.AsyncImage(
                        model = firstMediaUrl,
                        contentDescription = "Media",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                    if (isVideo) {
                        Surface(
                            modifier = Modifier.align(Alignment.Center).size(22.dp),
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.5f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp), tint = Color.White)
                            }
                        }
                    }
                    // Badge for multiple media
                    if (note.mediaUrls.size > 1) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                            shape = RoundedCornerShape(3.dp),
                            color = Color.Black.copy(alpha = 0.6f)
                        ) {
                            Text("${note.mediaUrls.size}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
                                    color = Color.White
                                ),
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
                        }
                    }
                }
            }
        }

        // Hashtags row (compact chips)
        if (note.hashtags.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                note.hashtags.take(3).forEach { tag ->
                    Text("#$tag",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF8FBC8F),
                        maxLines = 1)
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
}

@Composable
private fun HashtagResultRow(item: SearchResultItem.HashtagItem, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text("#${item.hashtag}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary)
        },
        supportingContent = {
            if (item.noteCount > 0) Text("${item.noteCount} notes in feed",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = {
            Icon(Icons.Default.Tag, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onClick() }.fillMaxWidth()
    )
}

@Composable
private fun DirectUserMatchRow(match: SearchResultItem.DirectUserMatch, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(match.author?.displayName ?: match.displayId,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("Direct match", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        },
        supportingContent = {
            Text(match.pubkeyHex.take(16) + "..." + match.pubkeyHex.takeLast(8),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            if (match.author?.avatarUrl != null) {
                coil.compose.AsyncImage(model = match.author.avatarUrl, contentDescription = null,
                    modifier = Modifier.size(42.dp).clip(CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            } else {
                Surface(Modifier.size(42.dp), shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onClick() }.fillMaxWidth()
    )
}

@Composable
private fun DirectNoteMatchRow(match: SearchResultItem.DirectNoteMatch, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(match.displayId, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("Direct match", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        },
        supportingContent = {
            Text(match.noteIdHex.take(16) + "..." + match.noteIdHex.takeLast(8),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = {
            Surface(Modifier.size(42.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Article, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.secondary)
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onClick() }.fillMaxWidth()
    )
}

// ── Helpers ──

@Composable
private fun highlightText(text: String, query: String) = buildAnnotatedString {
    if (query.isBlank() || !text.contains(query, ignoreCase = true)) {
        append(text)
        return@buildAnnotatedString
    }
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    var start = 0
    var idx = lowerText.indexOf(lowerQuery, start)
    while (idx >= 0) {
        append(text.substring(start, idx))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
            append(text.substring(idx, idx + query.length))
        }
        start = idx + query.length
        idx = lowerText.indexOf(lowerQuery, start)
    }
    append(text.substring(start))
}

private fun formatTimestamp(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestampMs
    return when {
        diff < 60_000 -> "now"
        diff < 3_600_000 -> "${diff / 60_000}m"
        diff < 86_400_000 -> "${diff / 3_600_000}h"
        diff < 604_800_000 -> "${diff / 86_400_000}d"
        else -> "${diff / 604_800_000}w"
    }
}
