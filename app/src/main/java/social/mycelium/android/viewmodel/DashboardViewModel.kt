package social.mycelium.android.viewmodel

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import social.mycelium.android.data.Note
import social.mycelium.android.data.NoteUpdate
import social.mycelium.android.data.UrlPreviewInfo
import social.mycelium.android.network.WebSocketClient
import social.mycelium.android.repository.ContactListRepository
import social.mycelium.android.data.LiveActivity
import social.mycelium.android.repository.LiveActivityRepository
import social.mycelium.android.repository.NotesRepository
import social.mycelium.android.repository.FeedSessionState
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.RelayEndpointStatus
import social.mycelium.android.relay.RelayState
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.services.UrlPreviewCache
import social.mycelium.android.services.UrlPreviewManager
import social.mycelium.android.services.UrlPreviewService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class DashboardUiState(
    val notes: List<Note> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentDestination: String = "home",
    val hasRelays: Boolean = false,
    val isLoadingFromRelays: Boolean = false,
    /** Pending new notes count for All feed (current relay set, no follow filter). */
    val newNotesCountAll: Int = 0,
    /** Pending new notes count for Following feed (current relay set, follow filter). */
    val newNotesCountFollowing: Int = 0,
    /** Follow list (kind-3 p-tags) for "Following" filter. */
    val followList: Set<String> = emptySet(),
    /** Relay connection state for feed/connection indicator. */
    val relayState: RelayState = RelayState.Disconnected,
    /** Per-relay summary for UI (e.g. "3/5 relays"); null when not applicable. */
    val relayCountSummary: String? = null,
    /** URL previews by note id (enrichment side channel); avoids replacing whole notes list when previews load. */
    val urlPreviewsByNoteId: Map<String, List<UrlPreviewInfo>> = emptyMap()
)

class DashboardViewModel : ViewModel() {
    private val webSocketClient = WebSocketClient()
    private val notesRepository = NotesRepository.getInstance()
    private val liveActivityRepository = LiveActivityRepository.getInstance()
    private val urlPreviewManager = UrlPreviewManager(UrlPreviewService(), UrlPreviewCache)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    /** NIP-53 live activities with status=LIVE for the chips row. */
    val liveActivities: StateFlow<List<LiveActivity>> = liveActivityRepository.liveActivities

    /** Feed session lifecycle: Idle → Loading → Live. Drives the loading indicator in the UI. */
    val feedSessionState: StateFlow<FeedSessionState> = notesRepository.feedSessionState

    companion object {
        private const val TAG = "DashboardViewModel"
    }

    init {
        loadInitialData()
        // Defer WebSocket: feed is relay-driven; connect only when a real backend exists (reduces startup/no-op connection)
        // connectWebSocket()
        observeNotesFromRepository()
        observeRelayConnectionState()
        // Profile→notes updates are coalesced and applied in NotesRepository (profile update coalescer)
    }

    private fun observeRelayConnectionState() {
        val stateMachine = RelayConnectionStateMachine.getInstance()
        viewModelScope.launch {
            stateMachine.state.collect { state ->
                _uiState.update { it.copy(relayState = state) }
            }
        }
        viewModelScope.launch {
            stateMachine.perRelayState.collect { perRelay ->
                val total = perRelay.size
                if (total <= 1) {
                    _uiState.update { it.copy(relayCountSummary = null) }
                    return@collect
                }
                val connected = perRelay.values.count { it == RelayEndpointStatus.Connected }
                _uiState.update { it.copy(relayCountSummary = "$connected/$total relays") }
            }
        }
    }

    /**
     * Set cache relay URLs for kind-0 profile fetches. Call from UI when account is available.
     */
    fun setCacheRelayUrls(urls: List<String>) {
        notesRepository.setCacheRelayUrls(urls)
    }

    /**
     * Load follow list (kind-3) for the given pubkey. Call from UI when account is available.
     * Uses ContactListRepository cache (5 min TTL) so repeated calls are cheap.
     * @param forceRefresh if true, bypass cache (e.g. pull-to-refresh on Following).
     */
    fun loadFollowList(pubkey: String, cacheRelayUrls: List<String>, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val cached = ContactListRepository.getCachedFollowList(pubkey)
            if (cached != null && !forceRefresh) {
                _uiState.update { it.copy(followList = cached) }
                liveActivityRepository.setFollowedPubkeys(cached)
                // Trigger outbox discovery with cached follow list
                if (cached.isNotEmpty()) {
                    startOutboxFeed(cached, cacheRelayUrls)
                }
                return@launch
            }
            val list = ContactListRepository.fetchFollowList(pubkey, cacheRelayUrls, forceRefresh)
            _uiState.update { it.copy(followList = list) }
            liveActivityRepository.setFollowedPubkeys(list)
            // Trigger outbox discovery after fresh fetch
            if (list.isNotEmpty()) {
                startOutboxFeed(list, cacheRelayUrls)
            }
        }
    }

    /**
     * Start outbox-aware feed: discover followed users' write relays via NIP-65
     * and subscribe to them for kind-1 notes we'd otherwise miss.
     * Called automatically after follow list loads. Can also be called manually
     * with explicit indexer relay URLs.
     */
    fun startOutboxFeed(followedPubkeys: Set<String>, indexerRelayUrls: List<String>) {
        notesRepository.startOutboxFeed(followedPubkeys, indexerRelayUrls)
    }

    /**
     * Set follow filter on notes: when enabled, only notes from followList authors are shown.
     * When Following is selected but followList is still empty (loading), pass null so repo shows all until loaded.
     */
    fun setFollowFilter(enabled: Boolean) {
        val list = _uiState.value.followList
        val toPass = if (enabled) { if (list.isEmpty()) null else list } else null
        notesRepository.setFollowFilter(toPass, enabled)
    }

    private fun loadInitialData() {
        _uiState.update {
            it.copy(notes = emptyList(), isLoading = false, hasRelays = false)
        }
    }

    /** Debounced enrichment job: only one runs at a time, after list stabilizes, so UI stays fast. */
    private var enrichmentJob: Job? = null

    /**
     * Observe notes from the NotesRepository; emit immediately for fast render; enrich with URL previews after list stabilizes.
     * Enrichment is triggered from displayedNotes (debounced at 150ms) so profile batch updates don't cancel it.
     */
    private fun observeNotesFromRepository() {
        // Fast path: push notes to UI immediately
        viewModelScope.launch {
            try {
                notesRepository.notes.collect { notes ->
                    try {
                        _uiState.update {
                            it.copy(
                                notes = notes,
                                isLoadingFromRelays = false,
                                hasRelays = notes.isNotEmpty() || it.hasRelays
                            )
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Notes collect failed: ${e.message}", e)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal: viewModelScope cancelled
            } catch (e: Throwable) {
                Log.e(TAG, "Notes flow failed: ${e.message}", e)
            }
        }

        // Enrichment: triggered from displayedNotes which is already debounced (150ms).
        // This prevents profile batch updates from cancelling enrichment repeatedly.
        viewModelScope.launch {
            try {
                notesRepository.displayedNotes.collect { displayed ->
                    if (displayed.isEmpty()) return@collect
                    enrichmentJob?.cancel()
                    enrichmentJob = viewModelScope.launch {
                        try {
                            delay(800)
                            val snapshot = displayed.map { it.id }
                            val enriched = withContext(Dispatchers.IO) {
                                urlPreviewManager.processNotesForUrlPreviews(displayed)
                            }
                            if (_uiState.value.notes.map { it.id }.take(snapshot.size) == snapshot) {
                                val previewsByNoteId = enriched.associate { it.id to it.urlPreviews }
                                _uiState.update { it.copy(urlPreviewsByNoteId = previewsByNoteId) }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // Normal: enrichment cancelled by newer emission — not an error
                        } catch (e: Throwable) {
                            Log.e(TAG, "Enrichment failed: ${e.message}", e)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal: viewModelScope cancelled
            } catch (e: Throwable) {
                Log.e(TAG, "Enrichment flow failed: ${e.message}", e)
            }
        }

        viewModelScope.launch {
            try {
                notesRepository.isLoading.collect { isLoading ->
                    _uiState.update { it.copy(isLoadingFromRelays = isLoading) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal: viewModelScope cancelled
            } catch (e: Throwable) {
                Log.e(TAG, "Loading flow failed: ${e.message}", e)
            }
        }

        viewModelScope.launch {
            try {
                notesRepository.error.collect { error ->
                    if (error != null) {
                        _uiState.update { it.copy(error = error) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal: viewModelScope cancelled
            } catch (e: Throwable) {
                Log.e(TAG, "Error flow failed: ${e.message}", e)
            }
        }

        viewModelScope.launch {
            try {
                notesRepository.newNotesCounts.collect { counts ->
                    _uiState.update {
                        it.copy(newNotesCountAll = counts.all, newNotesCountFollowing = counts.following)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal: viewModelScope cancelled
            } catch (e: Throwable) {
                Log.e(TAG, "NewNotesCounts flow failed: ${e.message}", e)
            }
        }
    }

    /**
     * Apply pending new notes to the feed (call on pull-to-refresh).
     */
    fun applyPendingNotes() {
        notesRepository.applyPendingNotes()
    }

    private fun connectWebSocket() {
        viewModelScope.launch {
            try {
                webSocketClient.connect()
                // No fake notes: feed is driven by NotesRepository (relay). WebSocket is for real-time like/share updates only.

                // Listen for real-time updates
                webSocketClient.realTimeUpdates.collect { update ->
                    handleRealTimeUpdate(update)
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket connection error: ${e.message}", e)
                _uiState.update { it.copy(error = "Connection error: ${e.message}") }
            }
        }
    }

    private fun handleRealTimeUpdate(update: NoteUpdate) {
        _uiState.update { state -> state.copy(
            notes = state.notes.map { note ->
                if (note.id == update.noteId) {
                    when (update.action) {
                        "like" -> note.copy(likes = note.likes + 1, isLiked = true)
                        "unlike" -> note.copy(likes = note.likes - 1, isLiked = false)
                        "share" -> note.copy(shares = note.shares + 1, isShared = true)
                        else -> note
                    }
                } else {
                    note
                }
            }
        ) }
    }

    fun toggleLike(noteId: String) {
        viewModelScope.launch {
            var isLikedAction = false

            _uiState.update { state -> state.copy(
                notes = state.notes.map { note ->
                    if (note.id == noteId) {
                        val updatedNote = if (note.isLiked) {
                            note.copy(likes = note.likes - 1, isLiked = false)
                        } else {
                            note.copy(likes = note.likes + 1, isLiked = true)
                        }
                        isLikedAction = updatedNote.isLiked
                        updatedNote
                    } else {
                        note
                    }
                }
            ) }

            // Send update via WebSocket
            val update = NoteUpdate(
                noteId = noteId,
                action = if (isLikedAction) "like" else "unlike",
                userId = "current_user",
                timestamp = System.currentTimeMillis()
            )
            webSocketClient.sendNoteUpdate(update)
        }
    }

    fun shareNote(noteId: String) {
        viewModelScope.launch {
            _uiState.update { state -> state.copy(
                notes = state.notes.map { note ->
                    if (note.id == noteId) {
                        note.copy(shares = note.shares + 1, isShared = true)
                    } else {
                        note
                    }
                }
            ) }

            val update = NoteUpdate(
                noteId = noteId,
                action = "share",
                userId = "current_user",
                timestamp = System.currentTimeMillis()
            )
            webSocketClient.sendNoteUpdate(update)
        }
    }

    fun commentOnNote(noteId: String) {
        viewModelScope.launch {
            _uiState.update { state -> state.copy(
                notes = state.notes.map { note ->
                    if (note.id == noteId) {
                        note.copy(comments = note.comments + 1)
                    } else {
                        note
                    }
                }
            ) }
        }
    }

    fun openProfile(userId: String) {
        // Navigate to profile - handled in UI layer
    }

    fun onSidebarItemClick(itemId: String) {
        when {
            itemId.startsWith("relay_category:") -> {
                val categoryId = itemId.removePrefix("relay_category:")
                Log.d(TAG, "Category clicked: $categoryId")
                // This will be handled by passing relay URLs from the UI layer
            }
            itemId.startsWith("relay:") -> {
                val relayUrl = itemId.removePrefix("relay:")
                Log.d(TAG, "Relay clicked: $relayUrl")
                setDisplayFilterOnly(listOf(relayUrl))
            }
            itemId == "profile" -> openProfile("current_user")
            itemId == "settings" -> {
                // Navigate to settings - handled in UI layer
            }
            itemId == "logout" -> {
                // Handle logout - handled in UI layer
            }
            else -> {
                Log.d(TAG, "Unknown sidebar item: $itemId")
            }
        }
    }

    fun onMoreOptionClick(option: String) {
        // Handle more options
    }

    fun navigateToDestination(destination: String) {
        _uiState.update { it.copy(currentDestination = destination) }
    }

    /**
     * Load notes from all general relays (subscription + display both use same list).
     */
    fun loadNotesFromAllGeneralRelays(allUserRelayUrls: List<String>) {
        loadNotesFromFavoriteCategory(allUserRelayUrls, allUserRelayUrls)
    }

    /** Debounce job for loadNotesFromFavoriteCategory so rapid LaunchedEffect re-fires collapse into one call. */
    private var loadNotesJob: Job? = null
    private val LOAD_NOTES_DEBOUNCE_MS = 300L

    /**
     * Set subscription to all user relays and display filter to sidebar selection.
     * Call on first load / when categories change. allUserRelayUrls = all relays we stay connected to; displayUrls = what to show (sidebar selection).
     * Debounced: rapid calls within 300ms collapse into a single subscription to avoid triple-fire on startup.
     */
    fun loadNotesFromFavoriteCategory(allUserRelayUrls: List<String>, displayUrls: List<String>) {
        if (allUserRelayUrls.isEmpty()) {
            Log.d(TAG, "No relays configured for favorite category")
            _uiState.update { it.copy(notes = emptyList(), hasRelays = false, isLoadingFromRelays = false) }
            return
        }

        // Apply display filter immediately (cheap, no subscription change)
        notesRepository.connectToRelays(if (displayUrls.isEmpty()) allUserRelayUrls else displayUrls)
        _uiState.update { it.copy(hasRelays = true) }

        // Debounce the expensive subscription call
        loadNotesJob?.cancel()
        loadNotesJob = viewModelScope.launch {
            delay(LOAD_NOTES_DEBOUNCE_MS)
            Log.d(TAG, "Loading notes: subscription=${allUserRelayUrls.size} relays, display=${displayUrls.size} relay(s)")
            _uiState.update { it.copy(isLoadingFromRelays = true) }
            try {
                notesRepository.ensureSubscriptionToNotes(allUserRelayUrls, limit = 100)
                _uiState.update { it.copy(isLoadingFromRelays = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading notes from relays: ${e.message}", e)
                _uiState.update { it.copy(error = "Failed to load notes: ${e.message}", isLoadingFromRelays = false) }
            }
        }
    }

    /**
     * Update display filter only (sidebar selection). Does NOT change subscription or follow filter;
     * only which relays' notes are shown. Follow and reply filters stay applied.
     */
    fun setDisplayFilterOnly(displayUrls: List<String>) {
        notesRepository.connectToRelays(displayUrls)
    }

    /**
     * Load notes from a specific relay only (display filter). Subscription stays on all relays.
     */
    fun loadNotesFromSpecificRelay(relayUrl: String) {
        setDisplayFilterOnly(listOf(relayUrl))
    }

    /**
     * Full re-fetch from relays. Use sparingly; pull-to-refresh uses applyPendingNotes instead.
     */
    fun refreshNotes() {
        viewModelScope.launch {
            notesRepository.refresh()
        }
    }

    /**
     * Push profile cache into the feed so notes show updated names/avatars.
     * Call when the feed becomes visible so cached profiles (e.g. from debug Fetch all) render.
     */
    fun syncFeedAuthorsFromCache() {
        notesRepository.refreshAuthorsFromCache()
    }

    override fun onCleared() {
        super.onCleared()
        webSocketClient.cleanup()
        // Do not call notesRepository.disconnectAll() - shared connection and notes outlive this screen
    }
}
