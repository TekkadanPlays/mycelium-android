package social.mycelium.android.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import social.mycelium.android.data.Author
import social.mycelium.android.data.NotificationData
import social.mycelium.android.data.NotificationType
import social.mycelium.android.data.Note
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.utils.normalizeAuthorIdForCache
import social.mycelium.android.relay.TemporarySubscriptionHandle
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for real Nostr notifications: events that reference the user (p tag).
 * Amethyst-style: kinds 1 (reply/mention), 7 (like), 6 (repost), 9735 (zap). No follows.
 * Parses e-tags for target note, root/reply for replies; consolidates reposts by reposted note id.
 * Seen IDs are persisted to SharedPreferences so badge survives app restart.
 */
object NotificationsRepository {

    private const val TAG = "NotificationsRepository"
    private const val NOTIFICATION_KIND_TEXT = 1
    private const val NOTIFICATION_KIND_REPOST = 6
    private const val NOTIFICATION_KIND_REACTION = 7
    private const val NOTIFICATION_KIND_GENERIC_REPOST = 16
    private const val NOTIFICATION_KIND_REPORT = 1984
    private const val NOTIFICATION_KIND_ZAP = 9735
    private const val NOTIFICATION_KIND_HIGHLIGHT = 9802
    private const val NOTIFICATION_KIND_TOPIC_REPLY = 1111
    private const val ONE_WEEK_SEC = 7 * 24 * 60 * 60L
    private const val PREFS_NAME = "notifications_seen"
    private const val PREFS_KEY_SEEN_IDS = "seen_ids"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })
    private val profileCache = ProfileMetadataCache.getInstance()
    private val json = Json { ignoreUnknownKeys = true }

    private val _notifications = MutableStateFlow<List<NotificationData>>(emptyList())
    val notifications: StateFlow<List<NotificationData>> = _notifications.asStateFlow()

    /** IDs of notifications the user has "seen" (opened notifications screen or tapped one). Badge and dropdown use unseen count. */
    private val _seenIds = MutableStateFlow<Set<String>>(emptySet())
    val seenIds: StateFlow<Set<String>> = _seenIds.asStateFlow()
    val unseenCount: StateFlow<Int> = combine(_notifications, _seenIds) { list, seen ->
        list.count { it.id !in seen }
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    private val notificationsById = ConcurrentHashMap<String, NotificationData>()
    private var notificationsHandle: TemporarySubscriptionHandle? = null

    /** Event IDs already processed — prevents re-processing on relay reconnect (which replays all stored events). */
    private val seenEventIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // ── Summary counts (today) ──────────────────────────────────────────────
    private val _todayReplies = MutableStateFlow(0)
    val todayReplies: StateFlow<Int> = _todayReplies.asStateFlow()
    private val _todayBoosts = MutableStateFlow(0)
    val todayBoosts: StateFlow<Int> = _todayBoosts.asStateFlow()
    private val _todayReactions = MutableStateFlow(0)
    val todayReactions: StateFlow<Int> = _todayReactions.asStateFlow()
    private val _todayZapSats = MutableStateFlow(0L)
    val todayZapSats: StateFlow<Long> = _todayZapSats.asStateFlow()
    private var cacheRelayUrls = listOf<String>()
    private var subscriptionRelayUrls = listOf<String>()
    /** Current user hex pubkey (p-tag); used to filter kind-7 so we only show reactions to our notes. */
    private var myPubkeyHex: String? = null
    /** Our kind-11 topic IDs — replies to these are "Thread replies" (replyKind=11), not "Comments" (1111). */
    private val myTopicIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var prefs: SharedPreferences? = null

    // ── Batched target note fetch ──────────────────────────────────────────────
    /** Pending target note fetch entry. */
    private data class PendingTargetFetch(
        val noteId: String,
        val notificationId: String,
        val update: (NotificationData) -> (Note?) -> NotificationData,
    )
    /** Buffer of pending target note fetches waiting to be flushed as one subscription.
     *  Keyed by parent noteId → list of notifications that need that parent verified. */
    private val pendingTargetFetches = java.util.concurrent.ConcurrentHashMap<String, MutableList<PendingTargetFetch>>()
    /** Debounce job for batched target note flush. */
    private var targetFetchBatchJob: kotlinx.coroutines.Job? = null
    private const val TARGET_FETCH_BATCH_DELAY_MS = 500L

    /** Call once from Application or Activity to enable persistent seen IDs. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSeenIds()
    }

    private fun loadSeenIds() {
        val stored = prefs?.getStringSet(PREFS_KEY_SEEN_IDS, emptySet()) ?: emptySet()
        _seenIds.value = stored.toSet()
    }

    private fun persistSeenIds() {
        scope.launch(Dispatchers.IO) {
            prefs?.edit()?.putStringSet(PREFS_KEY_SEEN_IDS, _seenIds.value)?.apply()
        }
    }

    fun setCacheRelayUrls(urls: List<String>) {
        cacheRelayUrls = urls
    }

    fun getCacheRelayUrls(): List<String> = cacheRelayUrls

    fun getMyPubkeyHex(): String? = myPubkeyHex

    /** Mark all current notifications as seen (e.g. when user opens the notifications screen). Clears badge. */
    fun markAllAsSeen() {
        _seenIds.value = _notifications.value.mapTo(mutableSetOf()) { it.id }
        persistSeenIds()
    }

    /** Mark one notification as seen (e.g. when user taps it to open thread). */
    fun markAsSeen(notificationId: String) {
        _seenIds.value = _seenIds.value + notificationId
        persistSeenIds()
    }

    /** Mark all notifications of a specific type as seen (e.g. when user switches to that tab). */
    fun markAsSeenByType(type: NotificationType) {
        val idsForType = _notifications.value.filter { it.type == type }.map { it.id }.toSet()
        if (idsForType.isNotEmpty()) {
            _seenIds.value = _seenIds.value + idsForType
            persistSeenIds()
        }
    }

    /** Trim seen IDs to only include IDs that still exist in the current notification list (prevents unbounded growth). */
    private fun trimSeenIds() {
        val currentIds = _notifications.value.mapTo(mutableSetOf()) { it.id }
        val trimmed = _seenIds.value.intersect(currentIds)
        if (trimmed.size != _seenIds.value.size) {
            _seenIds.value = trimmed
            persistSeenIds()
        }
    }

    /**
     * Start subscription for the given user; events with "p" tag = pubkey (replies, likes, reposts, zaps). No follows.
     */
    fun startSubscription(pubkey: String, relayUrls: List<String>) {
        if (relayUrls.isEmpty() || pubkey.isBlank()) {
            Log.w(TAG, "startSubscription: empty relays or pubkey")
            return
        }
        // Same user + active handle = already subscribed, skip restart
        if (myPubkeyHex == pubkey && notificationsHandle != null) {
            Log.d(TAG, "startSubscription: already active for ${pubkey.take(8)}..., skipping")
            return
        }
        val isNewUser = myPubkeyHex != pubkey
        stopSubscription()
        // Only clear notification data when switching users, not when re-subscribing for the same user
        // This preserves seen state and existing notifications across navigation
        if (isNewUser) {
            notificationsById.clear()
            seenEventIds.clear()
            likeByTargetId.clear()
            likeEmojiByTargetId.clear()
            repostByTargetId.clear()
            zapByTargetId.clear()
            _notifications.value = emptyList()
        }
        subscriptionRelayUrls = relayUrls
        myPubkeyHex = pubkey
        val since = (System.currentTimeMillis() / 1000) - ONE_WEEK_SEC
        // Primary filter: high-volume kinds (text, reaction, repost, zap, topic reply)
        val filter = Filter(
            kinds = listOf(NOTIFICATION_KIND_TEXT, NOTIFICATION_KIND_REPOST, NOTIFICATION_KIND_GENERIC_REPOST, NOTIFICATION_KIND_REACTION, NOTIFICATION_KIND_ZAP, NOTIFICATION_KIND_TOPIC_REPLY),
            tags = mapOf("p" to listOf(pubkey)),
            since = since,
            limit = 500
        )
        val stateMachine = RelayConnectionStateMachine.getInstance()
        // Merge all notification kinds into a single multi-filter subscription (saves 2 relay slots)
        val filters = mutableListOf(filter)
        // Secondary filter: lower-volume kinds (reports, highlights)
        filters.add(Filter(
            kinds = listOf(NOTIFICATION_KIND_REPORT, NOTIFICATION_KIND_HIGHLIGHT),
            tags = mapOf("p" to listOf(pubkey)),
            since = since,
            limit = 100
        ))
        notificationsHandle = stateMachine.requestTemporarySubscription(relayUrls, filters, priority = SubscriptionPriority.LOW) { event -> handleEvent(event) }
        Log.d(TAG, "Notifications subscription started for ${pubkey.take(8)}... on ${relayUrls.size} relays (${filters.size} filters, 1 slot)")

        // Fetch user's kind-11 topic IDs, then add thread replies filter to the same subscription
        scope.launch {
            val topicIds = fetchUserTopicIds(pubkey, relayUrls, since)
            myTopicIds.addAll(topicIds)
            if (topicIds.isNotEmpty()) {
                Log.d(TAG, "Found ${topicIds.size} user topics, re-subscribing with thread replies filter")
                // Re-create subscription with the additional thread replies filter
                notificationsHandle?.cancel()
                val allFilters = filters + Filter(
                    kinds = listOf(NOTIFICATION_KIND_TOPIC_REPLY),
                    tags = mapOf("E" to topicIds),
                    since = since,
                    limit = 200
                )
                notificationsHandle = stateMachine.requestTemporarySubscription(relayUrls, allFilters, priority = SubscriptionPriority.LOW) { event ->
                    handleEvent(event)
                }
                Log.d(TAG, "Notifications re-subscribed with ${allFilters.size} filters (still 1 slot)")
            } else {
                Log.d(TAG, "No user topics found, skipping thread replies filter")
            }
        }
    }

    fun stopSubscription() {
        try { notificationsHandle?.cancel(); notificationsHandle = null } catch (_: Exception) { }
        Log.d(TAG, "Notifications subscription stopped")
    }

    private val ACCEPTED_KINDS = setOf(
        NOTIFICATION_KIND_TEXT, NOTIFICATION_KIND_REPOST, NOTIFICATION_KIND_GENERIC_REPOST,
        NOTIFICATION_KIND_REACTION, NOTIFICATION_KIND_ZAP, NOTIFICATION_KIND_TOPIC_REPLY,
        NOTIFICATION_KIND_REPORT, NOTIFICATION_KIND_HIGHLIGHT
    )

    private fun handleEvent(event: Event) {
        if (event.kind !in ACCEPTED_KINDS) return
        // Deduplicate: skip events already processed (relay reconnects replay all stored events)
        if (!seenEventIds.add(event.id)) return
        // Skip notifications from muted/blocked users
        if (MuteListRepository.isHidden(event.pubKey)) return
        val author = profileCache.resolveAuthor(event.pubKey)
        if (profileCache.getAuthor(event.pubKey) == null && cacheRelayUrls.isNotEmpty()) {
            scope.launch { profileCache.requestProfiles(listOf(event.pubKey), cacheRelayUrls) }
        }
        val ts = event.createdAt * 1000L
        when (event.kind) {
            NOTIFICATION_KIND_REACTION -> handleLike(event, author, ts)
            NOTIFICATION_KIND_TEXT -> handleReply(event, author, ts)
            NOTIFICATION_KIND_TOPIC_REPLY -> handleTopicReply(event, author, ts)
            NOTIFICATION_KIND_REPOST, NOTIFICATION_KIND_GENERIC_REPOST -> handleRepost(event, author, ts)
            NOTIFICATION_KIND_ZAP -> handleZap(event, author, ts)
            NOTIFICATION_KIND_HIGHLIGHT -> handleHighlight(event, author, ts)
            NOTIFICATION_KIND_REPORT -> handleReport(event, author, ts)
            else -> { }
        }
    }

    private fun handleLike(event: Event, author: Author, ts: Long) {
        val eTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        if (eTag == null) return
        // Parse NIP-25 reaction emoji from event content (Amethyst-style)
        val rawContent = event.content.ifBlank { "+" }
        if (rawContent == "-") return // skip downvotes
        val emoji = when {
            rawContent == "+" -> "❤️"
            rawContent.startsWith(":") && rawContent.endsWith(":") -> rawContent // :shortcode: custom emoji
            else -> rawContent // actual emoji character(s)
        }
        // Extract NIP-30 custom emoji URL from "emoji" tags (e.g. ["emoji", "shortcode", "https://..."])
        val customEmojiUrl: String? = if (emoji.startsWith(":") && emoji.endsWith(":") && emoji.length > 2) {
            val shortcode = emoji.removePrefix(":").removeSuffix(":")
            event.tags.firstOrNull { it.size >= 3 && it[0] == "emoji" && it[1] == shortcode }?.get(2)
        } else null
        val list = likeByTargetId.getOrPut(eTag) { mutableListOf() }
        synchronized(list) {
            if (list.none { it.first == event.pubKey }) list.add(event.pubKey to ts)
        }
        // Track the emoji for this target note (use latest/most common)
        likeEmojiByTargetId[eTag] = emoji
        val actorPubkeys = list.map { it.first }.distinct()
        val latestTs = list.maxOfOrNull { it.second } ?: ts
        val action = if (emoji == "❤️") "liked your post" else "reacted $emoji to your post"
        val text = buildActorText(actorPubkeys, action)
        val data = NotificationData(
            id = "like:$eTag",
            type = NotificationType.LIKE,
            text = text,
            note = null,
            author = author,
            targetNoteId = eTag,
            actorPubkeys = actorPubkeys,
            sortTimestamp = latestTs,
            reactionEmoji = emoji,
            customEmojiUrl = customEmojiUrl
        )
        notificationsById[data.id] = data
        emitSorted()
        scope.launch { fetchAndSetTargetNote(eTag, data.id) { d -> { note -> d.copy(targetNote = note) } } }
        updateTodaySummary(NotificationType.LIKE, ts, 0L)
    }

    private fun handleReply(event: Event, author: Author, ts: Long) {
        // Don't show notifications for our own replies
        if (myPubkeyHex != null && normalizeAuthorIdForCache(author.id) == myPubkeyHex) return
        val rootId = getReplyRootNoteId(event)
        val replyToId = getReplyToNoteId(event)
        // Check if user is directly cited in content (nostr:npub1... / nostr:nprofile1...)
        val isDirectlyCitedInContent = myPubkeyHex != null && isUserCitedInContent(event.content, myPubkeyHex!!)
        // No root and not cited in content → not useful
        if (rootId == null && !isDirectlyCitedInContent) return
        val note = eventToNote(event)
        val replyId = event.id
        val isMention = rootId == null
        val text = if (isMention) "${author.displayName} mentioned you" else "${author.displayName} replied to your post"
        val notifType = if (isMention) NotificationType.MENTION else NotificationType.REPLY
        val data = NotificationData(
            id = event.id,
            type = notifType,
            text = text,
            note = note,
            author = author,
            rootNoteId = rootId,
            replyNoteId = replyId,
            replyKind = NOTIFICATION_KIND_TEXT,
            sortTimestamp = ts
        )
        notificationsById[event.id] = data
        emitSorted()
        if (rootId != null) {
            // Fetch the direct parent to verify the reply is TO one of our notes.
            // If the parent note author isn't us AND we're not cited in content,
            // the notification will be removed in flushTargetFetchBatch.
            val parentId = replyToId ?: rootId
            scope.launch { fetchAndSetTargetNote(parentId, event.id) { d -> { n -> d.copy(targetNote = n) } } }
        }
        // Update today's summary
        updateTodaySummary(if (isMention) NotificationType.MENTION else NotificationType.REPLY, ts, 0L)
    }

    /** Check if the user's pubkey is directly cited in content via nostr:npub1 or nostr:nprofile1 references. */
    private fun isUserCitedInContent(content: String, myPubkey: String): Boolean {
        if (content.isBlank()) return false
        val npubRegex = Regex("nostr:(npub1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)", RegexOption.IGNORE_CASE)
        val nprofileRegex = Regex("nostr:(nprofile1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)", RegexOption.IGNORE_CASE)
        for (match in npubRegex.findAll(content)) {
            try {
                val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute("nostr:${match.groupValues[1]}")
                val hex = (parsed?.entity as? com.example.cybin.nip19.NPub)?.hex
                if (hex != null && normalizeAuthorIdForCache(hex) == myPubkey) return true
            } catch (_: Exception) { }
        }
        for (match in nprofileRegex.findAll(content)) {
            try {
                val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute("nostr:${match.groupValues[1]}")
                val hex = (parsed?.entity as? com.example.cybin.nip19.NProfile)?.hex
                if (hex != null && normalizeAuthorIdForCache(hex) == myPubkey) return true
            } catch (_: Exception) { }
        }
        return false
    }

    /** NIP-22: root note id from uppercase "E" tag or ["e", id, ..., "root"] for kind-1111. */
    private fun getTopicReplyRootNoteId(event: Event): String? {
        val tags = event.tags
        tags.forEach { tag ->
            if (tag.size >= 2 && tag[0] == "E") return tag.getOrNull(1)
        }
        val eTags = tags.filter { it.size >= 2 && it[0] == "e" }
        val rootTag = eTags.firstOrNull { tag ->
            val m3 = tag.getOrNull(3)
            val m4 = tag.getOrNull(4)
            m3 == "root" || m4 == "root"
        }
        if (rootTag != null) return rootTag.getOrNull(1)
        return eTags.firstOrNull()?.getOrNull(1)
    }

    private fun handleTopicReply(event: Event, author: Author, ts: Long) {
        if (myPubkeyHex != null && normalizeAuthorIdForCache(author.id) == myPubkeyHex) return
        val rootId = getTopicReplyRootNoteId(event) ?: return
        // Accept if user is p-tagged; otherwise still create the notification and verify
        // via fetchAndSetTargetNote (which removes it if root author isn't us)
        val note = eventToNote(event)
        // If root note is one of our known topics, immediately classify as thread reply (replyKind=11)
        // so it appears in the Threads tab without waiting for target fetch enrichment.
        val isOurTopic = rootId in myTopicIds
        val kind = if (isOurTopic) 11 else NOTIFICATION_KIND_TOPIC_REPLY
        val text = if (isOurTopic) "${author.displayName} replied to your thread" else "${author.displayName} commented on your post"
        val data = NotificationData(
            id = event.id,
            type = NotificationType.REPLY,
            text = text,
            note = note,
            author = author,
            rootNoteId = rootId,
            replyNoteId = event.id,
            replyKind = kind,
            sortTimestamp = ts
        )
        notificationsById[event.id] = data
        emitSorted()
        scope.launch { fetchAndSetTargetNote(rootId, event.id) { d -> { n -> d.copy(targetNote = n) } } }
        updateTodaySummary(NotificationType.REPLY, ts, 0L)
    }

    private fun handleHighlight(event: Event, author: Author, ts: Long) {
        if (myPubkeyHex != null && normalizeAuthorIdForCache(author.id) == myPubkeyHex) return
        val highlightedContent = event.content.take(200)
        val data = NotificationData(
            id = event.id,
            type = NotificationType.HIGHLIGHT,
            text = "${author.displayName} highlighted your content",
            note = eventToNote(event),
            author = author,
            sortTimestamp = ts
        )
        notificationsById[event.id] = data
        emitSorted()
    }

    private fun handleReport(event: Event, author: Author, ts: Long) {
        // Reports are informational — show them so the user is aware
        val reportType = event.tags.firstOrNull { it.size >= 3 && it[0] == "report" }?.get(2)
            ?: event.tags.firstOrNull { it.size >= 3 && it[0] == "p" }?.getOrNull(2)
            ?: "unknown"
        val data = NotificationData(
            id = event.id,
            type = NotificationType.REPORT,
            text = "${author.displayName} reported you ($reportType)",
            note = eventToNote(event),
            author = author,
            sortTimestamp = ts
        )
        notificationsById[event.id] = data
        emitSorted()
    }

    /** Update today's summary counters for the notification summary bar. */
    private fun updateTodaySummary(type: NotificationType, ts: Long, zapSats: Long) {
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (ts < todayStart) return
        when (type) {
            NotificationType.REPLY, NotificationType.MENTION -> _todayReplies.value++
            NotificationType.REPOST -> _todayBoosts.value++
            NotificationType.LIKE -> _todayReactions.value++
            NotificationType.ZAP -> _todayZapSats.value += zapSats
            else -> { }
        }
    }

    /** NIP-10: root note id from "e" tag with "root" marker, or first "e" tag. */
    private fun getReplyRootNoteId(event: Event): String? {
        val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" }
        val rootTag = eTags.firstOrNull { tag ->
            val m3 = tag.getOrNull(3)
            val m4 = tag.getOrNull(4)
            m3 == "root" || m4 == "root"
        }
        if (rootTag != null) return rootTag.getOrNull(1)
        return eTags.firstOrNull()?.getOrNull(1)
    }

    /** NIP-10: direct parent (reply-to) note id from "e" tag with "reply" marker, or last "e" tag. */
    private fun getReplyToNoteId(event: Event): String? {
        val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" }
        if (eTags.isEmpty()) return null
        val replyTag = eTags.firstOrNull { tag ->
            val m3 = tag.getOrNull(3)
            val m4 = tag.getOrNull(4)
            m3 == "reply" || m4 == "reply"
        }
        if (replyTag != null) return replyTag.getOrNull(1)
        // Positional fallback: last e-tag is the reply-to (NIP-10)
        return if (eTags.size > 1) eTags.last().getOrNull(1) else eTags.first().getOrNull(1)
    }

    private val repostByTargetId = ConcurrentHashMap<String, MutableList<Pair<String, Long>>>()
    private val likeByTargetId = ConcurrentHashMap<String, MutableList<Pair<String, Long>>>()
    private val likeEmojiByTargetId = ConcurrentHashMap<String, String>()
    private val zapByTargetId = ConcurrentHashMap<String, MutableList<Triple<String, Long, Long>>>() // pubkey, timestamp, amountSats

    private fun handleRepost(event: Event, author: Author, ts: Long) {
        val repostedNoteId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
            ?: parseRepostedNoteIdFromContent(event.content)
        if (repostedNoteId == null) {
            val data = NotificationData(
                id = event.id,
                type = NotificationType.REPOST,
                text = "${author.displayName} reposted",
                note = null,
                author = author,
                sortTimestamp = ts
            )
            notificationsById[event.id] = data
            emitSorted()
            return
        }
        val list = repostByTargetId.getOrPut(repostedNoteId) { mutableListOf() }
        synchronized(list) {
            if (list.none { it.first == event.pubKey }) list.add(event.pubKey to ts)
        }
        val reposterPubkeys = list.map { it.first }.distinct()
        val latestTs = list.maxOfOrNull { it.second } ?: ts
        val targetNote = parseRepostedNoteFromContent(event.content)
        val text = buildActorText(reposterPubkeys, "reposted your post")
        val data = NotificationData(
            id = "repost:$repostedNoteId",
            type = NotificationType.REPOST,
            text = text,
            note = null,
            author = author,
            targetNote = targetNote,
            targetNoteId = if (targetNote == null) repostedNoteId else null,
            reposterPubkeys = reposterPubkeys,
            actorPubkeys = reposterPubkeys,
            sortTimestamp = latestTs
        )
        notificationsById[data.id] = data
        emitSorted()
        // Always fetch the reposted note so flushTargetFetchBatch can verify authorship
        // (even if we parsed it from content — content parsing doesn't verify it's OUR note)
        scope.launch { fetchAndSetTargetNote(repostedNoteId, data.id) { d -> { n -> d.copy(targetNote = n) } } }
        updateTodaySummary(NotificationType.REPOST, ts, 0L)
    }

    private fun parseRepostedNoteIdFromContent(content: String): String? {
        return Regex(""""id"\s*:\s*"([a-f0-9]{64})"""").find(content)?.groupValues?.get(1)
    }

    private fun parseRepostedNoteFromContent(content: String): Note? {
        val id = Regex(""""id"\s*:\s*"([a-f0-9]{64})"""").find(content)?.groupValues?.get(1) ?: return null
        val pubkey = Regex(""""pubkey"\s*:\s*"([a-f0-9]{64})"""").find(content)?.groupValues?.get(1) ?: return null
        val created = Regex(""""created_at"\s*:\s*([0-9]+)""").find(content)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val cont = Regex(""""content"\s*:\s*"((?:[^"\\\\]|\\\\.)*)"""").find(content)?.groupValues?.get(1) ?: ""
        val author = profileCache.resolveAuthor(pubkey)
        return Note(
            id = id,
            author = author,
            content = cont,
            timestamp = created * 1000L,
            likes = 0, shares = 0, comments = 0,
            isLiked = false, hashtags = emptyList(), mediaUrls = emptyList()
        )
    }

    private fun handleZap(event: Event, author: Author, ts: Long) {
        val eTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        if (eTag == null) return
        val amountSats = parseZapAmountSats(event)
        // Kind-9735 pubkey is the wallet/LNURL service (e.g. Coinos), NOT the actual zapper.
        // The real zapper's pubkey is inside the "description" tag which contains the kind-9734 zap request JSON.
        val realZapperPubkey = parseZapSenderPubkey(event)
        val zapperPubkey = realZapperPubkey ?: event.pubKey
        val zapperAuthor = if (realZapperPubkey != null) {
            val resolved = profileCache.resolveAuthor(realZapperPubkey)
            if (profileCache.getAuthor(realZapperPubkey) == null && cacheRelayUrls.isNotEmpty()) {
                scope.launch { profileCache.requestProfiles(listOf(realZapperPubkey), cacheRelayUrls) }
            }
            resolved
        } else author
        val list = zapByTargetId.getOrPut(eTag) { mutableListOf() }
        synchronized(list) {
            if (list.none { it.first == zapperPubkey }) list.add(Triple(zapperPubkey, ts, amountSats))
        }
        val actorPubkeys = list.map { it.first }.distinct()
        val latestTs = list.maxOfOrNull { it.second } ?: ts
        val totalSats = list.sumOf { it.third }
        val satsLabel = if (totalSats > 0) formatSats(totalSats) else ""
        val text = buildActorText(actorPubkeys, if (satsLabel.isNotEmpty()) "zapped $satsLabel" else "zapped your post")
        val data = NotificationData(
            id = "zap:$eTag",
            type = NotificationType.ZAP,
            text = text,
            note = null,
            author = zapperAuthor,
            targetNoteId = eTag,
            actorPubkeys = actorPubkeys,
            sortTimestamp = latestTs,
            zapAmountSats = totalSats
        )
        notificationsById[data.id] = data
        emitSorted()
        scope.launch { fetchAndSetTargetNote(eTag, data.id) { d -> { note -> d.copy(targetNote = note) } } }
        updateTodaySummary(NotificationType.ZAP, ts, amountSats)
    }

    /** Parse the real zapper's pubkey from the kind-9734 zap request embedded in the "description" tag. */
    private fun parseZapSenderPubkey(event: Event): String? {
        val descTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
            ?: return null
        return try {
            val pubkeyMatch = Regex(""""pubkey"\s*:\s*"([a-f0-9]{64})"""").find(descTag)
            pubkeyMatch?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    /** Parse zap amount from bolt11 tag or description tag's bolt11 field. */
    private fun parseZapAmountSats(event: Event): Long {
        // Try bolt11 tag directly
        val bolt11 = event.tags.firstOrNull { it.size >= 2 && it[0] == "bolt11" }?.get(1)
        if (bolt11 != null) {
            val sats = decodeBolt11Amount(bolt11)
            if (sats > 0) return sats
        }
        // Try description tag (zap request JSON) which may contain amount
        val descTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
        if (descTag != null) {
            try {
                val amountMatch = Regex(""""amount"\s*:\s*"?(\d+)"?""").find(descTag)
                val milliSats = amountMatch?.groupValues?.get(1)?.toLongOrNull()
                if (milliSats != null && milliSats > 0) return milliSats / 1000
            } catch (_: Exception) { }
        }
        return 0L
    }

    /** Decode amount from a bolt11 (BOLT-11) lightning invoice string. */
    private fun decodeBolt11Amount(bolt11: String): Long {
        val lower = bolt11.lowercase()
        // bolt11 format: lnbc<amount><multiplier>1p...
        val amountMatch = Regex("""^lnbc(\d+)([munp]?)""").find(lower) ?: return 0L
        val num = amountMatch.groupValues[1].toLongOrNull() ?: return 0L
        val multiplier = amountMatch.groupValues[2]
        // Convert to sats (1 BTC = 100_000_000 sats)
        val btcValue = when (multiplier) {
            "m" -> num * 100_000L       // milli-BTC -> sats
            "u" -> num * 100L           // micro-BTC -> sats
            "n" -> num / 10L            // nano-BTC -> sats (0.1 sat per nano)
            "p" -> num / 10_000L        // pico-BTC -> sats
            "" -> num * 100_000_000L    // whole BTC -> sats
            else -> 0L
        }
        return btcValue
    }

    /** Format sats for display: "1,000 sats", "21 sats", etc. */
    private fun formatSats(sats: Long): String {
        return when {
            sats >= 1_000_000 -> "${sats / 1_000_000}.${(sats % 1_000_000) / 100_000}M sats"
            sats >= 1_000 -> "${sats / 1_000}.${(sats % 1_000) / 100}K sats"
            else -> "$sats sats"
        }
    }

    /**
     * Fetch the user's kind-11 topic event IDs from relays so we can subscribe for kind-1111 replies.
     */
    private suspend fun fetchUserTopicIds(pubkey: String, relayUrls: List<String>, since: Long): List<String> {
        val topicIds = mutableListOf<String>()
        val stateMachine = RelayConnectionStateMachine.getInstance()
        val filter = Filter(
            kinds = listOf(11),
            authors = listOf(pubkey),
            since = since,
            limit = 100
        )
        val handle = stateMachine.requestOneShotSubscription(relayUrls, filter,
            priority = SubscriptionPriority.BACKGROUND, settleMs = 300L, maxWaitMs = 4_000L,
        ) { ev ->
            if (ev.kind == 11) {
                synchronized(topicIds) { topicIds.add(ev.id) }
            }
        }
        // Wait for EOSE-based auto-close (maxWaitMs + buffer)
        delay(4_500L)
        Log.d(TAG, "fetchUserTopicIds: found ${topicIds.size} topics for ${pubkey.take(8)}...")
        return topicIds.distinct()
    }

    /** Build human-readable actor text like "Alice liked your post" or "Alice, Bob, and 3 others liked your post". */
    private fun buildActorText(actorPubkeys: List<String>, action: String): String {
        val names = actorPubkeys.take(2).map { pk ->
            profileCache.getAuthor(pk)?.displayName?.takeIf { it.isNotBlank() }
                ?: profileCache.resolveAuthor(pk).displayName
        }
        return when (actorPubkeys.size) {
            1 -> "${names[0]} $action"
            2 -> "${names[0]} and ${names[1]} $action"
            else -> "${names[0]}, ${names[1]}, and ${actorPubkeys.size - 2} others $action"
        }
    }

    private fun emitSorted() {
        _notifications.value = notificationsById.values
            .sortedByDescending { it.sortTimestamp }
            .toList()
    }

    private fun fetchAndSetTargetNote(noteId: String, notificationId: String, update: (NotificationData) -> (Note?) -> NotificationData) {
        if (subscriptionRelayUrls.isEmpty()) return
        // Buffer for batched fetch — multiple notifications may share the same parent noteId
        pendingTargetFetches.getOrPut(noteId) { mutableListOf() }.add(PendingTargetFetch(noteId, notificationId, update))
        scheduleTargetFetchFlush()
    }

    /** Schedule a debounced flush of the target note fetch buffer. */
    private fun scheduleTargetFetchFlush() {
        targetFetchBatchJob?.cancel()
        targetFetchBatchJob = scope.launch {
            delay(TARGET_FETCH_BATCH_DELAY_MS)
            flushTargetFetchBatch()
        }
    }

    /** Flush all pending target note fetches as ONE batched subscription. */
    private suspend fun flushTargetFetchBatch() {
        if (pendingTargetFetches.isEmpty() || subscriptionRelayUrls.isEmpty()) return
        val batch = pendingTargetFetches.toMap()
        pendingTargetFetches.clear()

        val allNoteIds = batch.keys.toList()
        Log.d(TAG, "Flushing target note batch: ${allNoteIds.size} notes (was ${allNoteIds.size} individual subs)")

        // No kinds restriction: kind-1111 comments can target ANY event kind
        val filter = Filter(ids = allNoteIds, limit = allNoteIds.size)
        val fetched = java.util.concurrent.ConcurrentHashMap<String, Note>()
        val stateMachine = RelayConnectionStateMachine.getInstance()
        val handle = stateMachine.requestTemporarySubscription(subscriptionRelayUrls, filter, priority = SubscriptionPriority.LOW) { ev ->
            fetched[ev.id] = eventToNote(ev)
        }
        delay(2500)
        handle.cancel()
        // Apply fetched notes to their notifications; verify replies are actually TO us
        var removedCount = 0
        for ((noteId, pendingList) in batch) {
            val note = fetched[noteId]
            for (pending in pendingList) {
                val current = notificationsById[pending.notificationId] ?: continue
                // If parent note wasn't fetched, skip enrichment but keep the notification.
                // We can't verify authorship without the target, but removing creates false negatives
                // (legitimate replies vanish when the relay is slow or doesn't carry the parent).
                if (note == null) {
                    continue
                }
                val targetAuthorHex = normalizeAuthorIdForCache(note.author.id)
                val isOurNote = myPubkeyHex != null && targetAuthorHex == myPubkeyHex

                // Kind-7 (like): only show if target note author is the current user
                if (current.type == NotificationType.LIKE && !isOurNote) {
                    notificationsById.remove(pending.notificationId)
                    removedCount++
                    continue
                }
                // Kind-6/16 (repost): only show if the reposted note is authored by us
                if (current.type == NotificationType.REPOST && !isOurNote) {
                    notificationsById.remove(pending.notificationId)
                    // Also clean up the repostByTargetId aggregation entry
                    repostByTargetId.remove(noteId)
                    removedCount++
                    continue
                }
                // Kind-1 reply: verify the parent note is authored by us OR we're cited in content.
                if (current.type == NotificationType.REPLY && current.replyKind == NOTIFICATION_KIND_TEXT && !isOurNote) {
                    val isCitedInContent = myPubkeyHex != null && current.note != null &&
                        isUserCitedInContent(current.note.content, myPubkeyHex!!)
                    if (!isCitedInContent) {
                        notificationsById.remove(pending.notificationId)
                        removedCount++
                        continue
                    }
                    // Parent isn't ours but we're cited → reclassify as MENTION (not a direct reply)
                    val mentionUpdate = current.copy(
                        type = NotificationType.MENTION,
                        text = "${current.author?.displayName ?: "Someone"} mentioned you"
                    )
                    notificationsById[pending.notificationId] = mentionUpdate
                    continue
                }
                var updated = pending.update(current)(note)
                if (current.replyKind == NOTIFICATION_KIND_TOPIC_REPLY && note.kind == 11 && myPubkeyHex != null) {
                    val rootAuthorHex = normalizeAuthorIdForCache(note.author.id)
                    if (rootAuthorHex == myPubkeyHex) {
                        updated = updated.copy(replyKind = 11, text = "${current.author?.displayName ?: "Someone"} replied to your thread")
                    }
                }
                notificationsById[pending.notificationId] = updated
            }
        }
        if (removedCount > 0) Log.d(TAG, "Removed $removedCount false-positive notifications after target fetch")
        emitSorted()
    }

    private fun eventToNote(event: Event): Note {
        val author = profileCache.resolveAuthor(event.pubKey)
        val hashtags = event.tags.toList()
            .filter { it.size >= 2 && it[0] == "t" }
            .mapNotNull { it.getOrNull(1) }
        // Resolve nostr:npub1... mentions to @displayName for cleaner notification previews
        val resolvedContent = resolveNpubMentions(event.content)
        val mediaUrls = social.mycelium.android.utils.UrlDetector.findUrls(event.content)
            .filter { social.mycelium.android.utils.UrlDetector.isImageUrl(it) || social.mycelium.android.utils.UrlDetector.isVideoUrl(it) }
            .distinct()
        val quotedEventIds = social.mycelium.android.utils.Nip19QuoteParser.extractQuotedEventIds(event.content)
        return Note(
            id = event.id,
            author = author,
            content = resolvedContent,
            timestamp = event.createdAt * 1000L,
            likes = 0,
            shares = 0,
            comments = 0,
            isLiked = false,
            hashtags = hashtags,
            mediaUrls = mediaUrls,
            quotedEventIds = quotedEventIds,
            isReply = false,
            kind = event.kind
        )
    }

    /** Replace nostr:npub1... and nostr:nprofile1... with @displayName for notification text previews. */
    private fun resolveNpubMentions(content: String): String {
        val npubRegex = Regex("nostr:(npub1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)", RegexOption.IGNORE_CASE)
        val nprofileRegex = Regex("nostr:(nprofile1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)", RegexOption.IGNORE_CASE)
        var result = content
        // Resolve nprofile first (longer match)
        nprofileRegex.findAll(content).toList().reversed().forEach { match ->
            try {
                val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute("nostr:${match.groupValues[1]}")
                val hex = (parsed?.entity as? com.example.cybin.nip19.NProfile)?.hex
                if (hex != null && hex.length == 64) {
                    val author = profileCache.resolveAuthor(hex)
                    val name = author.displayName.takeIf { !it.endsWith("...") && it != author.username } ?: author.username
                    result = result.replaceRange(match.range, "@$name")
                }
            } catch (_: Exception) { }
        }
        // Resolve npub
        npubRegex.findAll(result).toList().reversed().forEach { match ->
            try {
                val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute("nostr:${match.groupValues[1]}")
                val hex = (parsed?.entity as? com.example.cybin.nip19.NPub)?.hex
                if (hex != null && hex.length == 64) {
                    val author = profileCache.resolveAuthor(hex)
                    val name = author.displayName.takeIf { !it.endsWith("...") && it != author.username } ?: author.username
                    result = result.replaceRange(match.range, "@$name")
                }
            } catch (_: Exception) { }
        }
        return result
    }
}
