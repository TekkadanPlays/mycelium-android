package social.mycelium.android.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import social.mycelium.android.data.Draft
import social.mycelium.android.data.DraftType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Singleton repository for managing NIP-37 drafts.
 * Stores drafts locally via SharedPreferences and syncs to drafts relays.
 */
object DraftsRepository {

    private const val TAG = "DraftsRepository"
    private const val PREFS_NAME = "drafts_storage"
    private const val KEY_PREFIX = "drafts_"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private var prefs: SharedPreferences? = null
    private var currentPubkey: String? = null

    private val _drafts = MutableStateFlow<List<Draft>>(emptyList())
    val drafts: StateFlow<List<Draft>> = _drafts.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun loadDrafts(pubkey: String) {
        currentPubkey = pubkey
        val key = "$KEY_PREFIX$pubkey"
        val jsonString = prefs?.getString(key, null)
        val loaded = if (jsonString != null) {
            try {
                json.decodeFromString<List<Draft>>(jsonString)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode drafts: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }
        _drafts.value = loaded.sortedByDescending { it.updatedAt }
    }

    fun saveDraft(draft: Draft) {
        val pubkey = currentPubkey ?: return
        // Check for existing draft with same ID (update) or same context (replace)
        val existing = _drafts.value.toMutableList()
        val idx = existing.indexOfFirst { it.id == draft.id }
        if (idx >= 0) {
            existing[idx] = draft.copy(updatedAt = System.currentTimeMillis())
        } else {
            existing.add(0, draft)
        }
        _drafts.value = existing.sortedByDescending { it.updatedAt }
        persistLocally(pubkey)
        Log.d(TAG, "Draft saved: ${draft.type} (${draft.id.take(8)})")
    }

    fun deleteDraft(draftId: String) {
        val pubkey = currentPubkey ?: return
        _drafts.update { it.filter { d -> d.id != draftId } }
        persistLocally(pubkey)
        Log.d(TAG, "Draft deleted: ${draftId.take(8)}")
    }

    fun getDraft(draftId: String): Draft? {
        return _drafts.value.find { it.id == draftId }
    }

    fun getDraftsForNote(rootId: String): List<Draft> {
        return _drafts.value.filter { it.rootId == rootId }
    }

    val draftCount: Int get() = _drafts.value.size

    /** Kind-1 root note drafts (not replies). */
    fun kind1RootDrafts(): List<Draft> =
        _drafts.value.filter { it.type == DraftType.NOTE }

    /** Kind-30023 long-form article drafts. */
    fun articleDrafts(): List<Draft> =
        _drafts.value.filter { it.type == DraftType.ARTICLE }

    /** Kind-11 topic root drafts (not replies). */
    fun topicRootDrafts(): List<Draft> =
        _drafts.value.filter { it.type == DraftType.TOPIC }

    /** Topic root drafts that include a specific hashtag. */
    fun topicDraftsForHashtag(hashtag: String): List<Draft> {
        val lower = hashtag.lowercase().removePrefix("#")
        return _drafts.value.filter { draft ->
            draft.type == DraftType.TOPIC && draft.hashtags.any { it.lowercase().removePrefix("#") == lower }
        }
    }

    /** Reply drafts whose parentId or rootId matches the given note ID. For inline thread injection. */
    fun replyDraftsForThread(noteId: String): List<Draft> =
        _drafts.value.filter { draft ->
            (draft.type == DraftType.REPLY_KIND1 || draft.type == DraftType.REPLY_KIND1111 || draft.type == DraftType.TOPIC_REPLY) &&
                (draft.rootId == noteId || draft.parentId == noteId)
        }

    // ── Scheduling helpers ──────────────────────────────────────────────

    /** All drafts that are scheduled (pending or completed). */
    fun getScheduledDrafts(): List<Draft> =
        _drafts.value.filter { it.isScheduled }

    /** Scheduled drafts that haven't been published yet. */
    fun getPendingScheduledDrafts(): List<Draft> =
        _drafts.value.filter { it.isScheduled && !it.isCompleted && it.signedEventJson != null }

    /** Mark a scheduled draft as successfully published. */
    fun markCompleted(draftId: String) {
        val pubkey = currentPubkey ?: return
        val existing = _drafts.value.toMutableList()
        val idx = existing.indexOfFirst { it.id == draftId }
        if (idx >= 0) {
            existing[idx] = existing[idx].copy(isCompleted = true, publishError = null, updatedAt = System.currentTimeMillis())
            _drafts.value = existing
            persistLocally(pubkey)
            Log.d(TAG, "Draft marked completed: ${draftId.take(8)}")
        }
    }

    /** Mark a scheduled draft as failed with an error message. */
    fun markFailed(draftId: String, error: String) {
        val pubkey = currentPubkey ?: return
        val existing = _drafts.value.toMutableList()
        val idx = existing.indexOfFirst { it.id == draftId }
        if (idx >= 0) {
            existing[idx] = existing[idx].copy(publishError = error, updatedAt = System.currentTimeMillis())
            _drafts.value = existing
            persistLocally(pubkey)
            Log.w(TAG, "Draft marked failed: ${draftId.take(8)} — $error")
        }
    }

    /** Clear all completed scheduled drafts. */
    fun clearCompletedScheduled() {
        val pubkey = currentPubkey ?: return
        _drafts.update { it.filter { d -> !(d.isScheduled && d.isCompleted) } }
        persistLocally(pubkey)
        Log.d(TAG, "Cleared completed scheduled drafts")
    }

    private fun persistLocally(pubkey: String) {
        val key = "$KEY_PREFIX$pubkey"
        val jsonString = json.encodeToString(_drafts.value)
        prefs?.edit()?.putString(key, jsonString)?.apply()
    }
}
