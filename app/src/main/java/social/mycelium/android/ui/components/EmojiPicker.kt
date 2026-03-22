package social.mycelium.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import java.text.BreakIterator
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import social.mycelium.android.repository.EmojiPackRepository
import social.mycelium.android.repository.EmojiPackSelectionRepository

private val SageGreen = Color(0xFF8FBC8F)

/**
 * Full-featured emoji picker dialog with category tabs, search, recent emojis,
 * custom emoji packs (NIP-30), and custom emoji string input.
 * Themed with Mycelium's sage green palette.
 */
@Composable
fun EmojiPickerDialog(
    recentEmojis: List<String>,
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    onCustomEmojiSelected: ((shortcode: String, url: String) -> Unit)? = null,
    onSaveDefaultEmoji: ((String) -> Unit)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    var customEmoji by remember { mutableStateOf("") }
    val pagerScope = rememberCoroutineScope()

    val categories = EmojiData.categories
    val isSearching = searchQuery.isNotEmpty()

    // Saved custom emoji packs — observe both saved addresses AND pack content flows for reactivity
    val savedAddresses by EmojiPackSelectionRepository.savedPacks.collectAsState()
    val allPacksMap by EmojiPackRepository.packs.collectAsState()
    val savedPacksWithContent = remember(savedAddresses, allPacksMap) {
        savedAddresses.map { addr -> addr to EmojiPackRepository.getCached(addr.coordinate) }
    }
    val hasSavedPacks = savedPacksWithContent.any { it.second != null && it.second!!.emojis.isNotEmpty() }

    // Build page index list: -1=Recent, -2=Packs (if any), 0..N=unicode categories
    val pageIndices = remember(hasSavedPacks, categories) {
        buildList {
            add(-1) // Recent
            if (hasSavedPacks) add(-2) // Packs
            categories.indices.forEach { add(it) }
        }
    }
    val pagerState = rememberPagerState(initialPage = 0) { pageIndices.size }
    val currentCategoryIndex = pageIndices.getOrElse(pagerState.currentPage) { -1 }

    // Filter emojis by search query (unicode + custom pack emojis)
    val searchResults = remember(searchQuery, allPacksMap) {
        if (searchQuery.isEmpty()) emptyList()
        else {
            val unicodeMatches = categories.flatMap { it.emojis }.filter { emoji ->
                emoji.contains(searchQuery, ignoreCase = true)
            }.distinct()
            unicodeMatches
        }
    }
    // Custom emoji search results (from packs)
    val customSearchResults = remember(searchQuery, allPacksMap) {
        if (searchQuery.isEmpty()) emptyList()
        else {
            val normalizedQuery = searchQuery.lowercase()
            allPacksMap.values.flatMap { pack ->
                pack.emojis.entries.filter { (shortcode, _) ->
                    shortcode.removeSurrounding(":").lowercase().contains(normalizedQuery)
                }.map { (shortcode, url) -> shortcode to url }
            }.distinctBy { it.second }
        }
    }

    // Recent + Quick access emojis for page -1
    val recentPageEmojis = remember(recentEmojis) {
        val recent = recentEmojis.take(16)
        val defaults = EmojiData.quickAccessDefaults.filter { it !in recent }
        recent + defaults
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = 520.dp),
            shape = androidx.compose.ui.graphics.RectangleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "React",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search emoji", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SageGreen,
                        cursorColor = SageGreen
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Category tabs - scrollable row (synced with pager)
                if (!isSearching) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(pageIndices.size) { pageIdx ->
                            val catIndex = pageIndices[pageIdx]
                            val icon = when (catIndex) {
                                -1 -> "🕐"
                                -2 -> "⭐"
                                else -> categories.getOrNull(catIndex)?.icon ?: ""
                            }
                            val label = when (catIndex) {
                                -1 -> "Recent"
                                -2 -> "Packs"
                                else -> categories.getOrNull(catIndex)?.name ?: ""
                            }
                            CategoryTab(
                                icon = icon,
                                label = label,
                                isSelected = pagerState.currentPage == pageIdx,
                                onClick = { pagerScope.launch { pagerState.animateScrollToPage(pageIdx) } }
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }

                // Swipeable emoji pages / search results
                if (isSearching) {
                    // Flat search grid (no pager)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(8),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (customSearchResults.isNotEmpty()) {
                            items(customSearchResults) { (shortcode, url) ->
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            if (onCustomEmojiSelected != null) {
                                                onCustomEmojiSelected(shortcode, url)
                                            } else {
                                                onEmojiSelected(shortcode)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = url,
                                        contentDescription = shortcode.removeSurrounding(":"),
                                        modifier = Modifier.size(32.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }
                        items(searchResults) { emoji ->
                            EmojiGridCell(emoji, allPacksMap, onEmojiSelected, onCustomEmojiSelected)
                        }
                    }
                } else {
                    // Swipeable pager — one page per category
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        key = { pageIndices.getOrElse(it) { -1 } }
                    ) { pageIdx ->
                        val catIndex = pageIndices.getOrElse(pageIdx) { -1 }
                        when (catIndex) {
                            -2 -> {
                                // Saved emoji packs grid
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(7),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    savedPacksWithContent.forEach { (_, pack) ->
                                        if (pack != null && pack.emojis.isNotEmpty()) {
                                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                                Text(
                                                    text = pack.name,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp)
                                                )
                                            }
                                            items(pack.emojis.entries.toList()) { (shortcode, url) ->
                                                Box(
                                                    modifier = Modifier
                                                        .aspectRatio(1f)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .clickable {
                                                            if (onCustomEmojiSelected != null) {
                                                                onCustomEmojiSelected(shortcode, url)
                                                            } else {
                                                                onEmojiSelected(shortcode)
                                                            }
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    AsyncImage(
                                                        model = url,
                                                        contentDescription = shortcode.removeSurrounding(":"),
                                                        modifier = Modifier.size(32.dp),
                                                        contentScale = ContentScale.Fit
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                // Unicode emoji grid (Recent or specific category)
                                val emojis = if (catIndex == -1) recentPageEmojis
                                    else categories.getOrNull(catIndex)?.emojis ?: emptyList()
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(8),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(emojis) { emoji ->
                                        EmojiGridCell(emoji, allPacksMap, onEmojiSelected, onCustomEmojiSelected)
                                    }
                                }
                            }
                        }
                    }
                }

                // Custom emoji input row at bottom
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customEmoji,
                        onValueChange = { customEmoji = it },
                        placeholder = { Text("Type emoji or combo…", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SageGreen,
                            cursorColor = SageGreen
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val text = customEmoji.trim()
                                if (text.isNotEmpty()) onEmojiSelected(text)
                            }
                        )
                    )

                    // Save to recents button
                    if (customEmoji.trim().isNotEmpty() && onSaveDefaultEmoji != null) {
                        FilledIconButton(
                            onClick = {
                                val text = customEmoji.trim()
                                if (text.isNotEmpty()) onSaveDefaultEmoji(text)
                            },
                            modifier = Modifier.size(36.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = SageGreen.copy(alpha = 0.15f),
                                contentColor = SageGreen
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Save", modifier = Modifier.size(18.dp))
                        }
                    }

                    // Send button
                    Button(
                        onClick = {
                            val text = customEmoji.trim()
                            if (text.isNotEmpty()) onEmojiSelected(text)
                        },
                        enabled = customEmoji.trim().isNotEmpty(),
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        Text("Send", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTab(
    icon: String,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) SageGreen.copy(alpha = 0.15f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = icon, fontSize = 16.sp)
            if (isSelected) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = SageGreen,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
