package social.mycelium.android.viewmodel

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import social.mycelium.android.data.Note
import social.mycelium.android.data.UrlPreviewInfo
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
    private val notesRepository = NotesRepository.getInstance()
    private val liveActivityRepository = LiveActivityRepository.getInstance()
    private val urlPreviewManager = UrlPreviewManager(UrlPreviewService(), UrlPreviewCache)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    /** NIP-53 live activities with status=LIVE for the chips row. */
    val liveActivities: StateFlow<List<LiveActivity>> = liveActivityRepository.liveActivities

    /** Feed session lifecycle: Idle → Loading → Live. Drives the loading indicator in the UI. */
    val feedSessionState: StateFlow<FeedSessionState> = notesRepository.feedSessionState

    /** True after the on-disk feed cache has been checked. UI waits for this before showing the loading overlay. */
    val feedCacheChecked: StateFlow<Boolean> = notesRepository.feedCacheChecked

    companion object {
        private const val TAG = "DashboardViewModel"
    }

    /** Debounced enrichment job: only one runs at a time, after list stabilizes, so UI stays fast. */
    private var enrichmentJob: Job? = null
    /** Note IDs already enriched (or attempted) — avoids redundant HTTP fetches on re-scroll. */
    private val enrichedNoteIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** How many notes ahead of the last visible item to prefetch URL previews for. */
    private val PREFETCH_AHEAD = 15
    /** Minimum interval between prefetch runs (ms) to avoid thrashing during fast scroll. */
    private val PREFETCH_DEBOUNCE_MS = 400L
    /** Current visible range reported by the UI. */
    private val _visibleRange = MutableStateFlow(0 to 0)

    init {
        loadInitialData()
        // Defer WebSocket: feed is relay-driven; connect only when a real backend exists (reduces startup/no-op connection)
        // connectWebSocket()
        observeNotesFromRepository()
        observeRelayConnectionState()
        observeFollowListUpdates()
        // Profile→notes updates are coalesced and applied in NotesRepository (profile update coalescer)
    }

    /** Reactively update followList in uiState when ContactListRepository cache changes (follow/unfollow). */
    private fun observeFollowListUpdates() {
        viewModelScope.launch {
            ContactListRepository.followListUpdates.collect { updated ->
                _uiState.update { it.copy(followList = updated) }
            }
        }
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
    /** Tracks the last pubkey+relay combo we loaded follow list for, preventing redundant re-fetches
     *  when NavHost recreates the dashboard composable (all LaunchedEffects re-fire). */
    private var lastFollowListPubkey: String? = null
    private var lastFollowListRelays: Set<String> = emptySet()

    fun loadFollowList(pubkey: String, cacheRelayUrls: List<String>, forceRefresh: Boolean = false) {
        // ViewModel-level idempotency: skip if we already loaded for this pubkey+relays
        // and the follow list is populated. Prevents re-fetch on composable recreation.
        val relaySet = cacheRelayUrls.toSet()
        if (!forceRefresh && pubkey == lastFollowListPubkey && relaySet == lastFollowListRelays
            && _uiState.value.followList?.isNotEmpty() == true) {
            return
        }
        lastFollowListPubkey = pubkey
        lastFollowListRelays = relaySet

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
     * When Following is selected but followList is still empty (loading), pass the empty set —
     * the repository drops all notes and uses lastAppliedKind1Filter for the subscription,
     * preventing global bleed. Never pass null when enabled=true.
     */
    /** Last follow filter state applied to the repository — prevents redundant calls on composable recreation. */
    private var lastFollowFilterEnabled: Boolean? = null
    private var lastFollowFilterList: Set<String>? = null

    fun setFollowFilter(enabled: Boolean) {
        val list = _uiState.value.followList
        // ViewModel-level idempotency: skip if follow filter state hasn't changed.
        // Prevents the repository from re-evaluating mode transitions on NavHost recreation.
        if (enabled == lastFollowFilterEnabled && list == lastFollowFilterList) return
        lastFollowFilterEnabled = enabled
        lastFollowFilterList = list
        val toPass = if (enabled) list else null
        notesRepository.setFollowFilter(toPass, enabled)
    }

    /**
     * Apply a custom pubkey set as the follow filter (e.g. from a NIP-51 people list).
     * Bypasses the ViewModel's follow list and passes the custom set directly.
     */
    fun setFollowFilterWithCustomList(pubkeys: Set<String>) {
        lastFollowFilterEnabled = true
        lastFollowFilterList = pubkeys
        notesRepository.setCustomListFilter(pubkeys)
    }

    private fun loadInitialData() {
        _uiState.update {
            it.copy(notes = emptyList(), isLoading = false, hasRelays = false)
        }
    }

    /**
     * Called by the UI (DashboardScreen) when the visible item range changes.
     * Drives the viewport-aware URL preview prefetch.
     */
    fun updateVisibleRange(firstVisible: Int, lastVisible: Int) {
        _visibleRange.value = firstVisible to lastVisible
    }

    /**
     * Observe notes from the NotesRepository; emit immediately for fast render; enrich with URL previews ahead of scroll.
     * Enrichment is viewport-aware: prefetches URL previews for notes below the current viewport so they're
     * ready by the time the user scrolls to them.
     */
    private fun observeNotesFromRepository() {
        // Fast path: push notes to UI immediately
        viewModelScope.launch {
            try {
                notesRepository.displayedNotes.collect { notes ->
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

        // Viewport-aware URL preview prefetch: enriches notes from firstVisible through
        // lastVisible + PREFETCH_AHEAD. Debounced to avoid thrashing during fast scroll.
        // Already-enriched note IDs are skipped. Results cached per-URL in UrlPreviewCache.
        viewModelScope.launch {
            try {
                _visibleRange.collect { (firstVisible, lastVisible) ->
                    enrichmentJob?.cancel()
                    enrichmentJob = viewModelScope.launch {
                        try {
                            delay(PREFETCH_DEBOUNCE_MS)
                            val displayed = notesRepository.displayedNotes.value
                            if (displayed.isEmpty()) return@launch

                            // Prefetch window: from firstVisible to lastVisible + PREFETCH_AHEAD
                            val prefetchEnd = (lastVisible + PREFETCH_AHEAD).coerceAtMost(displayed.size)
                            val prefetchStart = firstVisible.coerceAtLeast(0)
                            if (prefetchStart >= prefetchEnd) return@launch

                            val windowNotes = displayed.subList(prefetchStart, prefetchEnd)
                                .filter { it.id !in enrichedNoteIds }
                            if (windowNotes.isEmpty()) return@launch

                            // Mark as attempted before fetching to avoid duplicate runs
                            windowNotes.forEach { enrichedNoteIds.add(it.id) }

                            val previews = withContext(Dispatchers.IO) {
                                urlPreviewManager.enrichTopNotes(windowNotes, limit = windowNotes.size)
                            }
                            if (previews.isNotEmpty()) {
                                _uiState.update { state ->
                                    state.copy(urlPreviewsByNoteId = state.urlPreviewsByNoteId + previews)
                                }
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // Normal: enrichment cancelled by newer scroll position
                        } catch (e: Throwable) {
                            Log.e(TAG, "Prefetch enrichment failed: ${e.message}", e)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal: viewModelScope cancelled
            } catch (e: Throwable) {
                Log.e(TAG, "Enrichment flow failed: ${e.message}", e)
            }
        }

        // Initial enrichment: when feed first loads, prefetch the first batch of notes
        // before any scroll events arrive.
        viewModelScope.launch {
            try {
                notesRepository.displayedNotes.collect { displayed ->
                    if (displayed.isNotEmpty() && enrichedNoteIds.isEmpty()) {
                        delay(800)
                        updateVisibleRange(0, 0)
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) { }
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
    /** Last relay sets passed to loadNotesFromFavoriteCategory for ViewModel-level idempotency. */
    private var lastLoadAllRelays: Set<String> = emptySet()
    private var lastLoadDisplayRelays: Set<String> = emptySet()

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

        // ViewModel-level idempotency: if called with the same relay sets and notes
        // already exist, skip entirely. Prevents visible re-render when the dashboard
        // composable is recreated by NavHost on return from other screens.
        val allSet = allUserRelayUrls.toSet()
        val displaySet = displayUrls.toSet()
        if (allSet == lastLoadAllRelays && displaySet == lastLoadDisplayRelays && _uiState.value.notes.isNotEmpty()) {
            Log.d(TAG, "loadNotesFromFavoriteCategory: idempotent skip (same relays, ${_uiState.value.notes.size} notes)")
            return
        }
        lastLoadAllRelays = allSet
        lastLoadDisplayRelays = displaySet

        // Apply display filter immediately (cheap, no subscription change)
        notesRepository.connectToRelays(if (displayUrls.isEmpty()) allUserRelayUrls else displayUrls)
        _uiState.update { it.copy(hasRelays = true) }

        // Debounce the expensive subscription call
        loadNotesJob?.cancel()
        loadNotesJob = viewModelScope.launch {
            delay(LOAD_NOTES_DEBOUNCE_MS)
            Log.d(TAG, "Loading notes: subscription=${allUserRelayUrls.size} relays, display=${displayUrls.size} relay(s)")
            // Only show loading indicator when feed is empty (cold start).
            // When notes exist from cache, resubscription happens silently.
            if (_uiState.value.notes.isEmpty()) {
                _uiState.update { it.copy(isLoadingFromRelays = true) }
            }
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
        // Do not call notesRepository.disconnectAll() - shared connection and notes outlive this screen
    }
}
