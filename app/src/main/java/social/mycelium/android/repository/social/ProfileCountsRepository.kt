package social.mycelium.android.repository.social

import social.mycelium.android.debug.MLog
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import java.util.concurrent.ConcurrentHashMap
import social.mycelium.android.repository.relay.Nip65RelayListRepository

/**
 * Fetches profile statistics (following count, follower count) from indexer relays
 * via RelayConnectionStateMachine. Deduplicates across multiple relays and caches
 * results per pubkey with TTL.
 *
 * - **Following**: fetches the target user's kind-3 (contact list) and counts unique p-tags.
 * - **Followers**: queries kind-3 events that have a p-tag pointing to the target user.
 *   Indexer relays support this query; we collect unique authors to get the count.
 */
object ProfileCountsRepository {

    private const val TAG = "ProfileCountsRepo"

    /** How long to wait for kind-3 (following) responses. */
    private const val FOLLOWING_TIMEOUT_MS = 4_000L
    /** How long to collect follower kind-3 events. */
    private const val FOLLOWER_TIMEOUT_MS = 6_000L
    /** After first event arrives, settle for this long before returning. */
    private const val SETTLE_MS = 800L
    /** Cache TTL: 5 minutes. */
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    /** Max follower events to collect (prevent memory blow-up for popular accounts). */
    private const val MAX_FOLLOWER_EVENTS = 50_000

    data class ProfileCounts(
        val followingCount: Int?,
        val followerCount: Int?,
        val isLoadingFollowing: Boolean = false,
        val isLoadingFollowers: Boolean = false,
    )

    private data class CacheEntry(
        val counts: ProfileCounts,
        val timestampMs: Long,
    )

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
                CoroutineExceptionHandler { _, t -> MLog.e(TAG, "Coroutine failed: ${t.message}", t) }
    )

    /** Per-pubkey counts exposed to UI. */
    private val _countsMap = MutableStateFlow<Map<String, ProfileCounts>>(emptyMap())
    val countsMap: StateFlow<Map<String, ProfileCounts>> = _countsMap.asStateFlow()

    /** In-memory cache with TTL. */
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** Active subscription handles — cancel on re-fetch or cleanup. */
    private val activeHandles = ConcurrentHashMap<String, MutableList<TemporarySubscriptionHandle>>()

    /**
     * Fetch profile counts for [pubkeyHex]. Uses cache if fresh, otherwise
     * fires subscriptions to indexer relays via RelayConnectionStateMachine.
     *
     * @param userRelayUrls The user's configured relay URLs (used as supplement).
     */
    fun fetchCounts(pubkeyHex: String, userRelayUrls: List<String> = emptyList()) {
        // Check cache
        val cached = cache[pubkeyHex]
        if (cached != null && System.currentTimeMillis() - cached.timestampMs < CACHE_TTL_MS) {
            // Emit cached value
            updateCounts(pubkeyHex, cached.counts)
            return
        }

        // Cancel any in-flight fetches for this pubkey
        cancelFetches(pubkeyHex)

        // Mark loading
        updateCounts(pubkeyHex, ProfileCounts(
            followingCount = null, followerCount = null,
            isLoadingFollowing = true, isLoadingFollowers = true
        ))

        val indexerRelays = Nip65RelayListRepository.getIndexerRelayUrls(limit = 5)
        val allRelays = (indexerRelays + userRelayUrls).distinct().filter { it.isNotBlank() }
        if (allRelays.isEmpty()) {
            MLog.w(TAG, "No relays available for profile counts of ${pubkeyHex.take(8)}")
            updateCounts(pubkeyHex, ProfileCounts(null, null))
            return
        }

        scope.launch { fetchFollowingCount(pubkeyHex, allRelays) }
        scope.launch { fetchFollowerCount(pubkeyHex, indexerRelays.ifEmpty { allRelays }) }
    }

    /**
     * Fetch following count: get the target user's kind-3 and count unique p-tags.
     * First checks ContactListRepository cache to avoid redundant network fetch.
     */
    private suspend fun fetchFollowingCount(pubkeyHex: String, relayUrls: List<String>) {
        try {
            // Fast path: reuse ContactListRepository cache if available
            val cached = ContactListRepository.getCachedFollowList(pubkeyHex)
            if (cached != null) {
                MLog.d(TAG, "Following count for ${pubkeyHex.take(8)}: ${cached.size} (from ContactListRepository cache)")
                mergeCounts(pubkeyHex, followingCount = cached.size, isLoadingFollowing = false)
                return
            }

            val filter = Filter(
                kinds = listOf(3),
                authors = listOf(pubkeyHex),
                limit = 5 // multiple relays may return different versions; we pick newest
            )

            val collected = java.util.concurrent.CopyOnWriteArrayList<Event>()
            val firstEventAt = java.util.concurrent.atomic.AtomicLong(0)

            val handle = RelayConnectionStateMachine.getInstance()
                .requestTemporarySubscription(relayUrls, filter, priority = SubscriptionPriority.BACKGROUND) { event ->
                    if (event.kind == 3 && event.pubKey == pubkeyHex) {
                        collected.add(event)
                        firstEventAt.compareAndSet(0, System.currentTimeMillis())
                    }
                }
            trackHandle(pubkeyHex, handle)

            // Wait with early-exit on settle
            val deadline = System.currentTimeMillis() + FOLLOWING_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(100)
                val firstAt = firstEventAt.get()
                if (firstAt > 0 && System.currentTimeMillis() - firstAt >= SETTLE_MS) break
            }
            handle.cancel()

            val best = collected.maxByOrNull { it.createdAt }
            val count = if (best != null) {
                best.tags
                    .filter { it.size >= 2 && it[0] == "p" }
                    .map { it[1].lowercase() }
                    .distinct()
                    .count()
            } else null

            MLog.d(TAG, "Following count for ${pubkeyHex.take(8)}: $count (from ${relayUrls.size} relays, ${collected.size} events)")
            mergeCounts(pubkeyHex, followingCount = count, isLoadingFollowing = false)
        } catch (e: Exception) {
            MLog.e(TAG, "Following count fetch failed for ${pubkeyHex.take(8)}: ${e.message}")
            mergeCounts(pubkeyHex, followingCount = null, isLoadingFollowing = false)
        }
    }

    /** Interval for progressive follower count UI updates. */
    private const val FOLLOWER_PROGRESS_INTERVAL_MS = 500L

    /**
     * Fetch follower count: query kind-3 events with p-tag = target user.
     * Indexer relays support tag-based queries. We collect unique authors.
     * Emits progressive updates so the UI count ticks up live as events stream in.
     */
    private suspend fun fetchFollowerCount(pubkeyHex: String, relayUrls: List<String>) {
        try {
            if (relayUrls.isEmpty()) {
                MLog.w(TAG, "No indexer relays for follower count of ${pubkeyHex.take(8)}")
                mergeCounts(pubkeyHex, followerCount = null, isLoadingFollowers = false)
                return
            }

            val filter = Filter(
                kinds = listOf(3),
                tags = mapOf("p" to listOf(pubkeyHex)),
                limit = MAX_FOLLOWER_EVENTS
            )

            val uniqueAuthors = ConcurrentHashMap.newKeySet<String>()
            val lastEventAt = java.util.concurrent.atomic.AtomicLong(0)
            var lastEmittedCount = 0

            val handle = RelayConnectionStateMachine.getInstance()
                .requestTemporarySubscription(relayUrls, filter, priority = SubscriptionPriority.LOW) { event ->
                    if (event.kind == 3) {
                        uniqueAuthors.add(event.pubKey.lowercase())
                        lastEventAt.set(System.currentTimeMillis())
                    }
                }
            trackHandle(pubkeyHex, handle)

            // Poll loop with progressive UI updates
            val deadline = System.currentTimeMillis() + FOLLOWER_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(FOLLOWER_PROGRESS_INTERVAL_MS)

                // Emit progressive count if it changed
                val currentSize = uniqueAuthors.size
                if (currentSize > lastEmittedCount) {
                    lastEmittedCount = currentSize
                    mergeCounts(pubkeyHex, followerCount = currentSize, isLoadingFollowers = true)
                }

                // Settle: if stream has gone quiet (no new events for SETTLE_MS*2), we're done
                val lastAt = lastEventAt.get()
                if (lastAt > 0 && currentSize > 0) {
                    val quietMs = System.currentTimeMillis() - lastAt
                    if (quietMs >= SETTLE_MS * 2) break
                }
                // Cap
                if (currentSize >= MAX_FOLLOWER_EVENTS) break
            }
            handle.cancel()

            val count = uniqueAuthors.size.takeIf { it > 0 }
            MLog.d(TAG, "Follower count for ${pubkeyHex.take(8)}: $count (from ${relayUrls.size} indexer relays)")
            mergeCounts(pubkeyHex, followerCount = count, isLoadingFollowers = false)
        } catch (e: Exception) {
            MLog.e(TAG, "Follower count fetch failed for ${pubkeyHex.take(8)}: ${e.message}")
            mergeCounts(pubkeyHex, followerCount = null, isLoadingFollowers = false)
        }
    }

    /** Merge partial results into the current counts for a pubkey. */
    private fun mergeCounts(
        pubkeyHex: String,
        followingCount: Int? = null,
        followerCount: Int? = null,
        isLoadingFollowing: Boolean? = null,
        isLoadingFollowers: Boolean? = null,
    ) {
        val current = _countsMap.value[pubkeyHex] ?: ProfileCounts(null, null)
        val updated = current.copy(
            followingCount = followingCount ?: current.followingCount,
            followerCount = followerCount ?: current.followerCount,
            isLoadingFollowing = isLoadingFollowing ?: current.isLoadingFollowing,
            isLoadingFollowers = isLoadingFollowers ?: current.isLoadingFollowers,
        )
        updateCounts(pubkeyHex, updated)

        // Cache when both are done loading
        if (!updated.isLoadingFollowing && !updated.isLoadingFollowers) {
            cache[pubkeyHex] = CacheEntry(updated, System.currentTimeMillis())
        }
    }

    private fun updateCounts(pubkeyHex: String, counts: ProfileCounts) {
        _countsMap.value = _countsMap.value + (pubkeyHex to counts)
    }

    private fun trackHandle(pubkeyHex: String, handle: TemporarySubscriptionHandle) {
        activeHandles.getOrPut(pubkeyHex) { mutableListOf() }.add(handle)
    }

    private fun cancelFetches(pubkeyHex: String) {
        activeHandles.remove(pubkeyHex)?.forEach { it.cancel() }
    }

    /** Clear all caches and cancel all in-flight fetches. */
    fun clear() {
        activeHandles.values.flatten().forEach { it.cancel() }
        activeHandles.clear()
        cache.clear()
        _countsMap.value = emptyMap()
    }

    /** Invalidate cache for a specific pubkey (e.g. after follow/unfollow). */
    fun invalidate(pubkeyHex: String) {
        cache.remove(pubkeyHex)
        cancelFetches(pubkeyHex)
    }
}
