package social.mycelium.android.repository.social

import android.content.Context
import social.mycelium.android.debug.MLog
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
import social.mycelium.android.repository.social.NoteCounts

/**
 * Filter mode for topic-scoped moderation.
 * Controls how kind:1011 flags affect the topic feed.
 */
enum class ModerationFilterMode {
    /** No filtering — show all notes regardless of moderation flags. */
    OFF,
    /** Hide notes/users that exceed a global flag-count threshold. */
    THRESHOLD,
    /** Only count flags from users in your follow list (Web of Trust). */
    WOT
}

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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> MLog.e(TAG, "Coroutine failed: ${t.message}", t) })

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

    // Observable: map of (anchor#pubkey) -> exclusion count for UI badges
    private val _userExclusionCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val userExclusionCounts: StateFlow<Map<String, Int>> = _userExclusionCounts.asStateFlow()

    // Filter mode (persisted)
    private val _filterMode = MutableStateFlow(ModerationFilterMode.THRESHOLD)
    val filterMode: StateFlow<ModerationFilterMode> = _filterMode.asStateFlow()

    // Threshold: minimum number of flags before content is hidden
    private val _flagThreshold = MutableStateFlow(2)
    val flagThreshold: StateFlow<Int> = _flagThreshold.asStateFlow()

    // WoT follow set (pubkeys whose moderation flags we trust)
    @Volatile private var wotFollowSet: Set<String> = emptySet()

    // Show-anyway overrides: set of "anchor#noteId" or "anchor#pubkey" the user has dismissed
    private val showAnywayOverrides = mutableSetOf<String>()

    // Observable: version counter that bumps when filter config or overrides change (triggers recomposition)
    private val _filterVersion = MutableStateFlow(0L)
    val filterVersion: StateFlow<Long> = _filterVersion.asStateFlow()

    @Volatile private var appContext: Context? = null
    private var savePending = false

    companion object {
        private const val TAG = "ScopedModerationRepo"
        private const val PREFS_NAME = "nip22_moderation_cache"
        private const val PREFS_KEY = "moderation_events"
        private const val MAX_CACHED = 500
        private const val PREFS_KEY_FILTER_MODE = "filter_mode"
        private const val PREFS_KEY_THRESHOLD = "flag_threshold"
        private const val PREFS_KEY_OVERRIDES = "show_anyway_overrides"
        private const val DEFAULT_THRESHOLD = 2

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
        MLog.d(TAG, "Kind-1011 handler registered")
    }

    /**
     * Initialize persistence. Call from MainActivity.onCreate with applicationContext.
     * Restores cached moderation events from disk.
     */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            loadFromDisk()
            loadSettingsFromDisk()
        }
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
                MLog.d(TAG, "Restored $restored moderation events from disk")
            }
        } catch (e: Exception) {
            MLog.e(TAG, "loadFromDisk failed: ${e.message}", e)
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
            MLog.e(TAG, "saveToDisk failed: ${e.message}", e)
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
        val noteCounts = mutableMapOf<String, Int>()
        val userCounts = mutableMapOf<String, Int>()
        synchronized(this) {
            for ((key, flaggers) in offTopicFlags) {
                noteCounts["${key.first}#${key.second}"] = flaggers.size
            }
            for ((key, excluders) in userExclusions) {
                userCounts["${key.first}#${key.second}"] = excluders.size
            }
        }
        _offTopicCounts.value = noteCounts
        _userExclusionCounts.value = userCounts
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

        // Update observable user exclusion counts
        if (targetPubkey != null) {
            val key = "$anchor#$targetPubkey"
            val count = userExclusions[anchor to targetPubkey]?.size ?: 0
            _userExclusionCounts.value = _userExclusionCounts.value + (key to count)
        }

        scheduleSaveToDisk()

        MLog.d(TAG, "Kind-1011 received: anchor=$anchor note=${targetNoteId?.take(8)} user=${targetPubkey?.take(8)} by=${event.pubKey.take(8)}")
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
     * Get the event ID of the current user's off-topic flag on a note (for kind-5 deletion).
     * Returns null if the user hasn't flagged it.
     */
    fun getOwnFlagEventId(anchor: String, noteId: String, userPubkey: String): String? {
        return synchronized(this) {
            byAnchor[anchor]?.firstOrNull { it.pubkey == userPubkey && it.targetNoteId == noteId }?.id
        }
    }

    /**
     * Remove the current user's off-topic flag from local state.
     * Call after successfully publishing a kind-5 deletion event.
     */
    fun removeOwnFlag(anchor: String, noteId: String, userPubkey: String) {
        synchronized(this) {
            val eventId = byAnchor[anchor]?.firstOrNull { it.pubkey == userPubkey && it.targetNoteId == noteId }?.id
            if (eventId != null) {
                allEvents.remove(eventId)
                byAnchor[anchor]?.removeAll { it.id == eventId }
                offTopicFlags[anchor to noteId]?.remove(userPubkey)
            }
        }
        // Update observables
        val key = "$anchor#$noteId"
        val count = synchronized(this) { offTopicFlags[anchor to noteId]?.size ?: 0 }
        _offTopicCounts.value = if (count > 0) {
            _offTopicCounts.value + (key to count)
        } else {
            _offTopicCounts.value - key
        }
        _moderationCount.value = allEvents.size
        _filterVersion.value++
        scheduleSaveToDisk()
        MLog.d(TAG, "Removed own flag: anchor=$anchor note=${noteId.take(8)} by=${userPubkey.take(8)}")
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

    // ── Filter Mode & WoT ────────────────────────────────────────────────

    fun setFilterMode(mode: ModerationFilterMode) {
        _filterMode.value = mode
        _filterVersion.value++
        saveSettingsToDisk()
        MLog.d(TAG, "Filter mode set to $mode")
    }

    fun setFlagThreshold(threshold: Int) {
        _flagThreshold.value = threshold.coerceAtLeast(1)
        _filterVersion.value++
        saveSettingsToDisk()
    }

    /**
     * Update the WoT follow set. Call when the user's follow list is loaded/refreshed.
     */
    fun setWotFollowSet(pubkeys: Set<String>) {
        wotFollowSet = pubkeys
        _filterVersion.value++
        MLog.d(TAG, "WoT follow set updated: ${pubkeys.size} pubkeys")
    }

    // ── Show-Anyway Overrides ────────────────────────────────────────────

    /**
     * Dismiss moderation for a specific note or user within an anchor.
     * [key] format: "anchor#noteId" or "anchor#pubkey".
     */
    fun addShowAnywayOverride(key: String) {
        synchronized(this) { showAnywayOverrides.add(key) }
        _filterVersion.value++
        saveSettingsToDisk()
    }

    fun removeShowAnywayOverride(key: String) {
        synchronized(this) { showAnywayOverrides.remove(key) }
        _filterVersion.value++
        saveSettingsToDisk()
    }

    fun hasShowAnywayOverride(key: String): Boolean {
        return synchronized(this) { key in showAnywayOverrides }
    }

    // ── Filtering Queries ────────────────────────────────────────────────

    /**
     * Check whether a note should be hidden in the given anchor based on current filter mode.
     * Returns true if the note should be hidden (and no show-anyway override exists).
     */
    fun isNoteHidden(anchor: String, noteId: String): Boolean {
        val mode = _filterMode.value
        if (mode == ModerationFilterMode.OFF) return false

        val overrideKey = "$anchor#$noteId"
        if (hasShowAnywayOverride(overrideKey)) return false

        val flaggers = synchronized(this) { offTopicFlags[anchor to noteId] } ?: return false
        if (flaggers.isEmpty()) return false

        return when (mode) {
            ModerationFilterMode.OFF -> false
            ModerationFilterMode.THRESHOLD -> flaggers.size >= _flagThreshold.value
            ModerationFilterMode.WOT -> {
                val wot = wotFollowSet
                if (wot.isEmpty()) false
                else flaggers.count { it in wot } >= _flagThreshold.value
            }
        }
    }

    /**
     * Check whether a user should be hidden in the given anchor based on exclusion flags.
     */
    fun isUserHidden(anchor: String, pubkey: String): Boolean {
        val mode = _filterMode.value
        if (mode == ModerationFilterMode.OFF) return false

        val overrideKey = "${anchor}#user:$pubkey"
        if (hasShowAnywayOverride(overrideKey)) return false

        val excluders = synchronized(this) { userExclusions[anchor to pubkey] } ?: return false
        if (excluders.isEmpty()) return false

        return when (mode) {
            ModerationFilterMode.OFF -> false
            ModerationFilterMode.THRESHOLD -> excluders.size >= _flagThreshold.value
            ModerationFilterMode.WOT -> {
                val wot = wotFollowSet
                if (wot.isEmpty()) false
                else excluders.count { it in wot } >= _flagThreshold.value
            }
        }
    }

    /**
     * Get the effective flag count for a note (respects WoT mode).
     */
    fun getEffectiveFlagCount(anchor: String, noteId: String): Int {
        val flaggers = synchronized(this) { offTopicFlags[anchor to noteId] } ?: return 0
        return when (_filterMode.value) {
            ModerationFilterMode.OFF, ModerationFilterMode.THRESHOLD -> flaggers.size
            ModerationFilterMode.WOT -> {
                val wot = wotFollowSet
                if (wot.isEmpty()) flaggers.size else flaggers.count { it in wot }
            }
        }
    }

    /**
     * Get the effective exclusion count for a user (respects WoT mode).
     */
    fun getEffectiveExclusionCount(anchor: String, pubkey: String): Int {
        val excluders = synchronized(this) { userExclusions[anchor to pubkey] } ?: return 0
        return when (_filterMode.value) {
            ModerationFilterMode.OFF, ModerationFilterMode.THRESHOLD -> excluders.size
            ModerationFilterMode.WOT -> {
                val wot = wotFollowSet
                if (wot.isEmpty()) excluders.size else excluders.count { it in wot }
            }
        }
    }

    // ── Settings Persistence ─────────────────────────────────────────────

    private fun loadSettingsFromDisk() {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val modeName = prefs.getString(PREFS_KEY_FILTER_MODE, ModerationFilterMode.THRESHOLD.name)
            _filterMode.value = try {
                ModerationFilterMode.valueOf(modeName ?: ModerationFilterMode.THRESHOLD.name)
            } catch (_: Exception) {
                ModerationFilterMode.THRESHOLD
            }
            _flagThreshold.value = prefs.getInt(PREFS_KEY_THRESHOLD, DEFAULT_THRESHOLD)
            val overridesJson = prefs.getString(PREFS_KEY_OVERRIDES, null)
            if (overridesJson != null) {
                val arr = JSONArray(overridesJson)
                synchronized(this) {
                    showAnywayOverrides.clear()
                    for (i in 0 until arr.length()) {
                        showAnywayOverrides.add(arr.getString(i))
                    }
                }
            }
            MLog.d(TAG, "Settings loaded: mode=${_filterMode.value} threshold=${_flagThreshold.value} overrides=${showAnywayOverrides.size}")
        } catch (e: Exception) {
            MLog.e(TAG, "loadSettingsFromDisk failed: ${e.message}", e)
        }
    }

    private fun saveSettingsToDisk() {
        val ctx = appContext ?: return
        scope.launch {
            try {
                val overrides = synchronized(this@ScopedModerationRepository) {
                    JSONArray(showAnywayOverrides.toList())
                }
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREFS_KEY_FILTER_MODE, _filterMode.value.name)
                    .putInt(PREFS_KEY_THRESHOLD, _flagThreshold.value)
                    .putString(PREFS_KEY_OVERRIDES, overrides.toString())
                    .apply()
            } catch (e: Exception) {
                MLog.e(TAG, "saveSettingsToDisk failed: ${e.message}", e)
            }
        }
    }
}
