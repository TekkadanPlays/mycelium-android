package social.mycelium.android.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages kind-30011 parameterized replaceable votes for notes/topics/comments.
 *
 * Event structure:
 * ```json
 * {
 *   "kind": 30011,
 *   "content": "1" | "-1" | "0",
 *   "tags": [
 *     ["d", "vote:<target_note_id>"],
 *     ["e", "<target_note_id>", "<relay_hint>"],
 *     ["p", "<target_author_pubkey>"],
 *     ["k", "11"]
 *   ]
 * }
 * ```
 *
 * Vote semantics:
 * - Voting +1 when already +1 → publishes 0 (cancel)
 * - Voting -1 when already -1 → publishes 0 (cancel)
 * - Voting +1 when already -1 → publishes +1 (flip)
 * - Voting -1 when already +1 → publishes -1 (flip)
 */
object VoteRepository {

    private const val TAG = "VoteRepository"
    const val KIND_VOTE = 30011

    private val json = Json { ignoreUnknownKeys = true }

    // ── Own vote state ──────────────────────────────────────────────────

    /** Current user's vote per note: noteId → +1, -1, or 0 (no vote). */
    private val ownVoteByNoteId = ConcurrentHashMap<String, Int>()

    /** Aggregate vote score per note: noteId → net score (sum of all votes). */
    private val _scoreByNoteId = MutableStateFlow<Map<String, Int>>(emptyMap())
    val scoreByNoteId: StateFlow<Map<String, Int>> = _scoreByNoteId.asStateFlow()

    /** Per-note vote counts: noteId → (upvotes, downvotes). */
    private val upvotesByNoteId = ConcurrentHashMap<String, Int>()
    private val downvotesByNoteId = ConcurrentHashMap<String, Int>()

    // ── Query ───────────────────────────────────────────────────────────

    /** Get the current user's vote for a note (+1, -1, or 0). */
    fun getOwnVote(noteId: String): Int = ownVoteByNoteId[noteId] ?: 0

    /** Get the net score for a note (upvotes - downvotes). */
    fun getScore(noteId: String): Int {
        val up = upvotesByNoteId[noteId] ?: 0
        val down = downvotesByNoteId[noteId] ?: 0
        return up - down
    }

    // ── Vote toggle logic ───────────────────────────────────────────────

    /**
     * Compute the new vote value after the user taps upvote or downvote.
     * @param direction +1 for upvote, -1 for downvote
     * @return the new vote value to publish
     */
    fun computeNewVote(noteId: String, direction: Int): Int {
        val current = getOwnVote(noteId)
        return if (current == direction) 0 else direction
    }

    /**
     * Optimistically set the user's vote for a note.
     * Call this immediately when the user taps, before publish completes.
     */
    fun setOwnVote(noteId: String, value: Int) {
        if (noteId.isBlank()) return
        val oldVote = ownVoteByNoteId[noteId] ?: 0
        ownVoteByNoteId[noteId] = value
        // Adjust counts optimistically
        adjustCounts(noteId, oldVote, value)
        emitScoreUpdate()
        Log.d(TAG, "Own vote set: ${noteId.take(8)} = $value (was $oldVote)")
    }

    // ── Incoming event processing ───────────────────────────────────────

    /**
     * Process an incoming kind-30011 vote event from a relay.
     * Called by NoteCountsRepository when it encounters a kind-30011 event.
     */
    fun applyVoteEvent(noteId: String, voterPubkey: String, voteValue: Int, currentUserPubkey: String?) {
        if (noteId.isBlank()) return
        // If this is our own vote, update ownVoteByNoteId
        if (voterPubkey == currentUserPubkey && currentUserPubkey != null) {
            val existing = ownVoteByNoteId[noteId]
            if (existing == null) {
                ownVoteByNoteId[noteId] = voteValue
                Log.d(TAG, "Populated own vote for ${noteId.take(8)}: $voteValue")
            }
        }
        // Aggregate counts (simple accumulation — latest per pubkey wins on relay)
        when {
            voteValue > 0 -> upvotesByNoteId[noteId] = (upvotesByNoteId[noteId] ?: 0) + 1
            voteValue < 0 -> downvotesByNoteId[noteId] = (downvotesByNoteId[noteId] ?: 0) + 1
            // voteValue == 0 means vote was cancelled, no count change
        }
        emitScoreUpdate()
    }

    // ── Persistence ─────────────────────────────────────────────────────

    /** Load persisted own votes for this account. Call on login/account switch. */
    fun loadForAccount(context: Context, accountNpub: String?) {
        ownVoteByNoteId.clear()
        upvotesByNoteId.clear()
        downvotesByNoteId.clear()
        if (accountNpub.isNullOrBlank()) return
        val prefs = context.getSharedPreferences("Mycelium_votes_$accountNpub", Context.MODE_PRIVATE)
        val stored = prefs.getString("own_votes", null) ?: return
        try {
            val map = json.decodeFromString<Map<String, Int>>(stored)
            ownVoteByNoteId.putAll(map)
        } catch (_: Exception) {
            ownVoteByNoteId.clear()
        }
    }

    /** Persist current own votes for this account. Call after vote publish. */
    fun persist(context: Context, accountNpub: String?) {
        if (accountNpub.isNullOrBlank()) return
        val prefs = context.getSharedPreferences("Mycelium_votes_$accountNpub", Context.MODE_PRIVATE)
        val map = ownVoteByNoteId.toMap().filter { it.value != 0 }
        prefs.edit().putString("own_votes", json.encodeToString(map)).apply()
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private fun adjustCounts(noteId: String, oldVote: Int, newVote: Int) {
        // Remove old vote from counts
        when {
            oldVote > 0 -> upvotesByNoteId[noteId] = maxOf(0, (upvotesByNoteId[noteId] ?: 0) - 1)
            oldVote < 0 -> downvotesByNoteId[noteId] = maxOf(0, (downvotesByNoteId[noteId] ?: 0) - 1)
        }
        // Add new vote to counts
        when {
            newVote > 0 -> upvotesByNoteId[noteId] = (upvotesByNoteId[noteId] ?: 0) + 1
            newVote < 0 -> downvotesByNoteId[noteId] = (downvotesByNoteId[noteId] ?: 0) + 1
        }
    }

    private fun emitScoreUpdate() {
        val scores = mutableMapOf<String, Int>()
        val allNoteIds = (upvotesByNoteId.keys + downvotesByNoteId.keys)
        for (id in allNoteIds) {
            scores[id] = (upvotesByNoteId[id] ?: 0) - (downvotesByNoteId[id] ?: 0)
        }
        _scoreByNoteId.value = scores
    }
}
