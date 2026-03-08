package social.mycelium.android.repository

import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import social.mycelium.android.data.Author
import social.mycelium.android.data.IMetaData
import social.mycelium.android.data.Note
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import social.mycelium.android.utils.MediaAspectRatioCache
import social.mycelium.android.utils.Nip10ReplyDetector
import social.mycelium.android.utils.Nip19QuoteParser
import social.mycelium.android.utils.UrlDetector
import social.mycelium.android.utils.extractPubkeysFromContent
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * Independent feed repository for a single author's profile page.
 *
 * Opens a dedicated relay subscription (separate from the main home feed)
 * for the author's kind-1 notes. Supports relay-side cursor pagination
 * via the `until` filter parameter to load older history.
 *
 * All tabs (Notes, Replies, Media) share the same underlying kind-1 stream
 * because relays can't distinguish reply vs root notes. [loadMore] accepts
 * an `activeTab` index so pagination continues until the *active* tab gets
 * enough new items — e.g. scrolling on Replies keeps fetching even if most
 * new events are root notes. Per-tab exhaustion prevents infinite load loops.
 *
 * Lifecycle: create when entering a profile screen, call [dispose] when leaving.
 */
class ProfileFeedRepository(
    private val authorPubkey: String,
    private val relayUrls: List<String>,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    companion object {
        private const val TAG = "ProfileFeedRepo"
        private const val PAGE_SIZE = 500
        private const val INITIAL_LOAD_SIZE = 500
        private const val FLUSH_DEBOUNCE_MS = 120L
        private const val SETTLE_QUIET_MS = 1500L
        private const val MAX_WAIT_MS = 12000L
        /** Minimum new items the *active tab* must gain before we stop a load-more page. */
        private const val TAB_MIN_NEW = 15
        /** Maximum consecutive pages that produced zero items for a tab before declaring exhaustion. */
        private const val TAB_EMPTY_PAGE_LIMIT = 3

        // Tab indices — shared with ProfileScreen
        const val TAB_NOTES = 0
        const val TAB_REPLIES = 1
        const val TAB_MEDIA = 2

        // ── Static instance cache ──────────────────────────────────────
        // Survives composable lifecycle stops during Navigation Compose transitions
        // (e.g. profile → image_viewer → back). Without this, remember() creates a
        // new repo on every lifecycle restart, and start() wipes the notes.
        private val instanceCache = HashMap<String, ProfileFeedRepository>()

        /** Get or create a ProfileFeedRepository for [authorPubkey]. If the relay list
         *  changed, the old instance is disposed and a fresh one is created. */
        fun getOrCreate(authorPubkey: String, relayUrls: List<String>): ProfileFeedRepository {
            val existing = instanceCache[authorPubkey]
            if (existing != null && existing.relayUrls == relayUrls) return existing
            existing?.dispose()
            val repo = ProfileFeedRepository(authorPubkey, relayUrls)
            instanceCache[authorPubkey] = repo
            return repo
        }

        /** Remove a cached instance (call when the profile back-stack entry is fully destroyed). */
        fun evict(authorPubkey: String) {
            instanceCache.remove(authorPubkey)?.dispose()
        }
    }

    // ── Public state ────────────────────────────────────────────────────
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** Per-tab exhaustion: map of tab index → still has more content. */
    private val _perTabHasMore = MutableStateFlow(mapOf(TAB_NOTES to true, TAB_REPLIES to true, TAB_MEDIA to true))
    val perTabHasMore: StateFlow<Map<Int, Boolean>> = _perTabHasMore.asStateFlow()

    private val _timeGapIndex = MutableStateFlow<Int?>(null)
    val timeGapIndex: StateFlow<Int?> = _timeGapIndex.asStateFlow()

    // ── Internal state ──────────────────────────────────────────────────
    private var subscription: TemporarySubscriptionHandle? = null
    private var loadMoreSubscription: TemporarySubscriptionHandle? = null
    private val pendingEvents = java.util.concurrent.ConcurrentLinkedQueue<Pair<Event, String>>()
    private var flushJob: Job? = null
    private val seenIds = HashSet<String>()
    private val profileCache = ProfileMetadataCache.getInstance()

    /** 5th-percentile pagination cursor — resists outliers pulling cursor back years. */
    @Volatile private var paginationCursorMs: Long = 0L
    /** Tracks consecutive pages that added zero items to a specific tab. */
    private val tabEmptyPageCount = intArrayOf(0, 0, 0)

    /** Batched kind-0 profile requests for mentioned pubkeys. */
    private val pendingProfilePubkeys = Collections.synchronizedSet(LinkedHashSet<String>())
    private var profileBatchJob: Job? = null
    private val PROFILE_BATCH_DELAY_MS = 150L
    private val PROFILE_BATCH_SIZE = 60

    /**
     * Start the initial subscription for the author's recent notes.
     * Call once when the profile screen becomes visible.
     */
    fun start() {
        if (subscription != null) return
        if (relayUrls.isEmpty()) {
            Log.w(TAG, "No relay URLs for profile feed ($authorPubkey)")
            return
        }

        _isLoading.value = true
        _notes.value = emptyList()
        seenIds.clear()
        paginationCursorMs = 0L
        tabEmptyPageCount.fill(0)

        val filter = Filter(
            kinds = listOf(1, 6),
            authors = listOf(authorPubkey),
            limit = INITIAL_LOAD_SIZE
        )

        Log.d(TAG, "Starting profile feed for ${authorPubkey.take(8)}… on ${relayUrls.size} relays")
        val lastEventAt = AtomicLong(0L)
        subscription = RelayConnectionStateMachine.getInstance().requestTemporarySubscriptionWithRelay(
            relayUrls = relayUrls,
            filter = filter,
            priority = SubscriptionPriority.HIGH,
        ) { event, sourceRelayUrl ->
            if ((event.kind == 1 || event.kind == 6) && event.pubKey == authorPubkey) {
                lastEventAt.set(System.currentTimeMillis())
                pendingEvents.add(event to sourceRelayUrl)
                scheduleFlush()
            }
        }

        // Settle-based wait for initial batch
        scope.launch {
            var waited = 0L
            while (waited < MAX_WAIT_MS) {
                delay(200)
                waited += 200
                flushEvents()
                val lastAt = lastEventAt.get()
                if (lastAt > 0 && System.currentTimeMillis() - lastAt >= SETTLE_QUIET_MS) break
                if (_notes.value.size >= INITIAL_LOAD_SIZE) break
            }
            flushEvents()
            _isLoading.value = false
            Log.d(TAG, "Initial load: ${_notes.value.size} notes in ${waited}ms")
        }
    }

    /**
     * Load older notes, continuing until the [activeTab] gains at least [TAB_MIN_NEW]
     * new items or the global stream is exhausted.
     *
     * Since relays can't filter notes vs replies at the protocol level, a single kind-1
     * page may produce mostly root notes when the user is on the Replies tab. This method
     * will fetch up to 3 consecutive pages to satisfy the active tab's needs.
     */
    fun loadMore(activeTab: Int = TAB_NOTES) {
        if (_isLoadingMore.value) return
        val tabExhausted = _perTabHasMore.value[activeTab] == false
        if (tabExhausted || !_hasMore.value) return
        val currentNotes = _notes.value
        if (currentNotes.isEmpty()) return

        _isLoadingMore.value = true
        scope.launch {
            val tabCountBefore = countForTab(activeTab, currentNotes)
            var pagesLoaded = 0
            var globalExhausted = false

            while (pagesLoaded < 3) {
                val beforeCount = _notes.value.size
                val pageAdded = loadOnePage()
                pagesLoaded++

                if (pageAdded == 0) {
                    globalExhausted = true
                    break
                }

                // Check if the active tab got enough new items
                val tabCountNow = countForTab(activeTab, _notes.value)
                val tabGained = tabCountNow - tabCountBefore
                if (tabGained >= TAB_MIN_NEW) break
            }

            // Update per-tab exhaustion
            if (globalExhausted) {
                _hasMore.value = false
                _perTabHasMore.value = mapOf(TAB_NOTES to false, TAB_REPLIES to false, TAB_MEDIA to false)
                Log.d(TAG, "Global exhaustion after $pagesLoaded pages")
            } else {
                // Check if this specific tab gained anything across all pages
                val tabCountNow = countForTab(activeTab, _notes.value)
                val tabGained = tabCountNow - tabCountBefore
                if (tabGained == 0) {
                    tabEmptyPageCount[activeTab]++
                    if (tabEmptyPageCount[activeTab] >= TAB_EMPTY_PAGE_LIMIT) {
                        val updated = _perTabHasMore.value.toMutableMap()
                        updated[activeTab] = false
                        _perTabHasMore.value = updated
                        Log.d(TAG, "Tab $activeTab exhausted after ${TAB_EMPTY_PAGE_LIMIT} empty pages")
                    }
                } else {
                    tabEmptyPageCount[activeTab] = 0
                }
            }

            _isLoadingMore.value = false
            Log.d(TAG, "Load more (tab=$activeTab): total=${_notes.value.size}, pages=$pagesLoaded")
        }
    }

    /** Fetch a single page of older notes. Returns the number of raw events received from relays
     *  (NOT deduped additions — so exhaustion is based on relay availability, not overlap). */
    private suspend fun loadOnePage(): Int {
        val cursorMs = if (paginationCursorMs > 0) paginationCursorMs
            else _notes.value.minOfOrNull { it.timestamp } ?: return 0
        val untilSeconds = cursorMs / 1000

        val filter = Filter(
            kinds = listOf(1, 6),
            authors = listOf(authorPubkey),
            limit = PAGE_SIZE,
            until = untilSeconds
        )

        Log.d(TAG, "Loading page: until=$untilSeconds (cursor=$cursorMs) from ${relayUrls.size} relays")
        loadMoreSubscription?.cancel()
        val lastEventAt = AtomicLong(0L)
        val rawEventCount = java.util.concurrent.atomic.AtomicInteger(0)
        val oldestReceivedMs = AtomicLong(Long.MAX_VALUE)
        loadMoreSubscription = RelayConnectionStateMachine.getInstance().requestTemporarySubscriptionWithRelay(
            relayUrls = relayUrls,
            filter = filter,
            priority = SubscriptionPriority.HIGH,
        ) { event, sourceRelayUrl ->
            if ((event.kind == 1 || event.kind == 6) && event.pubKey == authorPubkey) {
                lastEventAt.set(System.currentTimeMillis())
                rawEventCount.incrementAndGet()
                val eventMs = event.createdAt * 1000L
                oldestReceivedMs.updateAndGet { prev -> minOf(prev, eventMs) }
                pendingEvents.add(event to sourceRelayUrl)
                scheduleFlush()
            }
        }

        // Settle-based wait
        var waited = 0L
        while (waited < MAX_WAIT_MS) {
            delay(200)
            waited += 200
            flushEvents()
            val lastAt = lastEventAt.get()
            if (lastAt > 0 && System.currentTimeMillis() - lastAt >= SETTLE_QUIET_MS) break
        }
        flushEvents()
        loadMoreSubscription?.cancel()
        loadMoreSubscription = null

        // Update cursor from actual oldest received event (precise, not inferred)
        val oldest = oldestReceivedMs.get()
        if (oldest < Long.MAX_VALUE && oldest > 0) {
            if (paginationCursorMs == 0L || oldest < paginationCursorMs) {
                paginationCursorMs = oldest
            }
        }

        val received = rawEventCount.get()
        Log.d(TAG, "Page done: $received raw events, feed=${_notes.value.size}, cursor=$paginationCursorMs")
        return received
    }

    /** Count items relevant to a specific tab. */
    private fun countForTab(tab: Int, notes: List<Note>): Int = when (tab) {
        TAB_NOTES -> notes.count { !it.isReply }
        TAB_REPLIES -> notes.count { it.isReply }
        TAB_MEDIA -> notes.count { it.mediaUrls.isNotEmpty() }
        else -> notes.size
    }

    /** Pause the live subscription (e.g. when navigating away but keeping the entry). */
    fun pause() {
        subscription?.pause()
        loadMoreSubscription?.cancel()
        loadMoreSubscription = null
        Log.d(TAG, "Paused profile feed for ${authorPubkey.take(8)}…")
    }

    /** Resume a paused live subscription. */
    fun resume() {
        subscription?.resume()
        Log.d(TAG, "Resumed profile feed for ${authorPubkey.take(8)}…")
    }

    /** Stop all subscriptions and clean up. Call when leaving the profile screen. */
    fun dispose() {
        subscription?.cancel()
        subscription = null
        loadMoreSubscription?.cancel()
        loadMoreSubscription = null
        flushJob?.cancel()
        flushJob = null
        pendingEvents.clear()
        Log.d(TAG, "Disposed profile feed for ${authorPubkey.take(8)}…")
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DEBOUNCE_MS)
            flushEvents()
        }
    }

    private fun flushEvents() {
        val batch = mutableListOf<Pair<Event, String>>()
        while (true) {
            val pair = pendingEvents.poll() ?: break
            batch.add(pair)
        }
        if (batch.isEmpty()) return

        val newNotes = mutableListOf<Note>()
        for ((event, sourceRelayUrl) in batch) {
            if (event.id in seenIds) continue
            seenIds.add(event.id)

            if (event.kind == 6) {
                val repostNote = handleKind6Repost(event, sourceRelayUrl)
                if (repostNote != null) {
                    // Merge with existing repost of same original note
                    val existingIdx = newNotes.indexOfFirst { it.id == repostNote.id }
                    if (existingIdx >= 0) {
                        val existing = newNotes[existingIdx]
                        val mergedAuthors = (repostNote.repostedByAuthors + existing.repostedByAuthors).distinctBy { it.id }
                        newNotes[existingIdx] = existing.copy(
                            repostedByAuthors = mergedAuthors,
                            repostTimestamp = maxOf(repostNote.repostTimestamp ?: 0L, existing.repostTimestamp ?: 0L)
                        )
                    } else {
                        newNotes.add(repostNote)
                    }
                }
                continue
            }

            val note = convertEventToNote(event, sourceRelayUrl)
            newNotes.add(note)
        }

        if (newNotes.isEmpty()) return

        // Merge with existing, dedup reposts with originals
        val currentNotes = _notes.value.toMutableList()
        for (note in newNotes) {
            val existingIdx = currentNotes.indexOfFirst { it.id == note.id }
            if (existingIdx >= 0) {
                // Merge repost authors if both are reposts of the same note
                val existing = currentNotes[existingIdx]
                if (note.repostedByAuthors.isNotEmpty() || existing.repostedByAuthors.isNotEmpty()) {
                    val mergedAuthors = (note.repostedByAuthors + existing.repostedByAuthors).distinctBy { it.id }
                    currentNotes[existingIdx] = existing.copy(
                        repostedByAuthors = mergedAuthors,
                        repostTimestamp = maxOf(note.repostTimestamp ?: 0L, existing.repostTimestamp ?: 0L)
                    )
                }
            } else {
                // If a repost arrives and the original kind-1 is already in feed, replace it
                val origId = note.originalNoteId
                if (origId != null) {
                    val origIdx = currentNotes.indexOfFirst { it.id == origId }
                    if (origIdx >= 0) currentNotes.removeAt(origIdx)
                }
                currentNotes.add(note)
            }
        }

        val merged = currentNotes
            .distinctBy { it.id }
            .sortedByDescending { it.repostTimestamp ?: it.timestamp }

        _notes.value = merged
        advancePaginationCursor(merged)

        // Detect temporal gap
        _timeGapIndex.value = social.mycelium.android.ui.screens.detectTimeGapIndex(merged)

        // Schedule profile batch fetch for any uncached mentioned pubkeys
        if (pendingProfilePubkeys.isNotEmpty()) {
            scheduleBatchProfileRequest()
        }
    }

    /** 5th-percentile cursor advancement — mirrors NotesRepository pattern. */
    private fun advancePaginationCursor(notes: List<Note>) {
        if (notes.isEmpty()) return
        val timestamps = notes.map { it.timestamp }.sorted()
        val p5Index = (timestamps.size * 0.05).toInt().coerceIn(0, timestamps.lastIndex)
        val candidate = timestamps[p5Index]
        val absMin = timestamps.first()
        val prev = paginationCursorMs
        if (prev == 0L || candidate < prev) {
            paginationCursorMs = candidate
        } else if (absMin < prev) {
            paginationCursorMs = absMin // stall-break
        }
    }

    /**
     * Schedule a debounced batch kind-0 profile fetch for mentioned/tagged pubkeys.
     * Uses ProfileMetadataCache's internal batching mechanism.
     */
    private fun scheduleBatchProfileRequest() {
        profileBatchJob?.cancel()
        profileBatchJob = scope.launch {
            delay(PROFILE_BATCH_DELAY_MS)
            while (pendingProfilePubkeys.isNotEmpty()) {
                val batch = synchronized(pendingProfilePubkeys) {
                    pendingProfilePubkeys.take(PROFILE_BATCH_SIZE).also { pendingProfilePubkeys.removeAll(it.toSet()) }
                }
                if (batch.isEmpty()) break
                if (relayUrls.isEmpty()) {
                    pendingProfilePubkeys.addAll(batch)
                    break
                }
                try {
                    profileCache.requestProfiles(batch, relayUrls)
                } catch (e: Throwable) {
                    Log.e(TAG, "Batch profile request failed: ${e.message}")
                }
                if (pendingProfilePubkeys.isNotEmpty()) delay(200)
            }
            profileBatchJob = null
        }
    }

    /** Queue a pubkey for batch profile fetching if not already cached. */
    private fun queueProfileIfMissing(pubkey: String) {
        val hex = pubkey.lowercase()
        if (profileCache.getAuthor(hex) == null) {
            pendingProfilePubkeys.add(hex)
        }
    }

    /**
     * Convert kind-1 event to Note — full parity with NotesRepository.convertEventToNote.
     * Extracts mentioned pubkeys, parses NIP-92 imeta, sets kind, etc.
     */
    private fun convertEventToNote(event: Event, sourceRelayUrl: String = ""): Note {
        val pubkeyHex = event.pubKey
        val author = profileCache.resolveAuthor(pubkeyHex)
        queueProfileIfMissing(pubkeyHex)
        // Request kind-0 for pubkeys mentioned in content (npub, nprofile, hex)
        extractPubkeysFromContent(event.content).forEach { hex -> queueProfileIfMissing(hex) }

        val hashtags = event.tags.toList()
            .filter { tag -> tag.size >= 2 && tag[0] == "t" }
            .mapNotNull { tag -> tag.getOrNull(1) }
        val mediaUrls = UrlDetector.findUrls(event.content)
            .filter { UrlDetector.isImageUrl(it) || UrlDetector.isVideoUrl(it) }
            .distinct()
        // NIP-92: parse imeta tags for dimensions, blurhash, mimeType
        val mediaMeta = IMetaData.parseAll(event.tags)
        for ((url, meta) in mediaMeta) {
            if (meta.width != null && meta.height != null && meta.height > 0) {
                MediaAspectRatioCache.add(url, meta.width, meta.height)
            }
        }
        val quotedRefs = Nip19QuoteParser.extractQuotedEventRefs(event.content)
        val quotedEventIds = quotedRefs.map { it.eventId }
        quotedRefs.forEach { ref ->
            if (ref.relayHints.isNotEmpty()) QuotedNoteCache.putRelayHints(ref.eventId, ref.relayHints)
        }
        val isReply = Nip10ReplyDetector.isReply(event)
        val rootNoteId = if (isReply) Nip10ReplyDetector.getRootId(event) else null
        val replyToId = if (isReply) Nip10ReplyDetector.getReplyToId(event) else null
        val tags = event.tags.map { it.toList() }
        val storedRelayUrl = sourceRelayUrl.ifEmpty { null }?.let { social.mycelium.android.utils.normalizeRelayUrl(it) }

        return Note(
            id = event.id,
            author = author,
            content = event.content,
            timestamp = event.createdAt * 1000L,
            likes = 0,
            shares = 0,
            comments = 0,
            isLiked = false,
            hashtags = hashtags,
            mediaUrls = mediaUrls,
            mediaMeta = mediaMeta,
            quotedEventIds = quotedEventIds,
            relayUrl = storedRelayUrl,
            relayUrls = if (storedRelayUrl != null) listOf(storedRelayUrl) else emptyList(),
            isReply = isReply,
            rootNoteId = rootNoteId,
            replyToId = replyToId,
            kind = event.kind,
            tags = tags,
        )
    }

    /**
     * Handle kind-6 repost: parse original note from event.content JSON.
     * Returns a Note with repostedByAuthors populated, or null if parsing fails.
     * For tag-only reposts (empty content), returns null — they require a relay fetch
     * that would add complexity; the original kind-1 should already be in the feed.
     */
    private fun handleKind6Repost(event: Event, sourceRelayUrl: String = ""): Note? {
        try {
            val reposterPubkey = event.pubKey
            val reposterAuthor = profileCache.resolveAuthor(reposterPubkey)
            queueProfileIfMissing(reposterPubkey)
            val repostTimestampMs = event.createdAt * 1000L
            val content = event.content
            val storedRelayUrl = sourceRelayUrl.ifEmpty { null }?.let { social.mycelium.android.utils.normalizeRelayUrl(it) }

            if (content.isBlank()) {
                // Tag-only repost — skip for now; original kind-1 should appear separately
                return null
            }

            // Content-embedded repost: parse the original note JSON
            val jsonObj = try {
                Json.parseToJsonElement(content) as? JsonObject
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse kind-6 repost JSON: ${e.message}")
                null
            } ?: return null

            val originalNoteId = (jsonObj["id"] as? JsonPrimitive)?.content ?: return null
            val notePubkey = (jsonObj["pubkey"] as? JsonPrimitive)?.content ?: return null
            val noteCreatedAt = (jsonObj["created_at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val noteContent = (jsonObj["content"] as? JsonPrimitive)?.content ?: ""

            val noteAuthor = profileCache.resolveAuthor(notePubkey)
            queueProfileIfMissing(notePubkey)
            // Also queue mentioned pubkeys from the reposted content
            extractPubkeysFromContent(noteContent).forEach { hex -> queueProfileIfMissing(hex) }

            val hashtags = (jsonObj["tags"] as? JsonArray)
                ?.mapNotNull { tag ->
                    val arr = tag as? JsonArray ?: return@mapNotNull null
                    if (arr.size >= 2 && (arr[0] as? JsonPrimitive)?.content == "t") {
                        (arr[1] as? JsonPrimitive)?.content
                    } else null
                } ?: emptyList()

            val mediaUrls = UrlDetector.findUrls(noteContent)
                .filter { UrlDetector.isImageUrl(it) || UrlDetector.isVideoUrl(it) }
                .distinct()
            // NIP-92: parse imeta from reposted note's JSON tags
            val repostImetaTags = (jsonObj["tags"] as? JsonArray)
                ?.mapNotNull { tag ->
                    val arr = tag as? JsonArray ?: return@mapNotNull null
                    if (arr.size >= 2 && (arr[0] as? JsonPrimitive)?.content == "imeta") {
                        arr.map { (it as? JsonPrimitive)?.content ?: "" }.toTypedArray()
                    } else null
                } ?: emptyList()
            val repostMediaMeta = if (repostImetaTags.isNotEmpty()) {
                IMetaData.parseAll(repostImetaTags.toTypedArray())
            } else emptyMap()
            for ((mUrl, mMeta) in repostMediaMeta) {
                if (mMeta.width != null && mMeta.height != null && mMeta.height > 0) {
                    MediaAspectRatioCache.add(mUrl, mMeta.width, mMeta.height)
                }
            }

            val quotedRefs = Nip19QuoteParser.extractQuotedEventRefs(noteContent)
            val quotedEventIds = quotedRefs.map { it.eventId }
            quotedRefs.forEach { ref ->
                if (ref.relayHints.isNotEmpty()) QuotedNoteCache.putRelayHints(ref.eventId, ref.relayHints)
            }

            // Parse NIP-10 e-tags from JSON to detect if the reposted note is a reply
            val jsonTags = jsonObj["tags"] as? JsonArray
            val eTags = jsonTags?.mapNotNull { tag ->
                val arr = tag as? JsonArray ?: return@mapNotNull null
                if (arr.size >= 2 && (arr[0] as? JsonPrimitive)?.content == "e") {
                    arr.map { (it as? JsonPrimitive)?.content ?: "" }.toTypedArray()
                } else null
            } ?: emptyList()
            val repostRootId = eTags.firstOrNull { it.size >= 4 && (it.getOrNull(3) == "root" || it.getOrNull(4) == "root") }?.getOrNull(1)
                ?: eTags.firstOrNull()?.getOrNull(1)
            val repostReplyToId = eTags.lastOrNull { it.size >= 4 && (it.getOrNull(3) == "reply" || it.getOrNull(4) == "reply") }?.getOrNull(1)
                ?: if (eTags.size >= 2) eTags.last().getOrNull(1) else eTags.firstOrNull()?.getOrNull(1)
            val repostIsReply = eTags.isNotEmpty() && eTags.any { tag ->
                when {
                    tag.size <= 3 -> true
                    tag.size >= 4 -> tag.getOrNull(3) == "reply" || tag.getOrNull(3) == "root" || tag.getOrNull(4) == "reply" || tag.getOrNull(4) == "root"
                    else -> false
                }
            }

            return Note(
                id = "repost:$originalNoteId",
                author = noteAuthor,
                content = noteContent,
                timestamp = noteCreatedAt * 1000L,
                likes = 0, shares = 0, comments = 0, isLiked = false,
                hashtags = hashtags, mediaUrls = mediaUrls, mediaMeta = repostMediaMeta,
                quotedEventIds = quotedEventIds,
                relayUrl = storedRelayUrl,
                relayUrls = if (storedRelayUrl != null) listOf(storedRelayUrl) else emptyList(),
                isReply = repostIsReply,
                rootNoteId = if (repostIsReply) repostRootId else null,
                replyToId = if (repostIsReply) repostReplyToId else null,
                originalNoteId = originalNoteId,
                repostedByAuthors = listOf(reposterAuthor),
                repostTimestamp = repostTimestampMs,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling kind-6 repost: ${e.message}", e)
            return null
        }
    }
}
