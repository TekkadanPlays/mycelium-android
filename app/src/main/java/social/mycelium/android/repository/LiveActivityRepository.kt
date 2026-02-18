package social.mycelium.android.repository

import android.util.Log
import social.mycelium.android.data.Author
import social.mycelium.android.data.LiveActivity
import social.mycelium.android.data.LiveActivityParticipant
import social.mycelium.android.data.LiveActivityStatus
import com.example.cybin.core.Event
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Repository for NIP-53 Live Activities (kind:30311).
 * Maintains a live list of active streams, auto-expires stale entries,
 * and resolves host profiles from ProfileMetadataCache.
 */
class LiveActivityRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })
    private val profileCache = ProfileMetadataCache.getInstance()
    private val relayStateMachine = social.mycelium.android.relay.RelayConnectionStateMachine.getInstance()

    /** All known live activities keyed by addressable id (kind:pubkey:dTag). */
    private val activitiesById = mutableMapOf<String, LiveActivity>()

    /** Live activities with status=LIVE, sorted by participant count desc then createdAt desc. */
    private val _liveActivities = MutableStateFlow<List<LiveActivity>>(emptyList())
    val liveActivities: StateFlow<List<LiveActivity>> = _liveActivities.asStateFlow()

    /** All activities (including planned/ended) for discovery screens. */
    private val _allActivities = MutableStateFlow<List<LiveActivity>>(emptyList())
    val allActivities: StateFlow<List<LiveActivity>> = _allActivities.asStateFlow()

    private var expiryJob: kotlinx.coroutines.Job? = null

    companion object {
        private const val TAG = "LiveActivityRepo"
        /** NIP-53: treat status=live events older than 1hr without update as ended. */
        private const val STALE_LIVE_THRESHOLD_SECS = 3600L
        /** How often to run the expiry sweep. */
        private const val EXPIRY_SWEEP_INTERVAL_MS = 60_000L

        @Volatile
        private var instance: LiveActivityRepository? = null
        fun getInstance(): LiveActivityRepository =
            instance ?: synchronized(this) { instance ?: LiveActivityRepository().also { instance = it } }
    }

    init {
        relayStateMachine.registerKind30311Handler { event, relayUrl ->
            handleLiveActivityEvent(event, relayUrl)
        }
        startExpirySweep()
        // Observe profile updates to refresh host author info
        scope.launch {
            profileCache.profileUpdated.collect { pubkey ->
                updateHostAuthor(pubkey)
            }
        }
    }

    /**
     * Handle an incoming kind:30311 event from a relay.
     */
    fun handleLiveActivityEvent(event: Event, relayUrl: String = "") {
        if (event.kind != 30311) return
        try {
            val activity = parseEvent(event, relayUrl)
            val addressableId = addressableId(event)

            val existing = activitiesById[addressableId]
            // Only accept if newer (addressable events are replaceable)
            if (existing != null && existing.createdAt >= activity.createdAt) {
                // Accumulate relay URL if from a different relay
                if (relayUrl.isNotEmpty() && relayUrl !in (existing.relayUrls)) {
                    val updated = existing.copy(relayUrls = existing.relayUrls + relayUrl)
                    activitiesById[addressableId] = updated
                    emitUpdate()
                }
                return
            }

            // Resolve host profile
            val hostAuthor = profileCache.resolveAuthor(activity.hostPubkey)
            val withAuthor = activity.copy(hostAuthor = hostAuthor)

            // Request profile if unknown
            if (profileCache.getAuthor(activity.hostPubkey) == null) {
                scope.launch {
                    val cacheRelays = getCacheRelayUrls()
                    if (cacheRelays.isNotEmpty()) {
                        profileCache.requestProfiles(listOf(activity.hostPubkey), cacheRelays)
                    }
                }
            }

            activitiesById[addressableId] = withAuthor
            emitUpdate()

            Log.d(TAG, "Live activity ${activity.status}: \"${activity.title}\" by ${activity.hostPubkey.take(8)} (${activitiesById.size} total)")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing kind:30311: ${e.message}", e)
        }
    }

    /**
     * Parse a kind:30311 Event into a LiveActivity data object.
     */
    private fun parseEvent(event: Event, relayUrl: String): LiveActivity {
        val tags = event.tags

        val dTag = tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
        val title = tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)
        val summary = tags.firstOrNull { it.size >= 2 && it[0] == "summary" }?.get(1)
        val image = tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1)
        val streaming = tags.firstOrNull { it.size >= 2 && it[0] == "streaming" }?.get(1)
        val recording = tags.firstOrNull { it.size >= 2 && it[0] == "recording" }?.get(1)
        val statusStr = tags.firstOrNull { it.size >= 2 && it[0] == "status" }?.get(1)
        val starts = tags.firstOrNull { it.size >= 2 && it[0] == "starts" }?.get(1)?.toLongOrNull()
        val ends = tags.firstOrNull { it.size >= 2 && it[0] == "ends" }?.get(1)?.toLongOrNull()
        val currentPart = tags.firstOrNull { it.size >= 2 && it[0] == "current_participants" }?.get(1)?.toIntOrNull()
        val totalPart = tags.firstOrNull { it.size >= 2 && it[0] == "total_participants" }?.get(1)?.toIntOrNull()

        val participants = tags.filter { it.size >= 2 && it[0] == "p" }.map { pTag ->
            LiveActivityParticipant(
                pubkey = pTag[1],
                role = pTag.getOrNull(3),
                relayHint = pTag.getOrNull(2)?.takeIf { it.isNotEmpty() }
            )
        }

        val hashtags = tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1] }

        val activityRelays = tags.filter { it.size >= 2 && it[0] == "relays" }
            .flatMap { it.drop(1) }

        val allRelays = (activityRelays + listOfNotNull(relayUrl.takeIf { it.isNotEmpty() })).distinct()

        var status = LiveActivityStatus.fromTag(statusStr)
        // NIP-53: auto-expire live events older than 1hr
        if (status == LiveActivityStatus.LIVE) {
            val nowSecs = System.currentTimeMillis() / 1000
            if (event.createdAt < nowSecs - STALE_LIVE_THRESHOLD_SECS) {
                status = LiveActivityStatus.ENDED
            }
        }

        // Determine host: first "p" with role "Host", or event author
        val hostPubkey = participants.firstOrNull {
            it.role?.equals("Host", ignoreCase = true) == true
        }?.pubkey ?: event.pubKey

        return LiveActivity(
            id = event.id,
            hostPubkey = hostPubkey,
            dTag = dTag,
            title = title,
            summary = summary,
            imageUrl = image,
            streamingUrl = streaming,
            recordingUrl = recording,
            status = status,
            startsAt = starts,
            endsAt = ends,
            currentParticipants = currentPart,
            totalParticipants = totalPart,
            participants = participants,
            hashtags = hashtags,
            relayUrls = allRelays,
            sourceRelayUrl = relayUrl.takeIf { it.isNotEmpty() },
            createdAt = event.createdAt
        )
    }

    private fun addressableId(event: Event): String {
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
        return "${event.kind}:${event.pubKey}:$dTag"
    }

    /**
     * Emit updated lists to StateFlows.
     */
    private fun emitUpdate() {
        val all = activitiesById.values.toList()
        _allActivities.value = all.sortedByDescending { it.createdAt }
        _liveActivities.value = all
            .filter { it.status == LiveActivityStatus.LIVE }
            .sortedWith(
                compareByDescending<LiveActivity> { it.currentParticipants ?: 0 }
                    .thenByDescending { it.createdAt }
            )
    }

    /**
     * Periodically sweep stale LIVE activities and mark them ENDED.
     */
    private fun startExpirySweep() {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            while (true) {
                delay(EXPIRY_SWEEP_INTERVAL_MS)
                val nowSecs = System.currentTimeMillis() / 1000
                var changed = false
                activitiesById.entries.forEach { (key, activity) ->
                    if (activity.status == LiveActivityStatus.LIVE &&
                        activity.createdAt < nowSecs - STALE_LIVE_THRESHOLD_SECS
                    ) {
                        activitiesById[key] = activity.copy(status = LiveActivityStatus.ENDED)
                        changed = true
                        Log.d(TAG, "Auto-expired stale live activity: \"${activity.title}\"")
                    }
                }
                if (changed) emitUpdate()
            }
        }
    }

    /**
     * Update host author when profile cache resolves a new profile.
     */
    private fun updateHostAuthor(pubkey: String) {
        val author = profileCache.getAuthor(pubkey) ?: return
        var changed = false
        activitiesById.entries.forEach { (key, activity) ->
            if (activity.hostPubkey.equals(pubkey, ignoreCase = true)) {
                activitiesById[key] = activity.copy(hostAuthor = author)
                changed = true
            }
        }
        if (changed) emitUpdate()
    }

    /** Clear all activities (e.g. on logout). */
    fun clear() {
        activitiesById.clear()
        _liveActivities.value = emptyList()
        _allActivities.value = emptyList()
    }

    /** Cache relay URLs provider — set by the ViewModel layer. */
    @Volatile
    private var cacheRelayUrlsProvider: (() -> List<String>)? = null

    fun setCacheRelayUrlsProvider(provider: () -> List<String>) {
        cacheRelayUrlsProvider = provider
    }

    private fun getCacheRelayUrls(): List<String> =
        cacheRelayUrlsProvider?.invoke() ?: emptyList()
}
