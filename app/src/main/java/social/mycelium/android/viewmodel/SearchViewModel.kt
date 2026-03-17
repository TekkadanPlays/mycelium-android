package social.mycelium.android.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import social.mycelium.android.data.Author
import social.mycelium.android.data.Note
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import social.mycelium.android.repository.ContactListRepository
import social.mycelium.android.repository.NotesRepository
import social.mycelium.android.repository.ProfileMetadataCache
import java.text.Normalizer
import kotlin.math.min

private const val TAG = "SearchViewModel"
private const val RECENT_SEARCHES_PREFS = "recent_searches"
private const val RECENT_SEARCHES_KEY = "searches"
private const val MAX_RECENT_SEARCHES = 15
private const val MAX_LOCAL_USER_RESULTS = 30
private const val MAX_LOCAL_NOTE_RESULTS = 20
private const val MAX_RELAY_RESULTS = 30

// ── Result types ──

sealed class SearchResultItem {
    data class UserItem(
        val author: Author,
        val score: Float,
        val isFollowed: Boolean = false,
        val matchSource: String = "" // e.g. "displayName", "nip05", "about"
    ) : SearchResultItem()

    data class NoteItem(
        val note: Note,
        val score: Float,
        val snippetHighlight: String = ""
    ) : SearchResultItem()

    data class HashtagItem(
        val hashtag: String,
        val noteCount: Int = 0
    ) : SearchResultItem()

    data class DirectUserMatch(
        val pubkeyHex: String,
        val displayId: String,
        val author: Author? = null
    ) : SearchResultItem()

    data class DirectNoteMatch(
        val noteIdHex: String,
        val displayId: String,
        val note: Note? = null
    ) : SearchResultItem()
}

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val userResults: List<SearchResultItem.UserItem> = emptyList(),
    val noteResults: List<SearchResultItem.NoteItem> = emptyList(),
    val hashtagResults: List<SearchResultItem.HashtagItem> = emptyList(),
    val directMatches: List<SearchResultItem> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val isLoadingRelay: Boolean = false,
    val selectedTab: SearchTab = SearchTab.PEOPLE,
    /** Total counts per tab for badge display */
    val userCount: Int = 0,
    val noteCount: Int = 0,
    val hashtagCount: Int = 0
)

enum class SearchTab { PEOPLE, NOTES, HASHTAGS }

@OptIn(FlowPreview::class)
class SearchViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Query text lives in its own StateFlow so search result updates never race with typing. */
    private val queryFlow = MutableStateFlow("")
    val queryText: StateFlow<String> = queryFlow.asStateFlow()
    private var nip50Handle: TemporarySubscriptionHandle? = null
    private var nip50Job: Job? = null

    private var appContext: Context? = null
    private var accountPubkey: String? = null
    private val json = Json { ignoreUnknownKeys = true }

    /** Tracks profile cache version so local search re-runs when profiles resolve (e.g. NIP-50 delivers kind-0). */
    private var lastProfileVersion = 0L
    private var profileInvalidationJob: Job? = null

    fun init(context: Context, pubkeyHex: String? = null) {
        appContext = context.applicationContext
        accountPubkey = pubkeyHex
        loadRecentSearches()
        // Watch profile cache updates — re-run local search when display names resolve
        startProfileInvalidation()
    }

    /** Re-run local search when ProfileMetadataCache updates (e.g. after NIP-50 kind-0 results). */
    private fun startProfileInvalidation() {
        profileInvalidationJob?.cancel()
        profileInvalidationJob = viewModelScope.launch {
            val profileCache = ProfileMetadataCache.getInstance()
            profileCache.profileVersion.collect { version ->
                if (version != lastProfileVersion && queryFlow.value.isNotBlank()) {
                    lastProfileVersion = version
                    // Debounce to avoid re-searching on every single profile update
                    delay(500)
                    val query = queryFlow.value.trim()
                    if (query.isNotBlank()) {
                        val followList = accountPubkey?.let { ContactListRepository.getCachedFollowList(it) } ?: emptySet()
                        val userResults = withContext(Dispatchers.Default) { searchUsersLocal(query, followList) }
                        _uiState.update { state ->
                            state.copy(
                                userResults = userResults,
                                userCount = userResults.size + state.directMatches.count { it is SearchResultItem.DirectUserMatch }
                            )
                        }
                    }
                }
            }
        }
    }

    init {
        // React to query changes with debounce
        viewModelScope.launch {
            queryFlow
                .debounce(150)
                .distinctUntilChanged()
                .collect { query ->
                    if (query.isBlank()) {
                        _uiState.update { it.copy(
                            isSearching = false,
                            userResults = emptyList(),
                            noteResults = emptyList(),
                            hashtagResults = emptyList(),
                            directMatches = emptyList(),
                            isLoadingRelay = false,
                            userCount = 0, noteCount = 0, hashtagCount = 0
                        )}
                        cancelNip50()
                        return@collect
                    }
                    _uiState.update { it.copy(isSearching = true) }
                    performSearch(query)
                }
        }
    }

    fun updateQuery(newQuery: String) {
        queryFlow.value = newQuery
    }

    fun selectTab(tab: SearchTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun clearQuery() {
        updateQuery("")
    }

    fun onSearchSubmit(query: String) {
        if (query.isNotBlank()) {
            saveRecentSearch(query.trim())
        }
    }

    fun removeRecentSearch(search: String) {
        val prefs = appContext?.getSharedPreferences(RECENT_SEARCHES_PREFS, Context.MODE_PRIVATE) ?: return
        val current = _uiState.value.recentSearches.toMutableList()
        current.remove(search)
        prefs.edit().putStringSet(RECENT_SEARCHES_KEY, current.toSet()).apply()
        _uiState.update { it.copy(recentSearches = current) }
    }

    fun clearRecentSearches() {
        val prefs = appContext?.getSharedPreferences(RECENT_SEARCHES_PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit().remove(RECENT_SEARCHES_KEY).apply()
        _uiState.update { it.copy(recentSearches = emptyList()) }
    }

    // ── Core search orchestrator ──

    private suspend fun performSearch(query: String) = withContext(Dispatchers.Default) {
        val trimmed = query.trim()

        // Layer 1: Bech32/hex direct resolution (instant)
        val directMatches = resolveDirect(trimmed)

        // Layer 2: Local fuzzy search (instant)
        val followList = accountPubkey?.let { ContactListRepository.getCachedFollowList(it) } ?: emptySet()
        val userResults = searchUsersLocal(trimmed, followList)
        val noteResults = searchNotesLocal(trimmed)
        val hashtagResults = searchHashtagsLocal(trimmed)

        _uiState.update { it.copy(
            isSearching = true,
            directMatches = directMatches,
            userResults = userResults,
            noteResults = noteResults,
            hashtagResults = hashtagResults,
            userCount = userResults.size + directMatches.count { it is SearchResultItem.DirectUserMatch },
            noteCount = noteResults.size + directMatches.count { it is SearchResultItem.DirectNoteMatch },
            hashtagCount = hashtagResults.size,
            // Auto-select tab with most results
            selectedTab = when {
                directMatches.isNotEmpty() -> if (directMatches.first() is SearchResultItem.DirectUserMatch) SearchTab.PEOPLE else SearchTab.NOTES
                userResults.isNotEmpty() -> SearchTab.PEOPLE
                noteResults.isNotEmpty() -> SearchTab.NOTES
                hashtagResults.isNotEmpty() -> SearchTab.HASHTAGS
                else -> _uiState.value.selectedTab
            }
        )}

        // Layer 3: NIP-50 relay-side search (async, streaming)
        // Like Amethyst, skip relay search when local results are already plentiful
        val hasEnoughLocal = userResults.size >= 10 || noteResults.size >= 10
        if (!hasEnoughLocal) {
            launchNip50Search(trimmed)
        } else {
            _uiState.update { it.copy(isLoadingRelay = false) }
        }
    }

    // ── Layer 1: Direct resolution (Bech32 / hex / hashtag) ──

    private fun resolveDirect(input: String): List<SearchResultItem> {
        val results = mutableListOf<SearchResultItem>()

        // Hashtag detection
        if (input.startsWith("#") && input.length > 1) {
            results.add(SearchResultItem.HashtagItem(input.substring(1).lowercase()))
            return results
        }

        // Try NIP-19 Bech32 via Nip19Parser
        try {
            val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute(input)
            if (parsed != null) {
                val profileCache = ProfileMetadataCache.getInstance()
                when (val entity = parsed.entity) {
                    is com.example.cybin.nip19.NPub -> {
                        val author = profileCache.getAuthor(entity.hex)
                        results.add(SearchResultItem.DirectUserMatch(
                            pubkeyHex = entity.hex,
                            displayId = input.take(16) + "..." + input.takeLast(8),
                            author = author
                        ))
                    }
                    is com.example.cybin.nip19.NProfile -> {
                        val author = profileCache.getAuthor(entity.hex)
                        results.add(SearchResultItem.DirectUserMatch(
                            pubkeyHex = entity.hex,
                            displayId = input.take(16) + "..." + input.takeLast(8),
                            author = author
                        ))
                    }
                    is com.example.cybin.nip19.NNote -> {
                        results.add(SearchResultItem.DirectNoteMatch(
                            noteIdHex = entity.hex,
                            displayId = input.take(16) + "..." + input.takeLast(8)
                        ))
                    }
                    is com.example.cybin.nip19.NEvent -> {
                        results.add(SearchResultItem.DirectNoteMatch(
                            noteIdHex = entity.hex,
                            displayId = input.take(16) + "..." + input.takeLast(8)
                        ))
                    }
                    else -> { /* NSec, NAddress — skip */ }
                }
                return results
            }
        } catch (e: Exception) {
            Log.d(TAG, "Bech32 parse failed for input: ${e.message}")
        }

        // 64-char hex: could be pubkey or event id
        if (input.length == 64 && input.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            val hex = input.lowercase()
            val author = ProfileMetadataCache.getInstance().getAuthor(hex)
            results.add(SearchResultItem.DirectUserMatch(
                pubkeyHex = hex,
                displayId = hex.take(12) + "..." + hex.takeLast(8),
                author = author
            ))
            results.add(SearchResultItem.DirectNoteMatch(
                noteIdHex = hex,
                displayId = hex.take(12) + "..." + hex.takeLast(8)
            ))
        }

        return results
    }

    // ── Layer 2: Local fuzzy search ──

    private fun searchUsersLocal(query: String, followList: Set<String>): List<SearchResultItem.UserItem> {
        val profileCache = ProfileMetadataCache.getInstance()
        val allProfiles = profileCache.getAllCached()
        if (allProfiles.isEmpty()) return emptyList()

        val normalizedQuery = normalize(query)
        val queryTokens = tokenize(normalizedQuery)
        val queryNoSpaces = normalizedQuery.replace(" ", "")

        val scored = mutableListOf<SearchResultItem.UserItem>()

        for ((pubkey, author) in allProfiles) {
            val score = scoreUser(author, normalizedQuery, queryTokens, queryNoSpaces)
            if (score > 0f) {
                val isFollowed = pubkey.lowercase() in followList
                val socialWeight = when {
                    isFollowed -> 3.0f
                    author.avatarUrl != null -> 1.2f
                    else -> 1.0f
                }
                val matchSource = detectMatchSource(author, normalizedQuery, queryTokens, queryNoSpaces)
                scored.add(SearchResultItem.UserItem(
                    author = author,
                    score = score * socialWeight,
                    isFollowed = isFollowed,
                    matchSource = matchSource
                ))
            }
        }

        return scored
            .sortedByDescending { it.score }
            .take(MAX_LOCAL_USER_RESULTS)
    }

    private fun scoreUser(
        author: Author,
        normalizedQuery: String,
        queryTokens: List<String>,
        queryNoSpaces: String
    ): Float {
        var maxScore = 0f

        // Check displayName
        val dn = normalize(author.displayName)
        maxScore = maxOf(maxScore, scoreField(dn, normalizedQuery, queryTokens, queryNoSpaces, 100f))

        // Check username
        val un = normalize(author.username)
        maxScore = maxOf(maxScore, scoreField(un, normalizedQuery, queryTokens, queryNoSpaces, 90f))

        // Check nip05
        val nip05 = author.nip05?.let { normalize(it) }
        if (nip05 != null) {
            maxScore = maxOf(maxScore, scoreField(nip05, normalizedQuery, queryTokens, queryNoSpaces, 50f))
        }

        // Check about/bio (lower weight)
        val about = author.about?.let { normalize(it) }
        if (about != null) {
            // Only token-match for bio (not prefix/exact), lower weight
            if (queryTokens.isNotEmpty() && queryTokens.all { token -> about.contains(token) }) {
                maxScore = maxOf(maxScore, 15f)
            } else if (about.contains(normalizedQuery)) {
                maxScore = maxOf(maxScore, 10f)
            }
        }

        // Check lud16
        val lud16 = author.lud16?.let { normalize(it) }
        if (lud16 != null) {
            maxScore = maxOf(maxScore, scoreField(lud16, normalizedQuery, queryTokens, queryNoSpaces, 30f))
        }

        return maxScore
    }

    /**
     * Score a single field against the query. Returns 0 if no match.
     * baseWeight is the max score for an exact match on this field.
     */
    private fun scoreField(
        fieldNormalized: String,
        normalizedQuery: String,
        queryTokens: List<String>,
        queryNoSpaces: String,
        baseWeight: Float
    ): Float {
        if (fieldNormalized.isBlank()) return 0f

        // Exact match
        if (fieldNormalized == normalizedQuery) return baseWeight

        // Starts with
        if (fieldNormalized.startsWith(normalizedQuery)) return baseWeight * 0.9f

        // Contains
        if (fieldNormalized.contains(normalizedQuery)) return baseWeight * 0.7f

        // No-space match: "stiginnisfil" matches "stig innisfil"
        val fieldNoSpaces = fieldNormalized.replace(" ", "")
        if (fieldNoSpaces.contains(queryNoSpaces)) return baseWeight * 0.75f
        if (queryNoSpaces.contains(fieldNoSpaces) && fieldNoSpaces.length > 2) return baseWeight * 0.6f

        // Token-all-match: every query token appears somewhere in the field
        if (queryTokens.size > 1 && queryTokens.all { token -> fieldNormalized.contains(token) }) {
            return baseWeight * 0.65f
        }

        // Individual token prefix match: at least one token starts the field or a word in the field
        val fieldWords = fieldNormalized.split(" ", "_", ".", "-").filter { it.isNotBlank() }
        if (queryTokens.isNotEmpty()) {
            val matchedTokens = queryTokens.count { token ->
                fieldWords.any { word -> word.startsWith(token) }
            }
            if (matchedTokens == queryTokens.size) return baseWeight * 0.6f
            if (matchedTokens > 0) return baseWeight * 0.3f * matchedTokens / queryTokens.size
        }

        // Levenshtein for short queries (≤15 chars) on short fields
        if (normalizedQuery.length in 2..15 && fieldNormalized.length <= 40) {
            val dist = levenshtein(normalizedQuery, fieldNormalized.take(normalizedQuery.length + 3))
            if (dist <= 1) return baseWeight * 0.5f
            if (dist <= 2 && normalizedQuery.length >= 5) return baseWeight * 0.25f
        }

        return 0f
    }

    private fun detectMatchSource(
        author: Author,
        normalizedQuery: String,
        queryTokens: List<String>,
        queryNoSpaces: String
    ): String {
        val dn = normalize(author.displayName)
        if (scoreField(dn, normalizedQuery, queryTokens, queryNoSpaces, 1f) > 0) return "displayName"
        val un = normalize(author.username)
        if (scoreField(un, normalizedQuery, queryTokens, queryNoSpaces, 1f) > 0) return "username"
        val nip05 = author.nip05?.let { normalize(it) }
        if (nip05 != null && scoreField(nip05, normalizedQuery, queryTokens, queryNoSpaces, 1f) > 0) return "nip05"
        val about = author.about?.let { normalize(it) }
        if (about != null && about.contains(normalizedQuery)) return "about"
        return ""
    }

    /**
     * Search notes using sed/awk-inspired text processing principles:
     *
     * sed-style:
     *   - Fast pre-filter: skip notes whose raw lowercase blob contains zero query
     *     tokens (like `sed '/pattern/d'`), avoiding expensive NFD normalization.
     *   - Single-pass transform: normalize each note's searchable text exactly once.
     *
     * awk-style:
     *   - Field splitting: decompose each note into typed fields (content, author,
     *     hashtags, topicTitle, tags) and score each with field-specific weights in
     *     a single pass (like `awk -F: '{print $1, $3}'`).
     *   - Aggregation: after scoring, boost notes from authors who appear multiple
     *     times in results (like `awk '{count[$1]++} END {…}'`).
     */
    private fun searchNotesLocal(query: String): List<SearchResultItem.NoteItem> {
        val notesRepo = NotesRepository.getInstance()
        val notes = notesRepo.notes.value
        if (notes.isEmpty()) return emptyList()

        val normalizedQuery = normalize(query)
        val queryTokens = tokenize(normalizedQuery)
        // Pre-compute lowercase tokens for the cheap pre-filter (no NFD, no regex)
        val lcTokens = query.lowercase().split(" ", "_", ".", "-", "@")
            .filter { it.isNotBlank() && it.length >= 2 }
            .ifEmpty { listOf(query.lowercase()) }
        val lcQuery = query.lowercase()

        val scored = mutableListOf<SearchResultItem.NoteItem>()
        val now = System.currentTimeMillis()
        // awk-style aggregation: track per-author match count for later boosting
        val authorHitCounts = mutableMapOf<String, Int>()

        for (note in notes) {
            // ── sed pre-filter: cheap lowercase scan ──────────────────────
            // Like `sed '/pattern/d'`: if the raw text contains zero tokens,
            // skip entirely. Avoids expensive normalize() on non-matching notes.
            val rawLc = note.content.lowercase()
            val authorLc = note.author.displayName.lowercase()
            val topicLc = note.topicTitle?.lowercase()
            val rawBlob = rawLc + " " + authorLc + " " + (topicLc ?: "")
            if (!lcTokens.any { rawBlob.contains(it) } &&
                !note.hashtags.any { it.lowercase().contains(lcQuery) } &&
                !note.tags.any { tag -> tag.size >= 2 && tag[1].lowercase().contains(lcQuery) }
            ) continue // Early exit — no match possible

            // ── awk field extraction: normalize once, split into fields ───
            val contentNorm = normalize(note.content)
            val authorNorm = normalize(note.author.displayName)

            // ── awk field-delimited scoring: one pass, field-specific weights ─
            var score = 0f
            var snippet = ""

            // Field 1: Content ($1) — highest weight
            if (contentNorm.contains(normalizedQuery)) {
                score = 50f
                snippet = extractSnippet(note.content, query)
            } else if (queryTokens.size > 1 && queryTokens.all { contentNorm.contains(it) }) {
                score = 35f
                snippet = extractSnippet(note.content, queryTokens.first())
            }

            // Field 2: Topic title ($2) — high weight for kind-11/1111 structured content
            if (topicLc != null) {
                val topicNorm = normalize(note.topicTitle!!)
                if (topicNorm.contains(normalizedQuery)) {
                    score = maxOf(score, 55f) // Topics are very intentional titles
                    if (snippet.isEmpty()) snippet = note.topicTitle + ": " + note.content.take(100)
                }
            }

            // Field 3: Hashtags ($3) — structured metadata, exact-ish match
            if (note.hashtags.any { normalize(it).contains(normalizedQuery) }) {
                score = maxOf(score, 40f)
                if (snippet.isEmpty()) snippet = note.content.take(120)
            }

            // Field 4: Tags ($4) — t-tags, subject tags (structured event metadata)
            if (score == 0f && note.tags.any { tag ->
                tag.size >= 2 && (tag[0] == "t" || tag[0] == "subject") &&
                    normalize(tag[1]).contains(normalizedQuery)
            }) {
                score = 35f
                if (snippet.isEmpty()) snippet = note.content.take(120)
            }

            // Field 5: Author ($5) — lower weight (they'll appear in People tab too)
            if (authorNorm.contains(normalizedQuery)) {
                score = maxOf(score, 30f)
                if (snippet.isEmpty()) snippet = note.content.take(120)
            }

            // Field 6: Mentioned pubkeys ($6) — if query matches a mentioned author
            if (score == 0f && note.mentionedPubkeys.isNotEmpty()) {
                val profileCache = ProfileMetadataCache.getInstance()
                for (pk in note.mentionedPubkeys) {
                    val mentioned = profileCache.getAuthor(pk)
                    if (mentioned != null && normalize(mentioned.displayName).contains(normalizedQuery)) {
                        score = 25f
                        if (snippet.isEmpty()) snippet = note.content.take(120)
                        break
                    }
                }
            }

            if (score <= 0f) continue

            // ── sed transform: freshness multiplier ──────────────────────
            val ageHours = (now - note.timestamp) / (1000 * 60 * 60)
            val freshness = when {
                ageHours < 24 -> 1.5f
                ageHours < 168 -> 1.2f
                else -> 1.0f
            }

            scored.add(SearchResultItem.NoteItem(
                note = note,
                score = score * freshness,
                snippetHighlight = snippet
            ))
            // awk aggregation: count hits per author
            val authorId = note.author.id.lowercase()
            authorHitCounts[authorId] = (authorHitCounts[authorId] ?: 0) + 1
        }

        // ── awk END block: apply author-frequency boost ──────────────────
        // Authors who match multiple notes are likely more relevant to the query.
        // Like `awk '{count[$1]++} END { for (k in count) if (count[k]>1) print k }'`
        if (authorHitCounts.any { it.value > 1 }) {
            for (i in scored.indices) {
                val item = scored[i]
                val authorId = item.note.author.id.lowercase()
                val hits = authorHitCounts[authorId] ?: 1
                if (hits > 1) {
                    // Diminishing boost: 1.2x for 2 hits, 1.3x for 3+
                    val boost = 1f + (0.1f * hits.coerceAtMost(4))
                    scored[i] = item.copy(score = item.score * boost)
                }
            }
        }

        return scored
            .sortedByDescending { it.score }
            .take(MAX_LOCAL_NOTE_RESULTS)
    }

    private fun searchHashtagsLocal(query: String): List<SearchResultItem.HashtagItem> {
        val normalizedQuery = normalize(query.removePrefix("#"))
        val notesRepo = NotesRepository.getInstance()
        val notes = notesRepo.notes.value

        // Collect all hashtags from feed with counts
        val hashtagCounts = mutableMapOf<String, Int>()
        for (note in notes) {
            for (tag in note.hashtags) {
                val normalized = tag.lowercase()
                if (normalized.contains(normalizedQuery)) {
                    hashtagCounts[normalized] = (hashtagCounts[normalized] ?: 0) + 1
                }
            }
        }

        return hashtagCounts.entries
            .sortedByDescending { it.value }
            .take(15)
            .map { SearchResultItem.HashtagItem(it.key, it.value) }
    }

    // ── Layer 3: NIP-50 relay search ──

    private fun launchNip50Search(query: String) {
        cancelNip50()
        _uiState.update { it.copy(isLoadingRelay = true) }

        nip50Job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val rsm = RelayConnectionStateMachine.getInstance()
                // Use indexer relays for NIP-50 search (they support it)
                val context = appContext ?: return@launch
                val storageManager = social.mycelium.android.repository.RelayStorageManager(context)
                val pubkey = accountPubkey
                val indexerRelays = if (pubkey != null) {
                    storageManager.loadIndexerRelays(pubkey).map { it.url }
                } else emptyList()

                // Common NIP-50 capable relays as fallback
                val nip50Relays = (indexerRelays + listOf(
                    "wss://relay.nostr.band",
                    "wss://search.nos.today"
                )).distinct().take(4)

                if (nip50Relays.isEmpty()) {
                    _uiState.update { it.copy(isLoadingRelay = false) }
                    return@launch
                }

                val profileCache = ProfileMetadataCache.getInstance()
                val followList = accountPubkey?.let { ContactListRepository.getCachedFollowList(it) } ?: emptySet()
                val seenUserPubkeys = _uiState.value.userResults.map { it.author.id.lowercase() }.toMutableSet()
                val seenNoteIds = _uiState.value.noteResults.map { it.note.id }.toMutableSet()

                // NIP-50 kind-0 search (people) and kind-1 search (notes)
                val userFilter = Filter(
                    kinds = listOf(0),
                    search = query,
                    limit = MAX_RELAY_RESULTS
                )
                val noteFilter = Filter(
                    kinds = listOf(1),
                    search = query,
                    limit = MAX_RELAY_RESULTS
                )

                // sed-style batched stream processing: buffer incoming events
                // and flush to UI periodically instead of per-event StateFlow updates.
                // Like `cat events | sed 's/…/…/' > output` vs running sed per line.
                val pendingUsers = java.util.concurrent.ConcurrentLinkedQueue<SearchResultItem.UserItem>()
                val pendingNotes = java.util.concurrent.ConcurrentLinkedQueue<SearchResultItem.NoteItem>()
                var flushJob: Job? = null
                val now = System.currentTimeMillis()

                fun scheduleFlush() {
                    if (flushJob?.isActive == true) return
                    flushJob = viewModelScope.launch {
                        delay(300) // Batch window — like sed buffering a stream
                        val userBatch = mutableListOf<SearchResultItem.UserItem>()
                        val noteBatch = mutableListOf<SearchResultItem.NoteItem>()
                        while (true) { pendingUsers.poll()?.let { userBatch.add(it) } ?: break }
                        while (true) { pendingNotes.poll()?.let { noteBatch.add(it) } ?: break }
                        if (userBatch.isNotEmpty() || noteBatch.isNotEmpty()) {
                            _uiState.update { state ->
                                val newUsers = if (userBatch.isNotEmpty())
                                    (state.userResults + userBatch).sortedByDescending { it.score }.take(50)
                                else state.userResults
                                val newNotes = if (noteBatch.isNotEmpty())
                                    (state.noteResults + noteBatch).sortedByDescending { it.score }.take(50)
                                else state.noteResults
                                state.copy(
                                    userResults = newUsers,
                                    noteResults = newNotes,
                                    userCount = newUsers.size + state.directMatches.count { it is SearchResultItem.DirectUserMatch },
                                    noteCount = newNotes.size + state.directMatches.count { it is SearchResultItem.DirectNoteMatch }
                                )
                            }
                        }
                    }
                }

                nip50Handle = rsm.requestTemporarySubscription(
                    relayUrls = nip50Relays,
                    filters = listOf(userFilter, noteFilter),
                    priority = SubscriptionPriority.NORMAL,
                    onEvent = { event: Event ->
                        try {
                            when (event.kind) {
                                0 -> {
                                    // sed-style dedup filter: skip already-seen pubkeys
                                    val pubkey = event.pubKey.lowercase()
                                    if (pubkey !in seenUserPubkeys) {
                                        seenUserPubkeys.add(pubkey)
                                        val author = parseKind0Event(event)
                                        if (author != null) {
                                            profileCache.putProfileIfNewer(pubkey, author, event.createdAt)
                                            val isFollowed = pubkey in followList
                                            pendingUsers.add(SearchResultItem.UserItem(
                                                author = author,
                                                score = 20f * if (isFollowed) 3f else 1f,
                                                isFollowed = isFollowed,
                                                matchSource = "relay"
                                            ))
                                            scheduleFlush()
                                        }
                                    }
                                }
                                1 -> {
                                    val noteId = event.id
                                    if (noteId !in seenNoteIds) {
                                        seenNoteIds.add(noteId)
                                        val note = parseKind1Event(event, profileCache)
                                        if (note != null) {
                                            val snippet = extractSnippet(note.content, query)
                                            val ageHours = (now - note.timestamp) / (1000 * 60 * 60)
                                            val freshness = when {
                                                ageHours < 24 -> 1.5f
                                                ageHours < 168 -> 1.2f
                                                else -> 1.0f
                                            }
                                            pendingNotes.add(SearchResultItem.NoteItem(
                                                note = note,
                                                score = 15f * freshness,
                                                snippetHighlight = snippet
                                            ))
                                            scheduleFlush()
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "NIP-50 event parse error: ${e.message}")
                        }
                    }
                )

                // Timeout: cancel after 8 seconds regardless
                delay(8000)
                flushJob?.cancel()
                // Final flush — drain any remaining buffered results
                val finalUsers = mutableListOf<SearchResultItem.UserItem>()
                val finalNotes = mutableListOf<SearchResultItem.NoteItem>()
                while (true) { pendingUsers.poll()?.let { finalUsers.add(it) } ?: break }
                while (true) { pendingNotes.poll()?.let { finalNotes.add(it) } ?: break }
                if (finalUsers.isNotEmpty() || finalNotes.isNotEmpty()) {
                    _uiState.update { state ->
                        val newUsers = (state.userResults + finalUsers).sortedByDescending { it.score }.take(50)
                        val newNotes = (state.noteResults + finalNotes).sortedByDescending { it.score }.take(50)
                        state.copy(
                            userResults = newUsers, noteResults = newNotes,
                            userCount = newUsers.size + state.directMatches.count { it is SearchResultItem.DirectUserMatch },
                            noteCount = newNotes.size + state.directMatches.count { it is SearchResultItem.DirectNoteMatch }
                        )
                    }
                }
                cancelNip50()
                _uiState.update { it.copy(isLoadingRelay = false) }

            } catch (e: Exception) {
                Log.w(TAG, "NIP-50 search failed: ${e.message}")
                _uiState.update { it.copy(isLoadingRelay = false) }
            }
        }
    }

    private fun cancelNip50() {
        nip50Handle?.cancel()
        nip50Handle = null
        nip50Job?.cancel()
        nip50Job = null
    }

    // ── Event parsing helpers ──

    private fun parseKind0Event(event: Event): Author? {
        return try {
            val content = event.content
            if (!content.startsWith("{")) return null
            val metadata = json.decodeFromString<Kind0Metadata>(content)
            Author(
                id = event.pubKey.lowercase(),
                username = metadata.name ?: event.pubKey.take(8) + "...",
                displayName = metadata.display_name ?: metadata.displayName ?: metadata.name ?: event.pubKey.take(8) + "...",
                avatarUrl = metadata.picture,
                about = metadata.about,
                nip05 = metadata.nip05,
                lud16 = metadata.lud16,
                banner = metadata.banner,
                website = metadata.website
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseKind1Event(event: Event, profileCache: ProfileMetadataCache): Note? {
        return try {
            val author = profileCache.resolveAuthor(event.pubKey)
            val allUrls = social.mycelium.android.utils.UrlDetector.findUrls(event.content)
            val mediaUrls = allUrls.filter {
                social.mycelium.android.utils.UrlDetector.isImageUrl(it) ||
                    social.mycelium.android.utils.UrlDetector.isVideoUrl(it)
            }
            Note(
                id = event.id,
                author = author,
                content = event.content,
                timestamp = event.createdAt * 1000L,
                kind = event.kind,
                hashtags = event.tags.filter { it.size >= 2 && it[0] == "t" }.mapNotNull { it.getOrNull(1) },
                mediaUrls = mediaUrls
            )
        } catch (e: Exception) {
            null
        }
    }

    @kotlinx.serialization.Serializable
    private data class Kind0Metadata(
        val name: String? = null,
        val display_name: String? = null,
        val displayName: String? = null,
        val picture: String? = null,
        val about: String? = null,
        val nip05: String? = null,
        val lud16: String? = null,
        val lud06: String? = null,
        val banner: String? = null,
        val website: String? = null
    )

    // ── Text normalization and fuzzy matching ──

    /** Normalize: lowercase, strip diacritics, collapse whitespace */
    private fun normalize(text: String): String {
        val lowered = text.lowercase().trim()
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("\\s+"), " ")
    }

    /** Split into tokens, filtering blanks */
    private fun tokenize(normalized: String): List<String> {
        return normalized.split(" ", "_", ".", "-", "@")
            .filter { it.isNotBlank() && it.length >= 2 }
    }

    /** Extract snippet around first match, with context */
    private fun extractSnippet(content: String, query: String): String {
        val idx = content.indexOf(query, ignoreCase = true)
        if (idx < 0) return content.take(120)
        val start = maxOf(0, idx - 40)
        val end = minOf(content.length, idx + query.length + 80)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < content.length) "..." else ""
        return prefix + content.substring(start, end).trim() + suffix
    }

    /** Levenshtein distance between two strings */
    private fun levenshtein(a: String, b: String): Int {
        val la = a.length
        val lb = b.length
        if (la == 0) return lb
        if (lb == 0) return la

        var prev = IntArray(lb + 1) { it }
        var curr = IntArray(lb + 1)

        for (i in 1..la) {
            curr[0] = i
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[lb]
    }

    // ── Recent searches persistence ──

    private fun loadRecentSearches() {
        val prefs = appContext?.getSharedPreferences(RECENT_SEARCHES_PREFS, Context.MODE_PRIVATE) ?: return
        val searches = prefs.getStringSet(RECENT_SEARCHES_KEY, emptySet())?.toList()?.take(MAX_RECENT_SEARCHES) ?: emptyList()
        _uiState.update { it.copy(recentSearches = searches) }
    }

    private fun saveRecentSearch(query: String) {
        val prefs = appContext?.getSharedPreferences(RECENT_SEARCHES_PREFS, Context.MODE_PRIVATE) ?: return
        val current = _uiState.value.recentSearches.toMutableList()
        current.remove(query) // Remove duplicate
        current.add(0, query) // Add to front
        val trimmed = current.take(MAX_RECENT_SEARCHES)
        prefs.edit().putStringSet(RECENT_SEARCHES_KEY, trimmed.toSet()).apply()
        _uiState.update { it.copy(recentSearches = trimmed) }
    }

    override fun onCleared() {
        super.onCleared()
        cancelNip50()
        profileInvalidationJob?.cancel()
    }
}
