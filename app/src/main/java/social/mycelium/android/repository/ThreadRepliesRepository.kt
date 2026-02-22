package social.mycelium.android.repository

import android.util.Log
import social.mycelium.android.data.Author
import social.mycelium.android.data.ThreadReply
import com.example.cybin.relay.SubscriptionPriority
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.utils.UrlDetector
import social.mycelium.android.utils.extractPubkeysFromContent
import com.example.cybin.core.Filter
import com.example.cybin.core.Event
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Repository for fetching and managing kind 1111 thread replies using the shared
 * RelayConnectionStateMachine (one-off subscription API). Handles threaded conversations
 * following NIP-22 (Threaded Replies). Uses the same NostrClient as the feed to avoid
 * duplicate connections to the same relays.
 *
 * Kind 1111 events are replies that (NIP-22 / RelayTools-style):
 * - Reference the root thread via uppercase "E" tag or ["e", id, ..., "root"]
 * - Reference the parent via lowercase "e" tag or ["e", id, ..., "reply"]
 * - Can be nested to create threaded conversations
 */
class ThreadRepliesRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })
    private val relayStateMachine = RelayConnectionStateMachine.getInstance()

    // Replies for a specific note ID
    private val _replies = MutableStateFlow<Map<String, List<ThreadReply>>>(emptyMap())
    val replies: StateFlow<Map<String, List<ThreadReply>>> = _replies.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var connectedRelays = listOf<String>()
    private var cacheRelayUrls = listOf<String>()
    private val activeSubscriptions = mutableMapOf<String, TemporarySubscriptionHandle>()
    private val profileCache = ProfileMetadataCache.getInstance()

    /** Relay URLs that yielded replies for the current thread root (for parent note RelayOrbs enrichment). */
    private val _replySourceRelays = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val replySourceRelays: StateFlow<Map<String, Set<String>>> = _replySourceRelays.asStateFlow()

    /**
     * Set cache relay URLs for kind-0 profile fetches (from RelayStorageManager.loadIndexerRelays).
     */
    fun setCacheRelayUrls(urls: List<String>) {
        cacheRelayUrls = urls
    }

    companion object {
        private const val TAG = "ThreadRepliesRepository"
        /** Short initial window; loading clears on first reply (live) or after this. Subscription stays open for more replies. */
        private const val INITIAL_LOAD_WINDOW_MS = 1500L
    }

    /**
     * Set relay URLs for subsequent fetchRepliesForNote. Connection happens when subscription is created (subscription-first so client knows which relays to use).
     */
    fun connectToRelays(relayUrls: List<String>) {
        Log.d(TAG, "Relay URLs set for thread replies: ${relayUrls.size}")
        connectedRelays = relayUrls
    }

    /**
     * Cancel all reply subscriptions and clear state. Does not disconnect the shared client.
     */
    fun disconnectAll() {
        Log.d(TAG, "Cleaning up kind 1111 reply subscriptions")
        activeSubscriptions.values.forEach { it.cancel() }
        activeSubscriptions.clear()
        connectedRelays = emptyList()
        _replies.value = emptyMap()
    }

    /**
     * Fetch kind 1111 replies for a specific note
     *
     * @param noteId The ID of the note to fetch replies for
     * @param relayUrls Optional list of relays to query (uses connected relays if not provided)
     * @param limit Maximum number of replies to fetch
     */
    /** Tracks which root notes are kind-11 topics (for accepting kind-1 replies alongside kind-1111). */
    private val topicRootIds = mutableSetOf<String>()

    suspend fun fetchRepliesForNote(
        noteId: String,
        relayUrls: List<String>? = null,
        limit: Int = 100,
        rootKind: Int = 1111
    ) {
        val targetRelays = relayUrls ?: connectedRelays
        if (targetRelays.isEmpty()) {
            Log.w(TAG, "No relays available to fetch replies")
            return
        }

        // Cancel ALL active subscriptions first to stop stale events from arriving
        activeSubscriptions.values.forEach { it.cancel() }
        activeSubscriptions.clear()

        _isLoading.value = true
        _error.value = null

        // Clear all other notes from the replies map so only the current thread is tracked.
        // This prevents stale replies from lingering when navigating between threads.
        val staleKeys = _replies.value.keys.filter { it != noteId }
        if (staleKeys.isNotEmpty()) {
            _replies.value = _replies.value - staleKeys.toSet()
        }
        // Emit an empty entry so the ViewModel collector fires and clears stale UI
        if (noteId !in _replies.value) {
            _replies.value = _replies.value + (noteId to emptyList())
        }

        // Track kind-11 roots so handleReplyEvent knows to accept kind-1 replies too
        if (rootKind == 11) topicRootIds.add(noteId) else topicRootIds.remove(noteId)

        try {
            // For kind-11 topics, also fetch kind-1 replies alongside kind-1111
            val replyKinds = if (rootKind == 11) listOf(1, 1111) else listOf(1111)
            Log.d(TAG, "Fetching kind $replyKinds replies for note ${noteId.take(8)}... from ${targetRelays.size} relays (shared client)")

            // Create filters for replies (NIP-22 root can be "e" or "E")
            val lowerFilter = Filter(
                kinds = replyKinds,
                tags = mapOf("e" to listOf(noteId)),
                limit = limit
            )
            val upperFilter = Filter(
                kinds = replyKinds,
                tags = mapOf("E" to listOf(noteId)),
                limit = limit
            )

            val lowerHandle = relayStateMachine.requestTemporarySubscriptionWithRelay(
                relayUrls = targetRelays,
                filter = lowerFilter,
                onEvent = { event, relayUrl -> handleReplyEvent(noteId, event, relayUrl) },
                priority = SubscriptionPriority.CRITICAL,
            )
            val upperHandle = relayStateMachine.requestTemporarySubscriptionWithRelay(
                relayUrls = targetRelays,
                filter = upperFilter,
                onEvent = { event, relayUrl -> handleReplyEvent(noteId, event, relayUrl) },
                priority = SubscriptionPriority.CRITICAL,
            )
            activeSubscriptions[noteId] = lowerHandle
            activeSubscriptions["$noteId:root"] = upperHandle

            // Clear loading after short window so UI shows live; subscription stays open for streaming replies
            delay(INITIAL_LOAD_WINDOW_MS)
            _isLoading.value = false
            Log.d(TAG, "Replies live for note ${noteId.take(8)}... (${getRepliesForNote(noteId).size} so far)")

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching replies: ${e.message}", e)
            _error.value = "Failed to load replies: ${e.message}"
            _isLoading.value = false
        }
    }

    /**
     * Handle incoming reply event from relay
     */
    private fun handleReplyEvent(noteId: String, event: Event, relayUrl: String = "") {
        try {
            // Accept kind-1111 always; accept kind-1 only when root is a kind-11 topic
            if (event.kind == 1111 || (event.kind == 1 && noteId in topicRootIds)) {
                val reply = convertEventToThreadReply(event, relayUrl).let { r ->
                    if (r.rootNoteId == null) r.copy(rootNoteId = noteId) else r
                }

                // Add reply to the collection for this note
                val currentReplies = _replies.value[noteId]?.toMutableList() ?: mutableListOf()

                // Track which relays yielded replies (for parent note RelayOrbs enrichment)
                if (relayUrl.isNotBlank()) {
                    val current = _replySourceRelays.value[noteId] ?: emptySet()
                    if (relayUrl !in current) {
                        _replySourceRelays.value = _replySourceRelays.value + (noteId to (current + relayUrl))
                    }
                }

                val existing = currentReplies.indexOfFirst { it.id == reply.id }
                if (existing < 0) {
                    currentReplies.add(reply)

                    // Update the flow with new replies (live update)
                    _replies.value = _replies.value + (noteId to currentReplies.sortedBy { it.timestamp })

                    // Clear loading as soon as we have at least one reply so UI shows content immediately
                    _isLoading.value = false
                    Log.d(TAG, "Added reply from ${reply.author.username}: ${reply.content.take(50)}... (Total: ${currentReplies.size})")
                } else if (reply.relayUrls.isNotEmpty()) {
                    // Same reply from a different relay — merge relay URLs
                    val existingReply = currentReplies[existing]
                    val merged = (existingReply.relayUrls + reply.relayUrls).distinct()
                    if (merged.size > existingReply.relayUrls.size) {
                        currentReplies[existing] = existingReply.copy(relayUrls = merged)
                        _replies.value = _replies.value + (noteId to currentReplies.sortedBy { it.timestamp })
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling reply event: ${e.message}", e)
        }
    }

    /**
     * Convert Nostr Event to ThreadReply data model
     */
    private fun convertEventToThreadReply(event: Event, relayUrl: String = ""): ThreadReply {
        val pubkeyHex = event.pubKey
        val author = profileCache.resolveAuthor(pubkeyHex)
        if (profileCache.getAuthor(pubkeyHex) == null && cacheRelayUrls.isNotEmpty()) {
            scope.launch { profileCache.requestProfiles(listOf(pubkeyHex), cacheRelayUrls) }
        }
        // Request kind-0 for pubkeys mentioned in content so @mentions resolve to display names
        val contentPubkeys = extractPubkeysFromContent(event.content).filter { profileCache.getAuthor(it) == null }
        if (contentPubkeys.isNotEmpty() && cacheRelayUrls.isNotEmpty()) {
            scope.launch { profileCache.requestProfiles(contentPubkeys, cacheRelayUrls) }
        }

        // Extract thread relationship from tags
        val tags = event.tags.map { it.toList() }
        val (rootId, replyToId, threadLevel) = ThreadReply.parseThreadTags(tags)

        // Extract hashtags from tags
        val hashtags = tags
            .filter { tag -> tag.size >= 2 && tag[0] == "t" }
            .mapNotNull { tag -> tag.getOrNull(1) }

        // Extract image and video URLs from content (embedded in card media area)
        val mediaUrls = UrlDetector.findUrls(event.content)
            .filter { UrlDetector.isImageUrl(it) || UrlDetector.isVideoUrl(it) }
            .distinct()

        return ThreadReply(
            id = event.id,
            author = author,
            content = event.content,
            timestamp = event.createdAt * 1000L, // Convert to milliseconds
            likes = 0,
            shares = 0,
            replies = 0,
            isLiked = false,
            hashtags = hashtags,
            mediaUrls = mediaUrls,
            rootNoteId = rootId,
            replyToId = replyToId,
            threadLevel = threadLevel,
            relayUrls = if (relayUrl.isNotBlank()) listOf(relayUrl) else emptyList(),
            kind = event.kind
        )
    }

    /**
     * Get replies for a specific note ID
     */
    fun getRepliesForNote(noteId: String): List<ThreadReply> {
        return _replies.value[noteId] ?: emptyList()
    }

    /**
     * Get reply count for a specific note
     */
    fun getReplyCount(noteId: String): Int {
        return _replies.value[noteId]?.size ?: 0
    }

    /**
     * Update author in all reply lists when profile cache is updated.
     */
    fun updateAuthorInReplies(pubkey: String) {
        val author = profileCache.getAuthor(pubkey) ?: return
        val current = _replies.value
        var updated = false
        val keyLower = pubkey.lowercase()
        val newMap = current.mapValues { (_, list) ->
            val newList = list.map { reply ->
                if (reply.author.id.lowercase() == keyLower) {
                    updated = true
                    reply.copy(author = author)
                } else reply
            }
            newList
        }
        if (updated) _replies.value = newMap
    }

    /**
     * Clear replies for a specific note
     */
    fun clearRepliesForNote(noteId: String) {
        activeSubscriptions[noteId]?.cancel()
        activeSubscriptions.remove(noteId)
        _replySourceRelays.value = _replySourceRelays.value - noteId
        _replies.value = _replies.value - noteId
    }

    /**
     * Clear all replies
     */
    fun clearAllReplies() {
        activeSubscriptions.values.forEach { it.cancel() }
        activeSubscriptions.clear()
        _replySourceRelays.value = emptyMap()
        _replies.value = emptyMap()
    }

    /**
     * Check if currently loading replies
     */
    fun isLoadingReplies(): Boolean = _isLoading.value
}
