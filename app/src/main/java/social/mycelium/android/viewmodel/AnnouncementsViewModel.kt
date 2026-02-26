package social.mycelium.android.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import social.mycelium.android.data.Author
import social.mycelium.android.data.Note
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.repository.RelayStorageManager
import social.mycelium.android.utils.Nip19QuoteParser
import social.mycelium.android.utils.UrlDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AnnouncementsVM"

@Immutable
data class AnnouncementsUiState(
    val notes: List<Note> = emptyList(),
    val isLoading: Boolean = false,
    val hasRelays: Boolean = false,
    val error: String? = null
)

class AnnouncementsViewModel(application: Application) : AndroidViewModel(application) {

    private val profileCache = ProfileMetadataCache.getInstance()
    private val storageManager = RelayStorageManager(application.applicationContext)
    private val relayStateMachine = RelayConnectionStateMachine.getInstance()

    private val _uiState = MutableStateFlow(AnnouncementsUiState())
    val uiState: StateFlow<AnnouncementsUiState> = _uiState.asStateFlow()

    private var subscriptionHandle: TemporarySubscriptionHandle? = null
    private val seenIds = mutableSetOf<String>()

    fun subscribe(pubkey: String) {
        val relays = storageManager.loadAnnouncementRelays(pubkey).map { it.url }
        _uiState.update { it.copy(hasRelays = relays.isNotEmpty()) }

        if (relays.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, notes = emptyList()) }
            return
        }

        unsubscribe()
        seenIds.clear()
        _uiState.update { it.copy(isLoading = true, notes = emptyList()) }

        val filter = Filter(
            kinds = listOf(1, 11, 30023),
            limit = 100
        )

        subscriptionHandle = relayStateMachine.requestTemporarySubscription(
            relayUrls = relays,
            filter = filter,
            onEvent = { event -> handleEvent(event) }
        )

        // Stop loading indicator after timeout
        viewModelScope.launch {
            delay(3000)
            _uiState.update { it.copy(isLoading = false) }
        }

        Log.d(TAG, "Subscribed to ${relays.size} announcement relays")
    }

    fun refresh(pubkey: String) {
        subscribe(pubkey)
    }

    private fun handleEvent(event: Event) {
        if (event.id in seenIds) return
        seenIds.add(event.id)

        val author = profileCache.resolveAuthor(event.pubKey)
        val hashtags = event.tags.toList()
            .filter { tag -> tag.size >= 2 && tag[0] == "t" }
            .mapNotNull { tag -> tag.getOrNull(1) }
        val mediaUrls = UrlDetector.findUrls(event.content)
            .filter { UrlDetector.isImageUrl(it) || UrlDetector.isVideoUrl(it) }
            .distinct()
        val quotedEventIds = Nip19QuoteParser.extractQuotedEventIds(event.content)
        val tags = event.tags.map { it.toList() }

        // Extract title for long-form content (kind 30023) or topics (kind 11)
        val title = event.tags.toList()
            .firstOrNull { it.size >= 2 && (it[0] == "title" || it[0] == "subject") }
            ?.getOrNull(1)

        val note = Note(
            id = event.id,
            author = author,
            content = event.content,
            timestamp = event.createdAt * 1000L,
            hashtags = hashtags,
            mediaUrls = mediaUrls,
            quotedEventIds = quotedEventIds,
            tags = tags,
            kind = event.kind,
            topicTitle = title
        )

        _uiState.update { state ->
            val updated = (state.notes + note).sortedByDescending { it.timestamp }
            state.copy(notes = updated, isLoading = false)
        }
    }

    private fun unsubscribe() {
        subscriptionHandle?.cancel()
        subscriptionHandle = null
    }

    override fun onCleared() {
        super.onCleared()
        unsubscribe()
    }
}
