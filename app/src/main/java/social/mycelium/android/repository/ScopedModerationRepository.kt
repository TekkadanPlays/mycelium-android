package social.mycelium.android.repository

import android.content.Context
import android.util.Log
import com.example.cybin.core.Event
import social.mycelium.android.relay.RelayConnectionStateMachine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * NIP-22 Scoped Moderation Repository.
 * Collects kind:1011 events from relays and indexes them by anchor + target.
 * Persists to SharedPreferences so flags survive app restarts.
 *
 * Singleton: registered once at app startup so events are collected as soon as relays connect.
 */
class ScopedModerationRepository private constructor() {

    /**
     * A single moderation opinion: who flagged what, in which scope, and why.
     */
    data class ModerationEvent(
        val id: String,
        val pubkey: String,
        val anchor: String,
        val targetNoteId: String?,
        val targetPubkey: String?,
        val reason: String,
        val timestamp: Long
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })

    // All moderation events indexed by ID (dedup)
    private val allEvents = mutableMapOf<String, ModerationEvent>()

    // Index: anchor -> list of moderation events
    private val byAnchor = mutableMapOf<String, MutableList<ModerationEvent>>()

    // Index: (anchor, noteId) -> set of moderator pubkeys
    private val offTopicFlags = mutableMapOf<Pair<String, String>, MutableSet<String>>()

    // Index: (anchor, pubkey) -> set of moderator pubkeys
    private val userExclusions = mutableMapOf<Pair<String, String>, MutableSet<String>>()

    // Observable state: total moderation event count (triggers recomposition)
    private val _moderationCount = MutableStateFlow(0)
    val moderationCount: StateFlow<Int> = _moderationCount.asStateFlow()

    // Observable: map of (anchor#noteId) -> flag count for UI badges
    private val _offTopicCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val offTopicCounts: StateFlow<Map<String, Int>> = _offTopicCounts.asStateFlow()

    @Volatile private var appContext: Context? = null
    private var savePending = false

    companion object {
        private const val TAG = "ScopedModerationRepo"
        private const val PREFS_NAME = "nip22_moderation_cache"
        private const val PREFS_KEY = "moderation_events"
        private const val MAX_CACHED = 500

        @Volatile
        private var instance: ScopedModerationRepository? = null
        fun getInstance(): ScopedModerationRepository =
            instance ?: synchronized(this) {
                instance ?: ScopedModerationRepository().also { instance = it }
            }
    }

    init {
        RelayConnectionStateMachine.getInstance().registerKind1011Handler { event ->
            handleModerationEvent(event)
        }
        Log.d(TAG, "Kind-1011 handler registered")
    }

    /**
     * Initialize persistence. Call from MainActivity.onCreate with applicationContext.
     * Restores cached moderation events from disk.
     */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch { loadFromDisk() }
    }

    private fun loadFromDisk() {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(PREFS_KEY, null) ?: return
            val arr = JSONArray(json)
            var restored = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val modEvent = ModerationEvent(
                    id = obj.getString("id"),
                    pubkey = obj.getString("pubkey"),
                    anchor = obj.getString("anchor"),
                    targetNoteId = obj.optString("targetNoteId", null),
                    targetPubkey = obj.optString("targetPubkey", null),
                    reason = obj.optString("reason", ""),
                    timestamp = obj.getLong("timestamp")
                )
                if (indexEvent(modEvent)) restored++
            }
            if (restored > 0) {
                rebuildObservables()
                Log.d(TAG, "Restored $restored moderation events from disk")
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadFromDisk failed: ${e.message}", e)
        }
    }

    private fun scheduleSaveToDisk() {
        if (savePending || appContext == null) return
        savePending = true
        scope.launch {
            delay(2000)
            savePending = false
            saveToDisk()
        }
    }

    private fun saveToDisk() {
        val ctx = appContext ?: return
        try {
            val events = synchronized(this) {
                allEvents.values.sortedByDescending { it.timestamp }.take(MAX_CACHED)
            }
            val arr = JSONArray()
            for (ev in events) {
                val obj = JSONObject()
                obj.put("id", ev.id)
                obj.put("pubkey", ev.pubkey)
                obj.put("anchor", ev.anchor)
                obj.put("targetNoteId", ev.targetNoteId ?: "")
                obj.put("targetPubkey", ev.targetPubkey ?: "")
                obj.put("reason", ev.reason)
                obj.put("timestamp", ev.timestamp)
                arr.put(obj)
            }
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREFS_KEY, arr.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveToDisk failed: ${e.message}", e)
        }
    }

    /**
     * Index a ModerationEvent into all maps. Returns true if it was new.
     */
    private fun indexEvent(modEvent: ModerationEvent): Boolean {
        synchronized(this) {
            if (modEvent.id in allEvents) return false
            allEvents[modEvent.id] = modEvent
            byAnchor.getOrPut(modEvent.anchor) { mutableListOf() }.add(modEvent)
            if (modEvent.targetNoteId != null) {
                offTopicFlags.getOrPut(modEvent.anchor to modEvent.targetNoteId) { mutableSetOf() }.add(modEvent.pubkey)
            }
            if (modEvent.targetPubkey != null) {
                userExclusions.getOrPut(modEvent.anchor to modEvent.targetPubkey) { mutableSetOf() }.add(modEvent.pubkey)
            }
            return true
        }
    }

    /**
     * Rebuild observable StateFlows from indexed data (after bulk load from disk).
     */
    private fun rebuildObservables() {
        _moderationCount.value = allEvents.size
        val counts = mutableMapOf<String, Int>()
        synchronized(this) {
            for ((key, flaggers) in offTopicFlags) {
                counts["${key.first}#${key.second}"] = flaggers.size
            }
        }
        _offTopicCounts.value = counts
    }

    private fun handleModerationEvent(event: Event) {
        if (event.id in allEvents) return

        val anchor = event.tags.firstOrNull { it.size >= 2 && it[0] == "I" }?.get(1) ?: return
        val targetNoteId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        val targetPubkey = event.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)

        if (targetNoteId == null && targetPubkey == null) return

        val modEvent = ModerationEvent(
            id = event.id,
            pubkey = event.pubKey,
            anchor = anchor,
            targetNoteId = targetNoteId,
            targetPubkey = targetPubkey,
            reason = event.content,
            timestamp = event.createdAt
        )

        if (!indexEvent(modEvent)) return

        _moderationCount.value = allEvents.size

        // Update observable off-topic counts
        if (targetNoteId != null) {
            val key = "$anchor#$targetNoteId"
            val count = offTopicFlags[anchor to targetNoteId]?.size ?: 0
            _offTopicCounts.value = _offTopicCounts.value + (key to count)
        }

        scheduleSaveToDisk()

        Log.d(TAG, "Kind-1011 received: anchor=$anchor note=${targetNoteId?.take(8)} user=${targetPubkey?.take(8)} by=${event.pubKey.take(8)}")
    }

    /**
     * Get the number of off-topic flags for a note within an anchor.
     */
    fun getOffTopicFlagCount(anchor: String, noteId: String): Int {
        return synchronized(this) { offTopicFlags[anchor to noteId]?.size ?: 0 }
    }

    /**
     * Get the pubkeys that flagged a note as off-topic within an anchor.
     */
    fun getOffTopicFlaggers(anchor: String, noteId: String): Set<String> {
        return synchronized(this) { offTopicFlags[anchor to noteId]?.toSet() ?: emptySet() }
    }

    /**
     * Get the number of exclusion flags for a user within an anchor.
     */
    fun getUserExclusionCount(anchor: String, pubkey: String): Int {
        return synchronized(this) { userExclusions[anchor to pubkey]?.size ?: 0 }
    }

    /**
     * Check if a specific moderator has flagged a note as off-topic.
     */
    fun hasModeratorFlagged(anchor: String, noteId: String, moderatorPubkey: String): Boolean {
        return synchronized(this) { offTopicFlags[anchor to noteId]?.contains(moderatorPubkey) == true }
    }

    /**
     * Get all moderation events for an anchor (for debug/inspection UI).
     */
    fun getModerationEventsForAnchor(anchor: String): List<ModerationEvent> {
        return synchronized(this) { byAnchor[anchor]?.toList() ?: emptyList() }
    }

    /**
     * Get all moderation events (for debug screen).
     */
    fun getAllModerationEvents(): List<ModerationEvent> {
        return synchronized(this) { allEvents.values.sortedByDescending { it.timestamp } }
    }
}
