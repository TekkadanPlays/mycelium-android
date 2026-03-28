package social.mycelium.android.ui.components.emoji

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.graphics.Paint
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import social.mycelium.android.repository.EmojiPackRepository
import social.mycelium.android.repository.EmojiPackSelectionRepository
import social.mycelium.android.repository.social.ReactionsRepository

private val SageGreen = Color(0xFF8FBC8F)

private enum class DrawerTab(val label: String) {
    EMOJIS("Emojis"),
    SETS("Sets"),
    GIFS("GIFs")
}

/**
 * Full-featured emoji drawer using ModalBottomSheet.
 * Three pill tabs: Emojis | Sets | GIFs
 *
 * **Emojis tab**: Favorites, Frequently Used, Custom Emoji Strings, then the full
 * stock Android emoji landscape organized by standard categories.
 *
 * **Sets tab**: All saved NIP-30 emoji packs, each in its own labeled section.
 *
 * **GIFs tab**: Coming soon placeholder.
 *
 * Search bar below tabs filters across both unicode emojis AND pack emojis.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiDrawer(
    accountNpub: String?,
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    onCustomEmojiSelected: ((shortcode: String, url: String) -> Unit)? = null,
    onSaveDefaultEmoji: ((String) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    var activeTab by remember { mutableStateOf(DrawerTab.EMOJIS) }
    var searchQuery by remember { mutableStateOf("") }
    var customEmoji by remember { mutableStateOf("") }

    val categories = remember { EmojiData.categories }

    // Saved custom emoji packs
    val savedAddresses by EmojiPackSelectionRepository.savedPacks.collectAsState()
    val allPacksMap by EmojiPackRepository.packs.collectAsState()
    val savedPacksWithContent = remember(savedAddresses, allPacksMap) {
        savedAddresses.map { addr -> addr to EmojiPackRepository.getCached(addr.coordinate) }
    }

    // Favorites and recently used
    val favoriteEmojis = remember(accountNpub) {
        ReactionsRepository.getFavoriteEmojis(context, accountNpub)
    }
    val recentEmojis = remember(accountNpub) {
        ReactionsRepository.getRecentEmojis(context, accountNpub)
    }

    // Custom emoji strings the user has saved (from onSaveDefaultEmoji)
    // These are entries in recent that are multi-grapheme (emoji combos)
    val customEmojiStrings = remember(recentEmojis) {
        recentEmojis.filter { it.length > 2 && !it.startsWith(":") }
    }

    // Search results — keyword search (e.g. "heart" → ❤️) + literal emoji match
    val isSearching = searchQuery.isNotEmpty()
    val unicodeSearchResults = remember(searchQuery) {
        if (searchQuery.isEmpty()) emptyList()
        else {
            val keywordMatches = EmojiData.searchByKeyword(searchQuery)
            val literalMatches = EmojiData.allEmojis.filter { it.contains(searchQuery, ignoreCase = true) }
            (keywordMatches + literalMatches).distinct()
        }
    }
    val customSearchResults = remember(searchQuery, allPacksMap) {
        if (searchQuery.isEmpty()) emptyList()
        else {
            val q = searchQuery.lowercase()
            allPacksMap.values.flatMap { pack ->
                pack.emojis.entries.filter { (shortcode, _) ->
                    shortcode.removeSurrounding(":").lowercase().contains(q)
                }.map { (shortcode, url) -> shortcode to url }
            }.distinctBy { it.second }
        }
    }

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
                Spacer(Modifier.height(6.dp))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // ── Pill-shaped tab row ──
            PillTabRow(
                activeTab = activeTab,
                onTabSelected = { activeTab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(6.dp))

            // ── Search bar ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(36.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                when (activeTab) {
                                    DrawerTab.EMOJIS -> "Search emojis\u2026"
                                    DrawerTab.SETS -> "Search sets\u2026"
                                    DrawerTab.GIFS -> "Search GIFs\u2026"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(SageGreen),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Tab content ──
            when (activeTab) {
                DrawerTab.EMOJIS -> EmojisTabContent(
                    isSearching = isSearching,
                    unicodeSearchResults = unicodeSearchResults,
                    customSearchResults = customSearchResults,
                    favoriteEmojis = favoriteEmojis,
                    recentEmojis = recentEmojis,
                    customEmojiStrings = customEmojiStrings,
                    categories = categories,
                    allPacksMap = allPacksMap,
                    customEmoji = customEmoji,
                    onCustomEmojiChanged = { customEmoji = it },
                    onEmojiSelected = onEmojiSelected,
                    onCustomEmojiSelected = onCustomEmojiSelected,
                    onSaveDefaultEmoji = onSaveDefaultEmoji,
                    modifier = Modifier.heightIn(max = screenHeight * 0.55f)
                )
                DrawerTab.SETS -> SetsTabContent(
                    isSearching = isSearching,
                    customSearchResults = customSearchResults,
                    savedPacksWithContent = savedPacksWithContent,
                    onEmojiSelected = onEmojiSelected,
                    onCustomEmojiSelected = onCustomEmojiSelected,
                    modifier = Modifier.heightIn(max = screenHeight * 0.45f)
                )
                DrawerTab.GIFS -> GifsTabContent(
                    modifier = Modifier.heightIn(max = screenHeight * 0.25f)
                )
            }
        }
    }
}

// ── Pill Tab Row ────────────────────────────────────────────────────────────

@Composable
private fun PillTabRow(
    activeTab: DrawerTab,
    onTabSelected: (DrawerTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DrawerTab.entries.forEach { tab ->
                val isActive = tab == activeTab
                Surface(
                    onClick = { onTabSelected(tab) },
                    shape = RoundedCornerShape(50),
                    color = if (isActive) SageGreen else Color.Transparent,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

// ── Emojis Tab ──────────────────────────────────────────────────────────────

@Composable
private fun EmojisTabContent(
    isSearching: Boolean,
    unicodeSearchResults: List<String>,
    customSearchResults: List<Pair<String, String>>,
    favoriteEmojis: List<String>,
    recentEmojis: List<String>,
    customEmojiStrings: List<String>,
    categories: List<EmojiData.EmojiCategory>,
    allPacksMap: Map<String, EmojiPackRepository.EmojiPack>,
    customEmoji: String,
    onCustomEmojiChanged: (String) -> Unit,
    onEmojiSelected: (String) -> Unit,
    onCustomEmojiSelected: ((shortcode: String, url: String) -> Unit)?,
    onSaveDefaultEmoji: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 38.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (isSearching) {
                // Search results: custom pack emojis first, then unicode
                if (customSearchResults.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader("Pack emojis")
                    }
                    items(customSearchResults, key = { it.second }) { (shortcode, url) ->
                        CustomEmojiCell(shortcode, url, onEmojiSelected, onCustomEmojiSelected)
                    }
                }
                if (unicodeSearchResults.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader("Emojis")
                    }
                    items(unicodeSearchResults, key = { it }) { emoji ->
                        EmojiGridCell(emoji, allPacksMap, onEmojiSelected, onCustomEmojiSelected)
                    }
                }
                if (customSearchResults.isEmpty() && unicodeSearchResults.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "No results",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                // ── Favorites ──
                if (favoriteEmojis.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader("⭐ Favorites")
                    }
                    items(favoriteEmojis, key = { "fav_$it" }) { emoji ->
                        EmojiGridCell(emoji, allPacksMap, onEmojiSelected, onCustomEmojiSelected)
                    }
                }

                // ── Frequently Used ──
                if (recentEmojis.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader("🕐 Frequently Used")
                    }
                    items(recentEmojis.take(24), key = { "recent_$it" }) { emoji ->
                        EmojiGridCell(emoji, allPacksMap, onEmojiSelected, onCustomEmojiSelected)
                    }
                }

                // ── Custom Emoji Strings ──
                if (customEmojiStrings.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader("✨ Emoji Strings")
                    }
                    items(customEmojiStrings, key = { "str_$it" }) { emoji ->
                        EmojiGridCell(emoji, allPacksMap, onEmojiSelected, onCustomEmojiSelected)
                    }
                }

                // ── Full emoji landscape by category ──
                categories.forEach { category ->
                    item(key = "hdr_${category.name}", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader("${category.icon} ${category.name}")
                    }
                    items(category.emojis, key = { "${category.name}_$it" }) { emoji ->
                        UnicodeEmojiCell(emoji, onEmojiSelected)
                    }
                }
            }
        }

        // ── Custom emoji input row at bottom ──
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Compact text input using BasicTextField
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(17.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (customEmoji.isEmpty()) {
                    Text(
                        "Type emoji or combo\u2026",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                BasicTextField(
                    value = customEmoji,
                    onValueChange = onCustomEmojiChanged,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(SageGreen),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val text = customEmoji.trim()
                            if (text.isNotEmpty()) onEmojiSelected(text)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Save to favorites button
            if (customEmoji.trim().isNotEmpty() && onSaveDefaultEmoji != null) {
                FilledIconButton(
                    onClick = {
                        val text = customEmoji.trim()
                        if (text.isNotEmpty()) onSaveDefaultEmoji(text)
                    },
                    modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SageGreen.copy(alpha = 0.15f),
                        contentColor = SageGreen
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Save", modifier = Modifier.size(16.dp))
                }
            }

            // Send button
            Button(
                onClick = {
                    val text = customEmoji.trim()
                    if (text.isNotEmpty()) onEmojiSelected(text)
                },
                enabled = customEmoji.trim().isNotEmpty(),
                modifier = Modifier.height(34.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SageGreen),
                contentPadding = PaddingValues(horizontal = 14.dp)
            ) {
                Text("Send", fontSize = 12.sp)
            }
        }
    }
}
// ── Sets Tab ────────────────────────────────────────────────────────────────

@Composable
private fun SetsTabContent(
    isSearching: Boolean,
    customSearchResults: List<Pair<String, String>>,
    savedPacksWithContent: List<Pair<EmojiPackSelectionRepository.PackAddress, EmojiPackRepository.EmojiPack?>>,
    onEmojiSelected: (String) -> Unit,
    onCustomEmojiSelected: ((shortcode: String, url: String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 40.dp),
        modifier = modifier
            .fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (isSearching) {
            if (customSearchResults.isNotEmpty()) {
                items(customSearchResults) { (shortcode, url) ->
                    CustomEmojiCell(shortcode, url, onEmojiSelected, onCustomEmojiSelected)
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "No results",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            val hasPacks = savedPacksWithContent.any { it.second != null && it.second!!.emojis.isNotEmpty() }
            if (!hasPacks) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "No emoji sets saved",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Browse profiles to find and save custom emoji packs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                savedPacksWithContent.forEach { (_, pack) ->
                    if (pack != null && pack.emojis.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(pack.name)
                        }
                        items(pack.emojis.entries.toList()) { (shortcode, url) ->
                            CustomEmojiCell(shortcode, url, onEmojiSelected, onCustomEmojiSelected)
                        }
                    }
                }
            }
        }
    }
}

// ── GIFs Tab ────────────────────────────────────────────────────────────────

@Composable
private fun GifsTabContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "🎞️",
                fontSize = 48.sp
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "GIFs coming soon",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "In the meantime, GIF emojis are available\nthrough your saved emoji sets",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── Shared Components ───────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp)
    )
}

/** Shared TextPaint for emoji rendering — avoids per-cell allocation. */
private val emojiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = 60f // ~24sp at mdpi; Canvas scales with density
    textAlign = Paint.Align.CENTER
}

/** Lightweight cell that draws emoji via Canvas — bypasses Compose Text layout entirely. */
@Composable
private fun UnicodeEmojiCell(
    emoji: String,
    onEmojiSelected: (String) -> Unit,
) {
    val density = LocalDensity.current
    val textSizePx = with(density) { 24.sp.toPx() }
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .height(38.dp)
            .fillMaxWidth()
            .pointerInput(emoji) {
                detectTapGestures { onEmojiSelected(emoji) }
            }
    ) {
        emojiPaint.textSize = textSizePx
        val x = size.width / 2f
        val y = size.height / 2f - (emojiPaint.ascent() + emojiPaint.descent()) / 2f
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText(emoji, x, y, emojiPaint)
        }
    }
}

/** Grid cell for mixed content (favorites, recents, search) — handles both unicode and custom shortcodes. */
@Composable
internal fun EmojiGridCell(
    emoji: String,
    allPacksMap: Map<String, EmojiPackRepository.EmojiPack>,
    onEmojiSelected: (String) -> Unit,
    onCustomEmojiSelected: ((shortcode: String, url: String) -> Unit)? = null,
) {
    val isCustom = emoji.startsWith(":") && emoji.endsWith(":") && emoji.length > 2
    val customUrl = remember(emoji, allPacksMap) {
        if (!isCustom) null
        else allPacksMap.values
            .firstNotNullOfOrNull { pack -> pack.emojis.entries.firstOrNull { ":${it.key}:" == emoji || it.key == emoji }?.value }
            ?: EmojiPackSelectionRepository.allSavedEmojis.value[emoji]
    }

    Box(
        modifier = Modifier
            .height(38.dp)
            .fillMaxWidth()
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
                modifier = Modifier.size(28.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = emoji,
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Visible
            )
        }
    }
}

@Composable
private fun CustomEmojiCell(
    shortcode: String,
    url: String,
    onEmojiSelected: (String) -> Unit,
    onCustomEmojiSelected: ((shortcode: String, url: String) -> Unit)?
) {
    Box(
        modifier = Modifier
            .height(38.dp)
            .fillMaxWidth()
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
            modifier = Modifier.size(28.dp),
            contentScale = ContentScale.Fit
        )
    }
}
