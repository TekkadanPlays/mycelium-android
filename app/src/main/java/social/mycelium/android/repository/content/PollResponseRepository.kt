package social.mycelium.android.repository.content

import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
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
import social.mycelium.android.relay.NoOpTemporaryHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * NIP-88 poll response repository.
 * Fetches kind-1018 responses for poll events (kind-1068) and tallies votes per option.
 * Also provides vote submission via Amber signing.
 */
object PollResponseRepository {

    private const val TAG = "PollResponseRepo"
    private const val RESPONSE_KIND = 1018
    private const val POLL_KIND = 1068
    private const val FETCH_TIMEOUT_MS = 5000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Room persistence ────────────────────────────────────────────────

    @Volatile var eventDao: social.mycelium.android.db.EventDao? = null

    /** Persist a kind-1018 event to Room. */
    private fun persistEvent(event: Event, pollId: String) {
        val dao = eventDao ?: return
        scope.launch(Dispatchers.IO) {
            try {
                dao.insert(social.mycelium.android.db.CachedEventEntity(
                    eventId = event.id,
                    kind = event.kind,
                    pubkey = event.pubKey.lowercase(),
                    createdAt = event.createdAt,
                    eventJson = event.toJson(),
                    referencedEventId = pollId
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist poll response ${event.id.take(8)}: ${e.message}")
            }
        }
    }

    /**
     * Load cached kind-1018 events from Room for a poll and build an initial tally.
     * Returns null if no cached events exist.
     */
    private suspend fun loadTallyFromRoom(pollId: String, myPubkey: String?): PollTally? {
        val dao = eventDao ?: return null
        val cached = dao.getByKindAndReference(RESPONSE_KIND, pollId)
        if (cached.isEmpty()) return null

        val votesByOption = mutableMapOf<String, MutableSet<String>>()
        val votersSeen = mutableSetOf<String>()
        val myVotes = mutableSetOf<String>()

        for (entity in cached) {
            val voterPubkey = entity.pubkey
            if (!votersSeen.add(voterPubkey)) continue
            try {
                val event = Event.fromJson(entity.eventJson)
                val responses = event.tags
                    .filter { it.size >= 2 && it[0] == "response" }
                    .map { it[1] }
                for (optionCode in responses) {
                    votesByOption.getOrPut(optionCode) { mutableSetOf() }.add(voterPubkey)
                    if (myPubkey != null && voterPubkey == myPubkey) {
                        myVotes.add(optionCode)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse cached poll response: ${e.message}")
            }
        }

        return PollTally(
            pollId = pollId,
            votesByOption = votesByOption.mapValues { it.value.toSet() },
            myVotedOptions = myVotes,
            totalVoters = votersSeen.size,
            isFetching = false
        )
    }

    /**
     * Vote tally for a single poll: option code → set of voter pubkeys.
     */
    data class PollTally(
        val pollId: String,
        val votesByOption: Map<String, Set<String>> = emptyMap(),
        /** Pubkey of the current user's vote (null if not voted). */
        val myVotedOptions: Set<String> = emptySet(),
        val totalVoters: Int = 0,
        val isFetching: Boolean = false,
        /** Option code → human-readable label (populated from poll event tags). */
        val optionLabels: Map<String, String> = emptyMap()
    )

    /** Poll ID → tally. UI observes this. */
    private val _tallies = MutableStateFlow<Map<String, PollTally>>(emptyMap())
    val tallies: StateFlow<Map<String, PollTally>> = _tallies.asStateFlow()

    /** Track in-flight fetches to avoid duplicates. */
    private val inFlightPolls = ConcurrentHashMap.newKeySet<String>()

    /** Track polls we've already fetched so we don't re-fetch on recomposition. */
    private val fetchedPolls = ConcurrentHashMap.newKeySet<String>()

    /** Last successful fetch timestamp (epoch seconds) per poll, for delta refresh. */
    private val lastFetchTimeSec = ConcurrentHashMap<String, Long>()

    /**
     * Fetch vote tallies for a poll. Idempotent — skips if already fetched or in-flight.
     * @param pollId The event ID of the kind-1068 poll.
     * @param relayUrls Relays to query (poll's relay tags + user relays).
     * @param myPubkey Current user's hex pubkey (to detect own vote).
     */
    fun fetchTally(pollId: String, relayUrls: List<String>, myPubkey: String?) {
        // If already fetched, do a delta refresh instead of full re-fetch
        if (pollId in fetchedPolls) {
            refreshTally(pollId, relayUrls, myPubkey)
            return
        }
        if (!inFlightPolls.add(pollId)) return
        if (relayUrls.isEmpty()) { inFlightPolls.remove(pollId); return }

        val fetchStartSec = System.currentTimeMillis() / 1000

        // Mark as fetching
        _tallies.value = _tallies.value + (pollId to PollTally(pollId, isFetching = true))

        scope.launch {
            try {
                // ── Phase 1: Seed from Room cache (instant, before relay fetch) ──
                val roomTally = loadTallyFromRoom(pollId, myPubkey)
                if (roomTally != null) {
                    _tallies.value = _tallies.value + (pollId to roomTally.copy(isFetching = true))
                    Log.d(TAG, "Poll $pollId: seeded ${roomTally.totalVoters} voters from Room cache")
                }

                // ── Phase 2: Fetch from relays and persist to Room ──
                val votesByOption = ConcurrentHashMap<String, MutableSet<String>>()
                val votersSeen = ConcurrentHashMap.newKeySet<String>()
                val myVotes = ConcurrentHashMap.newKeySet<String>()

                val filter = Filter(
                    kinds = listOf(RESPONSE_KIND),
                    tags = mapOf("e" to listOf(pollId)),
                    limit = 500
                )
                val stateMachine = RelayConnectionStateMachine.getInstance()
                stateMachine.requestOneShotSubscription(
                    relayUrls, filter, priority = SubscriptionPriority.LOW,
                    settleMs = 500L, maxWaitMs = FETCH_TIMEOUT_MS
                ) { event ->
                    if (event.kind != RESPONSE_KIND) return@requestOneShotSubscription
                    val voterPubkey = event.pubKey
                    if (!votersSeen.add(voterPubkey)) return@requestOneShotSubscription

                    val responses = event.tags
                        .filter { it.size >= 2 && it[0] == "response" }
                        .map { it[1] }
                    for (optionCode in responses) {
                        votesByOption.getOrPut(optionCode) { ConcurrentHashMap.newKeySet() }.add(voterPubkey)
                        if (myPubkey != null && voterPubkey == myPubkey) {
                            myVotes.add(optionCode)
                        }
                    }
                    // Persist every relay event to Room
                    persistEvent(event, pollId)
                }
                delay(FETCH_TIMEOUT_MS + 500L)

                val tally = PollTally(
                    pollId = pollId,
                    votesByOption = votesByOption.mapValues { it.value.toSet() },
                    myVotedOptions = myVotes.toSet(),
                    totalVoters = votersSeen.size,
                    isFetching = false
                )
                _tallies.value = _tallies.value + (pollId to tally)
                fetchedPolls.add(pollId)
                lastFetchTimeSec[pollId] = fetchStartSec
                Log.d(TAG, "Poll $pollId: ${votersSeen.size} voters, ${votesByOption.size} options with votes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch tally for $pollId: ${e.message}")
                _tallies.value = _tallies.value + (pollId to PollTally(pollId, isFetching = false))
            } finally {
                inFlightPolls.remove(pollId)
            }
        }
    }

    /**
     * Delta refresh: fetch only poll responses newer than our last fetch time,
     * then merge them into the existing tally. Called when a previously-fetched
     * poll re-enters the viewport (catching votes missed while off-screen).
     */
    private fun refreshTally(pollId: String, relayUrls: List<String>, myPubkey: String?) {
        if (!inFlightPolls.add(pollId)) return
        val sinceSec = lastFetchTimeSec[pollId] ?: run {
            inFlightPolls.remove(pollId)
            return
        }
        if (relayUrls.isEmpty()) { inFlightPolls.remove(pollId); return }
        val refreshStartSec = System.currentTimeMillis() / 1000

        scope.launch {
            try {
                val current = _tallies.value[pollId] ?: PollTally(pollId)
                val existingVoters = current.votesByOption.values.flatten().toMutableSet()
                val newVotesByOption = ConcurrentHashMap<String, MutableSet<String>>()
                val newMyVotes = ConcurrentHashMap.newKeySet<String>()
                var newVoterCount = 0

                val filter = Filter(
                    kinds = listOf(RESPONSE_KIND),
                    tags = mapOf("e" to listOf(pollId)),
                    since = sinceSec - 5, // 5s overlap to catch edge cases
                    limit = 200
                )
                RelayConnectionStateMachine.getInstance().requestOneShotSubscription(
                    relayUrls, filter, priority = SubscriptionPriority.LOW,
                    settleMs = 400L, maxWaitMs = 4000L
                ) { event ->
                    if (event.kind != RESPONSE_KIND) return@requestOneShotSubscription
                    val voterPubkey = event.pubKey
                    if (voterPubkey in existingVoters) return@requestOneShotSubscription
                    if (!existingVoters.add(voterPubkey)) return@requestOneShotSubscription // dedup within delta

                    val responses = event.tags
                        .filter { it.size >= 2 && it[0] == "response" }
                        .map { it[1] }
                    for (optionCode in responses) {
                        newVotesByOption.getOrPut(optionCode) { ConcurrentHashMap.newKeySet() }.add(voterPubkey)
                        if (myPubkey != null && voterPubkey == myPubkey) newMyVotes.add(optionCode)
                    }
                    newVoterCount++
                    persistEvent(event, pollId)
                }
                delay(4500L)

                if (newVoterCount > 0) {
                    val mergedVotes = current.votesByOption.toMutableMap()
                    for ((option, pubkeys) in newVotesByOption) {
                        mergedVotes[option] = (mergedVotes[option] ?: emptySet()) + pubkeys
                    }
                    val mergedMyVotes = current.myVotedOptions + newMyVotes
                    val updated = current.copy(
                        votesByOption = mergedVotes,
                        myVotedOptions = mergedMyVotes,
                        totalVoters = current.totalVoters + newVoterCount
                    )
                    _tallies.value = _tallies.value + (pollId to updated)
                    Log.d(TAG, "Poll $pollId delta refresh: +$newVoterCount new voters")
                } else {
                    Log.d(TAG, "Poll $pollId delta refresh: no new votes since $sinceSec")
                }
                lastFetchTimeSec[pollId] = refreshStartSec
            } catch (e: Exception) {
                Log.w(TAG, "Poll $pollId delta refresh failed: ${e.message}")
            } finally {
                inFlightPolls.remove(pollId)
            }
        }
    }

    /**
     * Build a kind-1018 poll response event (unsigned).
     * Caller must sign via Amber and publish.
     * @return Triple of (kind, content, tags) for event building.
     */
    fun buildResponseEvent(
        pollId: String,
        pollAuthorPubkey: String,
        selectedOptions: Set<String>,
        pollRelayHint: String? = null
    ): Triple<Int, String, List<List<String>>> {
        val tags = mutableListOf<List<String>>()
        // Reference the poll event
        if (pollRelayHint != null) {
            tags.add(listOf("e", pollId, pollRelayHint))
        } else {
            tags.add(listOf("e", pollId))
        }
        // Notify poll author
        tags.add(listOf("p", pollAuthorPubkey))
        // Selected options
        for (option in selectedOptions) {
            tags.add(listOf("response", option))
        }
        // Alt tag
        tags.add(listOf("alt", "Poll Response"))
        return Triple(RESPONSE_KIND, "", tags)
    }

    /**
     * Record a local vote optimistically (before relay confirmation).
     */
    fun recordLocalVote(pollId: String, myPubkey: String, selectedOptions: Set<String>) {
        val current = _tallies.value[pollId] ?: PollTally(pollId)
        val updatedVotes = current.votesByOption.toMutableMap()
        for (option in selectedOptions) {
            updatedVotes[option] = (updatedVotes[option] ?: emptySet()) + myPubkey
        }
        val updated = current.copy(
            votesByOption = updatedVotes,
            myVotedOptions = current.myVotedOptions + selectedOptions,
            totalVoters = current.totalVoters + 1
        )
        _tallies.value = _tallies.value + (pollId to updated)
    }

    /** Track active live subscriptions so we don't duplicate them. */
    private val liveSubscriptions = ConcurrentHashMap<String, TemporarySubscriptionHandle>()

    /**
     * Open a persistent subscription for new poll responses (kind-1018) arriving after now.
     * Call from a composable's DisposableEffect; cancel the returned handle on dispose.
     * The initial historical fetch is still done by [fetchTally]; this only catches new votes.
     */
    fun subscribeLive(pollId: String, relayUrls: List<String>, myPubkey: String?): TemporarySubscriptionHandle {
        if (relayUrls.isEmpty()) return NoOpTemporaryHandle
        // Don't duplicate if already subscribed
        liveSubscriptions[pollId]?.let { return it }

        val nowSec = System.currentTimeMillis() / 1000
        val filter = Filter(
            kinds = listOf(RESPONSE_KIND),
            tags = mapOf("e" to listOf(pollId)),
            since = nowSec
        )
        val stateMachine = RelayConnectionStateMachine.getInstance()
        val handle = stateMachine.requestTemporarySubscription(
            relayUrls, filter, priority = SubscriptionPriority.LOW
        ) { event ->
            if (event.kind != RESPONSE_KIND) return@requestTemporarySubscription
            val voterPubkey = event.pubKey
            val responses = event.tags
                .filter { it.size >= 2 && it[0] == "response" }
                .map { it[1] }
            if (responses.isEmpty()) return@requestTemporarySubscription

            // Incrementally update the tally
            val current = _tallies.value[pollId] ?: PollTally(pollId)
            // Check if voter already counted
            val existingVoters = current.votesByOption.values.flatten().toSet()
            if (voterPubkey in existingVoters) return@requestTemporarySubscription

            val updatedVotes = current.votesByOption.toMutableMap()
            val updatedMyVotes = current.myVotedOptions.toMutableSet()
            for (optionCode in responses) {
                updatedVotes[optionCode] = (updatedVotes[optionCode] ?: emptySet()) + voterPubkey
                if (myPubkey != null && voterPubkey == myPubkey) {
                    updatedMyVotes.add(optionCode)
                }
            }
            val updated = current.copy(
                votesByOption = updatedVotes,
                myVotedOptions = updatedMyVotes,
                totalVoters = current.totalVoters + 1
            )
            _tallies.value = _tallies.value + (pollId to updated)
            persistEvent(event, pollId)
            Log.d(TAG, "Live vote on poll ${pollId.take(8)}: +1 voter (${voterPubkey.take(8)})")
        }
        liveSubscriptions[pollId] = handle
        Log.d(TAG, "Live subscription opened for poll ${pollId.take(8)}")
        return handle
    }

    /** Cancel a live subscription (call on composable dispose). */
    fun cancelLive(pollId: String) {
        liveSubscriptions.remove(pollId)?.cancel()
        // Update timestamp so the next delta refresh starts from when live coverage ended
        if (pollId in fetchedPolls) {
            lastFetchTimeSec[pollId] = System.currentTimeMillis() / 1000
        }
    }

    /** Set option labels on an existing tally (called from PollBlock which has PollData). */
    fun setOptionLabels(pollId: String, labels: Map<String, String>) {
        val current = _tallies.value[pollId] ?: return
        if (current.optionLabels.isNotEmpty()) return // already set
        _tallies.value = _tallies.value + (pollId to current.copy(optionLabels = labels))
    }

    fun clear() {
        _tallies.value = emptyMap()
        inFlightPolls.clear()
        fetchedPolls.clear()
        lastFetchTimeSec.clear()
    }
}
