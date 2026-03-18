package social.mycelium.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.mycelium.android.repository.EmojiPackSelectionRepository

/**
 * Manages :shortcode: autocomplete state for compose screens.
 *
 * Detects when the user types `:<query>` and searches through saved emoji/GIF packs
 * for matching shortcodes. Selecting a result inserts the `:shortcode:` into the text
 * and collects the emoji tags needed for the event.
 *
 * Follows the same pattern as [MentionSuggestionState] but for NIP-30 custom emojis.
 */
class EmojiShortcodeSuggestionState(
    private val scope: CoroutineScope
) {
    data class EmojiSuggestion(
        val shortcode: String, // e.g. ":pepe_laugh:"
        val url: String,       // image URL
        val packName: String = ""
    )

    private val _suggestions = MutableStateFlow<List<EmojiSuggestion>>(emptyList())
    val suggestions: StateFlow<List<EmojiSuggestion>> = _suggestions.asStateFlow()

    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    /** The word (including leading :) that triggered the suggestion. */
    private var triggerWord: String = ""
    /** Start index of the trigger word in the text. */
    private var triggerStartIndex: Int = 0

    private var searchJob: Job? = null

    /** Emoji tags accumulated from accepted suggestions: list of ["emoji", "shortcode", "url"]. */
    private val _emojiTags = MutableStateFlow<List<Array<String>>>(emptyList())
    val emojiTags: StateFlow<List<Array<String>>> = _emojiTags.asStateFlow()

    /**
     * Called on every text/cursor change. Extracts the word at cursor position
     * and triggers search if it starts with ':'. Shows all emojis on bare ':'.
     */
    fun onTextChanged(text: String, cursorPosition: Int) {
        if (text.isEmpty() || cursorPosition == 0) {
            hide()
            return
        }

        val pos = cursorPosition.coerceAtMost(text.length)
        // Find the word boundary before cursor
        var start = pos - 1
        while (start >= 0 && text[start] != ' ' && text[start] != '\n') {
            start--
        }
        start++ // move past the space/newline

        val word = text.substring(start, pos)

        // Trigger on ':' alone (show all) or ':query' (filter). Don't trigger if shortcode is already closed.
        if (word.startsWith(":") && word.length >= 1 && word.indexOf(':', 1) == -1) {
            val query = word.removePrefix(":")
            triggerWord = word
            triggerStartIndex = start
            performSearch(query)
        } else {
            hide()
        }
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        searchJob = scope.launch {
            // Short debounce for typed queries, none for bare ':'
            if (query.isNotEmpty()) delay(80)

            val allEmojis = EmojiPackSelectionRepository.allSavedEmojis.value
            if (allEmojis.isEmpty()) {
                hide()
                return@launch
            }

            val results = if (query.isEmpty()) {
                // Bare ':' — show first batch alphabetically
                allEmojis.entries
                    .sortedBy { it.key.removeSurrounding(":").lowercase() }
                    .take(15)
                    .map { (shortcode, url) -> EmojiSuggestion(shortcode = shortcode, url = url) }
            } else {
                val normalizedQuery = query.lowercase()
                allEmojis.entries
                    .filter { (shortcode, _) ->
                        shortcode.removeSurrounding(":").lowercase().contains(normalizedQuery)
                    }
                    .sortedBy { (shortcode, _) ->
                        val code = shortcode.removeSurrounding(":").lowercase()
                        when {
                            code == normalizedQuery -> 0
                            code.startsWith(normalizedQuery) -> 1
                            else -> 2
                        }
                    }
                    .take(15)
                    .map { (shortcode, url) -> EmojiSuggestion(shortcode = shortcode, url = url) }
            }

            _suggestions.value = results
            _isVisible.value = results.isNotEmpty()
        }
    }

    /**
     * Called when the user selects a suggestion. Replaces the :trigger word
     * with `:shortcode: ` and returns the updated text + cursor position.
     * Also accumulates the emoji tag for event publishing.
     */
    fun accept(text: String, suggestion: EmojiSuggestion): Pair<String, Int> {
        val shortcode = suggestion.shortcode // already has : wrapping
        val replacement = "$shortcode "

        val endIndex = (triggerStartIndex + triggerWord.length).coerceAtMost(text.length)
        val newText = text.replaceRange(triggerStartIndex, endIndex, replacement)
        val newCursor = triggerStartIndex + replacement.length

        // Accumulate emoji tag for event publishing
        val code = shortcode.removeSurrounding(":")
        val existing = _emojiTags.value.toMutableList()
        if (existing.none { it[1] == code }) {
            existing.add(arrayOf("emoji", code, suggestion.url))
            _emojiTags.value = existing
        }

        hide()
        return newText to newCursor
    }

    /** Get the accumulated emoji tags for including in the published event. */
    fun getEmojiTagsForEvent(): List<Array<String>> = _emojiTags.value

    /** Reset emoji tags (call after publishing). */
    fun clearEmojiTags() {
        _emojiTags.value = emptyList()
    }

    fun hide() {
        _isVisible.value = false
        _suggestions.value = emptyList()
        triggerWord = ""
        searchJob?.cancel()
    }

    fun dispose() {
        hide()
        searchJob?.cancel()
    }
}

// ── UI Composable ─────────────────────────────────────────────────────────────

/**
 * Dropdown list of emoji suggestions for :shortcode: autocomplete.
 * Shows when [EmojiShortcodeSuggestionState.isVisible] is true.
 */
@Composable
fun EmojiShortcodeSuggestionList(
    emojiState: EmojiShortcodeSuggestionState,
    currentText: String,
    onTextUpdated: (newText: String, newCursor: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestions by emojiState.suggestions.collectAsState()
    val visible by emojiState.isVisible.collectAsState()

    if (!visible || suggestions.isEmpty()) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp),
        shadowElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(
                items = suggestions,
                key = { it.shortcode }
            ) { suggestion ->
                EmojiSuggestionRow(
                    suggestion = suggestion,
                    onClick = {
                        val (newText, newCursor) = emojiState.accept(currentText, suggestion)
                        onTextUpdated(newText, newCursor)
                    }
                )
            }
        }
    }
}

@Composable
private fun EmojiSuggestionRow(
    suggestion: EmojiShortcodeSuggestionState.EmojiSuggestion,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        // Emoji preview image — crossfade to avoid GIF loading jank
        AsyncImage(
            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(suggestion.url)
                .crossfade(true)
                .size(coil.size.Size(112, 112))
                .build(),
            contentDescription = suggestion.shortcode.removeSurrounding(":"),
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
        )

        Spacer(Modifier.width(10.dp))

        Text(
            text = suggestion.shortcode,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
