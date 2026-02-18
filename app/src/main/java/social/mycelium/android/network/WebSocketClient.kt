package social.mycelium.android.network

import social.mycelium.android.data.Note
import social.mycelium.android.data.NoteUpdate
import social.mycelium.android.data.WebSocketMessage
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

class WebSocketClient {
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocketSession: DefaultWebSocketSession? = null
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()
    
    private val _realTimeUpdates = MutableSharedFlow<NoteUpdate>()
    val realTimeUpdates: SharedFlow<NoteUpdate> = _realTimeUpdates.asSharedFlow()
    
    private val json = Json {
        ignoreUnknownKeys = true
    }
    
    suspend fun connect(serverUrl: String = "ws://localhost:8080/ws") {
        try {
            webSocketSession = client.webSocketSession(serverUrl)
            _isConnected.value = true
            
            // Start listening for messages
            scope.launch {
                listenForMessages()
            }
        } catch (e: Exception) {
            _isConnected.value = false
            // For demo purposes, we'll simulate connection and use sample data
            simulateConnection()
        }
    }
    
    private suspend fun simulateConnection() {
        // Simulate connection for demo purposes
        _isConnected.value = true
        // In a real app, you would load initial data from the server
        // For now, we'll use sample data
    }
    
    private suspend fun listenForMessages() {
        try {
            webSocketSession?.incoming?.consumeAsFlow()?.collect { frame ->
                if (frame is Frame.Text) {
                    val message = json.decodeFromString<WebSocketMessage>(frame.readText())
                    handleMessage(message)
                }
            }
        } catch (e: Exception) {
            _isConnected.value = false
        }
    }
    
    private suspend fun handleMessage(message: WebSocketMessage) {
        when (message.type) {
            "note_update" -> {
                val update = json.decodeFromString<NoteUpdate>(message.data)
                _realTimeUpdates.emit(update)
            }
            "notes_sync" -> {
                val notes = json.decodeFromString<List<Note>>(message.data)
                _notes.value = notes
            }
        }
    }
    
    suspend fun sendNoteUpdate(update: NoteUpdate) {
        try {
            val message = WebSocketMessage(
                type = "note_update",
                data = json.encodeToString(NoteUpdate.serializer(), update)
            )
            webSocketSession?.send(Frame.Text(json.encodeToString(WebSocketMessage.serializer(), message)))
        } catch (e: Exception) {
            // Handle error - in demo mode, we'll just simulate the update locally
            simulateNoteUpdate(update)
        }
    }
    
    private fun simulateNoteUpdate(update: NoteUpdate) {
        // Simulate real-time updates for demo purposes
        // In a real app, this would be handled by the server
        val currentNotes = _notes.value.toMutableList()
        val noteIndex = currentNotes.indexOfFirst { it.id == update.noteId }
        
        if (noteIndex != -1) {
            val note = currentNotes[noteIndex]
            val updatedNote = when (update.action) {
                "like" -> note.copy(likes = note.likes + 1, isLiked = true)
                "unlike" -> note.copy(likes = note.likes - 1, isLiked = false)
                "share" -> note.copy(shares = note.shares + 1, isShared = true)
                else -> note
            }
            currentNotes[noteIndex] = updatedNote
            _notes.value = currentNotes
        }
    }
    
    suspend fun loadNotes(notes: List<Note>) {
        _notes.value = notes
    }
    
    suspend fun disconnect() {
        webSocketSession?.close()
        webSocketSession = null
        _isConnected.value = false
    }
    
    fun cleanup() {
        scope.cancel()
        client.close()
    }
}
