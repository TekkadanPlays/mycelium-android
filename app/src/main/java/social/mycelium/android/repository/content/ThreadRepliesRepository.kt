package social.mycelium.android.repository.content

import android.util.Log
import social.mycelium.android.data.Author
import social.mycelium.android.data.ThreadReply
import com.example.cybin.relay.SubscriptionPriority
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import social.mycelium.android.repository.cache.ProfileMetadataCache
import social.mycelium.android.utils.EventRelayTracker
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
import social.mycelium.android.repository.cache.QuotedNoteCache
import social.mycelium.android.repository.relay.Nip65RelayListRepository
import social.mycelium.android.repository.relay.RelayStorageManager
import social.mycelium.android.repository.NotificationsRepository

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

    // Displayed replies for a specific note ID (shown in UI)
    private val _replies = MutableStateFlow<Map<String, List<ThreadReply>>>(emptyMap())
    val replies: StateFlow<Map<String, List<ThreadReply>>> = _replies.asStateFlow()

    // Pending new replies that arrived after the initial load window (not yet shown)
    private val pendingRepliesLock = Any()
    private val _pendingReplies = mutableMapOf<String, MutableList<ThreadReply>>() // noteId -> pending replies
    /** Total count of pending new replies for the current thread root. */
    private val _newReplyCount = MutableStateFlow(0)
    val newReplyCount: StateFlow<Int> = _newReplyCount.asStateFlow()
    /** Pending reply counts per parent ID (for inline 'x new replies' buttons on individual replies). */
    private val _newRepliesByParent = MutableStateFlow<Map<String, Int>>(emptyMap())
    val newRepliesByParent: StateFlow<Map<String, Int>> = _newRepliesByParent.asStateFlow()

    /** Cutoff: replies arriving after this timestamp go to pending. 0 = no cutoff (initial load). */
    @Volatile private var replyCutoffTimestampMs: Long = 0L
    /** Set to true after the initial load window completes; new replies go to pending after this. */
    @Volatile private var initialLoadComplete: Boolean = false

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

    /** Buffer for relay confirmations that arrived before their reply was injected. eventId → set of relay URLs. */
    private val pendingRelayConfirmations = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

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
        clearPendingReplies()
    }

    /**
     * Fetch kind 1111 replies for a specific note
     *
     * @param noteId The ID of the note to fetch replies for
     * @param relayUrls Optional list of relays to query (uses connected relays if not provided)
     * @param limit Maximum number of replies to fetch
     */
    /** Tracks root notes that accept kind-1 replies (kind-11 topics AND kind-1 notes). */
    private val kind1RootIds = mutableSetOf<String>()

    suspend fun fetchRepliesForNote(
        noteId: String,
        relayUrls: List<String>? = null,
        limit: Int = 100,
        rootKind: Int = 1111,
        authorPubkey: String? = null
    ) {
        val baseRelays = relayUrls ?: connectedRelays
        if (baseRelays.isEmpty()) {
            Log.w(TAG, "No relays available to fetch replies")
            return
        }
        // Enrich with author's outbox + inbox relays if available (NIP-65 kind-10002)
        val authorOutbox = authorPubkey?.let {
            Nip65RelayListRepository.getCachedOutboxRelays(it)
        }?.takeIf { it.isNotEmpty() } ?: emptyList()
        val authorInbox = authorPubkey?.let {
            Nip65RelayListRepository.getCachedInboxRelays(it)
        }?.takeIf { it.isNotEmpty() } ?: emptyList()
        val targetRelays = social.mycelium.android.relay.RelayHealthTracker.filterBlocked(
            (baseRelays + authorOutbox + authorInbox).distinct()
        )
        if (authorOutbox.isNotEmpty() || authorInbox.isNotEmpty()) {
            Log.d(TAG, "Enriched relays: ${baseRelays.size} base + ${authorOutbox.size} outbox + ${authorInbox.size} inbox = ${targetRelays.size} total")
        }

        // Cancel ALL active subscriptions first to stop stale events from arriving
        activeSubscriptions.values.forEach { it.cancel() }
        activeSubscriptions.clear()

        _isLoading.value = true
        _error.value = null
        initialLoadComplete = false
        profileCache.resetBackoff()
        replyCutoffTimestampMs = 0L
        clearPendingReplies()

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

        // Track roots that accept kind-1 replies (kind-11 topics AND kind-1 notes)
        if (rootKind == 11 || rootKind == 1) kind1RootIds.add(noteId) else kind1RootIds.remove(noteId)

        try {
            // For kind-11 topics and kind-1 notes, fetch kind-1 replies alongside kind-1111
            val replyKinds = if (rootKind == 11 || rootKind == 1) listOf(1, 1111) else listOf(1111)
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

            // Clear loading after short window; after this, new replies go to pending
            delay(INITIAL_LOAD_WINDOW_MS)
            _isLoading.value = false
            initialLoadComplete = true
            replyCutoffTimestampMs = System.currentTimeMillis()
            Log.d(TAG, "Replies live for note ${noteId.take(8)}... (${getRepliesForNote(noteId).size} displayed, cutoff set)")

            // Prefetch quoted notes from reply content so embedded quotes render without spinners
            val allQuotedIds = getRepliesForNote(noteId).flatMap { reply ->
                social.mycelium.android.utils.Nip19QuoteParser.extractQuotedEventIds(reply.content)
            }.distinct().filter { QuotedNoteCache.getCached(it) == null }
            if (allQuotedIds.isNotEmpty()) {
                // Build a stub Note with the quoted IDs so prefetchForNotes can resolve them
                val stub = social.mycelium.android.data.Note(
                    id = "prefetch-stub", author = social.mycelium.android.data.Author(id = "", username = "", displayName = ""),
                    content = "", timestamp = 0L, quotedEventIds = allQuotedIds
                )
                QuotedNoteCache.prefetchForNotes(listOf(stub))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching replies: ${e.message}", e)
            _error.value = "Failed to load replies: ${e.message}"
            _isLoading.value = false
        }
    }

    /**
     * Handle incoming reply event from relay.
     * During initial load window: replies go directly to displayed.
     * After cutoff: new replies go to pending (user sees "y new" badge).
     */
    private fun handleReplyEvent(noteId: String, event: Event, relayUrl: String = "") {
        try {
            // Cross-pollinate to notifications so badge updates even while viewing the thread
            NotificationsRepository.ingestEvent(event)
            // Accept kind-1111 always; accept kind-1 when root is a kind-11 topic or kind-1 note
            if (event.kind == 1111 || (event.kind == 1 && noteId in kind1RootIds)) {
                // Track relay URL for orb accumulation
                if (relayUrl.isNotBlank()) EventRelayTracker.addRelay(event.id, relayUrl)
                val reply = convertEventToThreadReply(event, relayUrl).let { r ->
                    if (r.rootNoteId == null) r.copy(rootNoteId = noteId) else r
                }

                // Track which relays yielded replies (for parent note RelayOrbs enrichment)
                if (relayUrl.isNotBlank()) {
                    val current = _replySourceRelays.value[noteId] ?: emptySet()
                    if (relayUrl !in current) {
                        _replySourceRelays.value = _replySourceRelays.value + (noteId to (current + relayUrl))
                    }
                }

                // Check if already displayed or pending (dedup)
                val currentReplies = _replies.value[noteId] ?: emptyList()
                if (currentReplies.any { it.id == reply.id }) {
                    // Already displayed — just merge relay URLs if needed
                    mergeRelayUrls(noteId, reply)
                    return
                }
                val alreadyPending = synchronized(pendingRepliesLock) {
                    _pendingReplies[noteId]?.any { it.id == reply.id } == true
                }
                if (alreadyPending) return

                // Route: during initial load → display immediately; after cutoff → pending
                if (!initialLoadComplete) {
                    addToDisplayed(noteId, reply)
                } else {
                    addToPending(noteId, reply)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling reply event: ${e.message}", e)
        }
    }

    /** Add a reply directly to the displayed list (during initial load). */
    private fun addToDisplayed(noteId: String, reply: ThreadReply) {
        // Enrich relay URLs from EventRelayTracker (accumulates across duplicate deliveries)
        val enrichedUrls = EventRelayTracker.enrichRelayUrls(reply.id, reply.relayUrls)
        val enrichedReply = if (enrichedUrls.size > reply.relayUrls.size) reply.copy(relayUrls = enrichedUrls) else reply
        val currentReplies = (_replies.value[noteId] ?: emptyList()).toMutableList()
        currentReplies.add(enrichedReply)
        _replies.value = _replies.value + (noteId to currentReplies.sortedBy { it.timestamp })
        _isLoading.value = false
    }

    /** Add a reply to the pending list (after initial load cutoff). */
    private fun addToPending(noteId: String, reply: ThreadReply) {
        synchronized(pendingRepliesLock) {
            _pendingReplies.getOrPut(noteId) { mutableListOf() }.add(reply)
        }
        updatePendingCounts(noteId)
        Log.d(TAG, "📬 Pending reply from ${reply.author.displayName}: ${reply.content.take(40)}...")
    }

    /**
     * Merge a confirmed relay URL into a displayed reply (called when relay sends OK).
     * Updates relay orbs in real-time as confirmations arrive.
     */
    fun mergePublishRelayUrl(eventId: String, relayUrl: String) {
        if (relayUrl.isBlank()) return
        val current = _replies.value
        for ((noteId, replies) in current) {
            val idx = replies.indexOfFirst { it.id == eventId }
            if (idx >= 0) {
                val reply = replies[idx]
                if (relayUrl in reply.relayUrls) return
                val updated = replies.toMutableList()
                updated[idx] = reply.copy(relayUrls = reply.relayUrls + relayUrl)
                _replies.value = current + (noteId to updated)
                Log.d(TAG, "Merged publish relay $relayUrl into reply ${eventId.take(8)}")
                return
            }
        }
        // Reply not yet injected — buffer for retroactive application
        pendingRelayConfirmations.getOrPut(eventId) { java.util.Collections.synchronizedSet(mutableSetOf()) }.add(relayUrl)
    }

    /** Merge relay URLs for an already-displayed reply (same event from different relay). */
    private fun mergeRelayUrls(noteId: String, reply: ThreadReply) {
        if (reply.relayUrls.isEmpty()) return
        val currentReplies = (_replies.value[noteId] ?: return).toMutableList()
        val idx = currentReplies.indexOfFirst { it.id == reply.id }
        if (idx < 0) return
        val existing = currentReplies[idx]
        val merged = (existing.relayUrls + reply.relayUrls).distinct()
        if (merged.size > existing.relayUrls.size) {
            currentReplies[idx] = existing.copy(relayUrls = merged)
            _replies.value = _replies.value + (noteId to currentReplies.sortedBy { it.timestamp })
        }
    }

    /** Update pending count flows after adding to pending. */
    private fun updatePendingCounts(noteId: String) {
        synchronized(pendingRepliesLock) {
            val pending = _pendingReplies[noteId] ?: emptyList()
            _newReplyCount.value = pending.size
            // Count per parent: replies whose replyToId is the root note or another reply
            val byParent = mutableMapOf<String, Int>()
            for (r in pending) {
                val parentId = r.replyToId ?: noteId // root-level replies have no replyToId
                byParent[parentId] = (byParent[parentId] ?: 0) + 1
            }
            _newRepliesByParent.value = byParent
        }
    }

    /**
     * Merge ALL pending replies into the displayed list (pull-to-refresh).
     */
    fun applyPendingReplies(noteId: String) {
        val toMerge: List<ThreadReply>
        synchronized(pendingRepliesLock) {
            toMerge = _pendingReplies[noteId]?.toList() ?: return
            _pendingReplies.remove(noteId)
        }
        if (toMerge.isEmpty()) return
        val currentReplies = (_replies.value[noteId] ?: emptyList()).toMutableList()
        val existingIds = currentReplies.map { it.id }.toSet()
        toMerge.filter { it.id !in existingIds }.forEach { currentReplies.add(it) }
        _replies.value = _replies.value + (noteId to currentReplies.sortedBy { it.timestamp })
        updatePendingCounts(noteId)
        Log.d(TAG, "Applied ${toMerge.size} pending replies for ${noteId.take(8)}")
    }

    /**
     * Merge pending replies for a specific parent into the displayed list
     * (inline "load new replies" button on a specific reply chain).
     */
    fun applyPendingRepliesForParent(noteId: String, parentId: String) {
        val toMerge: List<ThreadReply>
        synchronized(pendingRepliesLock) {
            val pending = _pendingReplies[noteId] ?: return
            val (forParent, rest) = pending.partition { (it.replyToId ?: noteId) == parentId }
            toMerge = forParent
            if (rest.isEmpty()) _pendingReplies.remove(noteId)
            else _pendingReplies[noteId] = rest.toMutableList()
        }
        if (toMerge.isEmpty()) return
        val currentReplies = (_replies.value[noteId] ?: emptyList()).toMutableList()
        val existingIds = currentReplies.map { it.id }.toSet()
        toMerge.filter { it.id !in existingIds }.forEach { currentReplies.add(it) }
        _replies.value = _replies.value + (noteId to currentReplies.sortedBy { it.timestamp })
        updatePendingCounts(noteId)
        Log.d(TAG, "Applied ${toMerge.size} pending replies for parent ${parentId.take(8)} in ${noteId.take(8)}")
    }

    /** Clear all pending replies and reset counts. */
    private fun clearPendingReplies() {
        synchronized(pendingRepliesLock) {
            _pendingReplies.clear()
        }
        _newReplyCount.value = 0
        _newRepliesByParent.value = emptyMap()
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
        // NIP-92: parse imeta tags for dimensions, blurhash, mimeType
        val mediaMeta = social.mycelium.android.data.IMetaData.parseAll(event.tags)
        for ((url, meta) in mediaMeta) {
            if (meta.width != null && meta.height != null && meta.height > 0) {
                social.mycelium.android.utils.MediaAspectRatioCache.add(url, meta.width, meta.height)
            }
        }

        // Extract p-tags for reply chain tagging (Amethyst-style)
        val mentionedPubkeys = tags
            .filter { tag -> tag.size >= 2 && (tag[0] == "p" || tag[0] == "P") }
            .mapNotNull { tag -> tag.getOrNull(1)?.takeIf { it.length == 64 } }
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
            mediaMeta = mediaMeta,
            rootNoteId = rootId,
            replyToId = replyToId,
            threadLevel = threadLevel,
            relayUrls = if (relayUrl.isNotBlank()) listOf(relayUrl) else emptyList(),
            kind = event.kind,
            mentionedPubkeys = mentionedPubkeys
        )
    }

    /**
     * Inject a locally published reply directly into the replies map for instant UI update.
     * Bypasses the relay round-trip so the user sees their own reply immediately.
     *
     * @param rootNoteId The root note this reply belongs to (must match an active subscription).
     * @param event The signed Event that was just published.
     */
    fun injectLocalReply(rootNoteId: String, event: Event) {
        handleReplyEvent(rootNoteId, event, "")
        // Apply any buffered relay confirmations that arrived before this injection
        val buffered = pendingRelayConfirmations.remove(event.id)
        if (buffered != null) {
            for (url in buffered) {
                mergePublishRelayUrl(event.id, url)
            }
            Log.d(TAG, "📌 Applied ${buffered.size} buffered relay confirmations for ${event.id.take(8)}")
        }
        Log.d(TAG, "📌 Injected local reply: ${event.id.take(8)} into thread ${rootNoteId.take(8)}")
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
