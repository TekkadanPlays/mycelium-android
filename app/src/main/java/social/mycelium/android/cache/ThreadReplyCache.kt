package social.mycelium.android.cache

import social.mycelium.android.data.Note
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * In-memory cache of kind-1 replies keyed by root note id.
 * Populated by NotesRepository from the shared state machine feed so thread view shows replies instantly.
 * Bounded by max roots with LRU eviction; supports trim for memory pressure.
 */
object ThreadReplyCache {

    /** Max number of thread roots to keep under normal use. */
    private const val MAX_ROOTS = 200

    /** Size to trim to when UI is hidden. */
    const val TRIM_SIZE_UI_HIDDEN = 100

    /** Size to trim to when app is in background. */
    const val TRIM_SIZE_BACKGROUND = 30

    // Access-order LinkedHashMap for LRU; oldest at iterator head.
    private val cache = object : LinkedHashMap<String, MutableList<Note>>(MAX_ROOTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<Note>>): Boolean =
            size > MAX_ROOTS
    }
    private val lock = ReentrantReadWriteLock()

    /** Emits (rootId, note) when a locally-published reply is added. */
    private val _localReplyAdded = MutableSharedFlow<Pair<String, Note>>(extraBufferCapacity = 8)
    val localReplyAdded: SharedFlow<Pair<String, Note>> = _localReplyAdded.asSharedFlow()

    fun addReply(rootId: String, note: Note) {
        lock.write {
            val list = cache.getOrPut(rootId) { mutableListOf() }
            if (!list.any { it.id == note.id }) {
                list.add(note)
                list.sortBy { it.timestamp }
            }
        }
    }

    /** Add a locally-published reply and notify observers (Kind1RepliesRepository). */
    fun addLocalReply(rootId: String, note: Note) {
        addReply(rootId, note)
        _localReplyAdded.tryEmit(rootId to note)
    }

    fun getReplies(rootId: String): List<Note> =
        lock.read {
            cache[rootId]?.toList() ?: emptyList()
        }

    fun clear() {
        lock.write {
            cache.clear()
        }
    }

    /**
     * Reduce cache to at most maxRoots entries (LRU eviction).
     * Thread-safe.
     */
    fun trimToSize(maxRoots: Int) {
        lock.write {
            while (cache.size > maxRoots && cache.isNotEmpty()) {
                val eldest = cache.keys.iterator().next()
                cache.remove(eldest)
            }
        }
    }
}
