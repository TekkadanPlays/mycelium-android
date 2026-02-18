package social.mycelium.android.viewmodel

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import social.mycelium.android.data.Note
import social.mycelium.android.data.Author
import social.mycelium.android.repository.NotesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class AnnouncementsUiState(
    val announcements: List<Note> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasRelay: Boolean = false
)

class AnnouncementsViewModel : ViewModel() {
    private val notesRepository = NotesRepository.getInstance()

    private val _uiState = MutableStateFlow(AnnouncementsUiState())
    val uiState: StateFlow<AnnouncementsUiState> = _uiState.asStateFlow()

    // Tekkadan announcement pubkey (example - configure this)
    private var tekkaданPubkey: String? = null
    private var announcementRelay: String? = null

    companion object {
        private const val TAG = "AnnouncementsViewModel"

        // Default Tekkadan values (configure these properly)
        const val DEFAULT_TEKKADAN_RELAY = "wss://relay.damus.io"

        // Create a fake Tekkadan announcement
        private val TEKKADAN_AUTHOR = Author(
            id = "tekkadan_fake_id",
            username = "tekkadan",
            displayName = "Tekkadan",
            avatarUrl = null,
            isVerified = true
        )
    }

    init {
        // Load with sample announcement initially
        loadSampleAnnouncement()
        observeNotesFromRepository()
    }

    private fun loadSampleAnnouncement() {
        val sampleAnnouncement = Note(
            id = "announcement_sample_1",
            author = TEKKADAN_AUTHOR,
            content = "Welcome to Mycelium! 🐸\n\nThis is your announcements feed. Official updates from Tekkadan will appear here.\n\nTo see real announcements, configure your announcement relay in Settings.",
            timestamp = System.currentTimeMillis(),
            likes = 0,
            shares = 0,
            comments = 0,
            isLiked = false,
            hashtags = listOf("Mycelium", "announcement"),
            mediaUrls = emptyList()
        )

        _uiState.update { it.copy(announcements = listOf(sampleAnnouncement), isLoading = false, hasRelay = false) }
    }

    private fun observeNotesFromRepository() {
        viewModelScope.launch {
            notesRepository.notes.collect { notes ->
                // If we have real notes, use those instead of sample
                if (notes.isNotEmpty()) {
                    _uiState.update { it.copy(announcements = notes, isLoading = false, hasRelay = true) }
                }
            }
        }

        viewModelScope.launch {
            notesRepository.isLoading.collect { isLoading ->
                _uiState.update { it.copy(isLoading = isLoading) }
            }
        }

        viewModelScope.launch {
            notesRepository.error.collect { error ->
                if (error != null) {
                    _uiState.update { it.copy(error = error) }
                }
            }
        }
    }

    /**
     * Set the Tekkadan pubkey for filtering announcements
     */
    fun setTekkaданPubkey(pubkey: String) {
        tekkaданPubkey = pubkey
        Log.d(TAG, "Tekkadan pubkey set to: ${pubkey.take(8)}...")
    }

    /**
     * Set the announcement relay
     */
    fun setAnnouncementRelay(relayUrl: String) {
        announcementRelay = relayUrl
        Log.d(TAG, "Announcement relay set to: $relayUrl")
    }

    /**
     * Load announcements from configured relay and author
     */
    fun loadAnnouncements() {
        val relay = announcementRelay ?: DEFAULT_TEKKADAN_RELAY
        val pubkey = tekkaданPubkey

        if (pubkey == null) {
            Log.w(TAG, "No Tekkadan pubkey configured, using default relay without filter")
            loadFromDefaultRelay(relay)
            return
        }

        Log.d(TAG, "Loading announcements from $relay for author ${pubkey.take(8)}...")
        _uiState.update { it.copy(hasRelay = true, isLoading = true) }

        viewModelScope.launch {
            try {
                // No disconnectAll() - state machine only updates subscription (fast feed switch)
                notesRepository.connectToRelays(listOf(relay))
                notesRepository.subscribeToAuthorNotes(
                    relayUrls = listOf(relay),
                    authorPubkey = pubkey,
                    limit = 50
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading announcements: ${e.message}", e)
                _uiState.update { it.copy(error = "Failed to load announcements: ${e.message}", isLoading = false) }
            }
        }
    }

    private fun loadFromDefaultRelay(relay: String) {
        Log.d(TAG, "Loading from default relay: $relay")
        _uiState.update { it.copy(hasRelay = true, isLoading = true) }

        viewModelScope.launch {
            try {
                // No disconnectAll() - state machine only updates subscription (fast feed switch)
                notesRepository.connectToRelays(listOf(relay))
                notesRepository.subscribeToNotes(limit = 50)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading from default relay: ${e.message}", e)
                _uiState.update { it.copy(error = "Failed to load: ${e.message}", isLoading = false) }
            }
        }
    }

    /**
     * Refresh announcements
     */
    fun refreshAnnouncements() {
        Log.d(TAG, "Refreshing announcements")
        viewModelScope.launch {
            notesRepository.refresh()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Do not call notesRepository.disconnectAll() - shared connection and notes outlive this screen
    }
}
