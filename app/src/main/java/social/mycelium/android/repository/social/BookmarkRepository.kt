package social.mycelium.android.repository.social

import social.mycelium.android.debug.MLog
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import com.example.cybin.signer.NostrSigner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle

/**
 * Repository for NIP-51 public bookmarks (kind 10003).
 *
 * Replaceable event: latest kind-10003 from the user holds all bookmarked note IDs as e-tags.
 * Also supports hashtag bookmarks (t-tags) and URL bookmarks (r-tags).
 */
object BookmarkRepository {

    private const val TAG = "BookmarkRepo"
    private const val KIND_BOOKMARKS = 10003

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t ->
            MLog.e(TAG, "Coroutine failed: ${t.message}", t)
        }
    )

    /** Set of bookmarked note IDs (e-tags from kind-10003). */
    private val _bookmarkedNoteIds = MutableStateFlow<Set<String>>(emptySet())
    val bookmarkedNoteIds: StateFlow<Set<String>> = _bookmarkedNoteIds.asStateFlow()

    /** Set of bookmarked hashtags (t-tags from kind-10003). */
    private val _bookmarkedHashtags = MutableStateFlow<Set<String>>(emptySet())
    val bookmarkedHashtags: StateFlow<Set<String>> = _bookmarkedHashtags.asStateFlow()

    private var bookmarkHandle: TemporarySubscriptionHandle? = null
    private var latestBookmarkEvent: Event? = null
    private var userPubkey: String? = null

    /**
     * Fetch the user's bookmark list from relays.
     */
    fun fetchBookmarks(pubkey: String, relayUrls: List<String>) {
        userPubkey = pubkey
        val filter = Filter(
            kinds = listOf(KIND_BOOKMARKS),
            authors = listOf(pubkey),
            limit = 1
        )
        bookmarkHandle?.cancel()
        val stateMachine = RelayConnectionStateMachine.getInstance()
        bookmarkHandle = stateMachine.requestOneShotSubscription(
            relayUrls, filter, priority = SubscriptionPriority.LOW,
            settleMs = 500L, maxWaitMs = 6_000L,
        ) { event ->
            if (event.kind == KIND_BOOKMARKS && event.pubKey.equals(pubkey, ignoreCase = true)) {
                val current = latestBookmarkEvent
                if (current == null || event.createdAt > current.createdAt) {
                    latestBookmarkEvent = event
                    parseBookmarks(event)
                }
            }
        }
        MLog.d(TAG, "Fetching bookmarks (one-shot) for ${pubkey.take(8)} on ${relayUrls.size} relays")
    }

    private fun parseBookmarks(event: Event) {
        val noteIds = mutableSetOf<String>()
        val hashtags = mutableSetOf<String>()

        for (tag in event.tags) {
            when (tag.firstOrNull()) {
                "e" -> tag.getOrNull(1)?.let { noteIds.add(it) }
                "t" -> tag.getOrNull(1)?.let { hashtags.add(it.lowercase()) }
            }
        }

        _bookmarkedNoteIds.value = noteIds
        _bookmarkedHashtags.value = hashtags
        MLog.d(TAG, "Parsed bookmarks: ${noteIds.size} notes, ${hashtags.size} hashtags")
    }

    /**
     * Add a note to public bookmarks by publishing an updated kind-10003 event.
     */
    fun addBookmark(noteId: String, signer: NostrSigner, relayUrls: Set<String>) {
        scope.launch {
            try {
                val current = _bookmarkedNoteIds.value
                if (noteId in current) {
                    MLog.d(TAG, "Already bookmarked ${noteId.take(8)}")
                    return@launch
                }
                val updated = current + noteId
                publishBookmarks(updated, _bookmarkedHashtags.value, signer, relayUrls)
                _bookmarkedNoteIds.value = updated
                MLog.d(TAG, "Bookmarked ${noteId.take(8)}")
            } catch (e: Exception) {
                MLog.e(TAG, "Failed to add bookmark: ${e.message}", e)
            }
        }
    }

    /**
     * Remove a note from public bookmarks.
     */
    fun removeBookmark(noteId: String, signer: NostrSigner, relayUrls: Set<String>) {
        scope.launch {
            try {
                val updated = _bookmarkedNoteIds.value - noteId
                publishBookmarks(updated, _bookmarkedHashtags.value, signer, relayUrls)
                _bookmarkedNoteIds.value = updated
                MLog.d(TAG, "Removed bookmark ${noteId.take(8)}")
            } catch (e: Exception) {
                MLog.e(TAG, "Failed to remove bookmark: ${e.message}", e)
            }
        }
    }

    fun isBookmarked(noteId: String): Boolean = noteId in _bookmarkedNoteIds.value

    private suspend fun publishBookmarks(
        noteIds: Set<String>,
        hashtags: Set<String>,
        signer: NostrSigner,
        relayUrls: Set<String>
    ) {
        val template = com.example.cybin.core.Event.build(KIND_BOOKMARKS) {
            noteIds.forEach { add(arrayOf("e", it)) }
            hashtags.forEach { add(arrayOf("t", it)) }
        }
        val signed = signer.sign(template)
        RelayConnectionStateMachine.getInstance().send(signed, relayUrls)
        latestBookmarkEvent = signed
        MLog.d(TAG, "Published bookmarks with ${noteIds.size} notes")
    }

    fun clearAll() {
        bookmarkHandle?.cancel()
        bookmarkHandle = null
        latestBookmarkEvent = null
        _bookmarkedNoteIds.value = emptySet()
        _bookmarkedHashtags.value = emptySet()
        userPubkey = null
    }
}
