package social.mycelium.android.repository

import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.crypto.KeyPair
import com.example.cybin.relay.SubscriptionPriority
import com.example.cybin.signer.NostrSigner
import com.example.cybin.signer.NostrSignerInternal
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.mycelium.android.data.Conversation
import social.mycelium.android.data.DirectMessage
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for NIP-17 gift-wrapped direct messages.
 *
 * Flow: kind 1059 (gift wrap) → NIP-44 decrypt → kind 13 (seal) → NIP-44 decrypt → kind 14 (rumor/chat message).
 *
 * Subscription: kind 1059 with #p = our pubkey on inbox relays.
 * Sending: build kind 14 rumor → wrap in kind 13 seal (signed by us) → wrap in kind 1059 gift wrap (signed by random key).
 */
object DirectMessageRepository {

    private const val TAG = "DirectMessageRepo"
    private const val KIND_GIFT_WRAP = 1059
    private const val KIND_SEAL = 13
    private const val KIND_DM = 14
    private const val TWO_WEEKS_SEC = 14 * 24 * 60 * 60L

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Coroutine failed: ${t.message}", t)
        }
    )

    private val profileCache = ProfileMetadataCache.getInstance()

    /** All decrypted messages keyed by gift-wrap event ID. */
    private val messagesById = ConcurrentHashMap<String, DirectMessage>()

    /** Messages grouped by peer pubkey (conversation partner). */
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    /** Messages for the currently open conversation. */
    private val _activeMessages = MutableStateFlow<List<DirectMessage>>(emptyList())
    val activeMessages: StateFlow<List<DirectMessage>> = _activeMessages.asStateFlow()

    /** Currently viewed peer pubkey (set when user opens a chat). */
    private var activePeer: String? = null

    /** Set of seen gift-wrap IDs to prevent duplicate processing. */
    private val processedIds = ConcurrentHashMap.newKeySet<String>()

    private var dmHandle: TemporarySubscriptionHandle? = null
    private var userPubkey: String? = null
    private var userSigner: NostrSigner? = null

    /** Debug: subscription status for UI display. */
    private val _debugStatus = MutableStateFlow("Not started")
    val debugStatus: StateFlow<String> = _debugStatus.asStateFlow()

    /**
     * Start subscribing to gift-wrapped DMs for the given user.
     */
    /** Well-known relays that commonly carry NIP-17 gift wraps. */
    private val DM_FALLBACK_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
        "wss://relay.primal.net",
        "wss://purplepag.es",
        "wss://auth.nostr1.com",
        "wss://inbox.nostr.wine",
        "wss://relay.nostr.net",
    )

    fun startSubscription(pubkey: String, signer: NostrSigner, relayUrls: List<String>) {
        userPubkey = pubkey
        userSigner = signer

        // Merge user relays + well-known DM relays for broad coverage
        val allRelays = (relayUrls + DM_FALLBACK_RELAYS)
            .map { it.trim().removeSuffix("/") }
            .distinct()

        _debugStatus.value = "Subscribing on ${allRelays.size} relays..."
        Log.d(TAG, "startSubscription: signer=${signer::class.simpleName}, pubkey=${pubkey.take(8)}, userRelays=${relayUrls.size}, total=${allRelays.size}")

        // Subscribe to kind 1059 with #p = our pubkey
        val since = (System.currentTimeMillis() / 1000) - TWO_WEEKS_SEC
        val filter = Filter(
            kinds = listOf(KIND_GIFT_WRAP),
            tags = mapOf("p" to listOf(pubkey)),
            since = since,
            limit = 500
        )
        Log.d(TAG, "Filter: kinds=[1059], #p=${pubkey.take(8)}, since=$since, limit=500")

        dmHandle?.cancel()
        val stateMachine = RelayConnectionStateMachine.getInstance()
        dmHandle = stateMachine.requestTemporarySubscription(
            allRelays, filter, priority = SubscriptionPriority.NORMAL
        ) { event ->
            Log.d(TAG, "Received event kind=${event.kind} id=${event.id.take(8)} from=${event.pubKey.take(8)}")
            _debugStatus.value = "Received ${processedIds.size + 1} events, ${messagesById.size} decrypted"
            handleGiftWrap(event)
        }
        _debugStatus.value = "Listening on ${allRelays.size} relays (${processedIds.size} events)"
        Log.d(TAG, "DM subscription started on ${allRelays.size} relays for ${pubkey.take(8)}...")
    }

    fun stopSubscription() {
        dmHandle?.cancel()
        dmHandle = null
        Log.d(TAG, "DM subscription stopped")
    }

    /**
     * Set the active conversation peer. Messages for this peer will be emitted to [activeMessages].
     */
    fun setActivePeer(peerPubkey: String?) {
        activePeer = peerPubkey
        refreshActiveMessages()
    }

    /**
     * Send a NIP-17 gift-wrapped DM to the specified recipient.
     */
    fun sendMessage(
        content: String,
        recipientPubkey: String,
        signer: NostrSigner,
        relayUrls: Set<String>,
        replyToId: String? = null
    ) {
        val senderPubkey = userPubkey ?: return
        scope.launch {
            try {
                // 1. Build kind 14 rumor (unsigned)
                val rumorCreatedAt = com.example.cybin.core.nowUnixSeconds()
                val rumorTags = mutableListOf<Array<String>>()
                rumorTags.add(arrayOf("p", recipientPubkey))
                replyToId?.let { rumorTags.add(arrayOf("e", it, "", "reply")) }
                val rumor = Event(
                    id = "", // Rumor has no ID/sig
                    pubKey = senderPubkey,
                    createdAt = rumorCreatedAt,
                    kind = KIND_DM,
                    tags = rumorTags.toTypedArray(),
                    content = content,
                    sig = "" // Unsigned rumor
                )
                val rumorJson = rumor.toJson()

                // 2. Build kind 13 seal: encrypt rumor to recipient, sign with our key
                val sealContent = signer.nip44Encrypt(rumorJson, recipientPubkey)
                // Randomize created_at (up to 2 days offset)
                val sealCreatedAt = rumorCreatedAt - (0..172800).random()
                val sealTemplate = com.example.cybin.core.EventTemplate(
                    createdAt = sealCreatedAt,
                    kind = KIND_SEAL,
                    tags = emptyArray(),
                    content = sealContent
                )
                val signedSeal = signer.sign(sealTemplate)

                // 3. Build kind 1059 gift wrap to RECIPIENT: encrypt seal with random key
                sendGiftWrap(signedSeal, recipientPubkey, relayUrls)

                // 4. Build kind 1059 gift wrap to SELF: so we can see sent messages
                val selfSealContent = signer.nip44Encrypt(rumorJson, senderPubkey)
                val selfSealTemplate = com.example.cybin.core.EventTemplate(
                    createdAt = sealCreatedAt,
                    kind = KIND_SEAL,
                    tags = emptyArray(),
                    content = selfSealContent
                )
                val selfSignedSeal = signer.sign(selfSealTemplate)
                sendGiftWrap(selfSignedSeal, senderPubkey, relayUrls)

                // Add to local state immediately
                val dm = DirectMessage(
                    id = signedSeal.id,
                    senderPubkey = senderPubkey,
                    recipientPubkey = recipientPubkey,
                    content = content,
                    createdAt = rumorCreatedAt,
                    replyToId = replyToId,
                    isOutgoing = true
                )
                messagesById[dm.id] = dm
                rebuildConversations()
                refreshActiveMessages()

                Log.d(TAG, "Sent DM to ${recipientPubkey.take(8)}...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send DM: ${e.message}", e)
            }
        }
    }

    private suspend fun sendGiftWrap(seal: Event, recipientPubkey: String, relayUrls: Set<String>) {
        val randomKeyPair = KeyPair() // Throwaway key
        val randomSigner = NostrSignerInternal(randomKeyPair)

        val wrapContent = randomSigner.nip44Encrypt(seal.toJson(), recipientPubkey)
        val wrapCreatedAt = com.example.cybin.core.nowUnixSeconds() - (0..172800).random()
        val wrapTemplate = com.example.cybin.core.EventTemplate(
            createdAt = wrapCreatedAt,
            kind = KIND_GIFT_WRAP,
            tags = arrayOf(arrayOf("p", recipientPubkey)),
            content = wrapContent
        )
        val signedWrap = randomSigner.sign(wrapTemplate)
        RelayConnectionStateMachine.getInstance().send(signedWrap, relayUrls)
    }

    /**
     * Handle an incoming kind 1059 gift wrap event.
     * Decrypts: gift wrap → seal → rumor.
     */
    private fun handleGiftWrap(event: Event) {
        if (event.kind != KIND_GIFT_WRAP) return
        if (!processedIds.add(event.id)) return // Already processed

        val signer = userSigner ?: return
        val myPubkey = userPubkey ?: return

        scope.launch {
            try {
                // Step 1: Decrypt gift wrap content using our key + gift wrap pubkey
                Log.d(TAG, "Step 1: Decrypting gift wrap ${event.id.take(8)} content(${event.content.length} chars) with wrapPubkey=${event.pubKey.take(8)}")
                val sealJson = signer.nip44Decrypt(event.content, event.pubKey)
                Log.d(TAG, "Step 1 result: sealJson length=${sealJson.length}, starts=${sealJson.take(60)}")
                val seal = Event.fromJsonOrNull(sealJson)
                if (seal == null || seal.kind != KIND_SEAL) {
                    Log.w(TAG, "Invalid seal in gift wrap ${event.id.take(8)}, seal=${seal?.kind}, jsonLen=${sealJson.length}")
                    return@launch
                }

                // Step 2: Decrypt seal content using our key + seal pubkey (sender)
                Log.d(TAG, "Step 2: Decrypting seal from ${seal.pubKey.take(8)}, content(${seal.content.length} chars)")
                val rumorJson = signer.nip44Decrypt(seal.content, seal.pubKey)
                Log.d(TAG, "Step 2 result: rumorJson length=${rumorJson.length}, starts=${rumorJson.take(60)}")
                val rumor = Event.fromJsonOrNull(rumorJson)
                if (rumor == null || rumor.kind != KIND_DM) {
                    Log.w(TAG, "Invalid rumor in seal from ${seal.pubKey.take(8)}, rumor=${rumor?.kind}")
                    return@launch
                }

                // Step 3: Extract message data from rumor
                val senderPubkey = seal.pubKey
                val recipientPubkey = rumor.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1) ?: myPubkey
                val replyToId = rumor.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
                val subject = rumor.tags.firstOrNull { it.firstOrNull() == "subject" }?.getOrNull(1)

                val dm = DirectMessage(
                    id = event.id,
                    senderPubkey = senderPubkey,
                    recipientPubkey = recipientPubkey,
                    content = rumor.content,
                    createdAt = rumor.createdAt,
                    replyToId = replyToId,
                    subject = subject,
                    isOutgoing = senderPubkey.equals(myPubkey, ignoreCase = true)
                )

                messagesById[dm.id] = dm
                rebuildConversations()
                refreshActiveMessages()

                Log.d(TAG, "Decrypted DM from ${senderPubkey.take(8)}: ${rumor.content.take(30)}...")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt gift wrap ${event.id.take(8)}: ${e.message}")
            }
        }
    }

    private fun rebuildConversations() {
        val myPubkey = userPubkey ?: return
        val allMessages = messagesById.values.toList()

        // Group by peer pubkey
        val grouped = allMessages.groupBy { dm ->
            if (dm.senderPubkey.equals(myPubkey, ignoreCase = true)) dm.recipientPubkey
            else dm.senderPubkey
        }

        val convos = grouped.map { (peerPubkey, messages) ->
            val sorted = messages.sortedByDescending { it.createdAt }
            val author = profileCache.getAuthor(peerPubkey)
            Conversation(
                peerPubkey = peerPubkey,
                peerDisplayName = author?.displayName
                    ?: author?.username
                    ?: peerPubkey.take(12) + "...",
                peerAvatarUrl = author?.avatarUrl,
                lastMessage = sorted.firstOrNull(),
                messageCount = messages.size,
                unreadCount = 0 // TODO: track read state
            )
        }.sortedByDescending { it.lastMessage?.createdAt ?: 0L }

        _conversations.value = convos
    }

    private fun refreshActiveMessages() {
        val peer = activePeer ?: return
        val myPubkey = userPubkey ?: return
        val messages = messagesById.values
            .filter { dm ->
                (dm.senderPubkey.equals(peer, ignoreCase = true) && dm.recipientPubkey.equals(myPubkey, ignoreCase = true)) ||
                (dm.senderPubkey.equals(myPubkey, ignoreCase = true) && dm.recipientPubkey.equals(peer, ignoreCase = true))
            }
            .sortedBy { it.createdAt }
        _activeMessages.value = messages
    }

    /** Clear the visual unread state on all conversations. */
    fun markAllAsRead() {
        val current = _conversations.value
        if (current.any { it.unreadCount > 0 }) {
            _conversations.value = current.map { it.copy(unreadCount = 0) }
        }
    }

    fun clearAll() {
        stopSubscription()
        messagesById.clear()
        processedIds.clear()
        _conversations.value = emptyList()
        _activeMessages.value = emptyList()
        activePeer = null
        userPubkey = null
        userSigner = null
    }
}
