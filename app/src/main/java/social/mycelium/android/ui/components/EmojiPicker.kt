package social.mycelium.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import social.mycelium.android.repository.EmojiPackRepository
import social.mycelium.android.repository.EmojiPackSelectionRepository
import social.mycelium.android.repository.GifSearchRepository

private val SageGreen = Color(0xFF8FBC8F)

/**
 * Full-featured emoji picker dialog with category tabs, search, recent emojis,
 * and custom emoji input. Themed with Mycelium's sage green palette.
 */
@Composable
fun EmojiPickerDialog(
    recentEmojis: List<String>,
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    onCustomEmojiSelected: ((shortcode: String, url: String) -> Unit)? = null,
    /** Called when user selects a GIF; fullUrl is the image URL to use as reaction content. */
    onGifSelected: ((fullUrl: String) -> Unit)? = null,
    onSaveDefaultEmoji: ((String) -> Unit)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    // -1 = Recent/Quick, -2 = Saved Packs, -3 = GIF search, 0+ = unicode categories
    var selectedCategoryIndex by remember { mutableIntStateOf(-1) }
    var customEmoji by remember { mutableStateOf("") }

    val categories = EmojiData.categories
    val isSearching = searchQuery.isNotEmpty()

    // Saved custom emoji packs — observe both saved addresses AND pack content flows for reactivity
    val savedAddresses by EmojiPackSelectionRepository.savedPacks.collectAsState()
    val allPacksMap by EmojiPackRepository.packs.collectAsState()
    val savedPacksWithContent = remember(savedAddresses, allPacksMap) {
        savedAddresses.map { addr -> addr to EmojiPackRepository.getCached(addr.coordinate) }
    }
    val hasSavedPacks = savedPacksWithContent.any { it.second != null && it.second!!.emojis.isNotEmpty() }

    // GIF search state
    var gifResults by remember { mutableStateOf<List<GifSearchRepository.GifResult>>(emptyList()) }
    var gifSearchQuery by remember { mutableStateOf("") }
    var isGifLoading by remember { mutableStateOf(false) }
    val gifScope = rememberCoroutineScope()
    var gifSearchJob by remember { mutableStateOf<Job?>(null) }
    val showGifGrid = selectedCategoryIndex == -3

    // Load trending GIFs when GIF tab is first selected
    LaunchedEffect(showGifGrid) {
        if (showGifGrid && gifResults.isEmpty() && gifSearchQuery.isBlank()) {
            isGifLoading = true
            gifResults = GifSearchRepository.trending()
            isGifLoading = false
        }
    }

    // Filter emojis by search query
    val searchResults = remember(searchQuery) {
        if (searchQuery.isEmpty()) emptyList()
        else categories.flatMap { it.emojis }.filter { emoji ->
            emoji.contains(searchQuery, ignoreCase = true)
        }.distinct()
    }

    // Current emoji list to display (unicode only; packs handled separately)
    val displayEmojis = when {
        isSearching && !showGifGrid -> searchResults
        selectedCategoryIndex == -1 -> {
            // Recent + Quick access defaults (deduped)
            val recent = recentEmojis.take(16)
            val defaults = EmojiData.quickAccessDefaults.filter { it !in recent }
            recent + defaults
        }
        selectedCategoryIndex == -2 -> emptyList() // Packs tab renders its own grid
        selectedCategoryIndex == -3 -> emptyList() // GIF tab renders its own grid
        else -> categories.getOrNull(selectedCategoryIndex)?.emojis ?: emptyList()
    }
    val showPacksGrid = selectedCategoryIndex == -2 && !isSearching

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

                // Category tabs - scrollable row
                if (!isSearching) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Recent tab
                        item {
                            CategoryTab(
                                icon = "🕐",
                                label = "Recent",
                                isSelected = selectedCategoryIndex == -1,
                                onClick = { selectedCategoryIndex = -1 }
                            )
                        }
                        // Saved Packs tab (only if user has saved packs)
                        if (hasSavedPacks) {
                            item {
                                CategoryTab(
                                    icon = "⭐",
                                    label = "Packs",
                                    isSelected = selectedCategoryIndex == -2,
                                    onClick = { selectedCategoryIndex = -2 }
                                )
                            }
                        }
                        // GIF search tab
                        if (onGifSelected != null) {
                            item {
                                CategoryTab(
                                    icon = "🎬",
                                    label = "GIF",
                                    isSelected = selectedCategoryIndex == -3,
                                    onClick = { selectedCategoryIndex = -3 }
                                )
                            }
                        }
                        // Category tabs
                        items(categories) { category ->
                            val index = categories.indexOf(category)
                            CategoryTab(
                                icon = category.icon,
                                label = category.name,
                                isSelected = selectedCategoryIndex == index,
                                onClick = { selectedCategoryIndex = index }
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }

                // Emoji grid / Packs grid / GIF grid
                if (showGifGrid) {
                    // GIF search bar + results grid
                    Column(modifier = Modifier.weight(1f)) {
                        // GIF search input
                        OutlinedTextField(
                            value = gifSearchQuery,
                            onValueChange = { query ->
                                gifSearchQuery = query
                                gifSearchJob?.cancel()
                                gifSearchJob = gifScope.launch {
                                    delay(400) // debounce
                                    isGifLoading = true
                                    gifResults = if (query.isBlank()) {
                                        GifSearchRepository.trending()
                                    } else {
                                        GifSearchRepository.search(query)
                                    }
                                    isGifLoading = false
                                }
                            },
                            placeholder = { Text("Search GIFs", style = MaterialTheme.typography.bodyMedium) },
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
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SageGreen,
                                cursorColor = SageGreen
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )

                        if (isGifLoading) {
                            Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.dp,
                                    color = SageGreen
                                )
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 4.dp),
                                contentPadding = PaddingValues(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(gifResults) { gif ->
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                            .clickable { onGifSelected?.invoke(gif.fullUrl) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AsyncImage(
                                            model = gif.previewUrl,
                                            contentDescription = "GIF",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                        }

                        // Tenor attribution
                        Text(
                            text = "Powered by Tenor",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                } else if (showPacksGrid) {
                    // Saved emoji packs — each pack as a labeled section
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(7),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        savedPacksWithContent.forEach { (addr, pack) ->
                            if (pack != null && pack.emojis.isNotEmpty()) {
                                // Pack header spanning full width
                                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        text = pack.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp)
                                    )
                                }
                                // Emoji images
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
                } else {
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
                        items(displayEmojis) { emoji ->
                            val isCustom = emoji.startsWith(":") && emoji.endsWith(":") && emoji.length > 2
                            val customUrl = if (isCustom) allPacksMap.values
                                .flatMap { it.emojis.entries }
                                .firstOrNull { ":${it.key}:" == emoji || it.key == emoji }
                                ?.value
                                ?: EmojiPackSelectionRepository.allSavedEmojis.value[emoji]
                            else null

                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (isCustom && customUrl != null && onCustomEmojiSelected != null) {
                                            onCustomEmojiSelected(emoji.removeSurrounding(":"), customUrl)
                                        } else {
                                            onEmojiSelected(emoji)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (customUrl != null) {
                                    AsyncImage(
                                        model = customUrl,
                                        contentDescription = emoji.removeSurrounding(":"),
                                        modifier = Modifier.size(32.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    Text(
                                        text = emoji,
                                        fontSize = 24.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                // Custom emoji input row at bottom (hidden in GIF mode)
                if (!showGifGrid) {
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
                        placeholder = { Text("Custom emoji", style = MaterialTheme.typography.bodySmall) },
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
                                firstGrapheme(customEmoji)?.let { onEmojiSelected(it) }
                            }
                        )
                    )

                    // Save to recents button
                    if (firstGrapheme(customEmoji) != null && onSaveDefaultEmoji != null) {
                        FilledIconButton(
                            onClick = { firstGrapheme(customEmoji)?.let { onSaveDefaultEmoji(it) } },
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
                        onClick = { firstGrapheme(customEmoji)?.let { onEmojiSelected(it) } },
                        enabled = firstGrapheme(customEmoji) != null,
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        Text("Send", fontSize = 13.sp)
                    }
                }
                } // end if (!showGifGrid)
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
