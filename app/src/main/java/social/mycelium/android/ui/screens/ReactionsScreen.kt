package social.mycelium.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import social.mycelium.android.repository.cache.ProfileMetadataCache
import social.mycelium.android.ui.components.common.ProfilePicture
import social.mycelium.android.ui.components.emoji.ReactionEmoji
import social.mycelium.android.viewmodel.ReactionsData
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.filled.Check

/**
 * Full-screen reactions viewer with tabs for Reactions, Zaps, and Boosts.
 * Navigated to from NoteCard and thread reply "See all" links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionsScreen(
    data: ReactionsData,
    onBackClick: () -> Unit,
    onProfileClick: (String) -> Unit,
) {
    val hasPollVotes = data.pollVotesByOption.isNotEmpty()
    val tabs = remember(hasPollVotes) {
        buildList {
            if (hasPollVotes) add(RxTab.POLL_VOTES)
            add(RxTab.REACTIONS)
            add(RxTab.ZAPS)
            add(RxTab.BOOSTS)
        }
    }

    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reactions") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            Text(
                                when (tab) {
                                    RxTab.POLL_VOTES -> "Votes (${data.pollTotalVoters})"
                                    RxTab.REACTIONS -> {
                                        val count = if (data.reactionAuthors.isNotEmpty()) data.reactionAuthors.values.sumOf { it.size } else data.reactions.size
                                        "Likes ($count)"
                                    }
                                    RxTab.ZAPS -> "Zaps (${data.zapAuthors.size})"
                                    RxTab.BOOSTS -> "Boosts (${data.boostAuthors.size})"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        icon = {
                            when (tab) {
                                RxTab.POLL_VOTES -> Icon(Icons.Outlined.HowToVote, null, Modifier.size(16.dp), tint = Color(0xFF8FBC8F))
                                RxTab.REACTIONS -> Icon(Icons.Filled.Favorite, null, Modifier.size(16.dp), tint = Color(0xFFE91E63))
                                RxTab.ZAPS -> Icon(Icons.Filled.Bolt, null, Modifier.size(16.dp), tint = Color(0xFFF59E0B))
                                RxTab.BOOSTS -> Icon(Icons.Filled.Repeat, null, Modifier.size(16.dp), tint = Color(0xFF4CAF50))
                            }
                        }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (tabs[page]) {
                    RxTab.POLL_VOTES -> RxPollVotesTab(
                        pollVotesByOption = data.pollVotesByOption,
                        pollOptionLabels = data.pollOptionLabels,
                        totalVoters = data.pollTotalVoters,
                        onProfileClick = onProfileClick,
                    )
                    RxTab.REACTIONS -> RxReactionsTab(
                        reactions = data.reactions,
                        reactionAuthors = data.reactionAuthors,
                        customEmojiUrls = data.customEmojiUrls,
                        onProfileClick = onProfileClick,
                    )
                    RxTab.ZAPS -> RxZapsTab(
                        zapAuthors = data.zapAuthors,
                        zapAmountByAuthor = data.zapAmountByAuthor,
                        zapTotalSats = data.zapTotalSats,
                        onProfileClick = onProfileClick,
                    )
                    RxTab.BOOSTS -> RxBoostsTab(
                        boostAuthors = data.boostAuthors,
                        onProfileClick = onProfileClick,
                    )
                }
            }
        }
    }
}

private enum class RxTab { POLL_VOTES, REACTIONS, ZAPS, BOOSTS }

@Composable
private fun RxPollVotesTab(
    pollVotesByOption: Map<String, List<String>>,
    pollOptionLabels: Map<String, String>,
    totalVoters: Int,
    onProfileClick: (String) -> Unit,
) {
    val profileCache = remember { ProfileMetadataCache.getInstance() }
    var profileRevision by remember { mutableIntStateOf(0) }
    val allPubkeys = remember(pollVotesByOption) { pollVotesByOption.values.flatten().toSet() }
    LaunchedEffect(allPubkeys) {
        val uncached = allPubkeys.filter { profileCache.getAuthor(it) == null }
        if (uncached.isNotEmpty()) profileCache.requestProfiles(uncached, profileCache.getConfiguredRelayUrls())
    }
    LaunchedEffect(allPubkeys) { profileCache.profileUpdated.collect { pk -> if (pk in allPubkeys) profileRevision++ } }
    @Suppress("UNUSED_EXPRESSION") profileRevision

    val pollGreen = Color(0xFF8FBC8F)

    // Sort options by vote count descending
    val sortedOptions = remember(pollVotesByOption) {
        pollVotesByOption.entries.sortedByDescending { it.value.size }
    }

    if (totalVoters == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No votes yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Summary header
        item(key = "poll_summary") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(Icons.Outlined.HowToVote, null, Modifier.size(20.dp), tint = pollGreen)
                Spacer(Modifier.width(8.dp))
                Text(
                    "$totalVoters voter${if (totalVoters != 1) "s" else ""} across ${sortedOptions.size} option${if (sortedOptions.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.titleSmall,
                    color = pollGreen,
                    fontWeight = FontWeight.Bold
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }

        sortedOptions.forEach { (optionCode, voterPubkeys) ->
            val label = pollOptionLabels[optionCode] ?: optionCode
            val percentage = if (totalVoters > 0) (voterPubkeys.size.toFloat() / totalVoters * 100).toInt() else 0

            item(key = "opt_hdr_$optionCode") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                ) {
                    Icon(
                        Icons.Filled.Check, null,
                        Modifier.size(16.dp),
                        tint = pollGreen
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${voterPubkeys.size} vote${if (voterPubkeys.size != 1) "s" else ""} ($percentage%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
            items(voterPubkeys, key = { "pv_${optionCode}_$it" }) { pubkey ->
                val author = profileCache.resolveAuthor(pubkey)
                RxAuthorRow(author = author, onProfileClick = onProfileClick) {
                    // No trailing content needed for poll votes
                }
            }
        }
    }
}

@Composable
private fun RxReactionsTab(
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
        reactions.map { emoji -> emoji to (reactionAuthors[emoji] ?: emptyList()) }
            .sortedByDescending { it.second.size }
    }

    if (reactions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No likes yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        grouped.forEach { (emoji, authors) ->
            if (authors.isNotEmpty()) {
                item(key = "hdr_$emoji") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                    ) {
                        ReactionEmoji(emoji = emoji, customEmojiUrls = customEmojiUrls, fontSize = 20.sp, imageSize = 22.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${authors.size} reaction${if (authors.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
                items(authors, key = { "rx_${emoji}_$it" }) { pubkey ->
                    val author = profileCache.resolveAuthor(pubkey)
                    RxAuthorRow(author = author, onProfileClick = onProfileClick) {
                        ReactionEmoji(emoji = emoji, customEmojiUrls = customEmojiUrls, fontSize = 14.sp, imageSize = 16.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RxZapsTab(
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

    if (zapAuthors.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No zaps yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (zapTotalSats > 0) {
            item(key = "zap_total") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(Icons.Filled.Bolt, null, Modifier.size(20.dp), tint = Color(0xFFF59E0B))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${social.mycelium.android.utils.ZapUtils.formatZapAmount(zapTotalSats)} sats total from ${sorted.size} zapper${if (sorted.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFF59E0B),
                        fontWeight = FontWeight.Bold
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        items(sorted, key = { "zap_$it" }) { pubkey ->
            val author = profileCache.resolveAuthor(pubkey)
            val sats = zapAmountByAuthor[pubkey] ?: 0L
            RxAuthorRow(author = author, onProfileClick = onProfileClick) {
                if (sats > 0) {
                    Text(
                        "⚡ ${social.mycelium.android.utils.ZapUtils.formatZapAmount(sats)} sats",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF59E0B),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun RxBoostsTab(
    boostAuthors: List<Author>,
    onProfileClick: (String) -> Unit,
) {
    if (boostAuthors.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No boosts yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(boostAuthors, key = { "boost_${it.id}" }) { author ->
            RxAuthorRow(author = author, onProfileClick = onProfileClick) {
                Icon(Icons.Filled.Repeat, null, Modifier.size(16.dp), tint = Color(0xFF4CAF50))
            }
        }
    }
}

@Composable
private fun RxAuthorRow(
    author: Author,
    onProfileClick: (String) -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onProfileClick(author.id) }
            .padding(vertical = 6.dp)
    ) {
        ProfilePicture(author = author, size = 36.dp, onClick = { onProfileClick(author.id) })
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = author.displayName.ifBlank { author.id.take(8) + "..." },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (author.displayName.isNotBlank() && author.id.length > 8) {
                Text(
                    text = author.id.take(12) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        trailing()
    }
}
