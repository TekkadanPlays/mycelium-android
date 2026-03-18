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
import social.mycelium.android.relay.RelayHealthTracker
import social.mycelium.android.relay.TemporarySubscriptionHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for NIP-17 gift-wrapped direct messages.
 *
 * ## Two-phase design (fetch vs decrypt)
 * 1. **Fetch phase** (automatic at login): Subscribes to kind-1059 gift wraps on inbox relays.
 *    Raw encrypted events are buffered without any Amber/signer interaction.
 * 2. **Decrypt phase** (user-triggered): When the user navigates to the DM page, [decryptPending]
 *    is called. This uses the full `nip44Decrypt` path which may launch Amber's foreground
 *    Activity for user approval. If the user declines, decryption stops — no harassment.
 *    Navigating away and back to the DM page triggers another attempt.
 *
 * This design ensures:
 * - No Amber popups unless the user explicitly visits DMs
 * - User has full control over whether to allow DM decryption
 * - Raw events are still collected so nothing is missed
 *
 * Flow: kind 1059 (gift wrap) → NIP-44 decrypt → kind 13 (seal) → NIP-44 decrypt → kind 14 (rumor/chat message).
 * Subscription: kind 1059 with #p = our pubkey on inbox relays.
 * Sending: build kind 14 rumor → wrap in kind 13 seal (signed by us) → wrap in kind 1059 gift wrap (signed by random key).
 */
object DirectMessageRepository {

    private const val TAG = "DirectMessageRepo"
    private const val KIND_GIFT_WRAP = 1059
    private const val KIND_SEAL = 13
    private const val KIND_DM = 14
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

    // ── Fetch phase: raw encrypted events buffered here ──────────────

    /** Raw kind-1059 gift wrap events, not yet decrypted. Keyed by event ID. */
    private val pendingGiftWraps = ConcurrentHashMap<String, Event>()

    /** Relay URLs where each gift wrap was seen. Keyed by event ID. */
    private val giftWrapRelays = ConcurrentHashMap<String, MutableSet<String>>()

    /** IDs already successfully decrypted — never re-attempt. */
    private val decryptedIds = ConcurrentHashMap.newKeySet<String>()

    /** Number of undecrypted gift wraps available (for badge display). */
    private val _pendingGiftWrapCount = MutableStateFlow(0)
    val pendingGiftWrapCount: StateFlow<Int> = _pendingGiftWrapCount.asStateFlow()

    /** True while a decrypt batch is in progress. */
    private val _isDecrypting = MutableStateFlow(false)
    val isDecrypting: StateFlow<Boolean> = _isDecrypting.asStateFlow()

    private var dmHandle: TemporarySubscriptionHandle? = null
    private var userPubkey: String? = null
    private var userSigner: NostrSigner? = null
    private var userInboxRelays: List<String> = emptyList()
    private var userOutboxRelays: List<String> = emptyList()

    /** Debug: subscription status for UI display. */
    private val _debugStatus = MutableStateFlow("Not started")
    val debugStatus: StateFlow<String> = _debugStatus.asStateFlow()

    /** Cache of fetched inbox relays per pubkey (NIP-65 kind-10002 only — kind-10050 intentionally ignored). */
    private val inboxRelayCache = ConcurrentHashMap<String, List<String>>()

    /** Maps gift wrap event IDs → DM message ID (seal ID) for relay orb confirmations. */
    private val giftWrapToDmId = ConcurrentHashMap<String, String>()

    private var confirmationObserverJob: kotlinx.coroutines.Job? = null

    /**
     * Start subscribing to gift-wrapped DMs for the given user.
     * **Fetch only** — raw kind-1059 events are buffered without any Amber interaction.
     * Call [decryptPending] when the user visits the DM page to trigger decryption.
     */
    fun startSubscription(pubkey: String, signer: NostrSigner, inboxRelays: List<String>, outboxRelays: List<String> = emptyList()) {
        userPubkey = pubkey
        userSigner = signer
        userInboxRelays = inboxRelays.map { it.trim().removeSuffix("/") }.distinct()
        userOutboxRelays = outboxRelays.map { it.trim().removeSuffix("/") }.distinct()

        // Subscribe on inbox + outbox combined so self-wraps sent to outbox are also found
        val allRelays = (userInboxRelays + userOutboxRelays).distinct()

        _debugStatus.value = "Subscribing on ${allRelays.size} relays..."
        Log.d(TAG, "startSubscription: pubkey=${pubkey.take(8)}, inbox=${userInboxRelays.size}, outbox=${userOutboxRelays.size}, total=${allRelays.size}")

        // Subscribe to kind 1059 with #p = our pubkey — no time constraint, fetch everything
        val filter = Filter(
            kinds = listOf(KIND_GIFT_WRAP),
            tags = mapOf("p" to listOf(pubkey)),
            limit = 5000
        )
        Log.d(TAG, "Filter: kinds=[1059], #p=${pubkey.take(8)}, limit=5000 (no since)")

        dmHandle?.cancel()
        val stateMachine = RelayConnectionStateMachine.getInstance()
        dmHandle = stateMachine.requestTemporarySubscriptionWithRelay(
            allRelays, filter, priority = SubscriptionPriority.NORMAL
        ) { event, relayUrl ->
            bufferGiftWrap(event, relayUrl)
        }
        _debugStatus.value = "Listening on ${allRelays.size} relays"
        Log.d(TAG, "DM subscription started on ${allRelays.size} relays for ${pubkey.take(8)}...")

        // Observe relay OK confirmations to update sent DM relay orbs
        confirmationObserverJob?.cancel()
        confirmationObserverJob = scope.launch {
            RelayHealthTracker.publishRelayConfirmed.collect { confirmation ->
                val dmId = giftWrapToDmId[confirmation.eventId] ?: return@collect
                val dm = messagesById[dmId] ?: return@collect
                val updatedUrls = (dm.relayUrls + confirmation.relayUrl).distinct()
                messagesById[dmId] = dm.copy(relayUrls = updatedUrls)
                Log.d(TAG, "Relay OK for DM ${dmId.take(8)}: ${confirmation.relayUrl} (${updatedUrls.size} confirmed)")
                refreshActiveMessages()
            }
        }
    }

    fun stopSubscription() {
        dmHandle?.cancel()
        dmHandle = null
        Log.d(TAG, "DM subscription stopped")
    }

    /** Buffer a raw gift wrap event without decrypting. */
    private fun bufferGiftWrap(event: Event, relayUrl: String) {
        if (event.kind != KIND_GIFT_WRAP) return
        // Track relay source even for already-decrypted events (merge relay URLs)
        giftWrapRelays.getOrPut(event.id) { java.util.Collections.synchronizedSet(mutableSetOf()) }.add(relayUrl)
        if (event.id in decryptedIds) {
            // Already decrypted — update relay URLs on existing message
            messagesById[event.id]?.let { existing ->
                val updatedRelays = giftWrapRelays[event.id]?.toList() ?: existing.relayUrls
                if (updatedRelays.size > existing.relayUrls.size) {
                    messagesById[event.id] = existing.copy(relayUrls = updatedRelays)
                }
            }
            return
        }
        if (pendingGiftWraps.putIfAbsent(event.id, event) == null) {
            _pendingGiftWrapCount.value = pendingGiftWraps.size
            _debugStatus.value = "${pendingGiftWraps.size} encrypted, ${messagesById.size} decrypted"
            Log.d(TAG, "Buffered gift wrap ${event.id.take(8)} (${pendingGiftWraps.size} pending)")
        }
    }

    /**
     * Decrypt all pending gift wraps. Called when the user visits the DM page.
     *
     * Uses the full `nip44Decrypt` path which may launch Amber's foreground Activity
     * for user approval. If the user declines (Amber throws or returns null), we stop
     * decrypting immediately — no harassment. The user can retry by navigating away
     * and back to the DM page.
     *
     * Successfully decrypted events are removed from the pending buffer and never
     * re-attempted. Events that fail for non-decline reasons (invalid JSON, wrong kind)
     * are also removed permanently.
     */
    fun decryptPending() {
        val signer = userSigner ?: return
        val myPubkey = userPubkey ?: return
        val pending = pendingGiftWraps.values.toList()
        if (pending.isEmpty()) {
            Log.d(TAG, "decryptPending: nothing to decrypt")
            return
        }

        Log.d(TAG, "decryptPending: ${pending.size} events to decrypt")
        _isDecrypting.value = true

        scope.launch {
            var declinedByUser = false
            var decryptedCount = 0
            var failedCount = 0
            var errorCount = 0

            for (event in pending) {
                if (declinedByUser) break

                try {
                    val eventRelays = giftWrapRelays[event.id]?.toList() ?: emptyList()
                    val dm = decryptGiftWrap(event, signer, myPubkey, eventRelays)
                    if (dm != null) {
                        messagesById[dm.id] = dm
                        decryptedCount++
                    } else {
                        failedCount++
                    }
                    // Whether dm is null (bad format) or valid, remove from pending
                    pendingGiftWraps.remove(event.id)
                    decryptedIds.add(event.id)
                } catch (e: IllegalStateException) {
                    // Amber declined or failed — stop immediately, respect the user's choice
                    Log.d(TAG, "decryptPending: Amber declined/failed — stopping (${e.message})")
                    declinedByUser = true
                } catch (e: Exception) {
                    Log.w(TAG, "decryptPending: error for ${event.id.take(8)}: ${e.message}")
                    errorCount++
                    // Non-decline failure (bad data) — remove from pending, don't retry
                    pendingGiftWraps.remove(event.id)
                    decryptedIds.add(event.id)
                }
            }

            _pendingGiftWrapCount.value = pendingGiftWraps.size
            _isDecrypting.value = false
            if (decryptedCount > 0) {
                rebuildConversations()
                refreshActiveMessages()
            }
            _debugStatus.value = "${pendingGiftWraps.size} encrypted, ${messagesById.size} decrypted" +
                if (declinedByUser) " (user declined)" else ""
            Log.d(TAG, "decryptPending: done — $decryptedCount ok, $failedCount bad-format, $errorCount errors, ${pendingGiftWraps.size} still pending" +
                if (declinedByUser) ", stopped by user decline" else "")
        }
    }

    /**
     * Decrypt a single gift wrap → seal → rumor. Uses the full signer path
     * (foreground-capable) so Amber can prompt the user.
     * Returns null if the event has bad format. Throws on Amber decline.
     */
    private suspend fun decryptGiftWrap(event: Event, signer: NostrSigner, myPubkey: String, relayUrls: List<String>): DirectMessage? {
        // Step 1: Decrypt gift wrap → seal JSON
        val sealJson = signer.nip44Decrypt(event.content, event.pubKey)
        val seal = Event.fromJsonOrNull(sealJson)
        if (seal == null || seal.kind != KIND_SEAL) {
            Log.w(TAG, "Invalid seal in gift wrap ${event.id.take(8)}, wrapPubkey=${event.pubKey.take(12)}, decryptedLen=${sealJson.length}, preview=${sealJson.take(120)}")
            return null
        }

        // Step 2: Decrypt seal → rumor JSON
        // NIP-17 rumors are UNSIGNED — they have no "sig" field. Event.fromJsonOrNull
        // requires sig, so we parse leniently with a fallback for missing sig.
        val rumorJson = signer.nip44Decrypt(seal.content, seal.pubKey)
        val rumor = parseEventLenient(rumorJson)
        if (rumor == null) {
            Log.w(TAG, "Failed to parse rumor JSON in seal from ${seal.pubKey.take(8)}, json=${rumorJson.take(80)}")
            return null
        }
        if (rumor.kind != KIND_DM) {
            Log.d(TAG, "Non-DM rumor kind=${rumor.kind} in seal from ${seal.pubKey.take(8)} (expected kind-$KIND_DM)")
            return null
        }

        // Step 3: Build DirectMessage
        val senderPubkey = seal.pubKey
        val recipientPubkey = rumor.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1) ?: myPubkey
        val replyToId = rumor.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
        val subject = rumor.tags.firstOrNull { it.firstOrNull() == "subject" }?.getOrNull(1)

        Log.d(TAG, "Decrypted DM from ${senderPubkey.take(8)}: ${rumor.content.take(30)}...")
        return DirectMessage(
            id = event.id,
            senderPubkey = senderPubkey,
            recipientPubkey = recipientPubkey,
            content = rumor.content,
            createdAt = rumor.createdAt,
            replyToId = replyToId,
            subject = subject,
            isOutgoing = senderPubkey.equals(myPubkey, ignoreCase = true),
            relayUrls = relayUrls
        )
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
                // NIP-17: send to recipient's inbox relays
                val recipientInbox = fetchInboxRelays(recipientPubkey, relayUrls)
                Log.d(TAG, "Recipient ${recipientPubkey.take(8)} inbox relays: $recipientInbox")
                val recipientWrapId = sendGiftWrap(signedSeal, recipientPubkey, recipientInbox.toSet())
                giftWrapToDmId[recipientWrapId] = signedSeal.id

                // 4. Build kind 1059 gift wrap to SELF: so we can see sent messages
                // NIP-17: send to our own inbox relays so our subscription picks them up
                val selfSealContent = signer.nip44Encrypt(rumorJson, senderPubkey)
                val selfSealTemplate = com.example.cybin.core.EventTemplate(
                    createdAt = sealCreatedAt,
                    kind = KIND_SEAL,
                    tags = emptyArray(),
                    content = selfSealContent
                )
                val selfSignedSeal = signer.sign(selfSealTemplate)
                // Self-wrap goes to inbox + outbox (outbox relays are already connected)
                val senderRelays = (userInboxRelays + userOutboxRelays).distinct().ifEmpty { relayUrls.toList() }
                Log.d(TAG, "Self-wrap to ${senderRelays.size} relays (inbox=${userInboxRelays.size} + outbox=${userOutboxRelays.size})")
                val selfWrapId = sendGiftWrap(selfSignedSeal, senderPubkey, senderRelays.toSet())
                giftWrapToDmId[selfWrapId] = signedSeal.id

                // Add to local state immediately — relay orbs start empty,
                // populated as OK confirmations arrive via RelayHealthTracker
                val dm = DirectMessage(
                    id = signedSeal.id,
                    senderPubkey = senderPubkey,
                    recipientPubkey = recipientPubkey,
                    content = content,
                    createdAt = rumorCreatedAt,
                    replyToId = replyToId,
                    isOutgoing = true,
                    relayUrls = emptyList()
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

    /**
     * Fetch inbox relays for a pubkey (NIP-65 kind-10002).
     *
     * DMs are delivered to the recipient's **inbox relays** — the same relays used for
     * all inbound events. Kind-10050 is intentionally ignored: it was unilaterally assigned
     * by certain clients without user consent, routing DMs to arbitrary relays that
     * circumvent the user's chosen relay configuration. An inbox is an inbox.
     *
     * Falls back to [fallbackRelays] if no kind-10002 is found.
     */
    private suspend fun fetchInboxRelays(pubkey: String, fallbackRelays: Set<String>): List<String> {
        // Return cached if available
        inboxRelayCache[pubkey]?.let { return it }

        val stateMachine = RelayConnectionStateMachine.getInstance()
        // Query on all our connected relays for better coverage
        val queryRelays = (userInboxRelays + userOutboxRelays).distinct().ifEmpty { fallbackRelays.toList() }
        if (queryRelays.isEmpty()) return fallbackRelays.toList()

        // Query kind-10002 (NIP-65 relay list) only — inbox relays for DM delivery
        val filter = Filter(
            authors = listOf(pubkey),
            kinds = listOf(10002),
            limit = 1
        )

        var result: List<String>? = null
        val latch = kotlinx.coroutines.CompletableDeferred<Unit>()

        val handle = stateMachine.requestTemporarySubscription(
            queryRelays, filter, priority = SubscriptionPriority.NORMAL
        ) { event ->
            if (!event.pubKey.equals(pubkey, ignoreCase = true)) return@requestTemporarySubscription
            if (event.kind != 10002) return@requestTemporarySubscription

            // NIP-65: ["r", "wss://relay.example.com", "read"|"write"|""]
            // Inbox = read relays (marker "read" or no marker)
            val inboxUrls = event.tags
                .filter { it.size >= 2 && it[0] == "r" }
                .filter { tag ->
                    tag.size == 2 ||
                    tag.getOrNull(2)?.lowercase() == "read" ||
                    tag.getOrNull(2).isNullOrBlank()
                }
                .map { it[1].trim().removeSuffix("/") }
                .distinct()
            if (inboxUrls.isNotEmpty()) {
                result = inboxUrls
                latch.complete(Unit)
            }
        }

        // Wait up to 5 seconds for a response
        try {
            kotlinx.coroutines.withTimeout(5000) { latch.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            Log.d(TAG, "Timeout fetching inbox relays for ${pubkey.take(8)}")
        }
        handle.cancel()

        val inbox = result ?: fallbackRelays.toList()
        val source = if (result != null) "kind-10002" else "fallback"
        inboxRelayCache[pubkey] = inbox
        Log.d(TAG, "fetchInboxRelays(${pubkey.take(8)}): ${inbox.size} relays ($source)")
        return inbox
    }

    /**
     * Send a gift wrap to the specified relays with delivery tracking.
     * Registers with RelayHealthTracker so OK responses are tracked, and
     * NIP-42 auth handler so auth-required relays get retried.
     * Returns the gift wrap event ID for relay orb updates.
     */
    private suspend fun sendGiftWrap(seal: Event, recipientPubkey: String, relayUrls: Set<String>): String {
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
        val stateMachine = RelayConnectionStateMachine.getInstance()
        Log.d(TAG, "Sending gift wrap ${signedWrap.id.take(8)} to ${recipientPubkey.take(8)} on ${relayUrls.size} relays: $relayUrls")
        stateMachine.send(signedWrap, relayUrls)
        // Track for NIP-42 auth retry (relay may require AUTH before accepting EVENT)
        stateMachine.nip42AuthHandler.trackPublishedEvent(signedWrap, relayUrls)
        // Track for OK response confirmation → relay orb updates
        RelayHealthTracker.registerPendingPublish(signedWrap.id, KIND_GIFT_WRAP, relayUrls)
        scope.launch {
            kotlinx.coroutines.delay(10_000)
            RelayHealthTracker.finalizePendingPublish(signedWrap.id)
        }
        return signedWrap.id
    }

    private fun rebuildConversations() {
        val myPubkey = userPubkey ?: return
        val allMessages = messagesById.values.toList()

        // Group by peer pubkey
        val grouped = allMessages.groupBy { dm ->
            if (dm.senderPubkey.equals(myPubkey, ignoreCase = true)) dm.recipientPubkey
            else dm.senderPubkey
        }

        // Fetch profiles for peers we don't have cached, then rebuild after they arrive
        val missingPeers = grouped.keys.filter { profileCache.getAuthor(it) == null }
        if (missingPeers.isNotEmpty()) {
            Log.d(TAG, "Requesting profiles for ${missingPeers.size} unknown DM peers")
            scope.launch {
                profileCache.requestProfiles(missingPeers, userInboxRelays.ifEmpty { listOf("wss://purplepag.es") })
                // requestProfiles is async (debounced batch) — schedule delayed rebuilds
                // to pick up profiles as they arrive from relays
                kotlinx.coroutines.delay(4000)
                rebuildConversationsInternal()
                kotlinx.coroutines.delay(6000)
                rebuildConversationsInternal()
            }
        }

        rebuildConversationsInternal()
    }

    /** Pure rebuild without triggering profile fetches (avoids recursion from delayed refreshes). */
    private fun rebuildConversationsInternal() {
        val myPubkey = userPubkey ?: return
        val allMessages = messagesById.values.toList()
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

    /**
     * Parse a Nostr event JSON leniently — tolerates missing "sig" field.
     * NIP-17 rumors (kind 14) are unsigned and don't have a signature.
     * Standard Event.fromJson() requires sig and throws without it.
     */
    private fun parseEventLenient(json: String): Event? {
        return try {
            val obj = org.json.JSONObject(json)
            val tagsArray = obj.optJSONArray("tags") ?: org.json.JSONArray()
            val tags = Array(tagsArray.length()) { i ->
                val inner = tagsArray.getJSONArray(i)
                Array(inner.length()) { j -> inner.getString(j) }
            }
            Event(
                id = obj.optString("id", ""),
                pubKey = obj.optString("pubkey", ""),
                createdAt = obj.optLong("created_at", 0L),
                kind = obj.optInt("kind", -1),
                tags = tags,
                content = obj.optString("content", ""),
                sig = obj.optString("sig", ""),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clearAll() {
        stopSubscription()
        messagesById.clear()
        pendingGiftWraps.clear()
        giftWrapRelays.clear()
        decryptedIds.clear()
        inboxRelayCache.clear()
        giftWrapToDmId.clear()
        confirmationObserverJob?.cancel()
        confirmationObserverJob = null
        _pendingGiftWrapCount.value = 0
        _isDecrypting.value = false
        _conversations.value = emptyList()
        _activeMessages.value = emptyList()
        _debugStatus.value = "Not started"
        activePeer = null
        userPubkey = null
        userSigner = null
        userInboxRelays = emptyList()
        userOutboxRelays = emptyList()
    }
}
