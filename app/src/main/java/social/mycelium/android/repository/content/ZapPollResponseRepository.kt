package social.mycelium.android.repository.content

import social.mycelium.android.debug.MLog
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
 * Zap poll response repository.
 * Fetches kind-9735 zap receipts for zap poll events (kind-6969) and tallies
 * sats per option. Votes in zap polls are zaps whose zap request contains
 * a ["poll_option", "<index>"] tag.
 */
object ZapPollResponseRepository {

    private const val TAG = "ZapPollResponseRepo"
    private const val ZAP_RECEIPT_KIND = 9735
    private const val FETCH_TIMEOUT_MS = 6000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Room persistence ────────────────────────────────────────────────

    @Volatile var eventDao: social.mycelium.android.db.EventDao? = null

    /** Persist a kind-9735 event to Room. */
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
                MLog.w(TAG, "Failed to persist zap receipt ${event.id.take(8)}: ${e.message}")
            }
        }
    }

    /**
     * Load cached kind-9735 events from Room for a zap poll and build an initial tally.
     * Returns null if no cached events exist.
     */
    private suspend fun loadTallyFromRoom(pollId: String, myPubkey: String?): ZapPollTally? {
        val dao = eventDao ?: return null
        val cached = dao.getByKindAndReference(ZAP_RECEIPT_KIND, pollId)
        if (cached.isEmpty()) return null

        val votes = mutableMapOf<Int, MutableList<ZapVote>>()
        val votersSeen = mutableSetOf<String>()
        val myVotes = mutableSetOf<Int>()
        var totalSats = 0L

        for (entity in cached) {
            try {
                val event = Event.fromJson(entity.eventJson)
                val descriptionTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }
                val zapRequestJson = descriptionTag?.getOrNull(1) ?: continue
                val zapRequest = Event.fromJson(zapRequestJson)
                val voterPubkey = zapRequest.pubKey
                if (!votersSeen.add(voterPubkey)) continue

                val pollOptionTag = zapRequest.tags.firstOrNull { it.size >= 2 && it[0] == "poll_option" }
                val optionIndex = pollOptionTag?.getOrNull(1)?.toIntOrNull() ?: continue
                val bolt11Tag = event.tags.firstOrNull { it.size >= 2 && it[0] == "bolt11" }
                val amountSats = bolt11Tag?.getOrNull(1)?.let { parseBolt11Amount(it) } ?: 0L

                val vote = ZapVote(voterPubkey, amountSats, optionIndex)
                votes.getOrPut(optionIndex) { mutableListOf() }.add(vote)
                totalSats += amountSats
                if (myPubkey != null && voterPubkey == myPubkey) myVotes.add(optionIndex)
            } catch (e: Exception) {
                MLog.w(TAG, "Failed to parse cached zap receipt: ${e.message}")
            }
        }

        return ZapPollTally(
            pollId = pollId,
            votesByOption = votes.mapValues { it.value.toList() },
            totalSats = totalSats,
            totalVoters = votersSeen.size,
            myVotedOptions = myVotes,
            isFetching = false
        )
    }

    /**
     * Zap vote for a single option from one voter.
     */
    data class ZapVote(
        val voterPubkey: String,
        val amountSats: Long,
        val optionIndex: Int
    )

    /**
     * Tally for a single zap poll.
     */
    data class ZapPollTally(
        val pollId: String,
        /** Option index → list of zap votes (voter pubkey + amount). */
        val votesByOption: Map<Int, List<ZapVote>> = emptyMap(),
        /** Total sats zapped across all options. */
        val totalSats: Long = 0,
        /** Total unique voters. */
        val totalVoters: Int = 0,
        /** Whether the current user has already voted (zapped an option). */
        val myVotedOptions: Set<Int> = emptySet(),
        val isFetching: Boolean = false,
        /** Option index → human-readable label. */
        val optionLabels: Map<Int, String> = emptyMap()
    )

    private val _tallies = MutableStateFlow<Map<String, ZapPollTally>>(emptyMap())
    val tallies: StateFlow<Map<String, ZapPollTally>> = _tallies.asStateFlow()

    private val inFlightPolls = ConcurrentHashMap.newKeySet<String>()
    private val fetchedPolls = ConcurrentHashMap.newKeySet<String>()

    /** Last successful fetch timestamp (epoch seconds) per poll, for delta refresh. */
    private val lastFetchTimeSec = ConcurrentHashMap<String, Long>()

    /**
     * Fetch zap receipt tallies for a zap poll. Idempotent.
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
        _tallies.value = _tallies.value + (pollId to ZapPollTally(pollId, isFetching = true))

        scope.launch {
            try {
                // ── Phase 1: Seed from Room cache (instant, before relay fetch) ──
                val roomTally = loadTallyFromRoom(pollId, myPubkey)
                if (roomTally != null) {
                    _tallies.value = _tallies.value + (pollId to roomTally.copy(isFetching = true))
                    MLog.d(TAG, "ZapPoll $pollId: seeded ${roomTally.totalVoters} voters from Room cache")
                }

                // ── Phase 2: Fetch from relays and persist to Room ──
                val votes = ConcurrentHashMap<Int, MutableList<ZapVote>>()
                val votersSeen = ConcurrentHashMap.newKeySet<String>()
                val myVotes = ConcurrentHashMap.newKeySet<Int>()
                var totalSats = 0L

                val filter = Filter(
                    kinds = listOf(ZAP_RECEIPT_KIND),
                    tags = mapOf("e" to listOf(pollId)),
                    limit = 500
                )
                val stateMachine = RelayConnectionStateMachine.getInstance()
                stateMachine.requestOneShotSubscription(
                    relayUrls, filter, priority = SubscriptionPriority.LOW,
                    settleMs = 500L, maxWaitMs = FETCH_TIMEOUT_MS
                ) { event ->
                    if (event.kind != ZAP_RECEIPT_KIND) return@requestOneShotSubscription

                    val descriptionTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }
                    val zapRequestJson = descriptionTag?.getOrNull(1) ?: return@requestOneShotSubscription

                    try {
                        val zapRequest = Event.fromJson(zapRequestJson)
                        val voterPubkey = zapRequest.pubKey

                        val pollOptionTag = zapRequest.tags.firstOrNull { it.size >= 2 && it[0] == "poll_option" }
                        val optionIndex = pollOptionTag?.getOrNull(1)?.toIntOrNull() ?: return@requestOneShotSubscription

                        val bolt11Tag = event.tags.firstOrNull { it.size >= 2 && it[0] == "bolt11" }
                        val amountSats = bolt11Tag?.getOrNull(1)?.let { parseBolt11Amount(it) } ?: 0L

                        if (!votersSeen.add(voterPubkey)) return@requestOneShotSubscription

                        val vote = ZapVote(voterPubkey, amountSats, optionIndex)
                        votes.getOrPut(optionIndex) { mutableListOf() }.add(vote)
                        totalSats += amountSats

                        if (myPubkey != null && voterPubkey == myPubkey) {
                            myVotes.add(optionIndex)
                        }
                    } catch (e: Exception) {
                        MLog.w(TAG, "Failed to parse zap request for poll $pollId: ${e.message}")
                    }
                    // Persist every relay event to Room
                    persistEvent(event, pollId)
                }

                delay(FETCH_TIMEOUT_MS + 500L)

                val tally = ZapPollTally(
                    pollId = pollId,
                    votesByOption = votes.mapValues { it.value.toList() },
                    totalSats = totalSats,
                    totalVoters = votersSeen.size,
                    myVotedOptions = myVotes.toSet(),
                    isFetching = false
                )
                _tallies.value = _tallies.value + (pollId to tally)
                fetchedPolls.add(pollId)
                lastFetchTimeSec[pollId] = fetchStartSec
                MLog.d(TAG, "ZapPoll $pollId: ${votersSeen.size} voters, ${totalSats} sats total")
            } catch (e: Exception) {
                MLog.e(TAG, "Failed to fetch zap poll tally for $pollId: ${e.message}")
                _tallies.value = _tallies.value + (pollId to ZapPollTally(pollId, isFetching = false))
            } finally {
                inFlightPolls.remove(pollId)
            }
        }
    }

    /**
     * Delta refresh: fetch only zap receipts newer than our last fetch time,
     * then merge them into the existing tally. Called when a previously-fetched
     * zap poll re-enters the viewport.
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
                val current = _tallies.value[pollId] ?: ZapPollTally(pollId)
                val existingVoters = current.votesByOption.values.flatten().map { it.voterPubkey }.toMutableSet()
                val newVotes = ConcurrentHashMap<Int, MutableList<ZapVote>>()
                val newMyVotes = ConcurrentHashMap.newKeySet<Int>()
                var newVoterCount = 0
                var newSats = 0L

                val filter = Filter(
                    kinds = listOf(ZAP_RECEIPT_KIND),
                    tags = mapOf("e" to listOf(pollId)),
                    since = sinceSec - 5,
                    limit = 200
                )
                RelayConnectionStateMachine.getInstance().requestOneShotSubscription(
                    relayUrls, filter, priority = SubscriptionPriority.LOW,
                    settleMs = 400L, maxWaitMs = 4000L
                ) { event ->
                    if (event.kind != ZAP_RECEIPT_KIND) return@requestOneShotSubscription
                    val descriptionTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }
                    val zapRequestJson = descriptionTag?.getOrNull(1) ?: return@requestOneShotSubscription
                    try {
                        val zapRequest = Event.fromJson(zapRequestJson)
                        val voterPubkey = zapRequest.pubKey
                        if (voterPubkey in existingVoters) return@requestOneShotSubscription
                        if (!existingVoters.add(voterPubkey)) return@requestOneShotSubscription

                        val pollOptionTag = zapRequest.tags.firstOrNull { it.size >= 2 && it[0] == "poll_option" }
                        val optionIndex = pollOptionTag?.getOrNull(1)?.toIntOrNull() ?: return@requestOneShotSubscription
                        val bolt11Tag = event.tags.firstOrNull { it.size >= 2 && it[0] == "bolt11" }
                        val amountSats = bolt11Tag?.getOrNull(1)?.let { parseBolt11Amount(it) } ?: 0L

                        val vote = ZapVote(voterPubkey, amountSats, optionIndex)
                        newVotes.getOrPut(optionIndex) { mutableListOf() }.add(vote)
                        newSats += amountSats
                        newVoterCount++
                        if (myPubkey != null && voterPubkey == myPubkey) newMyVotes.add(optionIndex)
                    } catch (_: Exception) {}
                    persistEvent(event, pollId)
                }
                delay(4500L)

                if (newVoterCount > 0) {
                    val mergedVotes = current.votesByOption.toMutableMap()
                    for ((option, votes) in newVotes) {
                        mergedVotes[option] = (mergedVotes[option] ?: emptyList()) + votes
                    }
                    val updated = current.copy(
                        votesByOption = mergedVotes,
                        totalSats = current.totalSats + newSats,
                        totalVoters = current.totalVoters + newVoterCount,
                        myVotedOptions = current.myVotedOptions + newMyVotes
                    )
                    _tallies.value = _tallies.value + (pollId to updated)
                    MLog.d(TAG, "ZapPoll $pollId delta refresh: +$newVoterCount voters, +$newSats sats")
                } else {
                    MLog.d(TAG, "ZapPoll $pollId delta refresh: no new votes since $sinceSec")
                }
                lastFetchTimeSec[pollId] = refreshStartSec
            } catch (e: Exception) {
                MLog.w(TAG, "ZapPoll $pollId delta refresh failed: ${e.message}")
            } finally {
                inFlightPolls.remove(pollId)
            }
        }
    }

    /** Record a local vote optimistically. */
    fun recordLocalVote(pollId: String, myPubkey: String, optionIndex: Int, amountSats: Long) {
        val current = _tallies.value[pollId] ?: ZapPollTally(pollId)
        val updatedVotes = current.votesByOption.toMutableMap()
        val vote = ZapVote(myPubkey, amountSats, optionIndex)
        updatedVotes[optionIndex] = (updatedVotes[optionIndex] ?: emptyList()) + vote
        val updated = current.copy(
            votesByOption = updatedVotes,
            totalSats = current.totalSats + amountSats,
            totalVoters = current.totalVoters + 1,
            myVotedOptions = current.myVotedOptions + optionIndex
        )
        _tallies.value = _tallies.value + (pollId to updated)
    }

    /** Track active live subscriptions so we don't duplicate them. */
    private val liveSubscriptions = ConcurrentHashMap<String, TemporarySubscriptionHandle>()

    /**
     * Open a persistent subscription for new zap receipts (kind-9735) arriving after now.
     * Call from a composable's DisposableEffect; cancel the returned handle on dispose.
     * The initial historical fetch is still done by [fetchTally]; this only catches new zap votes.
     */
    fun subscribeLive(pollId: String, relayUrls: List<String>, myPubkey: String?): TemporarySubscriptionHandle {
        if (relayUrls.isEmpty()) return NoOpTemporaryHandle
        liveSubscriptions[pollId]?.let { return it }

        val nowSec = System.currentTimeMillis() / 1000
        val filter = Filter(
            kinds = listOf(ZAP_RECEIPT_KIND),
            tags = mapOf("e" to listOf(pollId)),
            since = nowSec
        )
        val stateMachine = RelayConnectionStateMachine.getInstance()
        val handle = stateMachine.requestTemporarySubscription(
            relayUrls, filter, priority = SubscriptionPriority.LOW
        ) { event ->
            if (event.kind != ZAP_RECEIPT_KIND) return@requestTemporarySubscription
            val descriptionTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }
            val zapRequestJson = descriptionTag?.getOrNull(1) ?: return@requestTemporarySubscription
            try {
                val zapRequest = Event.fromJson(zapRequestJson)
                val voterPubkey = zapRequest.pubKey
                val pollOptionTag = zapRequest.tags.firstOrNull { it.size >= 2 && it[0] == "poll_option" }
                val optionIndex = pollOptionTag?.getOrNull(1)?.toIntOrNull() ?: return@requestTemporarySubscription
                val bolt11Tag = event.tags.firstOrNull { it.size >= 2 && it[0] == "bolt11" }
                val amountSats = bolt11Tag?.getOrNull(1)?.let { parseBolt11Amount(it) } ?: 0L

                val current = _tallies.value[pollId] ?: ZapPollTally(pollId)
                val existingVoters = current.votesByOption.values.flatten().map { it.voterPubkey }.toSet()
                if (voterPubkey in existingVoters) return@requestTemporarySubscription

                val vote = ZapVote(voterPubkey, amountSats, optionIndex)
                val updatedVotes = current.votesByOption.toMutableMap()
                updatedVotes[optionIndex] = (updatedVotes[optionIndex] ?: emptyList()) + vote
                val updatedMyVotes = if (myPubkey != null && voterPubkey == myPubkey)
                    current.myVotedOptions + optionIndex else current.myVotedOptions
                val updated = current.copy(
                    votesByOption = updatedVotes,
                    totalSats = current.totalSats + amountSats,
                    totalVoters = current.totalVoters + 1,
                    myVotedOptions = updatedMyVotes
                )
                _tallies.value = _tallies.value + (pollId to updated)
                persistEvent(event, pollId)
                MLog.d(TAG, "Live zap vote on poll ${pollId.take(8)}: +${amountSats} sats from ${voterPubkey.take(8)}")
            } catch (e: Exception) {
                MLog.w(TAG, "Live zap parse failed for poll $pollId: ${e.message}")
            }
        }
        liveSubscriptions[pollId] = handle
        MLog.d(TAG, "Live subscription opened for zap poll ${pollId.take(8)}")
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

    /** Set option labels on an existing tally. */
    fun setOptionLabels(pollId: String, labels: Map<Int, String>) {
        val current = _tallies.value[pollId] ?: return
        if (current.optionLabels.isNotEmpty()) return
        _tallies.value = _tallies.value + (pollId to current.copy(optionLabels = labels))
    }

    /**
     * Parse amount in sats from a BOLT-11 invoice string.
     * Format: lnbc<amount><multiplier>1...
     */
    private fun parseBolt11Amount(bolt11: String): Long {
        val lower = bolt11.lowercase()
        if (!lower.startsWith("lnbc")) return 0L
        val afterPrefix = lower.removePrefix("lnbc")
        // Find the amount+multiplier portion (before the first '1' separator that starts the data part)
        val separatorIdx = afterPrefix.indexOf('1')
        if (separatorIdx <= 0) return 0L
        val amountPart = afterPrefix.substring(0, separatorIdx)

        // Multiplier suffixes: m=milli, u=micro, n=nano, p=pico
        val multiplier: Double
        val numberPart: String
        when {
            amountPart.endsWith("m") -> { multiplier = 0.001; numberPart = amountPart.dropLast(1) }
            amountPart.endsWith("u") -> { multiplier = 0.000001; numberPart = amountPart.dropLast(1) }
            amountPart.endsWith("n") -> { multiplier = 0.000000001; numberPart = amountPart.dropLast(1) }
            amountPart.endsWith("p") -> { multiplier = 0.000000000001; numberPart = amountPart.dropLast(1) }
            else -> { multiplier = 1.0; numberPart = amountPart }
        }

        val btcAmount = numberPart.toLongOrNull()?.let { it * multiplier } ?: return 0L
        return (btcAmount * 100_000_000).toLong() // BTC to sats
    }

    fun clear() {
        _tallies.value = emptyMap()
        inFlightPolls.clear()
        fetchedPolls.clear()
        lastFetchTimeSec.clear()
    }
}
