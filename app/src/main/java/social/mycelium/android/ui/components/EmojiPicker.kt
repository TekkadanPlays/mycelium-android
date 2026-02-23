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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
    onSaveDefaultEmoji: ((String) -> Unit)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryIndex by remember { mutableIntStateOf(-1) } // -1 = Recent/Quick
    var customEmoji by remember { mutableStateOf("") }

    val categories = EmojiData.categories
    val isSearching = searchQuery.isNotEmpty()

    // Filter emojis by search query
    val searchResults = remember(searchQuery) {
        if (searchQuery.isEmpty()) emptyList()
        else categories.flatMap { it.emojis }.filter { emoji ->
            emoji.contains(searchQuery, ignoreCase = true)
        }.distinct()
    }

    // Current emoji list to display
    val displayEmojis = when {
        isSearching -> searchResults
        selectedCategoryIndex == -1 -> {
            // Recent + Quick access defaults (deduped)
            val recent = recentEmojis.take(16)
            val defaults = EmojiData.quickAccessDefaults.filter { it !in recent }
            recent + defaults
        }
        else -> categories.getOrNull(selectedCategoryIndex)?.emojis ?: emptyList()
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

                // Emoji grid
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
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onEmojiSelected(emoji) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = emoji,
                                fontSize = 24.sp,
                                textAlign = TextAlign.Center
                            )
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
