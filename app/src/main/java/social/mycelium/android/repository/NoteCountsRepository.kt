package social.mycelium.android.repository

import android.util.Log
import com.example.cybin.core.Event
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Aggregated counts per note: zap count and NIP-25 reactions (kind-7, kind-9735).
 *
 * Uses the **outbox model**: for each visible note, sends kind-7 and kind-9735 filters
 * to the relays where that note was actually seen (+ the note author's NIP-65 read relays
 * when available). This mirrors Amethyst's `filterRepliesAndReactionsToNotes` approach.
 *
 * Reactions and zap receipts are typically stored on the relays the note was published to
 * (the author's write/outbox relays) and the relays where reactors send them (the author's
 * read/inbox relays). By targeting those specific relays, we get accurate counts.
 */
data class NoteCounts(
    /** Total sats zapped to this note (sum of all bolt11 invoices). */
    val zapTotalSats: Long = 0,
    /** Number of distinct zap receipts. */
    val zapCount: Int = 0,
    /** Number of kind-1 replies referencing this note. */
    val replyCount: Int = 0,
    /** Distinct reaction emojis (e.g. ["❤️", "🔥"]); NIP-25 content or "+" as "❤️". */
    val reactions: List<String> = emptyList(),
    /** Pubkeys of authors who reacted, keyed by emoji. */
    val reactionAuthors: Map<String, List<String>> = emptyMap(),
    /** Pubkeys of authors who zapped this note, with their zap amount in sats. */
    val zapAmountByAuthor: Map<String, Long> = emptyMap(),
    /** Pubkeys of authors who zapped this note (ordered by receipt time). */
    val zapAuthors: List<String> = emptyList(),
    /** NIP-30 custom emoji URLs: maps ":shortcode:" to image URL for custom emoji reactions. */
    val customEmojiUrls: Map<String, String> = emptyMap()
)

object NoteCountsRepository {

    private const val TAG = "NoteCountsRepository"
    private const val DEBOUNCE_MS = 800L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })

    private val _countsByNoteId = MutableStateFlow<Map<String, NoteCounts>>(emptyMap())
    val countsByNoteId: StateFlow<Map<String, NoteCounts>> = _countsByNoteId.asStateFlow()

    /** Feed: noteId → list of relay URLs where that note was seen. */
    @Volatile
    private var feedNoteRelays: Map<String, List<String>> = emptyMap()

    /** Topic: noteId → relay URLs. */
    @Volatile
    private var topicNoteRelays: Map<String, List<String>> = emptyMap()

    /** Thread: noteId → relay URLs. */
    @Volatile
    private var threadNoteRelays: Map<String, List<String>> = emptyMap()

    @Volatile
    private var lastSubscribedNoteIds: Set<String> = emptySet()

    /** Persistent WebSocket pool: relay URL → open WebSocket. */
    private val wsPool = java.util.concurrent.ConcurrentHashMap<String, WebSocket>()
    /** Track which pool connections are ready (onOpen received). */
    private val wsReady = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** Current subscription ID per relay so we can CLOSE before sending a new REQ. */
    private val wsCurrentSubId = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Debounce job so rapid note-ID changes don't thrash subscriptions. */
    private var debounceJob: Job? = null
    /** Phase 2 job: delayed kind-7 + kind-9735 subscription after kind-1 replies. */
    private var phase2Job: Job? = null

    /** Dedup: event IDs we've already processed so relay overlap doesn't double-count. */
    private val processedEventIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /** Pending events waiting to be flushed into counts in a single batch. */
    private val pendingCountEvents = ConcurrentLinkedQueue<Event>()
    /** Debounce job for flushing pending count events. */
    private var countsFlushJob: Job? = null
    /** Debounce window: accumulate events for this long before flushing to StateFlow. */
    private const val COUNTS_FLUSH_DEBOUNCE_MS = 80L
    /** Max delay: force flush even if events keep arriving. */
    private const val COUNTS_FLUSH_MAX_DELAY_MS = 300L
    /** Timestamp of first un-flushed event (for max delay enforcement). */
    @Volatile private var firstPendingEventTs = 0L

    /**
     * Set note IDs + their relay URLs from the feed.
     * @param noteRelays map of noteId → list of relay URLs where that note was seen
     */
    fun setNoteIdsOfInterest(noteRelays: Map<String, List<String>>) {
        Log.d(TAG, "setNoteIdsOfInterest: ${noteRelays.size} feed notes")
        feedNoteRelays = noteRelays
        scheduleSubscriptionUpdate()
    }

    /**
     * Set note IDs from the current thread view (replies).
     */
    fun setThreadNoteIdsOfInterest(noteRelays: Map<String, List<String>>) {
        threadNoteRelays = noteRelays
        scheduleSubscriptionUpdate()
    }

    /**
     * Set note IDs from the topic feed.
     */
    fun setTopicNoteIdsOfInterest(noteRelays: Map<String, List<String>>) {
        topicNoteRelays = noteRelays
        scheduleSubscriptionUpdate()
    }

    private fun scheduleSubscriptionUpdate() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            // Cancel any pending phase 2 from a previous cycle before starting a new one
            phase2Job?.cancel()
            // Snapshot the merged note relay map for both phases
            val snapshot = feedNoteRelays + topicNoteRelays + threadNoteRelays
            updateCountsSubscription(phase = 1, overrideMerged = snapshot)
            // Phase 2: reactions + zaps after replies have had time to arrive
            phase2Job = launch {
                delay(PHASE2_DELAY_MS)
                Log.d(TAG, "Phase 2: sending kind-7 + kind-9735 enrichment")
                updateCountsSubscription(phase = 2, overrideMerged = snapshot)
            }
        }
    }

    /** Delay before sending kind-7/kind-9735 filters (let kind-1 replies arrive first). */
    private const val PHASE2_DELAY_MS = 600L

    /**
     * Force re-trigger the counts subscription even if note IDs haven't changed.
     */
    fun retrigger() {
        Log.d(TAG, "retrigger: forcing counts subscription update")
        lastSubscribedNoteIds = emptySet()
        scheduleSubscriptionUpdate()
    }

    /**
     * Update counts subscription using persistent WebSocket pool.
     * Connections are reused — only new REQ messages are sent when note IDs change.
     * Counts are preserved across updates (no reset).
     */
    /** Minimum number of new (unseen) note IDs required to trigger a re-subscription. */
    private const val RESUB_THRESHOLD = 5

    /**
     * @param phase 1 = kind-1 replies only, 2 = kind-7 reactions + kind-9735 zaps, 0 = all (legacy)
     */
    private fun updateCountsSubscription(phase: Int = 0, overrideMerged: Map<String, List<String>>? = null) {
        val merged = overrideMerged ?: (feedNoteRelays + topicNoteRelays + threadNoteRelays)
        if (merged.isEmpty()) {
            closeAllWebSockets()
            lastSubscribedNoteIds = emptySet()
            return
        }
        val mergedIds = merged.keys
        // Phase 2 reuses the same note IDs with different kinds — skip the idempotency guard
        if (phase != 2) {
            if (mergedIds == lastSubscribedNoteIds) return
            val newIds = mergedIds - lastSubscribedNoteIds
            if (newIds.size < RESUB_THRESHOLD && lastSubscribedNoteIds.isNotEmpty() && wsPool.isNotEmpty()) return
            lastSubscribedNoteIds = mergedIds
        }

        // Validate note IDs: must be exactly 64 hex chars (Nostr event ID)
        val hexRegex = Regex("^[0-9a-f]{64}$")
        val validMerged = merged.filterKeys { hexRegex.matches(it) }
        if (validMerged.isEmpty()) return

        // Build per-relay note ID groups
        val perRelayNoteIds = mutableMapOf<String, MutableSet<String>>()
        for ((noteId, relayUrls) in validMerged) {
            for (url in relayUrls.ifEmpty { FALLBACK_RELAYS }) {
                perRelayNoteIds.getOrPut(url) { mutableSetOf() }.add(noteId)
            }
        }
        val allNoteIds = validMerged.keys.take(200).toList()
        for (fallback in FALLBACK_RELAYS) {
            perRelayNoteIds.getOrPut(fallback) { mutableSetOf() }.addAll(allNoteIds)
        }

        Log.d(TAG, "Updating counts sub: ${mergedIds.size} notes across ${perRelayNoteIds.size} relays")

        for ((relayUrl, noteIds) in perRelayNoteIds) {
            val noteIdList = noteIds.take(200).toList()
            if (noteIdList.isEmpty()) continue
            val phaseLabel = when (phase) { 1 -> "p1"; 2 -> "p2"; else -> "all" }
            val subId = "counts_${phaseLabel}_" + System.currentTimeMillis().toString(36)
            val reqJson = when (phase) {
                1 -> buildPhase1ReqJson(subId, noteIdList)
                2 -> buildPhase2ReqJson(subId, noteIdList)
                else -> buildReqJson(subId, noteIdList)
            }

            val existingWs = wsPool[relayUrl]
            if (existingWs != null && wsReady[relayUrl] == true) {
                // Reuse: CLOSE previous sub, send new REQ
                val prevSub = wsCurrentSubId[relayUrl]
                if (prevSub != null) {
                    try { existingWs.send(JSONArray().apply { put("CLOSE"); put(prevSub) }.toString()) } catch (_: Exception) {}
                }
                wsCurrentSubId[relayUrl] = subId
                try {
                    Log.d(TAG, "WS reuse: $relayUrl — sending REQ ($subId, ${noteIdList.size} notes)")
                    existingWs.send(reqJson)
                } catch (_: Exception) {
                    // Connection died — remove and reopen
                    wsPool.remove(relayUrl)
                    wsReady.remove(relayUrl)
                    wsCurrentSubId.remove(relayUrl)
                    openPoolCountsWebSocket(relayUrl, subId, reqJson, noteIdList.size)
                }
            } else if (existingWs == null) {
                openPoolCountsWebSocket(relayUrl, subId, reqJson, noteIdList.size)
            }
        }
    }

    /**
     * Open a new persistent WebSocket connection to a relay for counts.
     * The connection stays open for reuse by subsequent subscription updates.
     */
    private fun openPoolCountsWebSocket(relayUrl: String, subId: String, reqJson: String, noteCount: Int) {
        val request = Request.Builder().url(relayUrl).build()
        val ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsReady[relayUrl] = true
                wsCurrentSubId[relayUrl] = subId
                webSocket.send(reqJson)
                Log.d(TAG, "WS open: $relayUrl — sending REQ ($subId, $noteCount notes)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val arr = JSONArray(text)
                    val type = arr.getString(0)
                    when (type) {
                        "EVENT" -> {
                            if (arr.length() >= 3) {
                                val eventJson = arr.getJSONObject(2)
                                val kind = eventJson.getInt("kind")
                                if (kind == 1 || kind == 7 || kind == 9735) {
                                    val event = parseEventFromJson(eventJson)
                                    if (event != null) onCountsEvent(event)
                                }
                            }
                        }
                        "EOSE" -> Log.d(TAG, "WS EOSE: $relayUrl ($subId)")
                        "NOTICE" -> Log.w(TAG, "WS NOTICE from $relayUrl: ${arr.optString(1)}")
                        "CLOSED" -> Log.w(TAG, "WS CLOSED from $relayUrl: ${arr.optString(1)} ${arr.optString(2)}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "WS parse error from $relayUrl: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure: $relayUrl — ${t.message}")
                wsPool.remove(relayUrl)
                wsReady.remove(relayUrl)
                wsCurrentSubId.remove(relayUrl)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed: $relayUrl ($code)")
                wsPool.remove(relayUrl)
                wsReady.remove(relayUrl)
                wsCurrentSubId.remove(relayUrl)
            }
        })
        wsPool[relayUrl] = ws
    }

    /** Phase 1: kind-1 replies only (fast reply counts). */
    private fun buildPhase1ReqJson(subId: String, noteIdList: List<String>): String {
        val eTagArray = JSONArray().apply { noteIdList.forEach { put(it) } }
        val filter1 = JSONObject().apply {
            put("kinds", JSONArray().put(1))
            put("#e", eTagArray)
            put("limit", 2000)
        }
        return JSONArray().apply {
            put("REQ")
            put(subId)
            put(filter1)
        }.toString()
    }

    /** Phase 2: kind-7 reactions + kind-9735 zaps (enrichment). */
    private fun buildPhase2ReqJson(subId: String, noteIdList: List<String>): String {
        val eTagArray = JSONArray().apply { noteIdList.forEach { put(it) } }
        val filter7 = JSONObject().apply {
            put("kinds", JSONArray().put(7))
            put("#e", eTagArray)
            put("limit", 2000)
        }
        val filter9735 = JSONObject().apply {
            put("kinds", JSONArray().put(9735))
            put("#e", eTagArray)
            put("limit", 200)
        }
        return JSONArray().apply {
            put("REQ")
            put(subId)
            put(filter7)
            put(filter9735)
        }.toString()
    }

    /** Legacy: all kinds in one REQ (used by retrigger / thread). */
    private fun buildReqJson(subId: String, noteIdList: List<String>): String {
        val eTagArray = JSONArray().apply { noteIdList.forEach { put(it) } }
        // Filter 1: kind-1 replies (to count replies per note)
        val filter1 = JSONObject().apply {
            put("kinds", JSONArray().put(1))
            put("#e", eTagArray)
            put("limit", 2000)
        }
        // Filter 2: kind-7 reactions
        val filter7 = JSONObject().apply {
            put("kinds", JSONArray().put(7))
            put("#e", eTagArray)
            put("limit", 2000)
        }
        // Filter 3: kind-9735 zap receipts
        val filter9735 = JSONObject().apply {
            put("kinds", JSONArray().put(9735))
            put("#e", eTagArray)
            put("limit", 200)
        }
        return JSONArray().apply {
            put("REQ")
            put(subId)
            put(filter1)
            put(filter7)
            put(filter9735)
        }.toString()
    }

    private fun parseEventFromJson(json: JSONObject): Event? {
        return try {
            val id = json.getString("id")
            val pubkey = json.getString("pubkey")
            val createdAt = json.getLong("created_at")
            val kind = json.getInt("kind")
            val content = json.optString("content", "")
            val sig = json.optString("sig", "")
            val tagsJson = json.getJSONArray("tags")
            val tags = Array(tagsJson.length()) { i ->
                val tagArr = tagsJson.getJSONArray(i)
                Array(tagArr.length()) { j -> tagArr.getString(j) }
            }
            Event(id, pubkey, createdAt, kind, tags, content, sig)
        } catch (e: Exception) {
            Log.w(TAG, "Event parse failed: ${e.message}")
            null
        }
    }

    private fun closeAllWebSockets() {
        wsPool.values.forEach { try { it.close(1000, "subscription update") } catch (_: Exception) {} }
        wsPool.clear()
        wsReady.clear()
        wsCurrentSubId.clear()
    }

    private val FALLBACK_RELAYS = emptyList<String>()

    /**
     * Called when a kind-1, kind-7, or kind-9735 event is received from ANY source:
     * counts WebSocket, main feed relay, or thread reply WebSocket.
     * Events are enqueued and flushed in batches to avoid N map copies for N events.
     */
    fun onCountsEvent(event: Event) {
        // Dedup across relays
        if (!processedEventIds.add(event.id)) return
        if (event.kind != 1 && event.kind != 7 && event.kind != 9735) return
        pendingCountEvents.add(event)
        if (firstPendingEventTs == 0L) firstPendingEventTs = System.currentTimeMillis()
        scheduleCountsFlush()
    }

    /**
     * Lightweight alias for onCountsEvent — call from any live event stream
     * (feed relay, thread WS, etc.) to keep counts in real time.
     */
    fun onLiveEvent(event: Event) = onCountsEvent(event)

    private fun scheduleCountsFlush() {
        countsFlushJob?.cancel()
        countsFlushJob = scope.launch {
            val elapsed = System.currentTimeMillis() - firstPendingEventTs
            val remaining = COUNTS_FLUSH_MAX_DELAY_MS - elapsed
            if (remaining > COUNTS_FLUSH_DEBOUNCE_MS) {
                delay(COUNTS_FLUSH_DEBOUNCE_MS)
            } else if (remaining > 0) {
                delay(remaining)
            }
            // else: max delay exceeded, flush immediately
            flushPendingCounts()
        }
    }

    /**
     * Drain all pending events and apply them to a single mutable snapshot of the counts map.
     * Emits exactly one StateFlow update at the end, regardless of how many events were queued.
     */
    private fun flushPendingCounts() {
        val batch = mutableListOf<Event>()
        while (true) {
            val ev = pendingCountEvents.poll() ?: break
            batch.add(ev)
        }
        firstPendingEventTs = 0L
        if (batch.isEmpty()) return

        val snapshot = _countsByNoteId.value.toMutableMap()
        val changedReplyNoteIds = mutableSetOf<String>()

        for (event in batch) {
            when (event.kind) {
                1 -> applyKind1Reply(event, snapshot, changedReplyNoteIds)
                7 -> applyKind7(event, snapshot)
                9735 -> applyKind9735(event, snapshot)
            }
        }

        _countsByNoteId.value = snapshot.toMap()

        // Sync ReplyCountCache for any notes whose reply count changed
        if (changedReplyNoteIds.isNotEmpty()) {
            for (noteId in changedReplyNoteIds) {
                val count = snapshot[noteId]?.replyCount ?: continue
                ReplyCountCache.set(noteId, count)
            }
        }

        Log.d(TAG, "Flushed ${batch.size} count events (${changedReplyNoteIds.size} reply count updates)")
    }

    private fun applyKind1Reply(event: Event, snapshot: MutableMap<String, NoteCounts>, changedReplyNoteIds: MutableSet<String>) {
        val eTags = event.tags.filter { it.size >= 2 && it.getOrNull(0) == "e" }
        if (eTags.isEmpty()) return

        val markedRoot = eTags.firstOrNull { pickETagMarker(it) == "root" }?.getOrNull(1)
        val markedReply = eTags.firstOrNull { pickETagMarker(it) == "reply" }?.getOrNull(1)

        // Only count the DIRECT parent — not the root — so reply counts reflect
        // depth-1 replies only (like Twitter/X). If markedReply exists, that's the
        // direct parent. If only markedRoot exists (no reply marker), this IS a
        // direct reply to root. For unmarked positional e-tags, last = direct parent.
        val directParent: String? = when {
            markedReply != null -> markedReply
            markedRoot != null -> markedRoot
            else -> eTags.lastOrNull()?.getOrNull(1)
        }

        if (directParent != null) {
            val counts = snapshot[directParent] ?: NoteCounts()
            snapshot[directParent] = counts.copy(replyCount = counts.replyCount + 1)
            changedReplyNoteIds.add(directParent)
        }
    }

    /**
     * Pick "root" or "reply" marker from an "e" tag, checking index 3, then 4, then 2
     * (same order as Amethyst MarkedETag.pickMarker) so we handle both NIP-10 orderings.
     */
    private fun pickETagMarker(tag: Array<out String>): String? {
        val m3 = tag.getOrNull(3)
        if (m3 == "root" || m3 == "reply") return m3
        val m4 = tag.getOrNull(4)
        if (m4 == "root" || m4 == "reply") return m4
        val m2 = tag.getOrNull(2)
        if (m2 == "root" || m2 == "reply") return m2
        return null
    }

    private fun applyKind7(event: Event, snapshot: MutableMap<String, NoteCounts>) {
        val noteId = event.tags.filter { it.getOrNull(0) == "e" }.lastOrNull()?.getOrNull(1) ?: return
        val content = event.content.ifBlank { "+" }
        val emoji = when {
            content == "+" -> "❤️"
            content == "-" -> return // skip downvotes for display
            content.startsWith(":") && content.endsWith(":") -> content // :shortcode:
            content.length <= 4 -> content // single emoji
            else -> content
        }
        val customEmojiUrl: String? = if (emoji.startsWith(":") && emoji.endsWith(":")) {
            val shortcode = emoji.removePrefix(":").removeSuffix(":")
            event.tags.firstOrNull { it.getOrNull(0) == "emoji" && it.getOrNull(1) == shortcode }?.getOrNull(2)
        } else null
        val authorPubkey = event.pubKey
        val counts = snapshot[noteId] ?: NoteCounts()
        val existing = counts.reactions.toMutableSet()
        existing.add(emoji)
        val authors = counts.reactionAuthors.toMutableMap()
        val emojiAuthors = (authors[emoji] ?: emptyList()).toMutableList()
        if (authorPubkey !in emojiAuthors) emojiAuthors.add(authorPubkey)
        authors[emoji] = emojiAuthors
        val emojiUrls = if (customEmojiUrl != null) {
            counts.customEmojiUrls.toMutableMap().also { it[emoji] = customEmojiUrl }
        } else counts.customEmojiUrls
        snapshot[noteId] = counts.copy(reactions = existing.toList(), reactionAuthors = authors, customEmojiUrls = emojiUrls)
    }

    private fun applyKind9735(event: Event, snapshot: MutableMap<String, NoteCounts>) {
        val noteId = event.tags.filter { it.getOrNull(0) == "e" }.lastOrNull()?.getOrNull(1) ?: return
        val senderPubkey = extractZapSenderPubkey(event) ?: event.pubKey
        val amountSats = extractZapAmountSats(event)
        if (amountSats > 0) Log.d(TAG, "Zap receipt ${event.id.take(8)}: ${amountSats} sats for note ${noteId.take(8)}")
        val counts = snapshot[noteId] ?: NoteCounts()
        val authors = counts.zapAuthors.toMutableList()
        if (senderPubkey !in authors) authors.add(senderPubkey)
        val amountMap = counts.zapAmountByAuthor.toMutableMap()
        amountMap[senderPubkey] = (amountMap[senderPubkey] ?: 0L) + amountSats
        snapshot[noteId] = counts.copy(
            zapCount = counts.zapCount + 1,
            zapTotalSats = counts.zapTotalSats + amountSats,
            zapAuthors = authors,
            zapAmountByAuthor = amountMap
        )
    }

    /**
     * Extract the actual zap sender pubkey from a kind-9735 receipt.
     * The receipt's "description" tag contains the original kind-9734 request JSON
     * whose pubKey is the sender.
     */
    private fun extractZapSenderPubkey(event: Event): String? {
        val descJson = event.tags.firstOrNull { it.getOrNull(0) == "description" }?.getOrNull(1) ?: return null
        return try {
            JSONObject(descJson).optString("pubkey").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract zap amount in sats from a kind-9735 receipt.
     * The "bolt11" tag contains the Lightning invoice; amount is encoded in the invoice prefix.
     * Format: lnbc<amount><multiplier> where multiplier is m(milli), u(micro), n(nano), p(pico).
     */
    private fun extractZapAmountSats(event: Event): Long {
        val bolt11 = event.tags.firstOrNull { it.getOrNull(0) == "bolt11" }?.getOrNull(1)
        if (bolt11 != null) {
            val sats = parseBolt11AmountSats(bolt11)
            if (sats > 0) return sats
            Log.d(TAG, "bolt11 parsed 0 sats for ${event.id.take(8)}: ${bolt11.take(30)}...")
        }
        // Fallback: NIP-57 kind-9734 request in "description" tag may have "amount" in millisats
        val descJson = event.tags.firstOrNull { it.getOrNull(0) == "description" }?.getOrNull(1)
        if (descJson != null) {
            try {
                val amountMsat = JSONObject(descJson).optLong("amount", 0L)
                if (amountMsat > 0) return amountMsat / 1000
            } catch (_: Exception) {}
        }
        return 0L
    }

    internal fun parseBolt11AmountSats(invoice: String): Long {
        val lower = invoice.lowercase()
        val prefix = when {
            lower.startsWith("lnbcrt") -> "lnbcrt"
            lower.startsWith("lnbc") -> "lnbc"
            lower.startsWith("lntbs") -> "lntbs"
            lower.startsWith("lntb") -> "lntb"
            else -> return 0L
        }
        val afterPrefix = lower.removePrefix(prefix)
        // BOLT11: amount is optional. If invoice starts with separator '1' immediately,
        // there's no amount (e.g. lnbc1... = no amount specified).
        if (afterPrefix.startsWith("1")) return 0L
        // Amount format: <digits>[.<digits>]<multiplier>1<data>
        // multiplier: m=milli, u=micro, n=nano, p=pico, empty=BTC
        // The '1' is the separator between human-readable and data parts.
        val amountRegex = Regex("^(\\d+\\.?\\d*)([munp]?)1")
        val match = amountRegex.find(afterPrefix) ?: return 0L
        val numStr = match.groupValues[1]
        val multiplier = match.groupValues[2]
        // If no multiplier, the number before '1' is BTC — but this is extremely rare.
        // Most zaps use u (micro) or m (milli). Guard against accidental 1 BTC parse.
        val btcAmount = numStr.toDoubleOrNull() ?: return 0L
        val sats = when (multiplier) {
            "m" -> (btcAmount * 100_000).toLong()       // milli-BTC
            "u" -> (btcAmount * 100).toLong()            // micro-BTC
            "n" -> (btcAmount * 0.1).toLong()             // nano-BTC
            "p" -> (btcAmount * 0.0001).toLong()          // pico-BTC
            "" -> (btcAmount * 100_000_000).toLong()      // BTC
            else -> 0L
        }
        return sats.coerceAtLeast(0L)
    }

    /**
     * Clear all counts and cancel subscription (e.g. on logout).
     */
    fun clear() {
        closeAllWebSockets()
        debounceJob?.cancel()
        countsFlushJob?.cancel()
        pendingCountEvents.clear()
        firstPendingEventTs = 0L
        feedNoteRelays = emptyMap()
        topicNoteRelays = emptyMap()
        threadNoteRelays = emptyMap()
        lastSubscribedNoteIds = emptySet()
        processedEventIds.clear()
        _countsByNoteId.value = emptyMap()
    }

    /**
     * Force a full reconnect: close all pool connections, clear dedup, and re-subscribe.
     * Use when connections may have died silently.
     */
    fun reconnect() {
        closeAllWebSockets()
        countsFlushJob?.cancel()
        pendingCountEvents.clear()
        firstPendingEventTs = 0L
        processedEventIds.clear()
        _countsByNoteId.value = emptyMap()
        lastSubscribedNoteIds = emptySet()
        scheduleSubscriptionUpdate()
    }
}
