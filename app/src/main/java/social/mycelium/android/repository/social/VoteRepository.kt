package social.mycelium.android.repository.social

import android.content.Context
import social.mycelium.android.debug.MLog
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
 *
 * Deduplication:
 * - One vote per voter per note. Latest event (by created_at) wins.
 * - Counts are recomputed from the per-voter map, never blindly incremented.
 * - Optimistic own-vote uses Long.MAX_VALUE timestamp so relay echos don't regress it.
 */
object VoteRepository {

    private const val TAG = "VoteRepository"
    const val KIND_VOTE = 30011

    private val json = Json { ignoreUnknownKeys = true }

    // ── Per-voter vote tracking ──────────────────────────────────────────

    /** Per-note, per-voter state: noteId → { voterPubkey → VoteEntry }. */
    private val votesByNote = ConcurrentHashMap<String, ConcurrentHashMap<String, VoteEntry>>()

    /** A single voter's vote on a single note. */
    private data class VoteEntry(val value: Int, val createdAt: Long)

    // ── Own vote state ──────────────────────────────────────────────────

    /** Current user's vote per note: noteId → +1, -1, or 0 (no vote). */
    private val ownVoteByNoteId = ConcurrentHashMap<String, Int>()

    /** Reactive own-vote map for Compose observation. */
    private val _ownVotes = MutableStateFlow<Map<String, Int>>(emptyMap())
    val ownVotes: StateFlow<Map<String, Int>> = _ownVotes.asStateFlow()

    /** Aggregate vote score per note: noteId → net score (sum of all votes). */
    private val _scoreByNoteId = MutableStateFlow<Map<String, Int>>(emptyMap())
    val scoreByNoteId: StateFlow<Map<String, Int>> = _scoreByNoteId.asStateFlow()

    /** Reactive per-note upvote counts for Compose observation. */
    private val _upvotesByNoteId = MutableStateFlow<Map<String, Int>>(emptyMap())
    val upvoteCounts: StateFlow<Map<String, Int>> = _upvotesByNoteId.asStateFlow()

    /** Reactive per-note downvote counts for Compose observation. */
    private val _downvotesByNoteId = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downvoteCounts: StateFlow<Map<String, Int>> = _downvotesByNoteId.asStateFlow()

    // ── Query ───────────────────────────────────────────────────────────

    /** Get the current user's vote for a note (+1, -1, or 0). */
    fun getOwnVote(noteId: String): Int = ownVoteByNoteId[noteId] ?: 0

    /** Get the net score for a note (upvotes - downvotes). */
    fun getScore(noteId: String): Int {
        val voters = votesByNote[noteId] ?: return 0
        var up = 0; var down = 0
        for (entry in voters.values) {
            if (entry.value > 0) up++ else if (entry.value < 0) down++
        }
        return up - down
    }

    /** Get total upvotes for a note. */
    fun getUpvotes(noteId: String): Int =
        votesByNote[noteId]?.values?.count { it.value > 0 } ?: 0

    /** Get total downvotes for a note. */
    fun getDownvotes(noteId: String): Int =
        votesByNote[noteId]?.values?.count { it.value < 0 } ?: 0

    /** Sum of upvotes across multiple notes (e.g. all notes in a topic). */
    fun getTotalUpvotes(noteIds: List<String>): Int = noteIds.sumOf { getUpvotes(it) }

    /** Sum of downvotes across multiple notes (e.g. all notes in a topic). */
    fun getTotalDownvotes(noteIds: List<String>): Int = noteIds.sumOf { getDownvotes(it) }

    /** Net score across multiple notes (total upvotes - total downvotes). */
    fun getTotalScore(noteIds: List<String>): Int = getTotalUpvotes(noteIds) - getTotalDownvotes(noteIds)

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
     * Uses Long.MAX_VALUE as timestamp so relay echoes (with real timestamps) never regress it.
     */
    fun setOwnVote(noteId: String, value: Int, currentUserPubkey: String? = null) {
        if (noteId.isBlank()) return
        val oldVote = ownVoteByNoteId[noteId] ?: 0
        ownVoteByNoteId[noteId] = value

        // Update per-voter map with optimistic timestamp
        val pubkey = currentUserPubkey ?: "__self__"
        val voters = votesByNote.getOrPut(noteId) { ConcurrentHashMap() }
        voters[pubkey] = VoteEntry(value, Long.MAX_VALUE)

        emitScoreUpdate()
        MLog.d(TAG, "Own vote set: ${noteId.take(8)} = $value (was $oldVote)")
    }

    // ── Incoming event processing ───────────────────────────────────────

    /**
     * Process an incoming kind-30011 vote event from a relay.
     * Called by NoteCountsRepository when it encounters a kind-30011 event.
     *
     * Deduplication: one vote per voter per note, latest created_at wins.
     * If the voter already has a newer entry, the incoming event is ignored.
     */
    fun applyVoteEvent(noteId: String, voterPubkey: String, voteValue: Int, createdAt: Long, currentUserPubkey: String?) {
        if (noteId.isBlank()) return

        val voters = votesByNote.getOrPut(noteId) { ConcurrentHashMap() }
        val existing = voters[voterPubkey]

        // Only accept if newer (or no previous entry)
        if (existing != null && existing.createdAt >= createdAt) {
            return // Already have a newer or equal vote from this voter
        }
        voters[voterPubkey] = VoteEntry(voteValue, createdAt)

        // If this is our own vote, update ownVoteByNoteId
        if (voterPubkey == currentUserPubkey && currentUserPubkey != null) {
            ownVoteByNoteId[noteId] = voteValue
            MLog.d(TAG, "Own vote from relay: ${noteId.take(8)} = $voteValue (ts=$createdAt)")
        }

        emitScoreUpdate()
    }

    // ── Persistence ─────────────────────────────────────────────────────

    /** Load persisted own votes for this account. Call on login/account switch. */
    fun loadForAccount(context: Context, accountNpub: String?) {
        ownVoteByNoteId.clear()
        votesByNote.clear()
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

    /** Recompute all reactive state from the per-voter maps. */
    private fun emitScoreUpdate() {
        val scores = mutableMapOf<String, Int>()
        val ups = mutableMapOf<String, Int>()
        val downs = mutableMapOf<String, Int>()

        for ((noteId, voters) in votesByNote) {
            var up = 0; var down = 0
            for (entry in voters.values) {
                if (entry.value > 0) up++ else if (entry.value < 0) down++
                // value == 0 is a cancel — counts as neither
            }
            scores[noteId] = up - down
            if (up > 0) ups[noteId] = up
            if (down > 0) downs[noteId] = down
        }

        _scoreByNoteId.value = scores
        _upvotesByNoteId.value = ups
        _downvotesByNoteId.value = downs
        _ownVotes.value = ownVoteByNoteId.toMap()
    }
}
