package social.mycelium.android.repository.feed

import social.mycelium.android.data.Note

/**
 * Persistent, incrementally-maintained indexes over the feed note list.
 *
 * Replaces the per-flush O(n) rebuilds of:
 * - `currentNotes.associateBy { it.id }`               → [byId]
 * - `buildSet { currentNotes.forEach { originalNoteId } }` → [repostedOriginalIds] + [repostOriginalToComposite]
 * - `buildMap { kind==30023 }` for article dedup         → [articleKeys]
 *
 * All mutations happen under the same `processEventMutex` that guards `_notes`,
 * so no additional synchronization is needed.
 *
 * **Thread-safety contract**: Write from the mutex holder only. Reads from
 * `updateDisplayedNotes` and `scheduleDisplayUpdate` are safe because they
 * run on the same single-threaded IO dispatcher as the mutex-holder flush.
 */
class FeedIndex {
    /** O(1) note lookup by ID. Replaces `currentNotes.associateBy { it.id }`. */
    val byId = HashMap<String, Note>(8192)

    /**
     * O(1) repost dedup: `originalNoteId → composite repost note ID`.
     * Replaces `buildSet { currentNotes.forEach { it.originalNoteId?.let(::add) } }`.
     * Also replaces the expensive `currentNotes.find { it.originalNoteId == event.id }` (line 380)
     * by providing a direct mapping to the repost note's ID for O(1) lookup.
     */
    val repostOriginalToComposite = HashMap<String, String>(2048)

    /** Read-only view of originalNoteIds currently in the feed. */
    val repostedOriginalIds: Set<String> get() = repostOriginalToComposite.keys

    /**
     * O(1) article dedup: `"authorHex:dTag" → timestamp`.
     * Replaces `buildMap { currentNotes.forEach { if (kind==30023 && dTag!=null) ... } }`.
     */
    val articleKeys = HashMap<String, Long>(256)

    /** Add a single note to all indexes. O(1). */
    fun addNote(note: Note) {
        byId[note.id] = note
        note.originalNoteId?.let { origId ->
            repostOriginalToComposite[origId] = note.id
        }
        if (note.kind == 30023 && note.dTag != null) {
            val key = "${note.author.id.lowercase()}:${note.dTag}"
            articleKeys[key] = note.timestamp
        }
    }

    /** Remove a single note from all indexes. O(1). */
    fun removeNote(note: Note) {
        byId.remove(note.id)
        note.originalNoteId?.let { origId ->
            // Only remove if this note is still the registered repost for that original
            if (repostOriginalToComposite[origId] == note.id) {
                repostOriginalToComposite.remove(origId)
            }
        }
        if (note.kind == 30023 && note.dTag != null) {
            val key = "${note.author.id.lowercase()}:${note.dTag}"
            articleKeys.remove(key)
        }
    }

    /** Update a note in the byId index (e.g. relay URL merge, author update). O(1). */
    fun updateNote(note: Note) {
        byId[note.id] = note
    }

    /** Clear all indexes. */
    fun clear() {
        byId.clear()
        repostOriginalToComposite.clear()
        articleKeys.clear()
    }

    /**
     * Full rebuild from a list of notes. Use as a safety net for
     * non-hot-path mutations (snapshot restore, full clear+replace)
     * where incremental updates would be overly complex.
     *
     * O(n) but only called on cold paths (mode switch, sign-in, Room restore).
     */
    fun rebuild(notes: List<Note>) {
        clear()
        for (note in notes) {
            addNote(note)
        }
    }

    /** Number of indexed notes. */
    val size: Int get() = byId.size
}

/**
 * Persistent index for the pending ("X new notes") buffer.
 *
 * Replaces O(p) `_pendingNewNotes.indexOfFirst { it.id == ... }` scans
 * and `_pendingNewNotes.map { it.id }.toSet()` rebuilds.
 *
 * Uses reference counting for repost originals so [removeById] is O(1)
 * instead of scanning all pending notes.
 *
 * Synchronized externally via `pendingNotesLock` (same as `_pendingNewNotes`).
 */
class PendingIndex {
    /** O(1) pending note lookup by ID. Replaces `indexOfFirst`. */
    val byId = LinkedHashMap<String, Note>(512)

    /** O(1) repost dedup: set of originalNoteIds in pending. */
    val repostOriginals = HashSet<String>(256)

    /** Reference count per originalNoteId — tracks how many pending notes share this origId.
     *  When count drops to 0, the origId is removed from [repostOriginals]. O(1) removal. */
    private val repostOriginalRefCounts = HashMap<String, Int>(256)

    /** IDs as a read-only set (for `pendingIds.contains()`). */
    val ids: Set<String> get() = byId.keys

    fun addNote(note: Note) {
        byId[note.id] = note
        note.originalNoteId?.let { origId ->
            repostOriginals.add(origId)
            repostOriginalRefCounts[origId] = (repostOriginalRefCounts[origId] ?: 0) + 1
        }
    }

    fun removeById(noteId: String) {
        val removed = byId.remove(noteId)
        removed?.originalNoteId?.let { origId ->
            val count = (repostOriginalRefCounts[origId] ?: 1) - 1
            if (count <= 0) {
                repostOriginals.remove(origId)
                repostOriginalRefCounts.remove(origId)
            } else {
                repostOriginalRefCounts[origId] = count
            }
        }
    }

    fun updateNote(note: Note) {
        byId[note.id] = note
    }

    fun clear() {
        byId.clear()
        repostOriginals.clear()
        repostOriginalRefCounts.clear()
    }

    fun rebuild(notes: List<Note>) {
        clear()
        for (note in notes) {
            addNote(note)
        }
    }

    val size: Int get() = byId.size
}
