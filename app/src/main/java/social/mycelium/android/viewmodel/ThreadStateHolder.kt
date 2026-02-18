package social.mycelium.android.viewmodel

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import social.mycelium.android.ui.screens.CommentState

/**
 * State holder for thread view states across navigation.
 * Persists scroll positions and comment collapse states for each individual thread.
 *
 * Similar to Primal's approach of maintaining state per screen instance.
 * Note: Does not survive process death, but survives configuration changes via remember.
 */
class ThreadStateHolder {
    // Map of threadId -> scroll state (first visible item index and offset)
    private val scrollStates = mutableStateMapOf<String, ScrollState>()

    // Map of threadId -> Map of commentId -> CommentState
    private val commentStates = mutableStateMapOf<String, MutableMap<String, CommentState>>()

    // Map of threadId -> expanded controls comment ID (for comment-thread UI)
    private val expandedControls = mutableStateMapOf<String, String?>()

    // Map of threadId -> expanded controls reply ID (for reply list: tap to show like/reply/zap row)
    private val expandedReplyControls = mutableStateMapOf<String, String?>()

    data class ScrollState(
        val firstVisibleItemIndex: Int = 0,
        val firstVisibleItemScrollOffset: Int = 0
    )

    /**
     * Get or create scroll state for a thread
     */
    fun getScrollState(threadId: String): ScrollState {
        return scrollStates.getOrPut(threadId) { ScrollState() }
    }

    /**
     * Save scroll state for a thread
     */
    fun saveScrollState(threadId: String, listState: LazyListState) {
        scrollStates[threadId] = ScrollState(
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
        )
    }

    /**
     * Get or create comment states map for a thread
     */
    fun getCommentStates(threadId: String): MutableMap<String, CommentState> {
        return commentStates.getOrPut(threadId) { mutableStateMapOf() }
    }

    /**
     * Get expanded controls comment ID for a thread
     */
    fun getExpandedControls(threadId: String): String? {
        return expandedControls[threadId]
    }

    /**
     * Set expanded controls comment ID for a thread
     */
    fun setExpandedControls(threadId: String, commentId: String?) {
        expandedControls[threadId] = commentId
    }

    /**
     * Get expanded controls reply ID for a thread (which reply card shows the action row)
     */
    fun getExpandedReplyControls(threadId: String): String? = expandedReplyControls[threadId]

    /**
     * Set expanded controls reply ID for a thread
     */
    fun setExpandedReplyControls(threadId: String, replyId: String?) {
        expandedReplyControls[threadId] = replyId
    }

    /**
     * Clear state for a specific thread (optional cleanup)
     */
    fun clearThreadState(threadId: String) {
        scrollStates.remove(threadId)
        commentStates.remove(threadId)
        expandedControls.remove(threadId)
        expandedReplyControls.remove(threadId)
    }
}

/**
 * Remember thread state holder that survives configuration changes
 */
@Composable
fun rememberThreadStateHolder(): ThreadStateHolder {
    return remember { ThreadStateHolder() }
}
