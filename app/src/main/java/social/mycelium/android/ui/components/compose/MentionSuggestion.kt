package social.mycelium.android.ui.components.compose

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.cybin.core.Filter
import com.example.cybin.nip19.toNpub
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import social.mycelium.android.data.Author
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import social.mycelium.android.repository.ContactListRepository
import social.mycelium.android.repository.ProfileMetadataCache
import java.text.Normalizer

private const val TAG = "MentionSuggestion"

// ── State holder ──────────────────────────────────────────────────────────────

/**
 * Manages @mention autocomplete state for compose screens.
 *
 * Detects when the user types `@<query>` and searches for matching users:
 * 1. Local follows + cached profiles (instant)
 * 2. NIP-50 relay-side search (async, for discovering unknown users)
 *
 * Modeled after Amethyst's UserSuggestionState but adapted to Mycelium's
 * ProfileMetadataCache and relay infrastructure.
 */
class MentionSuggestionState(
    private val scope: CoroutineScope,
    private val accountPubkey: String? = null
) {
    private val _suggestions = MutableStateFlow<List<MentionSuggestion>>(emptyList())
    val suggestions: StateFlow<List<MentionSuggestion>> = _suggestions.asStateFlow()

    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    /** The word (including @) that triggered the suggestion. */
    private var triggerWord: String = ""
    /** Start index of the trigger word in the text. */
    private var triggerStartIndex: Int = 0

    private var searchJob: Job? = null
    private var nip50Handle: TemporarySubscriptionHandle? = null
    private var nip50Job: Job? = null

    /**
     * Called on every text/cursor change. Extracts the word at cursor position
     * and triggers search if it starts with '@'.
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

        if (word.startsWith("@") && word.length >= 2) {
            val query = word.removePrefix("@")
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
            delay(150) // debounce

            val profileCache = ProfileMetadataCache.getInstance()
            val followList = accountPubkey?.let { ContactListRepository.getCachedFollowList(it) } ?: emptySet()

            // Local search across cached profiles
            val localResults = withContext(Dispatchers.Default) {
                searchLocal(query, profileCache, followList)
            }

            _suggestions.value = localResults
            _isVisible.value = localResults.isNotEmpty()

            // If local results are sparse, also do NIP-50 relay search
            if (localResults.size < 5 && query.length >= 2) {
                launchNip50Search(query, profileCache, followList)
            }
        }
    }

    private fun searchLocal(
        query: String,
        profileCache: ProfileMetadataCache,
        followList: Set<String>
    ): List<MentionSuggestion> {
        val allProfiles = profileCache.getAllCached()
        if (allProfiles.isEmpty()) return emptyList()

        val normalizedQuery = normalize(query)
        val results = mutableListOf<MentionSuggestion>()

        for ((pubkey, author) in allProfiles) {
            val score = scoreAuthor(author, normalizedQuery)
            if (score > 0f) {
                val isFollowed = pubkey.lowercase() in followList
                val socialWeight = if (isFollowed) 3.0f else 1.0f
                results.add(MentionSuggestion(
                    author = author,
                    score = score * socialWeight,
                    isFollowed = isFollowed
                ))
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(15)
    }

    private fun scoreAuthor(author: Author, normalizedQuery: String): Float {
        var maxScore = 0f

        val dn = normalize(author.displayName)
        if (dn.isNotBlank()) {
            if (dn == normalizedQuery) maxScore = 100f
            else if (dn.startsWith(normalizedQuery)) maxScore = maxOf(maxScore, 90f)
            else if (dn.contains(normalizedQuery)) maxScore = maxOf(maxScore, 60f)
        }

        val un = normalize(author.username)
        if (un.isNotBlank()) {
            if (un == normalizedQuery) maxScore = maxOf(maxScore, 95f)
            else if (un.startsWith(normalizedQuery)) maxScore = maxOf(maxScore, 85f)
            else if (un.contains(normalizedQuery)) maxScore = maxOf(maxScore, 55f)
        }

        val nip05 = author.nip05?.let { normalize(it) }
        if (nip05 != null) {
            if (nip05.contains(normalizedQuery)) maxScore = maxOf(maxScore, 40f)
        }

        return maxScore
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Kind0Content(
        val name: String? = null,
        val display_name: String? = null,
        val picture: String? = null,
        val about: String? = null,
        val nip05: String? = null,
        val lud16: String? = null,
    )

    private fun parseKind0(pubkey: String, content: String): Author? {
        return try {
            if (!content.startsWith("{")) return null
            val p = json.decodeFromString<Kind0Content>(content)
            val key = pubkey.lowercase()
            val dn = p.display_name?.trim()?.takeIf { it.isNotBlank() && it != "null" }
                ?: p.name?.trim()?.takeIf { it.isNotBlank() && it != "null" }
                ?: key.take(8) + "…"
            val un = p.name?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: dn
            Author(
                id = key,
                username = un,
                displayName = dn,
                avatarUrl = p.picture?.trim()?.takeIf { it.isNotBlank() },
                nip05 = p.nip05?.trim()?.takeIf { it.isNotBlank() },
                lud16 = p.lud16?.trim()?.takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) { null }
    }

    private fun launchNip50Search(
        query: String,
        profileCache: ProfileMetadataCache,
        followList: Set<String>
    ) {
        nip50Handle?.cancel()
        nip50Job?.cancel()

        val searchRelays = listOf("wss://relay.nostr.band", "wss://search.nos.today")
        val filter = Filter(
            kinds = listOf(0),
            search = query,
            limit = 20
        )

        nip50Job = scope.launch {
            try {
                val rsm = RelayConnectionStateMachine.getInstance()
                nip50Handle = rsm.requestTemporarySubscription(
                    filters = listOf(filter),
                    relayUrls = searchRelays,
                    onEvent = { event ->
                        if (event.kind == 0) {
                            val author = parseKind0(event.pubKey, event.content)
                            if (author != null) {
                                profileCache.putProfileIfNewer(event.pubKey, author, event.createdAt)
                            }
                        }
                    },
                    priority = SubscriptionPriority.LOW
                )

                // After a short delay, re-run local search to pick up newly cached profiles
                delay(1500)
                val updatedResults = withContext(Dispatchers.Default) {
                    searchLocal(query, profileCache, followList)
                }
                _suggestions.value = updatedResults
                _isVisible.value = updatedResults.isNotEmpty()

                // Close NIP-50 sub after results arrive
                delay(3000)
                nip50Handle?.cancel()
                nip50Handle = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "NIP-50 mention search failed: ${e.message}")
            }
        }
    }

    /**
     * Called when the user selects a suggestion. Replaces the @trigger word
     * with `nostr:npub1...` and returns the updated text + cursor position.
     */
    fun accept(text: String, author: Author): Pair<String, Int> {
        val npub = try { author.id.toNpub() } catch (_: Exception) { author.id }
        val mention = "nostr:$npub "

        val endIndex = (triggerStartIndex + triggerWord.length).coerceAtMost(text.length)
        val newText = text.replaceRange(triggerStartIndex, endIndex, mention)
        val newCursor = triggerStartIndex + mention.length

        hide()
        return newText to newCursor
    }

    fun hide() {
        _isVisible.value = false
        _suggestions.value = emptyList()
        triggerWord = ""
        searchJob?.cancel()
        nip50Handle?.cancel()
        nip50Handle = null
        nip50Job?.cancel()
    }

    fun dispose() {
        hide()
        searchJob?.cancel()
    }

    private fun normalize(input: String): String {
        if (input.isBlank()) return ""
        val nfkd = Normalizer.normalize(input, Normalizer.Form.NFKD)
        return nfkd.replace(Regex("\\p{M}"), "").lowercase().trim()
    }
}

data class MentionSuggestion(
    val author: Author,
    val score: Float,
    val isFollowed: Boolean = false
)

// ── UI Composable ─────────────────────────────────────────────────────────────

/**
 * Dropdown list of user suggestions for @mention autocomplete.
 * Shows when [MentionSuggestionState.isVisible] is true.
 */
@Composable
fun MentionSuggestionList(
    mentionState: MentionSuggestionState,
    currentText: String,
    onTextUpdated: (newText: String, newCursor: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestions by mentionState.suggestions.collectAsState()
    val visible by mentionState.isVisible.collectAsState()

    if (!visible || suggestions.isEmpty()) return

    val listState = rememberLazyListState()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp),
        shadowElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(
                items = suggestions,
                key = { it.author.id }
            ) { suggestion ->
                MentionSuggestionRow(
                    suggestion = suggestion,
                    onClick = {
                        val (newText, newCursor) = mentionState.accept(currentText, suggestion.author)
                        onTextUpdated(newText, newCursor)
                    }
                )
            }
        }
    }
}

@Composable
private fun MentionSuggestionRow(
    suggestion: MentionSuggestion,
    onClick: () -> Unit
) {
    val author = suggestion.author

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Avatar
        AsyncImage(
            model = author.avatarUrl,
            contentDescription = "Profile picture",
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
        )

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = author.displayName.ifBlank { author.username.ifBlank { author.id.take(8) + "…" } },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (suggestion.isFollowed) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "following",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            val subtitle = when {
                author.username.isNotBlank() -> "@${author.username}"
                author.nip05?.isNotBlank() == true -> author.nip05!!
                else -> author.id.take(12) + "…"
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
