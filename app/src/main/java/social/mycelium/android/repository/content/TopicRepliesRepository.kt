package social.mycelium.android.repository.content

import android.util.Log
import social.mycelium.android.data.Note
import social.mycelium.android.relay.RelayConnectionStateMachine
import com.example.cybin.core.Event
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import social.mycelium.android.repository.feed.NotesRepository

/**
 * Repository for tracking kind:1 replies to kind:11 topics.
 * 
 * According to NIP-22 Anchored Events spec:
 * - kind:1 notes with matching I tags can reply to kind:11 topics
 * - Uses NIP-10 threading (e tags with root/reply markers)
 * - Enables threaded conversations where regular notes participate in topic discussions
 * 
 * This allows kind:1 for visibility across the mesh network while maintaining topic context.
 */
class TopicRepliesRepository private constructor() {
    
    // Map of topic ID -> list of kind:1 reply notes
    private val _repliesByTopicId = MutableStateFlow<Map<String, List<Note>>>(emptyMap())
    val repliesByTopicId: StateFlow<Map<String, List<Note>>> = _repliesByTopicId.asStateFlow()
    
    // Map of anchor -> list of kind:1 notes with matching I tag (for anchor-scoped feeds)
    private val _notesByAnchor = MutableStateFlow<Map<String, List<Note>>>(emptyMap())
    val notesByAnchor: StateFlow<Map<String, List<Note>>> = _notesByAnchor.asStateFlow()
    
    companion object {
        @Volatile
        private var instance: TopicRepliesRepository? = null
        
        fun getInstance(): TopicRepliesRepository {
            return instance ?: synchronized(this) {
                instance ?: TopicRepliesRepository().also { instance = it }
            }
        }
        
        private const val TAG = "TopicRepliesRepo"
    }
    
    init {
        // Listen to kind:1 events from NotesRepository
        // We'll check each note for I tags and e tags pointing to kind:11 topics
        Log.d(TAG, "TopicRepliesRepository initialized - tracking kind:1 replies to kind:11 topics")
    }
    
    /**
     * Process a kind:1 note to check if it's a reply to a kind:11 topic
     * Returns true if the note is a topic reply
     */
    fun processKind1Note(note: Note): Boolean {
        // Extract I tags (anchors)
        val anchors = extractAnchors(note)
        
        // Extract e tags to find root/reply references
        val rootId = extractRootEventId(note)
        
        // If note has both anchors and a root reference, it's potentially a topic reply
        if (anchors.isNotEmpty() && rootId != null) {
            // Add to replies by topic ID
            addReplyToTopic(rootId, note)
            
            // Add to notes by anchor for anchor-scoped feeds
            anchors.forEach { anchor ->
                addNoteToAnchor(anchor, note)
            }
            
            Log.d(TAG, "Tracked kind:1 reply ${note.id.take(8)} to topic $rootId with anchors: $anchors")
            return true
        }
        
        // Even without root reference, if note has I tags, add to anchor feeds
        if (anchors.isNotEmpty()) {
            anchors.forEach { anchor ->
                addNoteToAnchor(anchor, note)
            }
            Log.d(TAG, "Tracked kind:1 note ${note.id.take(8)} with anchors: $anchors")
            return true
        }
        
        return false
    }
    
    /**
     * Extract anchor identifiers from I tags (NIP-22)
     * I tags format: ["I", "<anchor-value>"]
     */
    private fun extractAnchors(note: Note): List<String> {
        return note.tags
            .filter { it.size >= 2 && it[0] == "I" }
            .map { it[1] }
    }
    
    /**
     * Extract root event ID from e tags (NIP-10)
     * Looks for e tags with "root" marker or uses first e tag as fallback
     */
    private fun extractRootEventId(note: Note): String? {
        // First try to find e tag with "root" marker
        val rootTag = note.tags
            .find { it.size >= 4 && it[0] == "e" && it[3] == "root" }
        
        if (rootTag != null && rootTag.size >= 2) {
            return rootTag[1]
        }
        
        // Fallback to rootNoteId field if available
        if (note.rootNoteId != null) {
            return note.rootNoteId
        }
        
        // Last resort: use first e tag as root
        val firstETag = note.tags.find { it.size >= 2 && it[0] == "e" }
        return firstETag?.getOrNull(1)
    }
    
    /**
     * Add a reply to a topic's reply list
     */
    private fun addReplyToTopic(topicId: String, reply: Note) {
        val current = _repliesByTopicId.value.toMutableMap()
        val replies = current[topicId]?.toMutableList() ?: mutableListOf()
        
        // Avoid duplicates
        if (replies.none { it.id == reply.id }) {
            replies.add(reply)
            // Sort by timestamp (oldest first for thread order)
            replies.sortBy { it.timestamp }
            current[topicId] = replies
            _repliesByTopicId.value = current
        }
    }
    
    /**
     * Add a note to an anchor's note list
     */
    private fun addNoteToAnchor(anchor: String, note: Note) {
        val current = _notesByAnchor.value.toMutableMap()
        val notes = current[anchor]?.toMutableList() ?: mutableListOf()
        
        // Avoid duplicates
        if (notes.none { it.id == note.id }) {
            notes.add(note)
            // Sort by timestamp (newest first for feed order)
            notes.sortByDescending { it.timestamp }
            current[anchor] = notes
            _notesByAnchor.value = current
        }
    }
    
    /**
     * Get replies for a specific topic
     */
    fun getRepliesForTopic(topicId: String): List<Note> {
        return _repliesByTopicId.value[topicId] ?: emptyList()
    }
    
    /**
     * Get notes for a specific anchor
     */
    fun getNotesForAnchor(anchor: String): List<Note> {
        return _notesByAnchor.value[anchor] ?: emptyList()
    }
    
    /**
     * Get reply count for a topic
     */
    fun getReplyCount(topicId: String): Int {
        return _repliesByTopicId.value[topicId]?.size ?: 0
    }
    
    /**
     * Clear all cached replies (for memory management)
     */
    fun clearCache() {
        _repliesByTopicId.value = emptyMap()
        _notesByAnchor.value = emptyMap()
        Log.d(TAG, "Cleared topic replies cache")
    }
}
