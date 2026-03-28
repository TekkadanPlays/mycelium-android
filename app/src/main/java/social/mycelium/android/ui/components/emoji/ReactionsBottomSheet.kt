package social.mycelium.android.ui.components.emoji

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import social.mycelium.android.data.Author
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.ui.components.common.ProfilePicture

/**
 * Bottom sheet showing full reaction/zap/boost details in a tabbed layout.
 * Tabs are only shown if the corresponding data exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionsBottomSheet(
    onDismiss: () -> Unit,
    reactions: List<String>,
    reactionAuthors: Map<String, List<String>>,
    customEmojiUrls: Map<String, String>,
    zapAuthors: List<String>,
    zapAmountByAuthor: Map<String, Long>,
    zapTotalSats: Long,
    boostAuthors: List<Author>,
    onProfileClick: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Build tab list dynamically based on available data
    val tabs = remember(reactions, zapAuthors, boostAuthors) {
        buildList {
            if (reactions.isNotEmpty()) add(ReactionsTab.REACTIONS)
            if (zapAuthors.isNotEmpty()) add(ReactionsTab.ZAPS)
            if (boostAuthors.isNotEmpty()) add(ReactionsTab.BOOSTS)
        }
    }

    if (tabs.isEmpty()) {
        onDismiss()
        return
    }

    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 300.dp, max = 500.dp)
        ) {
            // Tab row
            if (tabs.size > 1) {
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(tab.label, style = MaterialTheme.typography.labelLarge) },
                            icon = {
                                when (tab) {
                                    ReactionsTab.REACTIONS -> Icon(Icons.Filled.Favorite, null, Modifier.size(16.dp), tint = Color(0xFFE91E63))
                                    ReactionsTab.ZAPS -> Icon(Icons.Filled.Bolt, null, Modifier.size(16.dp), tint = Color(0xFFF59E0B))
                                    ReactionsTab.BOOSTS -> Icon(Icons.Filled.Repeat, null, Modifier.size(16.dp), tint = Color(0xFF4CAF50))
                                }
                            }
                        )
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (tabs[page]) {
                    ReactionsTab.REACTIONS -> ReactionsTabContent(
                        reactions = reactions,
                        reactionAuthors = reactionAuthors,
                        customEmojiUrls = customEmojiUrls,
                        onProfileClick = onProfileClick,
                    )
                    ReactionsTab.ZAPS -> ZapsTabContent(
                        zapAuthors = zapAuthors,
                        zapAmountByAuthor = zapAmountByAuthor,
                        zapTotalSats = zapTotalSats,
                        onProfileClick = onProfileClick,
                    )
                    ReactionsTab.BOOSTS -> BoostsTabContent(
                        boostAuthors = boostAuthors,
                        onProfileClick = onProfileClick,
                    )
                }
            }
        }
    }
}

private enum class ReactionsTab(val label: String) {
    REACTIONS("Reactions"),
    ZAPS("Zaps"),
    BOOSTS("Boosts")
}

@Composable
private fun ReactionsTabContent(
    reactions: List<String>,
    reactionAuthors: Map<String, List<String>>,
    customEmojiUrls: Map<String, String>,
    onProfileClick: (String) -> Unit,
) {
    val profileCache = remember { ProfileMetadataCache.getInstance() }
    var profileRevision by remember { mutableIntStateOf(0) }
    val allPubkeys = remember(reactionAuthors) { reactionAuthors.values.flatten().toSet() }
    LaunchedEffect(allPubkeys) {
        val uncached = allPubkeys.filter { profileCache.getAuthor(it) == null }
        if (uncached.isNotEmpty()) profileCache.requestProfiles(uncached, profileCache.getConfiguredRelayUrls())
    }
    LaunchedEffect(allPubkeys) { profileCache.profileUpdated.collect { pk -> if (pk in allPubkeys) profileRevision++ } }
    @Suppress("UNUSED_EXPRESSION") profileRevision

    val grouped = remember(reactions, reactionAuthors) {
        reactions.map { emoji ->
            emoji to (reactionAuthors[emoji] ?: emptyList())
        }.sortedByDescending { it.second.size }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        grouped.forEach { (emoji, authors) ->
            if (authors.isNotEmpty()) {
                item(key = "header_$emoji") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    ) {
                        ReactionEmoji(emoji = emoji, customEmojiUrls = customEmojiUrls, fontSize = 18.sp, imageSize = 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${authors.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(authors, key = { "reaction_${emoji}_$it" }) { pubkey ->
                    val author = profileCache.resolveAuthor(pubkey)
                    AuthorRow(author = author, onProfileClick = onProfileClick) {
                        Text(" reacted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ZapsTabContent(
    zapAuthors: List<String>,
    zapAmountByAuthor: Map<String, Long>,
    zapTotalSats: Long,
    onProfileClick: (String) -> Unit,
) {
    val profileCache = remember { ProfileMetadataCache.getInstance() }
    var profileRevision by remember { mutableIntStateOf(0) }
    val allPubkeys = remember(zapAuthors) { zapAuthors.toSet() }
    LaunchedEffect(allPubkeys) {
        val uncached = allPubkeys.filter { profileCache.getAuthor(it) == null }
        if (uncached.isNotEmpty()) profileCache.requestProfiles(uncached, profileCache.getConfiguredRelayUrls())
    }
    LaunchedEffect(allPubkeys) { profileCache.profileUpdated.collect { pk -> if (pk in allPubkeys) profileRevision++ } }
    @Suppress("UNUSED_EXPRESSION") profileRevision

    val sorted = remember(zapAuthors, zapAmountByAuthor) {
        zapAuthors.distinct().sortedByDescending { zapAmountByAuthor[it] ?: 0L }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (zapTotalSats > 0) {
            item(key = "zap_total") {
                Text(
                    "${social.mycelium.android.utils.ZapUtils.formatZapAmount(zapTotalSats)} sats total",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFF59E0B),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
        items(sorted, key = { "zap_$it" }) { pubkey ->
            val author = profileCache.resolveAuthor(pubkey)
            val sats = zapAmountByAuthor[pubkey] ?: 0L
            AuthorRow(author = author, onProfileClick = onProfileClick) {
                if (sats > 0) {
                    Text(
                        " ⚡ ${social.mycelium.android.utils.ZapUtils.formatZapAmount(sats)} sats",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF59E0B)
                    )
                } else {
                    Text(" zapped", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun BoostsTabContent(
    boostAuthors: List<Author>,
    onProfileClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(boostAuthors, key = { "boost_${it.id}" }) { author ->
            AuthorRow(author = author, onProfileClick = onProfileClick) {
                Text(" boosted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AuthorRow(
    author: Author,
    onProfileClick: (String) -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onProfileClick(author.id) }
            .padding(vertical = 4.dp)
    ) {
        ProfilePicture(author = author, size = 28.dp, onClick = { onProfileClick(author.id) })
        Spacer(Modifier.width(8.dp))
        Text(
            text = author.displayName.ifBlank { author.id.take(8) + "..." },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        trailing()
    }
}
