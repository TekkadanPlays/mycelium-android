package social.mycelium.android.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import social.mycelium.android.data.Author
import social.mycelium.android.data.Note
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Deep-link target from tapping an Android notification. */
data class PendingNotificationNav(
    val noteId: String,
    val rootNoteId: String? = null,
    val notifType: String? = null
)

/** Thread to open as overlay on the notifications screen (set by deep-link, consumed by composable). */
data class PendingNotifThread(
    val note: Note,
    val replyKind: Int = 1,
    val highlightReplyId: String? = null,
)

/** Holds reaction/zap/boost data for the dedicated ReactionsScreen. */
data class ReactionsData(
    val noteId: String,
    val reactions: List<String> = emptyList(),
    val reactionAuthors: Map<String, List<String>> = emptyMap(),
    val customEmojiUrls: Map<String, String> = emptyMap(),
    val zapAuthors: List<String> = emptyList(),
    val zapAmountByAuthor: Map<String, Long> = emptyMap(),
    val zapTotalSats: Long = 0L,
    val boostAuthors: List<Author> = emptyList(),
    /** NIP-88: poll vote data — non-empty when the note is a kind-1068 poll. */
    val pollVotesByOption: Map<String, List<String>> = emptyMap(),
    /** NIP-88: poll option labels keyed by option code. */
    val pollOptionLabels: Map<String, String> = emptyMap(),
    /** NIP-88: total unique voters on this poll. */
    val pollTotalVoters: Int = 0,
)

data class AppState(
    val currentScreen: String = "dashboard",
    val isSearchMode: Boolean = false,
    val selectedAuthor: Author? = null,
    val selectedNote: Note? = null,
    val previousScreen: String? = null,
    val threadSourceScreen: String? = null,
    /** Relay URLs to use for thread replies when opened from a feed (e.g. topics). Null = use default/favorite category. */
    val threadRelayUrls: List<String>? = null,
    /** When non-null, navigate to image viewer with these URLs and initial index. */
    val imageViewerUrls: List<String>? = null,
    val imageViewerInitialIndex: Int = 0,
    /** When non-null, navigate to video viewer with these URLs and initial index. */
    val videoViewerUrls: List<String>? = null,
    val videoViewerInitialIndex: Int = 0,
    /** Instance key from the feed player so fullscreen reuses the same pooled ExoPlayer. */
    val videoViewerInstanceKey: String? = null,
    /** True when the video viewer was opened by tapping PiP — gesture-back should restore PiP. */
    val videoViewerFromPip: Boolean = false,
    val threadScrollPosition: Int = 0,
    val threadExpandedComments: Set<String> = emptySet(),
    val threadExpandedControls: String? = null,
    val feedScrollPosition: Int = 0,
    val profileScrollPosition: Int = 0,
    val userProfileScrollPosition: Int = 0,
    val backPressCount: Int = 0,
    val showExitSnackbar: Boolean = false,
    val isExitWindowActive: Boolean = false,
    /** Shared media album page index per note ID so album position persists across feed/thread/viewer. */
    val mediaPageByNoteId: Map<String, Int> = emptyMap(),
    /** Note being replied to (shown at top of reply compose screen). Cleared after navigation. */
    val replyToNote: Note? = null,
    /** Notes stored by ID for thread navigation (supports stacked threads). */
    val notesById: Map<String, Note> = emptyMap()
)

class AppViewModel : ViewModel() {
    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    fun updateCurrentScreen(screen: String) {
        _appState.update { it.copy(currentScreen = screen) }
    }

    fun updateSearchMode(isSearchMode: Boolean) {
        _appState.update { it.copy(isSearchMode = isSearchMode) }
    }

    fun updateSelectedAuthor(author: Author?) {
        _appState.update { it.copy(selectedAuthor = author) }
    }

    fun updateSelectedNote(note: Note?) {
        _appState.update { it.copy(selectedNote = note, notesById = if (note != null) it.notesById + (note.id to note) else it.notesById) }
    }

    /** Store a note by ID for thread navigation without changing selectedNote. */
    fun storeNoteForThread(note: Note) {
        _appState.update { it.copy(notesById = it.notesById + (note.id to note)) }
    }

    fun updatePreviousScreen(screen: String?) {
        _appState.update { it.copy(previousScreen = screen) }
    }

    fun updateThreadSourceScreen(screen: String?) {
        _appState.update { it.copy(threadSourceScreen = screen) }
    }

    fun updateThreadRelayUrls(urls: List<String>?) {
        _appState.update { it.copy(threadRelayUrls = urls) }
    }

    fun openImageViewer(urls: List<String>, initialIndex: Int = 0) {
        _appState.update { it.copy(imageViewerUrls = urls, imageViewerInitialIndex = initialIndex.coerceIn(0, urls.size - 1)) }
    }

    fun clearImageViewer() {
        _appState.update { it.copy(imageViewerUrls = null, imageViewerInitialIndex = 0) }
    }

    fun setReplyToNote(note: Note?) {
        _appState.update { it.copy(replyToNote = note) }
    }

    fun openVideoViewer(urls: List<String>, initialIndex: Int = 0, instanceKey: String? = null, fromPip: Boolean = false) {
        _appState.update { it.copy(videoViewerUrls = urls, videoViewerInitialIndex = initialIndex.coerceIn(0, urls.size - 1), videoViewerInstanceKey = instanceKey, videoViewerFromPip = fromPip) }
    }

    fun clearVideoViewer() {
        _appState.update { it.copy(videoViewerUrls = null, videoViewerInitialIndex = 0, videoViewerInstanceKey = null, videoViewerFromPip = false) }
    }

    /** Pending navigation from tapping an Android notification. */
    private val _pendingNotificationNav = MutableStateFlow<PendingNotificationNav?>(null)
    val pendingNotificationNav: StateFlow<PendingNotificationNav?> = _pendingNotificationNav.asStateFlow()

    fun setPendingNotificationNav(nav: PendingNotificationNav?) {
        _pendingNotificationNav.value = nav
    }

    fun consumePendingNotificationNav(): PendingNotificationNav? {
        val nav = _pendingNotificationNav.value
        _pendingNotificationNav.value = null
        return nav
    }

    /** Pending thread to open as overlay on the notifications screen (set by deep-link handler). */
    private val _pendingNotifThread = MutableStateFlow<PendingNotifThread?>(null)
    val pendingNotifThread: StateFlow<PendingNotifThread?> = _pendingNotifThread.asStateFlow()

    fun setPendingNotifThread(thread: PendingNotifThread) {
        _pendingNotifThread.value = thread
    }

    fun consumePendingNotifThread(): PendingNotifThread? {
        val t = _pendingNotifThread.value
        _pendingNotifThread.value = null
        return t
    }

    /** Store the current album page for a note so it persists across feed/thread/viewer. */
    fun updateMediaPage(noteId: String, page: Int) {
        val current = _appState.value.mediaPageByNoteId
        if (current[noteId] != page) {
            _appState.update { it.copy(mediaPageByNoteId = it.mediaPageByNoteId + (noteId to page)) }
        }
    }

    fun getMediaPage(noteId: String): Int = _appState.value.mediaPageByNoteId[noteId] ?: 0

    fun updateThreadScrollPosition(position: Int) {
        _appState.update { it.copy(threadScrollPosition = position) }
    }

    fun updateThreadExpandedComments(comments: Set<String>) {
        _appState.update { it.copy(threadExpandedComments = comments) }
    }

    fun updateThreadExpandedControls(commentId: String?) {
        _appState.update { it.copy(threadExpandedControls = commentId) }
    }

    fun updateFeedScrollPosition(position: Int) {
        _appState.update { it.copy(feedScrollPosition = position) }
    }

    fun updateProfileScrollPosition(position: Int) {
        _appState.update { it.copy(profileScrollPosition = position) }
    }

    fun updateUserProfileScrollPosition(position: Int) {
        _appState.update { it.copy(userProfileScrollPosition = position) }
    }

    fun updateBackPressCount(count: Int) {
        _appState.update { it.copy(backPressCount = count) }
    }

    fun updateShowExitSnackbar(show: Boolean) {
        _appState.update { it.copy(showExitSnackbar = show) }
    }

    fun updateExitWindowActive(isActive: Boolean) {
        _appState.update { it.copy(isExitWindowActive = isActive) }
    }

    // ── Viewed thread tracking for "Clear Read" ──

    /** Note IDs the user has opened in thread view this session. */
    private val viewedThreadIds = mutableSetOf<String>()

    /** Note IDs hidden from the feed via "Clear Read". */
    private val _hiddenNoteIds = MutableStateFlow<Set<String>>(emptySet())
    val hiddenNoteIds: StateFlow<Set<String>> = _hiddenNoteIds.asStateFlow()

    /** Mark a note as viewed (called when opening a thread). */
    fun markThreadViewed(noteId: String) {
        viewedThreadIds.add(noteId)
    }

    /** Whether there are viewed notes that can be cleared. */
    fun hasViewedNotes(): Boolean = viewedThreadIds.isNotEmpty() && viewedThreadIds.any { it !in _hiddenNoteIds.value }

    /** Move all viewed thread IDs into the hidden set. */
    fun clearReadNotes() {
        if (viewedThreadIds.isEmpty()) return
        _hiddenNoteIds.value = _hiddenNoteIds.value + viewedThreadIds
    }

    /** Pending reaction data for the ReactionsScreen route. */
    private val _pendingReactionsData = MutableStateFlow<ReactionsData?>(null)
    val pendingReactionsData: StateFlow<ReactionsData?> = _pendingReactionsData.asStateFlow()

    fun storeReactionsData(data: ReactionsData) {
        _pendingReactionsData.value = data
    }

    fun clearReactionsData() {
        _pendingReactionsData.value = null
    }

    fun resetToDashboard() {
        _appState.value = AppState()
    }

    fun handleAppExit(): Boolean {
        val currentState = _appState.value
        return if (currentState.currentScreen == "dashboard") {
            if (!currentState.isExitWindowActive) {
                // First back press - show snackbar and start 3-second window
                updateBackPressCount(1)
                updateShowExitSnackbar(true)
                updateExitWindowActive(true)
                
                // Start 3-second timeout to reset exit window
                viewModelScope.launch {
                    kotlinx.coroutines.delay(3000) // 3 seconds
                    updateExitWindowActive(false)
                    updateBackPressCount(0)
                    updateShowExitSnackbar(false) // Dismiss snackbar when window expires
                }
                
                false // Don't exit yet
            } else {
                // Second back press within 3-second window - exit
                true
            }
        } else {
            // Not on dashboard - handle normal navigation
            navigateBack()
            false
        }
    }

    fun navigateBack(): String {
        val currentState = _appState.value
        return when {
            currentState.isSearchMode -> {
                updateSearchMode(false)
                "search_mode_handled"
            }
            currentState.currentScreen == "about" && currentState.previousScreen == "settings" -> {
                updateCurrentScreen("settings")
                updatePreviousScreen(null)
                "settings"
            }
            currentState.currentScreen == "appearance" -> {
                updateCurrentScreen("settings")
                "settings"
            }
            currentState.currentScreen == "thread" -> {
                val sourceScreen = currentState.threadSourceScreen ?: "dashboard"
                updateCurrentScreen(sourceScreen)
                updateThreadSourceScreen(null)
                updateThreadRelayUrls(null)
                sourceScreen
            }
            currentState.currentScreen == "profile" -> {
                if (currentState.threadSourceScreen == "thread") {
                    updateCurrentScreen("thread")
                    updateThreadSourceScreen(null)
                    "thread"
                } else {
                    updateCurrentScreen("dashboard")
                    "dashboard"
                }
            }
            currentState.currentScreen == "user_profile" -> {
                updateCurrentScreen("dashboard")
                "dashboard"
            }
            currentState.currentScreen == "settings" -> {
                updateCurrentScreen("dashboard")
                "dashboard"
            }
            else -> {
                updateCurrentScreen("dashboard")
                "dashboard"
            }
        }
    }
}
