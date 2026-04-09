package social.mycelium.android.ui.components.note

import androidx.compose.runtime.Stable
import social.mycelium.android.data.Note
import social.mycelium.android.repository.social.NoteCounts
import social.mycelium.android.repository.sync.ZapType

/**
 * Grouped callback lambdas for NoteCard interactions.
 * Marked @Stable so Compose treats reference-equal instances as unchanged,
 * preventing unnecessary recomposition when the feed list rebuilds.
 */
@Stable
class NoteCardCallbacks(
    val onLike: (String) -> Unit = {},
    val onShare: (String) -> Unit = {},
    val onComment: (String) -> Unit = {},
    val onReact: (Note, String) -> Unit = { _, _ -> },
    val onBoost: ((Note) -> Unit)? = null,
    val onQuote: ((Note) -> Unit)? = null,
    val onFork: ((Note) -> Unit)? = null,
    val onVote: ((String, String, Int) -> Unit)? = null,
    val onProfileClick: (String) -> Unit = {},
    val onNoteClick: (Note) -> Unit = {},
    val onImageTap: (Note, List<String>, Int) -> Unit = { _, _, _ -> },
    val onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    val onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    val onZap: (String, Long) -> Unit = { _, _ -> },
    val onCustomZap: (String) -> Unit = {},
    val onCustomZapSend: ((Note, Long, ZapType, String) -> Unit)? = null,
    val onZapSettings: () -> Unit = {},
    val onRelayClick: (relayUrl: String) -> Unit = {},
    val onNavigateToRelayList: ((List<String>) -> Unit)? = null,
    val onFollowAuthor: ((String) -> Unit)? = null,
    val onUnfollowAuthor: ((String) -> Unit)? = null,
    val onMessageAuthor: ((String) -> Unit)? = null,
    val onBlockAuthor: ((String) -> Unit)? = null,
    val onMuteAuthor: ((String) -> Unit)? = null,
    val onBookmarkToggle: ((String, Boolean) -> Unit)? = null,
    val onDelete: ((Note) -> Unit)? = null,
    val onMediaPageChanged: (Int) -> Unit = {},
    val onSeeAllReactions: (() -> Unit)? = null,
    val onPollVote: ((String, String, Set<String>, String?) -> Unit)? = null,
    val onDeleteReaction: ((noteId: String, reactionEventId: String, emoji: String) -> Unit)? = null,
)

/**
 * Override counts and reaction data from NoteCountsRepository.
 * Grouped to reduce NoteCard parameter count. Immutable data holder.
 */
@Stable
class NoteCardOverrides(
    val replyCount: Int? = null,
    val zapCount: Int? = null,
    val zapTotalSats: Long? = null,
    val reactions: List<String>? = null,
    val reactionAuthors: Map<String, List<String>>? = null,
    val zapAuthors: List<String>? = null,
    val zapAmountByAuthor: Map<String, Long>? = null,
    val customEmojiUrls: Map<String, String>? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NoteCardOverrides) return false
        return replyCount == other.replyCount &&
                zapCount == other.zapCount &&
                zapTotalSats == other.zapTotalSats &&
                reactions == other.reactions &&
                reactionAuthors == other.reactionAuthors &&
                zapAuthors == other.zapAuthors &&
                zapAmountByAuthor == other.zapAmountByAuthor &&
                customEmojiUrls == other.customEmojiUrls
    }

    override fun hashCode(): Int {
        var result = replyCount ?: 0
        result = 31 * result + (zapCount ?: 0)
        result = 31 * result + (zapTotalSats?.hashCode() ?: 0)
        result = 31 * result + (reactions?.hashCode() ?: 0)
        result = 31 * result + (reactionAuthors?.hashCode() ?: 0)
        result = 31 * result + (zapAuthors?.hashCode() ?: 0)
        result = 31 * result + (zapAmountByAuthor?.hashCode() ?: 0)
        result = 31 * result + (customEmojiUrls?.hashCode() ?: 0)
        return result
    }

    companion object {
        val EMPTY = NoteCardOverrides()

        fun fromCounts(counts: NoteCounts?, replyCount: Int? = null): NoteCardOverrides {
            if (counts == null && replyCount == null) return EMPTY
            return NoteCardOverrides(
                replyCount = replyCount ?: counts?.replyCount,
                zapCount = counts?.zapCount,
                zapTotalSats = counts?.zapTotalSats,
                reactions = counts?.reactions,
                reactionAuthors = counts?.reactionAuthors,
                zapAuthors = counts?.zapAuthors,
                zapAmountByAuthor = counts?.zapAmountByAuthor,
                customEmojiUrls = counts?.customEmojiUrls,
            )
        }
    }
}

/**
 * Display configuration flags for NoteCard appearance.
 * Grouped to reduce parameter count. Immutable.
 */
@Stable
class NoteCardConfig(
    val showActionRow: Boolean = true,
    val actionRowSchema: ActionRowSchema = ActionRowSchema.KIND1_FEED,
    val rootAuthorId: String? = null,
    val expandLinkPreviewInThread: Boolean = false,
    val showHashtagsSection: Boolean = true,
    val initialMediaPage: Int = 0,
    val isVisible: Boolean = true,
    val compactMedia: Boolean = false,
    val showSensitiveContent: Boolean = false,
    val moderationFlagCount: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NoteCardConfig) return false
        return showActionRow == other.showActionRow &&
                actionRowSchema == other.actionRowSchema &&
                rootAuthorId == other.rootAuthorId &&
                expandLinkPreviewInThread == other.expandLinkPreviewInThread &&
                showHashtagsSection == other.showHashtagsSection &&
                initialMediaPage == other.initialMediaPage &&
                isVisible == other.isVisible &&
                compactMedia == other.compactMedia &&
                showSensitiveContent == other.showSensitiveContent &&
                moderationFlagCount == other.moderationFlagCount
    }

    override fun hashCode(): Int {
        var result = showActionRow.hashCode()
        result = 31 * result + actionRowSchema.hashCode()
        result = 31 * result + (rootAuthorId?.hashCode() ?: 0)
        result = 31 * result + expandLinkPreviewInThread.hashCode()
        result = 31 * result + showHashtagsSection.hashCode()
        result = 31 * result + initialMediaPage
        result = 31 * result + isVisible.hashCode()
        result = 31 * result + compactMedia.hashCode()
        result = 31 * result + showSensitiveContent.hashCode()
        result = 31 * result + moderationFlagCount
        return result
    }
}

/**
 * Per-note interaction state (zap progress, own reactions, etc.).
 * Grouped to reduce parameter count.
 */
@Stable
class NoteCardInteractionState(
    val isZapInProgress: Boolean = false,
    val isZapped: Boolean = false,
    val isBoosted: Boolean = false,
    val myZappedAmount: Long? = null,
    val ownVoteValue: Int = 0,
    val voteScore: Int = 0,
    val isAuthorFollowed: Boolean = false,
    val shouldCloseZapMenus: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NoteCardInteractionState) return false
        return isZapInProgress == other.isZapInProgress &&
                isZapped == other.isZapped &&
                isBoosted == other.isBoosted &&
                myZappedAmount == other.myZappedAmount &&
                ownVoteValue == other.ownVoteValue &&
                voteScore == other.voteScore &&
                isAuthorFollowed == other.isAuthorFollowed &&
                shouldCloseZapMenus == other.shouldCloseZapMenus
    }

    override fun hashCode(): Int {
        var result = isZapInProgress.hashCode()
        result = 31 * result + isZapped.hashCode()
        result = 31 * result + isBoosted.hashCode()
        result = 31 * result + (myZappedAmount?.hashCode() ?: 0)
        result = 31 * result + ownVoteValue
        result = 31 * result + voteScore
        result = 31 * result + isAuthorFollowed.hashCode()
        result = 31 * result + shouldCloseZapMenus.hashCode()
        return result
    }
}
