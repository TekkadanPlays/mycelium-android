package social.mycelium.android.viewmodel

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import social.mycelium.android.data.Author
import social.mycelium.android.data.NoteWithReplies
import social.mycelium.android.data.ThreadReply
import social.mycelium.android.data.ThreadedReply
import social.mycelium.android.data.Note
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.repository.ThreadRepliesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

@Immutable
data class ThreadRepliesUiState(
    val note: Note? = null,
    val replies: List<ThreadReply> = emptyList(),
    val threadedReplies: List<ThreadedReply> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val totalReplyCount: Int = 0,
    val sortOrder: ReplySortOrder = ReplySortOrder.CHRONOLOGICAL
)

enum class ReplySortOrder {
    CHRONOLOGICAL,      // Oldest first
    REVERSE_CHRONOLOGICAL, // Newest first
    MOST_LIKED          // Most liked first
}

/**
 * ViewModel for managing thread replies (kind 1111 events)
 * Handles fetching, organizing, and displaying threaded conversations
 */
class ThreadRepliesViewModel : ViewModel() {
    private val repository = ThreadRepliesRepository()

    private val _uiState = MutableStateFlow(ThreadRepliesUiState())
    val uiState: StateFlow<ThreadRepliesUiState> = _uiState.asStateFlow()

    /** Relay URLs that yielded replies for the current thread root (for parent note RelayOrbs enrichment). */
    val replySourceRelays: StateFlow<Map<String, Set<String>>> = repository.replySourceRelays

    /** Pending optimistic replies (current thread only); removed when real reply arrives. */
    private val _optimisticReplies = MutableStateFlow<List<ThreadReply>>(emptyList())
    /** Last repository-only replies (no optimistic) so we can re-merge when adding optimistic. */
    private var _lastRepoReplies: List<ThreadReply> = emptyList()

    companion object {
        private const val TAG = "ThreadRepliesViewModel"
    }

    init {
        observeRepliesFromRepository()
        ProfileMetadataCache.getInstance().profileUpdated
            .onEach { pubkey -> repository.updateAuthorInReplies(pubkey) }
            .launchIn(viewModelScope)
    }

    /**
     * Set cache relay URLs for kind-0 profile fetches (used when loading replies).
     */
    fun setCacheRelayUrls(urls: List<String>) {
        repository.setCacheRelayUrls(urls)
    }

    /**
     * Observe replies from the repository
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
     * Load thread replies for a specific note
     */
    fun loadRepliesForNote(note: Note, relayUrls: List<String>) {
        val previousNoteId = _uiState.value.note?.id
        Log.d(TAG, "Loading replies for note ${note.id.take(8)}... from ${relayUrls.size} relays")

        // Clear previous thread's state to prevent stale replies from showing
        if (previousNoteId != null && previousNoteId != note.id) {
            repository.clearRepliesForNote(previousNoteId)
            Log.d(TAG, "Cleared previous thread ${previousNoteId.take(8)} before loading new one")
        }

        _optimisticReplies.value = emptyList()
        _lastRepoReplies = emptyList()
        _uiState.update { it.copy(note = note, replies = emptyList(), threadedReplies = emptyList(), totalReplyCount = 0, isLoading = true, error = null) }

        viewModelScope.launch {
            try {
                // Connect to relays if not already connected
                repository.connectToRelays(relayUrls)

                // Fetch replies for this note
                repository.fetchRepliesForNote(
                    noteId = note.id,
                    relayUrls = relayUrls,
                    limit = 200
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading replies: ${e.message}", e)
                _uiState.update { it.copy(error = "Failed to load replies: ${e.message}", isLoading = false) }
            }
        }
    }

    /**
     * Update replies state with sorting and threading. Merges repository replies with optimistic
     * replies and removes optimistic when a matching real reply exists.
     * @param replies When from repository: repo-only list. When already containing opt- ids: merged list (e.g. from likeReply).
     */
    private fun updateRepliesState(replies: List<ThreadReply>) {
        val noteId = _uiState.value.note?.id ?: return
        val isAlreadyMerged = replies.any { it.id.startsWith("opt-") }
        val merged = if (isAlreadyMerged) {
            replies
        } else {
            _lastRepoReplies = replies
            val optimistic = _optimisticReplies.value.filter { it.rootNoteId == noteId || it.rootNoteId == null }
            val matched = optimistic.filter { opt ->
                replies.any { r ->
                    r.author.id == opt.author.id && r.content == opt.content && r.replyToId == opt.replyToId
                }
            }
            if (matched.isNotEmpty()) {
                _optimisticReplies.value = _optimisticReplies.value - matched
            }
            val stillPending = _optimisticReplies.value.filter { it.rootNoteId == noteId || it.rootNoteId == null }
            replies + stillPending
        }

        val sortedReplies = when (_uiState.value.sortOrder) {
            ReplySortOrder.CHRONOLOGICAL -> merged.sortedBy { it.timestamp }
            ReplySortOrder.REVERSE_CHRONOLOGICAL -> merged.sortedByDescending { it.timestamp }
            ReplySortOrder.MOST_LIKED -> merged.sortedByDescending { it.likes }
        }

        val threadedReplies = organizeRepliesIntoThreads(sortedReplies)

        _uiState.update { it.copy(replies = sortedReplies, threadedReplies = threadedReplies, totalReplyCount = merged.size, isLoading = false) }
        // Cache only direct (depth-1) reply count for feed cards — not the entire chain
        val directCount = merged.count { it.replyToId == noteId || it.replyToId == null }
        social.mycelium.android.repository.ReplyCountCache.set(noteId, directCount)

        Log.d(TAG, "Updated replies state: ${merged.size} replies, ${threadedReplies.size} threads")
    }

    /**
     * Add an optimistic reply so it appears immediately; removed when the real reply arrives from relays.
     */
    fun addOptimisticReply(
        rootId: String,
        parentId: String?,
        content: String,
        currentUserAuthor: Author
    ) {
        val noteId = _uiState.value.note?.id ?: return
        if (rootId != noteId) return
        val opt = ThreadReply(
            id = "opt-${UUID.randomUUID()}",
            author = currentUserAuthor,
            content = content,
            timestamp = System.currentTimeMillis(),
            likes = 0,
            shares = 0,
            replies = 0,
            isLiked = false,
            hashtags = emptyList(),
            mediaUrls = emptyList(),
            rootNoteId = rootId,
            replyToId = parentId,
            threadLevel = if (parentId == null || parentId == rootId) 0 else 1,
            relayUrls = emptyList(),
            kind = 1111
        )
        _optimisticReplies.value = _optimisticReplies.value + opt
        updateRepliesState(_lastRepoReplies)
    }

    /**
     * Organize flat list of replies into threaded structure
     */
    private fun organizeRepliesIntoThreads(replies: List<ThreadReply>): List<ThreadedReply> {
        if (replies.isEmpty()) return emptyList()

        val noteId = _uiState.value.note?.id ?: return emptyList()
        val replyMap = replies.associateBy { it.id }

        // Build threaded structure recursively
        fun buildThreadedReply(reply: ThreadReply, level: Int = 0): ThreadedReply {
            val children = replies
                .filter { it.replyToId == reply.id }
                .map { buildThreadedReply(it, level + 1) }
                .sortedBy { it.reply.timestamp }

            return ThreadedReply(
                reply = reply,
                children = children,
                level = level
            )
        }

        // Topics (Kind 1111): strict roots = only direct-to-thread or no parent. Nested replies (replyToId = another reply.id) become children.
        val rootReplies = replies
            .filter { reply ->
                reply.replyToId == noteId || reply.replyToId == null
            }
            .map { buildThreadedReply(it) }

        return when (_uiState.value.sortOrder) {
            ReplySortOrder.CHRONOLOGICAL -> rootReplies.sortedBy { it.reply.timestamp }
            ReplySortOrder.REVERSE_CHRONOLOGICAL -> rootReplies.sortedByDescending { it.reply.timestamp }
            ReplySortOrder.MOST_LIKED -> rootReplies.sortedByDescending { it.reply.likes }
        }
    }

    /**
     * Change sort order for replies
     */
    fun setSortOrder(sortOrder: ReplySortOrder) {
        if (_uiState.value.sortOrder != sortOrder) {
            _uiState.update { it.copy(sortOrder = sortOrder) }
            updateRepliesState(_lastRepoReplies)
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
     * Get direct replies count (top-level replies only)
     */
    fun getDirectRepliesCount(): Int {
        val noteId = _uiState.value.note?.id ?: return 0
        return _uiState.value.replies.count { reply ->
            reply.replyToId == noteId || reply.isDirectReply
        }
    }

    /**
     * Get nested replies count
     */
    fun getNestedRepliesCount(): Int {
        val directCount = getDirectRepliesCount()
        return _uiState.value.totalReplyCount - directCount
    }

    /**
     * Clear all replies and reset state
     */
    fun clearReplies() {
        repository.clearAllReplies()
        _optimisticReplies.value = emptyList()
        _lastRepoReplies = emptyList()
        _uiState.value = ThreadRepliesUiState()
    }

    /**
     * Clear replies for specific note
     */
    fun clearRepliesForNote(noteId: String) {
        repository.clearRepliesForNote(noteId)
        _optimisticReplies.value = _optimisticReplies.value.filter { it.rootNoteId != noteId }
        if (_uiState.value.note?.id == noteId) {
            _lastRepoReplies = emptyList()
            _uiState.update { it.copy(replies = emptyList(), threadedReplies = emptyList(), totalReplyCount = 0) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnectAll()
    }
}
