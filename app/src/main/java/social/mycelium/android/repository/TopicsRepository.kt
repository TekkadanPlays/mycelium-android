package social.mycelium.android.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.LruCache
import social.mycelium.android.data.Author
import social.mycelium.android.data.Note
import social.mycelium.android.relay.RelayConnectionStateMachine
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import com.example.cybin.relay.SubscriptionPriority
import social.mycelium.android.relay.TemporarySubscriptionHandle
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Data class for Kind 11 Topic Note
 */
@Serializable
data class TopicNote(
    val id: String,
    val author: Author,
    val title: String,
    val content: String,
    val hashtags: List<String>,
    val timestamp: Long,
    val replyCount: Int = 0,
    val relayUrl: String = "",
    val relayUrls: List<String> = emptyList()
) {
    /**
     * Convert TopicNote to Note for navigation and display compatibility.
     * Sets kind = 11 and topicTitle so thread views render correctly.
     */
    fun toNote(): Note = Note(
        id = id,
        author = author,
        content = content,
        timestamp = timestamp,
        likes = 0,
        shares = 0,
        comments = replyCount,
        isLiked = false,
        hashtags = hashtags,
        mediaUrls = emptyList(),
        kind = 11,
        topicTitle = title.ifEmpty { null },
        relayUrl = relayUrl.ifEmpty { null },
        relayUrls = relayUrls.ifEmpty { listOfNotNull(relayUrl).filter { it.isNotEmpty() } }
    )
}

/**
 * Data class for Hashtag Statistics
 */
@Serializable
data class HashtagStats(
    val hashtag: String,
    val topicCount: Int,
    val totalReplies: Int,
    val latestActivity: Long,
    val topicIds: List<String> = emptyList(),
    val relayUrls: List<String> = emptyList()
)

/**
 * Repository for fetching and managing Kind 11 topic events and hashtag statistics.
 * Handles topic discovery, hashtag extraction, and relay-aware aggregation.
 *
 * **Singleton**: only one kind-11 handler is registered. Must be initialized at app startup
 * (e.g. MainActivity.onCreate) so events are collected as soon as relays connect; if created
 * only when the user opens the Topics screen, events that arrived earlier are lost.
 *
 * Features:
 * - Live streaming of topics from relays (like NotesRepository)
 * - Persistent cache with SharedPreferences (unlike home feed)
 * - LruCache for in-memory fast access
 * - Real-time event processing with cache updates
 *
 * Kind 11 events are topics that:
 * - Have a "title" tag
 * - Reference hashtags via "t" tags
 * - Can receive Kind 1111 replies
 */
class TopicsRepository private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })
    private val relayStateMachine = RelayConnectionStateMachine.getInstance()
    private val profileCache = ProfileMetadataCache.getInstance()

    private var cacheRelayUrls = listOf<String>()

    /** Batched kind-0 profile requests for topics (same pattern as NotesRepository). */
    private val pendingProfilePubkeys = java.util.Collections.synchronizedSet(HashSet<String>())
    private var profileBatchJob: kotlinx.coroutines.Job? = null
    private val PROFILE_BATCH_DELAY_MS = 50L
    private val PROFILE_BATCH_SIZE = 60

    // Persistent storage
    private val sharedPrefs: SharedPreferences = appContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // In-memory LruCache for fast access (Amethyst-style)
    private val topicsCache = LruCache<String, TopicNote>(CACHE_SIZE)

    // All topics indexed by ID - stream live to UI as they arrive
    private val _topics = MutableStateFlow<Map<String, TopicNote>>(emptyMap())
    val topics: StateFlow<Map<String, TopicNote>> = _topics.asStateFlow()

    // Hashtag statistics aggregated from topics
    private val _hashtagStats = MutableStateFlow<List<HashtagStats>>(emptyList())
    val hashtagStats: StateFlow<List<HashtagStats>> = _hashtagStats.asStateFlow()

    // Topics grouped by hashtag
    private val _topicsByHashtag = MutableStateFlow<Map<String, List<TopicNote>>>(emptyMap())
    val topicsByHashtag: StateFlow<Map<String, List<TopicNote>>> = _topicsByHashtag.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Track if we're actively receiving events (separate from loading state)
    private val _isReceivingEvents = MutableStateFlow(false)
    val isReceivingEvents: StateFlow<Boolean> = _isReceivingEvents.asStateFlow()

    /** Display filter: which relay(s) to show (sidebar selection). */
    private var connectedRelays = listOf<String>()
    /** Subscription set: all user relays we stay connected to (set once; shared with NotesRepository). */
    private var subscriptionRelays = listOf<String>()
    private var hasReceivedFirstEvent = false

    // Track which relay set was used when fetching (for filtering cached topics)
    private var currentRelaySet = setOf<String>()

    /** When true, only show topics from authors in followFilter (same idea as Home Following). Empty list = show All until loaded. */
    private var followFilterEnabled = false
    private var followFilter: Set<String>? = null

    /** True only when Following is on and we have a non-empty follow list; otherwise treat as All. */
    private fun isFollowFilterActive(): Boolean = followFilterEnabled && !followFilter.isNullOrEmpty()

    /** Cutoff: topics with timestamp > this go to pending until user refreshes (like NotesRepository). */
    private var topicsCutoffTimestampMs = 0L
    private val pendingTopicsLock = Any()
    private val _pendingNewTopics = mutableListOf<TopicNote>()
    private val _newTopicsCount = MutableStateFlow(0)
    val newTopicsCount: StateFlow<Int> = _newTopicsCount.asStateFlow()

    /** Dirty flag: set when topics change, cleared after save. Prevents pointless periodic saves. */
    @Volatile private var cacheDirty = false

    // ── Pagination state for loading older topics ──────────────────────────
    private val _isLoadingOlderTopics = MutableStateFlow(false)
    val isLoadingOlderTopics: StateFlow<Boolean> = _isLoadingOlderTopics.asStateFlow()

    private val _topicsPaginationExhausted = MutableStateFlow(false)
    val topicsPaginationExhausted: StateFlow<Boolean> = _topicsPaginationExhausted.asStateFlow()

    /** Cursor for pagination: oldest topic timestamp (ms) seen so far. */
    @Volatile private var topicsPaginationCursorMs: Long = 0L
    private var olderTopicsHandle: TemporarySubscriptionHandle? = null

    companion object {
        private const val TAG = "TopicsRepository"
        private const val TOPIC_FETCH_TIMEOUT_MS = 2000L // Clear loading after 2s if no events
        private const val SUBSCRIPTION_SETTLE_MS = 400L   // Let state machine apply subscription
        private const val CACHE_PREFS = "topics_cache"
        private const val CACHE_KEY = "cached_topics"
        private const val CACHE_SIZE = 1000
        private const val CACHE_SAVE_INTERVAL = 30000L // Save to disk every 30 seconds, not on every event
        /** Page size for loading older topics. Kind-11 is sparse so we ask for a lot. */
        private const val TOPICS_PAGE_SIZE = 200
        /** Timeout for older topics page. */
        private const val TOPICS_OLDER_TIMEOUT_MS = 12000L
        /** Settle time for older topics page. */
        private const val TOPICS_OLDER_SETTLE_MS = 2000L

        @Volatile
        private var instance: TopicsRepository? = null
        fun getInstance(context: Context): TopicsRepository =
            instance ?: synchronized(this) {
                instance ?: TopicsRepository(context.applicationContext).also { instance = it }
            }
        /** Get singleton if already initialized (no Context needed). Null if never created. */
        fun getInstanceOrNull(): TopicsRepository? = instance
    }

    init {
        relayStateMachine.registerKind11Handler { event, relayUrl -> handleTopicEvent(event, relayUrl) }
        loadCacheFromStorage()
        startPeriodicCacheSaving()
        startProfileUpdateCoalescer()
    }

    /** Listen for profile updates and refresh topic authors in batches (same as NotesRepository). */
    private fun startProfileUpdateCoalescer() {
        scope.launch {
            val batch = mutableSetOf<String>()
            var flushJob: kotlinx.coroutines.Job? = null
            profileCache.profileUpdated.collect { pubkey ->
                batch.add(pubkey.lowercase())
                flushJob?.cancel()
                flushJob = scope.launch {
                    delay(80L)
                    val snapshot = batch.toSet()
                    batch.clear()
                    if (snapshot.isNotEmpty()) updateTopicAuthors(snapshot)
                }
            }
        }
    }

    /** Update author info in all topics that match the given pubkeys. */
    private fun updateTopicAuthors(pubkeys: Set<String>) {
        val current = _topics.value
        if (current.isEmpty()) return
        var changed = false
        val updated = current.mapValues { (_, topic) ->
            val key = topic.author.id.lowercase()
            if (key in pubkeys) {
                val freshAuthor = profileCache.getAuthor(key)
                if (freshAuthor != null && freshAuthor != topic.author) {
                    changed = true
                    topic.copy(author = freshAuthor)
                } else topic
            } else topic
        }
        if (changed) {
            _topics.value = updated
            cacheDirty = true
            computeHashtagStatistics()
        }
    }

    /**
     * Set cache relay URLs for kind-0 profile fetches. Call when account is available.
     */
    fun setCacheRelayUrls(urls: List<String>) {
        cacheRelayUrls = urls
    }

    /**
     * Set follow filter for topics: when enabled, only topics from authors in the list are shown (like Home Following).
     */
    fun setFollowFilter(followList: Set<String>?, enabled: Boolean) {
        followFilterEnabled = enabled
        followFilter = followList?.map { it.lowercase() }?.toSet()
        computeHashtagStatistics()
        updateNewTopicsCount()
    }

    /**
     * Align with the shared subscription (all user relays). Does NOT call requestFeedChange when the
     * state machine already has the same relay set: Home/Topics share one connection; opening Topics
     * must not tear down and recreate the subscription. Only request a feed change when we are
     * the first to set relays (e.g. app opened on Topics) or relay set actually changed.
     */
    fun setSubscriptionRelays(allUserRelayUrls: List<String>) {
        if (allUserRelayUrls.sorted() == subscriptionRelays.sorted()) return
        val current = relayStateMachine.currentSubscription.value
        val sameRelays = current.relayUrls.sorted() == allUserRelayUrls.sorted()
        subscriptionRelays = allUserRelayUrls
        hasReceivedFirstEvent = false  // Reset so loading clears on first event from new relays
        if (sameRelays) {
            Log.d(TAG, "Topics: subscription already active for ${allUserRelayUrls.size} relays (no feed change)")
            return
        }
        Log.d(TAG, "Topics subscription relays set: ${allUserRelayUrls.size} relays (requesting feed change)")
        relayStateMachine.requestFeedChange(allUserRelayUrls, relayStateMachine.getCurrentKind1Filter())
    }

    /**
     * Set display filter only (sidebar selection). Does NOT change subscription; only filters which topics are shown.
     */
    fun connectToRelays(displayFilterUrls: List<String>) {
        val normalizedUrls = displayFilterUrls.sorted()
        val currentUrls = connectedRelays.sorted()
        if (normalizedUrls == currentUrls) {
            Log.d(TAG, "Topics display filter unchanged")
            _isLoading.value = false // Always clear loading so we never get stuck on "Connecting to relays..."
            return
        }
        Log.d(TAG, "Topics display filter: ${displayFilterUrls.size} relay(s)")
        connectedRelays = displayFilterUrls
        currentRelaySet = displayFilterUrls.toSet()
        _isLoading.value = false
        computeHashtagStatistics()
    }

    /**
     * Clear local state only. Does not disconnect shared client (Home/Topics share one connection).
     */
    fun disconnectAll() {
        Log.d(TAG, "Clearing topics state (shared client stays connected)")
        connectedRelays = emptyList()
        subscriptionRelays = emptyList()
        currentRelaySet = emptySet()
        followFilterEnabled = false
        followFilter = null
        synchronized(pendingTopicsLock) { _pendingNewTopics.clear(); _newTopicsCount.value = 0 }
        resetTopicsPagination()
        clearAllTopics()
    }

    /**
     * Fetch Kind 11 topics from relays via shared state machine (subscription swap, no disconnect).
     *
     * @param relayUrls Optional list of relays to query (uses connected relays if not provided)
     * @param limit Maximum number of topics to fetch
     * @param since Optional timestamp to fetch only recent topics
     */
    /**
     * Refresh display filter and loading state. Subscription is already combined kind-1+kind-11 from setSubscriptionRelays; events flow via registerKind11Handler.
     */
    suspend fun fetchTopics(
        relayUrls: List<String>? = null,
        limit: Int = 100,
        since: Long? = null
    ) {
        val targetRelays = relayUrls ?: connectedRelays
        if (targetRelays.isEmpty()) {
            Log.w(TAG, "No relays available for topics display filter")
            return
        }

        _isLoading.value = true
        _isReceivingEvents.value = false
        _error.value = null
        currentRelaySet = targetRelays.toSet()
        computeHashtagStatistics()

        try {
            delay(TOPIC_FETCH_TIMEOUT_MS)
            _isLoading.value = false
            Log.d(TAG, "📻 Topics display filter: ${targetRelays.size} relay(s) (subscription shared, events flow from state machine)")
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchTopics: ${e.message}", e)
            _error.value = "Failed to load topics: ${e.message}"
            _isLoading.value = false
        }
    }

    /**
     * Handle incoming Kind 11 topic event from relay - with caching and pending-new-topics (like NotesRepository).
     * Topics with timestamp > cutoff go to pending until user refreshes.
     */
    private fun handleTopicEvent(event: Event, relayUrl: String = "") {
        try {
            if (event.kind == 11) {
                if (!hasReceivedFirstEvent) {
                    hasReceivedFirstEvent = true
                    topicsCutoffTimestampMs = System.currentTimeMillis()
                    _isLoading.value = false
                    _isReceivingEvents.value = true
                    Log.d(TAG, "🎉 First topic received, clearing loading state")
                }

                val topic = convertEventToTopicNote(event, relayUrl)

                if (isFollowFilterActive() && topic.author.id.lowercase() !in followFilter!!) return

                topicsCache.put(topic.id, topic)

                val currentTopics = _topics.value.toMutableMap()
                val existing = currentTopics[topic.id]
                if (existing != null) {
                    // Accumulate relay URL if this is a new relay for an existing topic
                    if (relayUrl.isNotBlank() && relayUrl !in existing.relayUrls) {
                        val updatedUrls = existing.relayUrls + relayUrl
                        currentTopics[topic.id] = existing.copy(
                            relayUrls = updatedUrls,
                            relayUrl = updatedUrls.firstOrNull() ?: existing.relayUrl
                        )
                        _topics.value = currentTopics
                        cacheDirty = true
                    }
                    return
                }

                val isAfterCutoff = topicsCutoffTimestampMs > 0 && topic.timestamp > topicsCutoffTimestampMs
                if (isAfterCutoff) {
                    synchronized(pendingTopicsLock) {
                        if (_pendingNewTopics.any { it.id == topic.id }) return
                        _pendingNewTopics.add(topic)
                        _newTopicsCount.value = _pendingNewTopics.size
                    }
                    Log.d(TAG, "📬 New topic (pending): ${topic.title}")
                } else {
                    currentTopics[topic.id] = topic
                    _topics.value = currentTopics
                    cacheDirty = true
                    computeHashtagStatistics()
                    Log.d(TAG, "✅ Added topic from relay: ${topic.title} (Total: ${currentTopics.size})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling topic event: ${e.message}", e)
        }
    }

    private fun updateNewTopicsCount() {
        val filter = followFilter
        val count = synchronized(pendingTopicsLock) {
            if (!isFollowFilterActive() || filter == null) _pendingNewTopics.size
            else _pendingNewTopics.count { it.author.id.lowercase() in filter }
        }
        _newTopicsCount.value = count
    }

    /**
     * Inject a locally published topic into the pending queue so the "x new topics" counter
     * updates immediately. The user can then pull-to-refresh to see it (live awareness pattern).
     */
    fun injectLocalTopic(event: Event, relayUrl: String = "") {
        val topic = convertEventToTopicNote(event, relayUrl)
        topicsCache.put(topic.id, topic)
        val current = _topics.value.toMutableMap()
        if (!current.containsKey(topic.id)) {
            current[topic.id] = topic
            _topics.value = current
            cacheDirty = true
            computeHashtagStatistics()
        }
        Log.d(TAG, "Injected local topic: ${topic.title} (${topic.id.take(8)})")
    }

    /**
     * Merge pending new topics into the main list (call from pull-to-refresh or when user taps "x new topics").
     */
    fun applyPendingTopics() {
        val toMerge = synchronized(pendingTopicsLock) {
            if (_pendingNewTopics.isEmpty()) return
            _pendingNewTopics.toList().also { _pendingNewTopics.clear(); _newTopicsCount.value = 0 }
        }
        if (toMerge.isEmpty()) return
        val current = _topics.value.toMutableMap()
        toMerge.forEach { t -> if (!current.containsKey(t.id)) current[t.id] = t }
        _topics.value = current
        cacheDirty = true
        computeHashtagStatistics()
        Log.d(TAG, "Applied ${toMerge.size} pending topics")
    }

    /** Schedule a batch profile fetch (debounced). Same pattern as NotesRepository. */
    private fun scheduleBatchProfileRequest() {
        profileBatchJob?.cancel()
        profileBatchJob = scope.launch {
            delay(PROFILE_BATCH_DELAY_MS)
            val batch: List<String>
            synchronized(pendingProfilePubkeys) {
                batch = pendingProfilePubkeys.take(PROFILE_BATCH_SIZE).toList()
                pendingProfilePubkeys.removeAll(batch.toSet())
            }
            if (batch.isNotEmpty()) {
                val relayUrls = cacheRelayUrls.filter { it.isNotBlank() }
                if (relayUrls.isNotEmpty()) {
                    profileCache.requestProfiles(batch, relayUrls)
                }
            }
            // If there are still remaining, schedule another batch
            if (pendingProfilePubkeys.isNotEmpty()) scheduleBatchProfileRequest()
        }
    }

    /**
     * Convert Nostr Event to TopicNote
     */
    private fun convertEventToTopicNote(event: Event, relayUrl: String = ""): TopicNote {
        val pubkeyHex = event.pubKey
        val author = profileCache.resolveAuthor(pubkeyHex)
        if (profileCache.getAuthor(pubkeyHex) == null) {
            pendingProfilePubkeys.add(pubkeyHex)
            scheduleBatchProfileRequest()
        }

        // Extract title from tags
        val tags = event.tags.map { it.toList() }
        val title = extractTitle(tags)

        // Extract hashtags from "t" tags
        val hashtags = tags
            .filter { tag -> tag.size >= 2 && tag[0] == "t" }
            .mapNotNull { tag -> tag.getOrNull(1)?.lowercase() }
            .distinct()

        val relayUrls = relayUrl.split(",").filter { it.isNotBlank() }
        return TopicNote(
            id = event.id,
            author = author,
            title = title,
            content = event.content,
            hashtags = hashtags,
            timestamp = event.createdAt * 1000L, // Convert to milliseconds
            replyCount = 0, // Will be updated when we fetch Kind 1111 replies
            relayUrl = relayUrls.firstOrNull() ?: relayUrl,
            relayUrls = relayUrls
        )
    }

    /**
     * Extract title from event tags
     * Looks for "title" tag, falls back to first line of content
     */
    private fun extractTitle(tags: List<List<String>>): String {
        // Look for "title" tag
        val titleTag = tags.find { it.size >= 2 && it[0] == "title" }
        if (titleTag != null) {
            return titleTag[1]
        }

        // No title tag found - will use content preview instead
        return ""
    }

    /**
     * Compute hashtag statistics from collected topics (filtered by relay set and optional follow list).
     */
    private fun computeHashtagStatistics() {
        var topics = if (currentRelaySet.isEmpty()) {
            _topics.value.values
        } else {
            _topics.value.values.filter { topic ->
                topic.relayUrl.isEmpty() || topic.relayUrl.split(",").toSet().intersect(currentRelaySet).isNotEmpty()
            }
        }
        if (isFollowFilterActive() && followFilter != null) {
            val filter = followFilter!!
            topics = topics.filter { it.author.id.lowercase() in filter }
        }

        val hashtagMap = mutableMapOf<String, MutableList<TopicNote>>()

        // Group topics by hashtag
        topics.forEach { topic ->
            topic.hashtags.forEach { hashtag ->
                hashtagMap.getOrPut(hashtag) { mutableListOf() }.add(topic)
            }
        }

        // Build statistics
        val stats = hashtagMap.map { (hashtag, topicList) ->
            val allRelayUrls = topicList.flatMap { it.relayUrls }.filter { it.isNotBlank() }.distinct()
            HashtagStats(
                hashtag = hashtag,
                topicCount = topicList.size,
                totalReplies = topicList.sumOf { it.replyCount },
                latestActivity = topicList.maxOfOrNull { it.timestamp } ?: 0L,
                topicIds = topicList.map { it.id },
                relayUrls = allRelayUrls
            )
        }.sortedByDescending { it.topicCount } // Sort by most topics

        _hashtagStats.value = stats

        // Update topics by hashtag map
        _topicsByHashtag.value = hashtagMap.mapValues { it.value.sortedByDescending { topic -> topic.timestamp } }

        Log.d(TAG, "Computed stats for ${stats.size} hashtags")
    }

    /**
     * Get topics for a specific hashtag (filtered by relay set and optional follow list).
     */
    fun getTopicsForHashtag(hashtag: String): List<TopicNote> {
        var list = _topicsByHashtag.value[hashtag.lowercase()] ?: emptyList()
        if (currentRelaySet.isNotEmpty()) {
            list = list.filter { t -> t.relayUrl.isEmpty() || t.relayUrl.split(",").toSet().intersect(currentRelaySet).isNotEmpty() }
        }
        if (isFollowFilterActive() && followFilter != null) {
            list = list.filter { it.author.id.lowercase() in followFilter!! }
        }
        return list
    }

    /**
     * Get hashtag statistics
     */
    fun getHashtagStats(): List<HashtagStats> {
        return _hashtagStats.value
    }

    /**
     * Get all topics as a list sorted by timestamp (filtered by relay set and optional follow list).
     */
    fun getAllTopics(): List<TopicNote> {
        var list = _topics.value.values
        if (currentRelaySet.isNotEmpty()) {
            list = list.filter { t -> t.relayUrl.isEmpty() || t.relayUrl.split(",").toSet().intersect(currentRelaySet).isNotEmpty() }
        }
        if (isFollowFilterActive() && followFilter != null) {
            list = list.filter { it.author.id.lowercase() in followFilter!! }
        }
        return list.sortedByDescending { it.timestamp }
    }

    /**
     * Clear all topics and statistics
     */
    fun clearAllTopics() {
        topicsCache.evictAll()
        _topics.value = emptyMap()
        _hashtagStats.value = emptyList()
        _topicsByHashtag.value = emptyMap()
        synchronized(pendingTopicsLock) { _pendingNewTopics.clear(); _newTopicsCount.value = 0 }
        resetTopicsPagination()

        scope.launch {
            clearPersistentCache()
        }
    }

    /**
     * Refresh topics from relays (fetch recent topics only)
     */
    suspend fun refresh(relayUrls: List<String>? = null) {
        // Reconnect if relays changed
        if (relayUrls != null && relayUrls != connectedRelays) {
            Log.d(TAG, "🔄 Relays changed during refresh, reconnecting")
            connectToRelays(relayUrls)
        }

        // Don't clear cache - just fetch new/recent topics
        fetchTopics(relayUrls, limit = 200)
    }

    /**
     * Full refresh - clear cache and fetch all topics
     */
    suspend fun fullRefresh(relayUrls: List<String>? = null) {
        clearAllTopics()
        fetchTopics(relayUrls, limit = 500)
    }

    /**
     * Load older topics beyond the current feed via relay-side cursor pagination.
     * Creates a temporary subscription with `until` = pagination cursor.
     * Called when user scrolls to the bottom of the hashtag feed.
     */
    fun loadOlderTopics(forHashtag: String? = null) {
        if (_isLoadingOlderTopics.value) return
        if (_topicsPaginationExhausted.value) return

        val relays = subscriptionRelays.ifEmpty { connectedRelays }.ifEmpty { return }

        // Initialize cursor from existing topics if not set
        val topics = _topics.value.values
        if (topicsPaginationCursorMs == 0L && topics.isNotEmpty()) {
            topicsPaginationCursorMs = topics.minOf { it.timestamp }
        }
        if (topicsPaginationCursorMs == 0L) return

        val untilSec = topicsPaginationCursorMs / 1000

        _isLoadingOlderTopics.value = true
        olderTopicsHandle?.cancel()

        // Build filter: kind-11, optional hashtag tag filter
        val filter = if (forHashtag != null) {
            Filter(kinds = listOf(11), limit = TOPICS_PAGE_SIZE, until = untilSec, tags = mapOf("t" to listOf(forHashtag.lowercase())))
        } else {
            Filter(kinds = listOf(11), limit = TOPICS_PAGE_SIZE, until = untilSec)
        }

        Log.d(TAG, "loadOlderTopics: until=$untilSec hashtag=${forHashtag ?: "all"} from ${relays.size} relays")

        val lastEventAt = AtomicLong(0L)
        val eventCount = AtomicInteger(0)
        val oldestReceivedMs = AtomicLong(Long.MAX_VALUE)

        olderTopicsHandle = relayStateMachine.requestTemporarySubscriptionWithRelay(
            relayUrls = relays,
            filters = listOf(filter),
            priority = SubscriptionPriority.HIGH,
        ) { event, relayUrl ->
            if (event.kind == 11) {
                lastEventAt.set(System.currentTimeMillis())
                eventCount.incrementAndGet()
                val eventMs = event.createdAt * 1000L
                oldestReceivedMs.updateAndGet { prev -> minOf(prev, eventMs) }
                // Process through the same handler (adds to topics map, computes stats)
                handleTopicEvent(event, relayUrl)
            }
        }

        // Settle-based wait
        scope.launch {
            val deadline = System.currentTimeMillis() + TOPICS_OLDER_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(300)
                val lastAt = lastEventAt.get()
                if (lastAt > 0) {
                    val quietMs = System.currentTimeMillis() - lastAt
                    if (quietMs >= TOPICS_OLDER_SETTLE_MS) break
                }
            }
            olderTopicsHandle?.cancel()
            olderTopicsHandle = null

            val received = eventCount.get()

            // Update cursor from oldest received event
            val oldest = oldestReceivedMs.get()
            if (oldest < Long.MAX_VALUE && oldest > 0) {
                if (topicsPaginationCursorMs == 0L || oldest < topicsPaginationCursorMs) {
                    topicsPaginationCursorMs = oldest
                }
            }

            if (received == 0) {
                _topicsPaginationExhausted.value = true
                Log.d(TAG, "Topics pagination exhausted: relay returned 0 events")
            } else {
                Log.d(TAG, "Older topics loaded: $received received, total=${_topics.value.size}")
            }
            _isLoadingOlderTopics.value = false
        }
    }

    /** Reset pagination state (call when topics are cleared or subscription changes). */
    private fun resetTopicsPagination() {
        topicsPaginationCursorMs = 0L
        _topicsPaginationExhausted.value = false
        _isLoadingOlderTopics.value = false
        olderTopicsHandle?.cancel()
        olderTopicsHandle = null
    }

    /**
     * Check if currently loading topics
     */
    fun isLoadingTopics(): Boolean = _isLoading.value

    /**
     * Fetch topics for initial load (wide window so existing topics are found).
     * Uses 7-day since; keep fetchRecentTopics for pull-to-refresh (24h only).
     */
    suspend fun fetchInitialTopics(relayUrls: List<String>? = null) {
        val sevenDaysAgo = System.currentTimeMillis() / 1000 - 86400 * 7
        fetchTopics(relayUrls, limit = 100, since = sevenDaysAgo)
    }

    /**
     * Fetch only the most recent topics (last 24 hours).
     * Use for pull-to-refresh / "recent only" updates.
     */
    suspend fun fetchRecentTopics(relayUrls: List<String>? = null) {
        val oneDayAgo = System.currentTimeMillis() / 1000 - 86400
        fetchTopics(relayUrls, limit = 50, since = oneDayAgo)
    }

    /**
     * Get topic from cache by ID (fast LruCache lookup)
     */
    fun getTopicFromCache(topicId: String): TopicNote? {
        return topicsCache.get(topicId)
    }

    /**
     * Get all cached topics
     */
    fun getAllCachedTopics(): List<TopicNote> {
        val cached = mutableListOf<TopicNote>()
        val snapshot = topicsCache.snapshot()
        snapshot.values.forEach { topic ->
            cached.add(topic)
        }
        return cached.sortedByDescending { it.timestamp }
    }

    /**
     * Load topics cache from persistent storage
     */
    private fun loadCacheFromStorage() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cachedJson = sharedPrefs.getString(CACHE_KEY, null)
                    if (cachedJson != null) {
                        val cachedTopics = json.decodeFromString<List<TopicNote>>(cachedJson)

                        // Populate LruCache
                        cachedTopics.forEach { topic ->
                            topicsCache.put(topic.id, topic)
                        }

                        // Populate StateFlow
                        val topicsMap = cachedTopics.associateBy { it.id }
                        _topics.value = topicsMap

                        // Compute initial statistics
                        computeHashtagStatistics()

                        Log.d(TAG, "💾 Loaded ${cachedTopics.size} topics from persistent cache")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to load topics cache: ${e.message}", e)
            }
        }
    }

    /**
     * Save topics cache to persistent storage (called periodically, not per-event)
     */
    private suspend fun saveCacheToStorage() {
        try {
            withContext(Dispatchers.IO) {
                val topicsList = _topics.value.values.toList()
                if (topicsList.isEmpty()) {
                    return@withContext
                }

                val cachedJson = json.encodeToString(topicsList)

                sharedPrefs.edit()
                    .putString(CACHE_KEY, cachedJson)
                    .apply()

                Log.d(TAG, "💾 Saved ${topicsList.size} topics to persistent cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save topics cache: ${e.message}", e)
        }
    }

    /**
     * Start periodic cache saving (every 30 seconds instead of per-event)
     */
    private fun startPeriodicCacheSaving() {
        scope.launch {
            while (true) {
                delay(CACHE_SAVE_INTERVAL)
                if (!cacheDirty) continue
                try {
                    saveCacheToStorage()
                    cacheDirty = false
                } catch (e: Exception) {
                    Log.e(TAG, "\u274C Periodic cache save failed: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Clear persistent cache
     */
    private suspend fun clearPersistentCache() {
        try {
            withContext(Dispatchers.IO) {
                sharedPrefs.edit()
                    .remove(CACHE_KEY)
                    .apply()

                Log.d(TAG, "🧹 Cleared persistent topics cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear persistent cache: ${e.message}", e)
        }
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            memoryCount = topicsCache.size(),
            persistentCount = _topics.value.size,
            maxSize = CACHE_SIZE
        )
    }

    /**
     * Update all topics with the given author (from profile cache). Call when profileUpdated emits.
     */
    fun updateAuthorInTopics(pubkey: String) {
        val author = profileCache.getAuthor(pubkey) ?: return
        val keyLower = pubkey.lowercase()
        val updated = _topics.value.mapValues { (_, topic) ->
            if (topic.author.id.lowercase() == keyLower) topic.copy(author = author) else topic
        }
        _topics.value = updated
        computeHashtagStatistics()
    }

    data class CacheStats(
        val memoryCount: Int,
        val persistentCount: Int,
        val maxSize: Int
    )
}
