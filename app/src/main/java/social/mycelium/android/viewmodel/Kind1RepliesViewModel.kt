package social.mycelium.android.viewmodel

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import social.mycelium.android.data.Note
import social.mycelium.android.data.ThreadReply
import social.mycelium.android.data.ThreadedReply
import social.mycelium.android.data.toThreadReplyForThread
import social.mycelium.android.repository.Kind1RepliesRepository
import social.mycelium.android.repository.ProfileMetadataCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.mycelium.android.relay.RelayHealthTracker

@Immutable
data class Kind1RepliesUiState(
    val note: Note? = null,
    val replies: List<Note> = emptyList(),
    val threadedReplies: List<ThreadedReply> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val totalReplyCount: Int = 0,
    val sortOrder: Kind1ReplySortOrder = Kind1ReplySortOrder.CHRONOLOGICAL
)

enum class Kind1ReplySortOrder {
    CHRONOLOGICAL,           // Oldest first
    REVERSE_CHRONOLOGICAL,   // Newest first
    MOST_LIKED               // Most liked first
}

/**
 * ViewModel for managing Kind 1 replies to Kind 1 notes
 * Handles fetching, organizing, and displaying threaded conversations for home feed
 */
class Kind1RepliesViewModel : ViewModel() {
    private val repository = Kind1RepliesRepository()

    private val _uiState = MutableStateFlow(Kind1RepliesUiState())
    val uiState: StateFlow<Kind1RepliesUiState> = _uiState.asStateFlow()

    /** Relay URLs that yielded replies for the current thread root (for parent note RelayOrbs enrichment). */
    val replySourceRelays: StateFlow<Map<String, Set<String>>> = repository.replySourceRelays

    companion object {
        private const val TAG = "Kind1RepliesViewModel"
    }

    /** Debounced profile update: batch pubkeys for 100ms then apply all at once. */
    private val pendingProfileUpdates = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var profileUpdateFlushJob: kotlinx.coroutines.Job? = null
    private val PROFILE_UPDATE_DEBOUNCE_MS = 100L

    init {
        observeRepliesFromRepository()
        ProfileMetadataCache.getInstance().profileUpdated
            .onEach { pubkey ->
                pendingProfileUpdates.add(pubkey.lowercase())
                profileUpdateFlushJob?.cancel()
                profileUpdateFlushJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(PROFILE_UPDATE_DEBOUNCE_MS)
                    val batch = synchronized(pendingProfileUpdates) {
                        pendingProfileUpdates.toSet().also { pendingProfileUpdates.clear() }
                    }
                    if (batch.isEmpty()) return@launch
                    repository.updateAuthorsInRepliesBatch(batch)
                }
            }
            .launchIn(viewModelScope)
        // Observe relay OK confirmations: merge relay URLs into displayed replies for orb updates
        RelayHealthTracker.publishRelayConfirmed
            .onEach { (eventId, relayUrl) ->
                repository.mergePublishRelayUrl(eventId, relayUrl)
            }
            .launchIn(viewModelScope)
    }

    /**
     * Set cache relay URLs for kind-0 profile fetches (used when loading replies).
     */
    fun setCacheRelayUrls(urls: List<String>) {
        repository.setCacheRelayUrls(urls)
    }

    /**
     * Observe replies from the repository. Every time the list updates (new reply or fetched parent),
     * we re-run organizeRepliesIntoThreads so the tree stays correct (Amethyst-style).
     */
    private fun observeRepliesFromRepository() {
        viewModelScope.launch {
            repository.replies.collect { repliesMap ->
                val currentNote = _uiState.value.note ?: return@collect
                // Only update if the emission contains data for the current thread;
                // skip spurious emissions from other threads' subscriptions to avoid
                // flashing empty state or stale replies.
                if (currentNote.id !in repliesMap) return@collect
                val replies = repliesMap[currentNote.id] ?: emptyList()
                updateRepliesState(replies)
            }
        }

        viewModelScope.launch {
            repository.isLoading.collect { isLoading ->
                _uiState.update { it.copy(isLoading = isLoading) }
            }
        }

        viewModelScope.launch {
            repository.error.collect { error ->
                if (error != null) {
                    _uiState.update { it.copy(error = error) }
                }
            }
        }
    }

    /**
     * Load Kind 1 replies for a specific note
     */
    fun loadRepliesForNote(note: Note, relayUrls: List<String>) {
        val previousNoteId = _uiState.value.note?.id
        Log.d(TAG, "Loading Kind 1 replies for note ${note.id.take(8)}... from ${relayUrls.size} relays")

        // Clear previous thread's state to prevent stale replies from showing
        if (previousNoteId != null && previousNoteId != note.id) {
            repository.clearRepliesForNote(previousNoteId)
            Log.d(TAG, "Cleared previous thread ${previousNoteId.take(8)} before loading new one")
        }

        // Always clear replies when switching notes to prevent stale comments from
        // lingering. The repository will re-emit cached replies (if any) synchronously
        // in fetchRepliesForNote before the WebSocket fetch begins.
        _uiState.update { it.copy(note = note, replies = emptyList(), threadedReplies = emptyList(), totalReplyCount = 0, isLoading = true, error = null) }

        viewModelScope.launch {
            try {
                // Connect to relays if not already connected
                repository.connectToRelays(relayUrls)

                // Fetch replies for this note (includes author's outbox relays if cached)
                repository.fetchRepliesForNote(
                    noteId = note.id,
                    relayUrls = relayUrls,
                    limit = 200,
                    authorPubkey = note.author.id
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading replies: ${e.message}", e)
                _uiState.update { it.copy(error = "Failed to load replies: ${e.message}", isLoading = false) }
            }
        }
    }

    /**
     * Update replies state with sorting and threaded structure (NIP-10 reply chains).
     * Fast-path: skip if reply set is unchanged (same count + same IDs) to avoid
     * redundant sort/thread/recompose when the second relay delivers identical events.
     * @param forceResort when true, bypass the fast-path (e.g. sort order changed but replies are the same).
     */
    private fun updateRepliesState(replies: List<Note>, forceResort: Boolean = false) {
        val current = _uiState.value
        // Fast-path: skip if reply set is unchanged (unless sort order changed)
        if (!forceResort && replies.size == current.totalReplyCount && replies.size > 0) {
            val currentIds = current.replies.mapTo(HashSet(current.replies.size)) { it.id }
            if (replies.all { it.id in currentIds }) return
        }

        val sortedReplies = when (current.sortOrder) {
            Kind1ReplySortOrder.CHRONOLOGICAL -> replies.sortedBy { it.timestamp }
            Kind1ReplySortOrder.REVERSE_CHRONOLOGICAL -> replies.sortedByDescending { it.timestamp }
            Kind1ReplySortOrder.MOST_LIKED -> replies.sortedByDescending { it.likes }
        }
        val threadReplies = sortedReplies.map { it.toThreadReplyForThread() }
        val threadedReplies = organizeRepliesIntoThreads(threadReplies)

        val noteId = current.note?.id
        _uiState.update { it.copy(replies = sortedReplies, threadedReplies = threadedReplies, totalReplyCount = replies.size, isLoading = false) }
        // Cache total reply count for feed cards
        if (noteId != null) {
            social.mycelium.android.repository.ReplyCountCache.set(noteId, replies.size)
        }

        // Thread completeness trace
        traceThreadCompleteness(noteId, threadReplies, threadedReplies)
    }

    /**
     * Trace thread completeness: log orphans (missing parents), max depth, and chain structure.
     * This tells us definitively what events we're missing.
     */
    private fun traceThreadCompleteness(
        rootId: String?,
        flatReplies: List<ThreadReply>,
        threadedReplies: List<ThreadedReply>
    ) {
        if (flatReplies.isEmpty()) {
            Log.d(TAG, "TRACE [${rootId?.take(8)}]: 0 replies")
            return
        }
        val replyIds = flatReplies.map { it.id }.toSet()

        // Find orphans: replies whose replyToId is not in the set and not the root
        val orphans = flatReplies.filter { r ->
            r.replyToId != null && r.replyToId != rootId && r.replyToId !in replyIds
        }
        val missingParentIds = orphans.map { it.replyToId!! }.toSet()

        // Count direct vs nested
        val directReplies = flatReplies.count { r -> r.replyToId == rootId || r.replyToId == null }
        val nestedReplies = flatReplies.size - directReplies

        // Max depth
        fun maxDepth(tree: List<ThreadedReply>): Int =
            if (tree.isEmpty()) 0 else 1 + maxDepth(tree.flatMap { it.children })
        val depth = maxDepth(threadedReplies)

        val sb = StringBuilder()
        sb.append("TRACE [${rootId?.take(8)}]: ${flatReplies.size} replies (${directReplies} direct, ${nestedReplies} nested), ")
        sb.append("${threadedReplies.size} root threads, depth=$depth")
        if (orphans.isNotEmpty()) {
            sb.append(", ${orphans.size} ORPHANS missing parents: ${missingParentIds.joinToString { it.take(8) }}")
        }
        Log.d(TAG, sb.toString())

        // Log chain structure for threads with nesting
        if (depth > 1) {
            fun traceChain(node: ThreadedReply, indent: String = "  ") {
                val marker = if (node.isOrphan) " [ORPHAN]" else ""
                Log.d(TAG, "${indent}├─ ${node.reply.id.take(8)} (L${node.level})${marker} → ${node.children.size} children")
                node.children.forEach { traceChain(it, "$indent  ") }
            }
            threadedReplies.forEach { root ->
                if (root.children.isNotEmpty()) {
                    Log.d(TAG, "CHAIN: ${root.reply.id.take(8)} (L0) → ${root.children.size} children")
                    root.children.forEach { traceChain(it) }
                }
            }
        }
    }

    /**
     * Organize flat list of replies into threaded structure.
     * Direct replies to root and replies with no parent are roots.
     * Orphan replies (whose replyToId doesn't match any reply in the list) are also
     * promoted to root level so they remain visible even when the parent wasn't fetched.
     */
    private fun organizeRepliesIntoThreads(replies: List<ThreadReply>): List<ThreadedReply> {
        if (replies.isEmpty()) return emptyList()
        val noteId = _uiState.value.note?.id ?: return emptyList()
        val replyIds = replies.map { it.id }.toSet()

        // Index children by parent for O(1) lookup instead of O(N) filter per node
        val childrenByParent = replies.groupBy { it.replyToId }

        fun buildThreadedReply(reply: ThreadReply, level: Int = 0, visited: Set<String> = emptySet()): ThreadedReply {
            // Cycle protection: if we've already visited this reply, stop recursion
            if (reply.id in visited) return ThreadedReply(reply = reply, children = emptyList(), level = level)
            val nextVisited = visited + reply.id
            val children = (childrenByParent[reply.id] ?: emptyList())
                .map { buildThreadedReply(it, level + 1, nextVisited) }
                .sortedBy { it.reply.timestamp }
            return ThreadedReply(reply = reply, children = children, level = level)
        }

        // Roots: direct replies to thread root, replies with no parent, OR orphans whose
        // parent isn't in the fetched list (so they don't silently disappear).
        val rootReplies = replies
            .filter { reply ->
                reply.replyToId == noteId ||
                reply.replyToId == null ||
                reply.replyToId !in replyIds
            }
            .map { reply ->
                val isOrphan = reply.replyToId != null &&
                    reply.replyToId != noteId &&
                    reply.replyToId !in replyIds
                if (isOrphan) {
                    Log.d(TAG, "Orphan reply ${reply.id.take(8)} — parent ${reply.replyToId?.take(8)} not in ${replyIds.size} replies")
                }
                buildThreadedReply(reply).copy(isOrphan = isOrphan)
            }

        // Verify all replies are accounted for (no silently dropped replies)
        val threadedCount = countThreadedReplies(rootReplies)
        if (threadedCount != replies.size) {
            Log.w(TAG, "Threading mismatch: ${replies.size} replies but $threadedCount in tree — ${replies.size - threadedCount} lost")
        }

        return when (_uiState.value.sortOrder) {
            Kind1ReplySortOrder.CHRONOLOGICAL -> rootReplies.sortedBy { it.reply.timestamp }
            Kind1ReplySortOrder.REVERSE_CHRONOLOGICAL -> rootReplies.sortedByDescending { it.reply.timestamp }
            Kind1ReplySortOrder.MOST_LIKED -> rootReplies.sortedByDescending { it.reply.likes }
        }
    }

    /** Count total replies in a threaded tree (for consistency verification). */
    private fun countThreadedReplies(tree: List<ThreadedReply>): Int =
        tree.sumOf { 1 + countThreadedReplies(it.children) }

    /**
     * Change sort order for replies
     */
    fun setSortOrder(sortOrder: Kind1ReplySortOrder) {
        if (_uiState.value.sortOrder != sortOrder) {
            _uiState.update { it.copy(sortOrder = sortOrder) }
            updateRepliesState(_uiState.value.replies, forceResort = true)
        }
    }

    /**
     * Refresh replies for current note
     */
    fun refreshReplies(relayUrls: List<String>) {
        val currentNote = _uiState.value.note
        if (currentNote != null) {
            loadRepliesForNote(currentNote, relayUrls)
        }
    }

    /**
     * Like a reply
     */
    fun likeReply(replyId: String) {
        viewModelScope.launch {
            val currentReplies = _uiState.value.replies
            val updatedReplies = currentReplies.map { reply ->
                if (reply.id == replyId) {
                    reply.copy(
                        likes = reply.likes + (if (reply.isLiked) -1 else 1),
                        isLiked = !reply.isLiked
                    )
                } else {
                    reply
                }
            }
            updateRepliesState(updatedReplies)
        }
    }

    /**
     * Build threaded structure for display
     * Returns map of parent ID to child notes for hierarchical rendering
     */
    fun buildThreadStructure(): Map<String, List<Note>> {
        val noteId = _uiState.value.note?.id ?: return emptyMap()
        return repository.buildThreadStructure(noteId)
    }

    /**
     * Clear all replies and reset state
     */
    fun clearReplies() {
        repository.clearAllReplies()
        _uiState.value = Kind1RepliesUiState()
    }

    /**
     * Clear replies for specific note
     */
    fun clearRepliesForNote(noteId: String) {
        repository.clearRepliesForNote(noteId)
        if (_uiState.value.note?.id == noteId) {
            _uiState.update { it.copy(replies = emptyList(), threadedReplies = emptyList(), totalReplyCount = 0) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnectAll()
    }
}
