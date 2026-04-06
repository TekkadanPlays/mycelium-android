package social.mycelium.android.repository

import android.content.Context
import android.content.SharedPreferences
import social.mycelium.android.debug.MLog
import social.mycelium.android.data.Author
import social.mycelium.android.data.NotificationData
import social.mycelium.android.data.NotificationType
import social.mycelium.android.data.VerificationStatus
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

import social.mycelium.android.repository.messaging.DirectMessageRepository
import social.mycelium.android.repository.sync.AccountScopedRegistry
import social.mycelium.android.repository.cache.ProfileMetadataCache
import social.mycelium.android.repository.feed.DeepHistoryFetcher
import social.mycelium.android.repository.social.MuteListRepository
import social.mycelium.android.repository.feed.NotesRepository
import social.mycelium.android.repository.cache.QuotedNoteCache
/**
 * Repository for real Nostr notifications: events that reference the user (p tag).
 * Amethyst-style: kinds 1 (reply/mention), 7 (like), 6 (repost), 9735 (zap). No follows.
 * Parses e-tags for target note, root/reply for replies; consolidates reposts by reposted note id.
 * Seen IDs are persisted to SharedPreferences so badge survives app restart.
 */
class NotificationsRepository(
    /** Hex pubkey of the account this instance belongs to. */
    private val ownerPubkeyHex: String,
    /** Parent coroutine scope — cancelled when the AccountScope is destroyed. */
    private val parentScope: CoroutineScope,
) {

    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]) +
                CoroutineExceptionHandler { _, t -> MLog.e(TAG, "Coroutine failed: ${t.message}", t) }
    )
    private val profileCache = ProfileMetadataCache.getInstance()

    companion object {
        private const val TAG = "NotificationsRepository"
        private const val NOTIFICATION_KIND_TEXT = 1
        private const val NOTIFICATION_KIND_REPOST = 6
        private const val NOTIFICATION_KIND_REACTION = 7
        private const val NOTIFICATION_KIND_GENERIC_REPOST = 16
        private const val NOTIFICATION_KIND_REPORT = 1984
        private const val NOTIFICATION_KIND_ZAP = 9735
        private const val NOTIFICATION_KIND_HIGHLIGHT = 9802
        private const val NOTIFICATION_KIND_BADGE_AWARD = 8
        private const val NOTIFICATION_KIND_TOPIC_REPLY = 1111
        private const val NOTIFICATION_KIND_POLL = 1068
        private const val NOTIFICATION_KIND_POLL_RESPONSE = 1018
        private const val ONE_MONTH_SEC = 30 * 24 * 60 * 60L
        private const val PREFS_NAME = "notifications_seen"
        private const val PREFS_KEY_SEEN_IDS = "seen_ids"
        private const val PREFS_KEY_PUBKEY = "my_pubkey_hex"
        private const val PREFS_KEY_MARK_ALL_SEEN_EPOCH_MS = "mark_all_seen_epoch_ms"
        private const val SWEEP_FALLBACK_DELAY_MS = 15_000L
        private const val TARGET_FETCH_BATCH_DELAY_MS = 200L
        private const val TARGET_FETCH_TIMEOUT_MS = 2500L
        private const val TARGET_FETCH_RETRY_TIMEOUT_MS = 4000L
        private const val MAX_TARGET_FETCH_RETRIES = 2
        /** Notification ID modulus — expanded from 10k to 100k to reduce hash collisions
         *  that cause unrelated notifications to silently stomp each other. */
        private const val NOTIFICATION_ID_MODULUS = 100_000


        /** Maximum time to wait for profile/enrichment data before firing the notification anyway. */
        private const val ENRICHMENT_WAIT_MS = 3000L
        private const val ENRICHMENT_POLL_MS = 200L

        /** Matches placeholder author names from resolveAuthor (e.g. "a1b2c3d4..."). */
        private val HEX_PLACEHOLDER_REGEX = Regex("^[0-9a-f]{8}\\.\\.\\.$")

        // ── Backward-compatible static shim ──────────────────────────────────
        // Delegates to the active account's instance via AccountScopedRegistry.
        // All existing call sites (MyceliumNavigation, NotificationsScreen, etc.)
        // continue working without changes.

        /** Get the active account's NotificationsRepository instance, or null. */
        private fun active(): NotificationsRepository? =
            AccountScopedRegistry.getActiveScope()?.notificationsRepository

        // ── Static property shims ──
        val notifications: StateFlow<List<NotificationData>>
            get() = active()?.notifications ?: MutableStateFlow(
                emptyList()
            )
        val seenIds: StateFlow<Set<String>> get() = active()?.seenIds ?: MutableStateFlow(emptySet())
        val unseenCount: StateFlow<Int> get() = active()?.unseenCount ?: MutableStateFlow(0)
        val todayReplies: StateFlow<Int> get() = active()?.todayReplies ?: MutableStateFlow(0)
        val todayBoosts: StateFlow<Int> get() = active()?.todayBoosts ?: MutableStateFlow(0)
        val todayReactions: StateFlow<Int> get() = active()?.todayReactions ?: MutableStateFlow(0)
        val todayZapSats: StateFlow<Long> get() = active()?.todayZapSats ?: MutableStateFlow(0L)

        // ── Static method shims ──
        fun init(context: Context) {
            active()?.init(context)
        }

        fun enableAndroidNotifications() {
            active()?.enableAndroidNotifications()
        }

        fun setCacheRelayUrls(urls: List<String>) {
            active()?.setCacheRelayUrls(urls)
        }

        fun getCacheRelayUrls(): List<String> = active()?.getCacheRelayUrls() ?: emptyList()
        fun getMyPubkeyHex(): String? = active()?.getMyPubkeyHex()
        fun markAllAsSeen() {
            active()?.markAllAsSeen()
        }

        fun markAsSeen(notificationId: String) {
            active()?.markAsSeen(notificationId)
        }

        fun markAsSeenByType(type: NotificationType) {
            active()?.markAsSeenByType(type)
        }

        fun updateSubscriptionRelayUrls(allRelays: List<String>) {
            active()?.updateSubscriptionRelayUrls(allRelays)
        }

        fun findNotificationByNoteId(noteId: String): NotificationData? = active()?.findNotificationByNoteId(noteId)
        fun startSubscription(
            pubkey: String,
            inboxRelayUrls: List<String>,
            outboxRelayUrls: List<String>,
            categoryRelayUrls: List<String>
        ) {
            // Route to the correct account's repo by pubkey (not just active).
            // This enables starting subscriptions for background accounts too.
            val target = AccountScopedRegistry.getScope(pubkey)?.notificationsRepository
                ?: active() // Fallback to active if scope not found (legacy path)
            target?.startSubscription(pubkey, inboxRelayUrls, outboxRelayUrls, categoryRelayUrls)
        }

        fun stopSubscription() {
            active()?.stopSubscription()
        }

        /**
         * Fan out cross-pollinated events to ALL loaded account repos.
         * Each instance's own p-tag/E-tag/q-tag gate rejects events that
         * don't reference its owner pubkey — preventing cross-account leakage.
         */
        fun ingestEvent(event: Event) {
            val scopes = AccountScopedRegistry.allScopes.value
            if (scopes.isEmpty()) return
            for ((_, scope) in scopes) {
                if (scope.initialized) {
                    scope.notificationsRepository.ingestEvent(event)
                }
            }
        }

        /** Ingest a historical event (e.g. from DeepHistoryFetcher) into all loaded account repos.
         *  Auto-marks as seen so it populates the list but does NOT increment the badge. */
        fun ingestEventAsHistorical(event: Event) {
            val scopes = AccountScopedRegistry.allScopes.value
            if (scopes.isEmpty()) return
            for ((_, scope) in scopes) {
                if (scope.initialized) {
                    scope.notificationsRepository.ingestEventAsHistorical(event)
                }
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val _notifications = MutableStateFlow<List<NotificationData>>(emptyList())
    val notifications: StateFlow<List<NotificationData>> = _notifications.asStateFlow()

    /** IDs of notifications the user has "seen" (opened notifications screen or tapped one). Badge and dropdown use unseen count. */
    /** Thread-safe backing set for seen notification IDs.
     *  Replaces the old MutableStateFlow<Set<String>> pattern which suffered from
     *  a non-atomic read-modify-write race: concurrent `_seenIds.value = _seenIds.value + id`
     *  would silently drop additions under high throughput.
     *  All mutations go through [addSeenId] / [addSeenIds] which atomically add to the
     *  backing ConcurrentHashMap and then snapshot into the StateFlow for UI observers. */
    private val _seenIdsBacking = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val _seenIds = MutableStateFlow<Set<String>>(emptySet())
    val seenIds: StateFlow<Set<String>> = _seenIds.asStateFlow()

    /** Atomically add a single seen ID. Thread-safe, no lost updates. */
    private fun addSeenId(id: String) {
        if (_seenIdsBacking.add(id)) {
            _seenIds.value = _seenIdsBacking.toSet()
        }
    }

    /** Atomically add multiple seen IDs. Thread-safe, emits once. */
    private fun addSeenIds(ids: Collection<String>) {
        var added = false
        for (id in ids) {
            if (_seenIdsBacking.add(id)) added = true
        }
        if (added) {
            _seenIds.value = _seenIdsBacking.toSet()
        }
    }

    /** Suppresses unseen count during initial relay replay so the badge doesn't flicker
     *  as historical events arrive before the markAllSeen watermark auto-marks them. */
    private val _replaySettled = MutableStateFlow(false)
    val unseenCount: StateFlow<Int> = combine(_notifications, _seenIds, _replaySettled) { list, seen, settled ->
        if (!settled) 0 else list.count { it.id !in seen }
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    private val notificationsById = ConcurrentHashMap<String, NotificationData>()

    /** Active notification subscription handles (inbox deep + sweep + extras). */
    private val notificationHandles = CopyOnWriteArrayList<TemporarySubscriptionHandle>()

    // ── Emit polling ──────────────────────────────────────────────────────
    // A single long-lived coroutine polls emitDirty every 100ms and emits when set.
    // Replaces thousands of cancel+relaunch Job allocations with zero-alloc dirty checks.
    // See startEmitPoller() / emitSorted() / emitSortedImmediate().
    private val EMIT_DEBOUNCE_MS = 100L

    /** Force an immediate emit (bypass debounce). Used after critical operations
     *  like markAllAsSeen or notification removal where the UI must update instantly. */
    @Volatile
    private var emitDirty = false

    /** Fallback inbox relays for users without a NIP-65 relay list. */
    private val BOOTSTRAP_INBOX_RELAYS = listOf(
        "wss://relay.damus.io", "wss://relay.primal.net", "wss://nos.lol",
        "wss://nostr.mom", "wss://relay.nostr.band",
    )

    // seenEventIds REMOVED — handleEvent is idempotent for all types:
    // - Non-consolidated (replies/quotes/comments): notificationsById[event.id] = data is an overwrite
    // - Consolidated (likes/zaps/reposts): actorPubkeys.distinct() handles re-processing
    // The SubscriptionMultiplexer's per-merged-subscription dedup (10K cap) still prevents
    // identical events from the same relay session from wastefully double-processing.
    // Removing this layer allows Phase 3 events to re-enter when classification context changes.

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

    /** Current user hex pubkey (p-tag); used to filter kind-7 so we only show reactions to our notes.
     *  Initialized from [ownerPubkeyHex] — immutable for the lifetime of this instance.
     *  This is the primary cross-account leakage guard: events that don't reference this pubkey are rejected. */
    private var myPubkeyHex: String? = ownerPubkeyHex

    /** Our kind-11 topic IDs — replies to these are "Thread replies" (replyKind=11), not "Comments" (1111). */
    private val myTopicIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Our kind-1 note IDs — used to detect quotes (q-tag references to our notes). */
    private val myNoteIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Cached event tags for kind-1 notifications, keyed by event ID.
     *  Used by Phase 3 reclassification sweep to detect q-tags retroactively
     *  (since myNoteIds isn't populated until after Phase 1 EOSE). */
    private val kind1EventTags = ConcurrentHashMap<String, List<List<String>>>()

    /** Lock for serializing read-modify-write on consolidated notification IDs (like:*, repost:*, zap:*).
     *  ConcurrentHashMap guarantees per-op thread safety, but the compound
     *  read→compute→write in handleLike/handleRepost/handleZap is NOT atomic.
     *  Without this, two concurrent events (e.g., two users reacting to the same note)
     *  that arrive from different cross-pollination paths can race, causing the second
     *  write to clobber the first and silently drop a reaction/zap/repost. */
    private val consolidationLock = Any()

    /** Consolidated notification IDs that have already fired a system notification in this session.
     *  Prevents duplicate push notifications when a new actor arrives for the same aggregated
     *  notification (e.g. 2nd person likes the same post). The first fire creates the tray entry;
     *  subsequent events silently update it without re-playing sound/vibration. */
    private val firedNotifIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** True while processing events from [ingestEventAsHistorical]. Handlers check this flag
     *  to skip push notification and auto-mark consolidated IDs as seen. */
    @Volatile
    private var isHistoricalIngestion = false

    private var prefs: SharedPreferences? = null
    @Volatile
    private var appContext: Context? = null

    /** Wall-clock millis when the user last pressed "Read All".
     *  Any notification with sortTimestamp <= this is auto-marked as seen,
     *  even if it arrives from a later subscription phase (e.g. Phase 3). */
    @Volatile
    private var markAllSeenEpochMs: Long = 0L

    /** Epoch seconds when the current notification subscription session started.
     *  Push notifications are only fired for events created AFTER this timestamp,
     *  so historical relay replay never spams the notification shade. */
    @Volatile
    private var sessionStartEpochSec = Long.MAX_VALUE

    // ── Batched target note fetch ──────────────────────────────────────────────
    /** Pending target note fetch entry. */
    private data class PendingTargetFetch(
        val noteId: String,
        val notificationId: String,
        val update: (NotificationData) -> (Note?) -> NotificationData,
        val retryCount: Int = 0,
    )

    /** Buffer of pending target note fetches waiting to be flushed as one subscription.
     *  Keyed by parent noteId → list of notifications that need that parent verified. */
    private val pendingTargetFetches = java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<PendingTargetFetch>>()

    /** Tick counter for continuous validation loop to periodically retry exhausted fetches. */
    private var targetFetchValidationTick = 0

    /** Dirty flag for target fetch polling loop. Set to true when new entries are added to pendingTargetFetches. */
    @Volatile
    private var targetFetchDirty = false
    private var targetFetchPollerJob: Job? = null
    private val targetFetchMutex = kotlinx.coroutines.sync.Mutex()

    /** Call once from Application or Activity to provide app context. */
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Keep filtering pubkey aligned with this instance's account. Shared PREFS_KEY_PUBKEY is
        // written by whichever account last ran startSubscription — it must not override ownerPubkeyHex
        // (multi-account) or become null when the key is absent (drops all feed cross-pollination
        // until startSubscription runs).
        myPubkeyHex = ownerPubkeyHex
        MLog.d(TAG, "init: myPubkeyHex bound to owner ${ownerPubkeyHex.take(8)}")
        // Restore "Read All" watermark so historical events stay seen across restarts
        markAllSeenEpochMs = prefs?.getLong(PREFS_KEY_MARK_ALL_SEEN_EPOCH_MS, 0L) ?: 0L
        if (markAllSeenEpochMs > 0) MLog.d(TAG, "init: restored markAllSeenEpochMs=$markAllSeenEpochMs")
        // Restore persisted seen IDs so read state survives restarts.
        // Without this, every restart creates a burst of "new" notifications
        // for events between the watermark and now.
        restoreSeenIds()
    }

    /** Allow Android notifications for events created after this moment.
     *  Called once the initial relay replay window has settled. */
    fun enableAndroidNotifications() {
        sessionStartEpochSec = System.currentTimeMillis() / 1000
        MLog.d(TAG, "Push notifications enabled for events after epoch=$sessionStartEpochSec")
        startDmNotificationObserver()
    }

    private var dmNotifObserverJob: Job? = null

    /** Observe DirectMessageRepository for new gift wraps and fire obfuscated DM notifications. */
    private fun startDmNotificationObserver() {
        dmNotifObserverJob?.cancel()
        dmNotifObserverJob = scope.launch {
            DirectMessageRepository.newDmSignal.collect { giftWrapId ->
                fireDmNotification(giftWrapId)
            }
        }
        if (social.mycelium.android.ui.settings.NotificationPreferences.notifyDMs.value) {
            DirectMessageRepository.ensureSubscriptionForNotifications()
        }
    }

    /** Fire an obfuscated (or optionally decrypted) DM notification. */
    private fun fireDmNotification(giftWrapId: String) {
        val ctx = appContext ?: return
        val prefs = social.mycelium.android.ui.settings.NotificationPreferences
        if (!prefs.pushEnabled.value) return
        if (!prefs.isNotificationAllowedForAccount(ownerPubkeyHex, NotificationType.DM)) return

        val title = "Mycelium"
        val body = "New private message"
        val notifId = social.mycelium.android.services.NotificationChannelManager.NOTIFICATION_ID_SOCIAL_BASE +
                (giftWrapId.hashCode() and 0x7FFFFFFF) % NOTIFICATION_ID_MODULUS

        MLog.d(TAG, "fireDmNotification: giftWrap=${giftWrapId.take(8)} account=${ownerPubkeyHex.take(8)}")
        social.mycelium.android.services.NotificationChannelManager.postSocialNotification(
            ctx,
            social.mycelium.android.services.NotificationChannelManager.CHANNEL_DMS,
            notifId,
            title,
            body,
            notifType = NotificationType.DM.name,
            accountPubkey = ownerPubkeyHex
        )
    }

    /**
     * Post an Android notification for a social event, respecting user preferences.
     * Each notification type maps to a specific Android notification channel so the user
     * can independently configure sounds, vibration, and visibility in system settings.
     */
    private fun fireAndroidNotification(
        type: NotificationType, title: String, body: String, notifIdSuffix: String,
        eventEpochSec: Long = 0L, noteId: String? = null, rootNoteId: String? = null
    ) {
        // Suppress entirely during deep-fetch historical ingestion
        if (isHistoricalIngestion) return
        // Suppress historical replay: only fire for events created after session start
        if (eventEpochSec > 0 && eventEpochSec < sessionStartEpochSec) {
            MLog.d(
                TAG,
                "fireNotif SUPPRESSED (historical): type=$type epoch=$eventEpochSec < session=$sessionStartEpochSec suffix=${
                    notifIdSuffix.take(8)
                }"
            )
            return
        }
        if (sessionStartEpochSec == Long.MAX_VALUE) {
            MLog.d(TAG, "fireNotif SUPPRESSED (not enabled yet): type=$type suffix=${notifIdSuffix.take(8)}")
            return
        }
        // Dedup: consolidated notifications (like:X, zap:X, repost:X) fire once per session.
        // Subsequent events silently update the notification text without re-playing
        // sound/vibration. This prevents notification spam for popular posts.
        if (!firedNotifIds.add(notifIdSuffix)) {
            MLog.d(TAG, "fireNotif SUPPRESSED (already fired): type=$type suffix=${notifIdSuffix.take(8)}")
            return
        }
        // NOTE: We intentionally do NOT gate on _seenIds here. seenIds tracks which
        // notifications the user has "read" in the UI (for badge count). For aggregated
        // types (zaps, likes, reposts), the notification ID stays the same (e.g. "zap:$eTag")
        // even when new events arrive — so gating on seenIds would permanently silence
        // all future zaps/likes on notes the user has already viewed in the notification list.
        // The historical replay gate (sessionStartEpochSec) and event-level dedup (seenEventIds
        // in handleEvent) already prevent spam.
        val ctx = appContext ?: return
        val prefs = social.mycelium.android.ui.settings.NotificationPreferences
        if (!prefs.pushEnabled.value) {
            MLog.d(TAG, "fireNotif SUPPRESSED (push disabled): type=$type suffix=${notifIdSuffix.take(8)}")
            return
        }
        // Use per-account preference lookup so background accounts respect their own settings
        // (not the active account's in-memory state flows).
        if (!prefs.isNotificationAllowedForAccount(ownerPubkeyHex, type)) {
            MLog.d(
                TAG,
                "fireNotif SUPPRESSED (channel disabled for ${ownerPubkeyHex.take(8)}): type=$type suffix=${
                    notifIdSuffix.take(8)
                }"
            )
            return
        }

        // Defer posting only when the author name is a hex placeholder (profile not yet cached).
        // Wait up to ENRICHMENT_WAIT_MS for the profile to resolve so the notification
        // shows a real display name instead of "a1b2c3d4...". If already resolved, fire immediately.
        scope.launch {
            val data = notificationsById[notifIdSuffix]
            val authorName = data?.author?.displayName ?: title
            val isPlaceholder = HEX_PLACEHOLDER_REGEX.matches(authorName)

            if (isPlaceholder) {
                val startMs = System.currentTimeMillis()
                while (System.currentTimeMillis() - startMs < ENRICHMENT_WAIT_MS) {
                    delay(ENRICHMENT_POLL_MS)
                    val current = notificationsById[notifIdSuffix] ?: break
                    val authorResolved = current.author?.displayName?.let {
                        !HEX_PLACEHOLDER_REGEX.matches(it)
                    } ?: false
                    if (authorResolved) break
                }
                // Notification may have been removed during enrichment
                if (notificationsById[notifIdSuffix] == null) {
                    MLog.d(
                        TAG,
                        "fireNotif CANCELLED (removed during enrichment): type=$type suffix=${notifIdSuffix.take(8)}"
                    )
                    return@launch
                }
            }

            // Re-read the enriched notification data for final title/body
            val enriched = notificationsById[notifIdSuffix]
            val finalTitle = enriched?.author?.displayName ?: title
            val finalBody = enriched?.text ?: body
            val finalType = enriched?.type ?: type

            val channelId = when (finalType) {
                NotificationType.REPLY -> social.mycelium.android.services.NotificationChannelManager.CHANNEL_REPLIES
                NotificationType.COMMENT -> social.mycelium.android.services.NotificationChannelManager.CHANNEL_COMMENTS
                NotificationType.LIKE -> social.mycelium.android.services.NotificationChannelManager.CHANNEL_REACTIONS
                NotificationType.ZAP -> social.mycelium.android.services.NotificationChannelManager.CHANNEL_ZAPS
                NotificationType.REPOST -> social.mycelium.android.services.NotificationChannelManager.CHANNEL_REPOSTS
                NotificationType.MENTION -> social.mycelium.android.services.NotificationChannelManager.CHANNEL_MENTIONS
                NotificationType.QUOTE -> social.mycelium.android.services.NotificationChannelManager.CHANNEL_MENTIONS
                NotificationType.HIGHLIGHT -> social.mycelium.android.services.NotificationChannelManager.CHANNEL_MENTIONS
                NotificationType.DM -> social.mycelium.android.services.NotificationChannelManager.CHANNEL_DMS
                NotificationType.POLL_VOTE -> social.mycelium.android.services.NotificationChannelManager.CHANNEL_POLLS
                NotificationType.REPORT -> social.mycelium.android.services.NotificationChannelManager.CHANNEL_MENTIONS
                NotificationType.BADGE_AWARD -> social.mycelium.android.services.NotificationChannelManager.CHANNEL_REACTIONS
            }
            val notifId =
                social.mycelium.android.services.NotificationChannelManager.NOTIFICATION_ID_SOCIAL_BASE + (notifIdSuffix.hashCode() and 0x7FFFFFFF) % NOTIFICATION_ID_MODULUS
            MLog.d(
                TAG,
                "fireNotif POSTING: type=$finalType channel=$channelId id=$notifId noteId=${noteId?.take(8)} account=${
                    ownerPubkeyHex.take(8)
                } title=$finalTitle"
            )
            social.mycelium.android.services.NotificationChannelManager.postSocialNotification(
                ctx, channelId, notifId, finalTitle, finalBody,
                noteId = noteId, rootNoteId = rootNoteId, notifType = finalType.name,
                accountPubkey = ownerPubkeyHex
            )
        }
    }

    // Seen IDs are in-memory only — no persistence needed.
    // System notifications are cleared on read; on restart everything is fresh.

    fun setCacheRelayUrls(urls: List<String>) {
        cacheRelayUrls = urls
    }

    fun getCacheRelayUrls(): List<String> = cacheRelayUrls

    fun getMyPubkeyHex(): String? = myPubkeyHex

    /** Mark all current notifications as seen (e.g. when user opens the notifications screen). Clears badge.
     *  Also sets a watermark timestamp so that historical events arriving later (e.g. Phase 3
     *  thread replies / quotes) are auto-marked as seen without requiring another button press. */
    fun markAllAsSeen() {
        addSeenIds(_notifications.value.map { it.id })
        markAllSeenEpochMs = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            prefs?.edit()?.putLong(PREFS_KEY_MARK_ALL_SEEN_EPOCH_MS, markAllSeenEpochMs)?.apply()
        }
        persistSeenIds()
        cancelAllSystemNotifications()
        MLog.d(TAG, "markAllAsSeen: watermark set to $markAllSeenEpochMs, seenIds=${_seenIds.value.size}")
    }

    /** Look up a NotificationData by the event/note ID (used for deep-link navigation from notification tap). */
    fun findNotificationByNoteId(noteId: String): NotificationData? {
        // Direct lookup by notification ID (for replies/quotes/comments, id == event.id)
        notificationsById[noteId]?.let { return it }
        // For aggregated notifications (likes, zaps, reposts), the id is "like:$eTag" etc.
        // Search by noteId matching the notification's note ID, targetNoteId, or replyNoteId
        return notificationsById.values.firstOrNull { data ->
            data.note?.id == noteId || data.targetNoteId == noteId || data.targetNote?.id == noteId || data.replyNoteId == noteId
        }
    }

    /** Mark one notification as seen (e.g. when user taps it to open thread). */
    fun markAsSeen(notificationId: String) {
        addSeenId(notificationId)
        cancelSystemNotification(notificationId)
        persistSeenIds()
    }

    /** Mark all notifications of a specific type as seen (e.g. when user switches to that tab). */
    fun markAsSeenByType(type: NotificationType) {
        val idsForType = _notifications.value.filter { it.type == type }.map { it.id }.toSet()
        if (idsForType.isNotEmpty()) {
            addSeenIds(idsForType)
            idsForType.forEach { cancelSystemNotification(it) }
            persistSeenIds()
        }
    }

    /** Cancel a single Android system notification by its notification ID suffix. */
    private fun cancelSystemNotification(notifIdSuffix: String) {
        val ctx = appContext ?: return
        val mgr = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return
        val notifId =
            social.mycelium.android.services.NotificationChannelManager.NOTIFICATION_ID_SOCIAL_BASE + (notifIdSuffix.hashCode() and 0x7FFFFFFF) % NOTIFICATION_ID_MODULUS
        mgr.cancel(notifId)
    }

    /** Cancel all social Android system notifications (IDs in the social range). */
    private fun cancelAllSystemNotifications() {
        val ctx = appContext ?: return
        val mgr = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return
        // Cancel all active notifications from our app except the foreground service notification
        mgr.activeNotifications.forEach { sbn ->
            if (sbn.id != social.mycelium.android.services.NotificationChannelManager.NOTIFICATION_ID_RELAY_SERVICE) {
                mgr.cancel(sbn.id)
            }
        }
    }

    /** Update the relay URLs used for notification enrichment (target-note fetches, badge
     *  definitions, poll enrichment). Call when the user adds/removes relays so new relays
     *  are included in subsequent notification fetches. */
    fun updateSubscriptionRelayUrls(allRelays: List<String>) {
        if (allRelays.isEmpty()) return
        val old = subscriptionRelayUrls
        subscriptionRelayUrls = allRelays
        cacheRelayUrls = allRelays
        if (old.sorted() != allRelays.sorted()) {
            MLog.d(TAG, "Notification relay URLs updated: ${old.size} → ${allRelays.size}")
        }
    }

    /** Trim seen IDs to only include IDs that still exist in the current notification list (prevents unbounded growth). */
    private fun trimSeenIds() {
        val currentIds = _notifications.value.mapTo(mutableSetOf()) { it.id }
        val removed = _seenIdsBacking.retainAll(currentIds)
        if (removed) {
            _seenIds.value = _seenIdsBacking.toSet()
            persistSeenIds()
        }
    }

    // ── Seen ID persistence ──────────────────────────────────────────────────
    // Persist seen IDs to SharedPreferences so read state survives app restarts.
    // Without this, every restart creates a burst of "new" notifications for events
    // between the markAllSeen watermark and the current time.

    private val PREFS_KEY_SEEN_IDS_SET = "seen_ids_set"
    private val MAX_PERSISTED_SEEN_IDS = 2000
    private var seenIdsPersistJob: Job? = null

    /** Debounced persist of seen IDs. Called after any seen state change. */
    private fun persistSeenIds() {
        seenIdsPersistJob?.cancel()
        seenIdsPersistJob = scope.launch(Dispatchers.IO) {
            delay(500L) // Debounce: batch rapid seen changes
            val ids = _seenIdsBacking.toSet()
            // Cap at MAX to prevent SharedPreferences from growing unbounded
            val toSave = if (ids.size > MAX_PERSISTED_SEEN_IDS) {
                // Keep the most recent IDs (intersection with current notifications)
                val currentNotifIds = _notifications.value.mapTo(mutableSetOf()) { it.id }
                ids.intersect(currentNotifIds).take(MAX_PERSISTED_SEEN_IDS)
            } else ids
            prefs?.edit()?.putStringSet(PREFS_KEY_SEEN_IDS_SET, toSave.toSet())?.apply()
        }
    }

    /** Restore seen IDs from SharedPreferences. Called during init(). */
    private fun restoreSeenIds() {
        val saved = prefs?.getStringSet(PREFS_KEY_SEEN_IDS_SET, null)
        if (saved != null && saved.isNotEmpty()) {
            _seenIdsBacking.addAll(saved)
            _seenIds.value = _seenIdsBacking.toSet()
            MLog.d(TAG, "Restored ${saved.size} seen IDs from disk")
        }
    }

    /**
     * Start tiered notification subscriptions for the given user.
     *
     * **Phase 1** (immediate): Deep fetch from inbox relays — these are where well-behaved
     * clients send reactions/replies TO us. No `since` restriction, high limits → complete
     * user history.
     *
     * **Phase 2** (delayed [SWEEP_DELAY_MS]): Moderate sweep of outbox/category relays
     * (minus inbox set) — catches notifications from users who don't know our inbox relays.
     * Uses a 1-week `since` window with modest limits.
     *
     * **Phase 3** (delayed 2s after phase 1): Discover user's kind-11 topic IDs and kind-1
     * note IDs, then subscribe for kind-1111 thread replies (E-tag) and quote posts (q-tag)
     * on inbox relays.
     *
     * All subscriptions use [SubscriptionPriority.HIGH] so notifications arrive promptly
     * alongside feed and profile subs, preempting lower-priority enrichment queries.
     * Connections to new relays are jittered at the pool level ([CybinRelayPool.connectWithJitter])
     * to avoid thundering-herd on startup.
     *
     * @param inboxRelayUrls  NIP-65 read relays (where others send events TO us)
     * @param outboxRelayUrls NIP-65 write relays (where our notes live)
     * @param categoryRelayUrls  User's custom relay categories
     */
    fun startSubscription(
        pubkey: String,
        inboxRelayUrls: List<String>,
        outboxRelayUrls: List<String>,
        categoryRelayUrls: List<String>,
    ) {
        // ── Strict owner gate ────────────────────────────────────────────────
        // Each NotificationsRepository instance is bound to ownerPubkeyHex.
        // Reject any attempt to start a subscription for a different pubkey.
        if (pubkey != ownerPubkeyHex) {
            MLog.e(
                TAG,
                "startSubscription REJECTED: pubkey ${pubkey.take(8)} != owner ${ownerPubkeyHex.take(8)} — use the correct account's repo"
            )
            return
        }
        val allRelays = (inboxRelayUrls + outboxRelayUrls + categoryRelayUrls)
            .map { it.trim().removeSuffix("/") }
            .distinct()
        if (allRelays.isEmpty() || pubkey.isBlank()) {
            MLog.w(TAG, "startSubscription: empty relays or pubkey")
            return
        }
        if (myPubkeyHex == pubkey && notificationHandles.isNotEmpty()) {
            MLog.d(TAG, "startSubscription: already active for ${pubkey.take(8)}..., skipping")
            return
        }
        val isNewUser = myPubkeyHex != pubkey
        MLog.d(
            TAG,
            "startSubscription: isNewUser=$isNewUser (myPubkeyHex=${myPubkeyHex?.take(8)}, pubkey=${pubkey.take(8)}), seenIds=${_seenIds.value.size}"
        )
        stopSubscription()
        // Re-suppress push notifications until enableAndroidNotifications() is called
        sessionStartEpochSec = Long.MAX_VALUE
        // Suppress badge count during initial replay (unmasked after Phase 1 EOSE)
        _replaySettled.value = false
        if (isNewUser) {
            notificationsById.clear()
            myTopicIds.clear()
            myNoteIds.clear()
            kind1EventTags.clear()
            targetFetchExhaustedIds.clear()
            _notifications.value = emptyList()
        }
        subscriptionRelayUrls = allRelays
        myPubkeyHex = pubkey
        // Persist pubkey so cold-start recognises the same user
        scope.launch(Dispatchers.IO) { prefs?.edit()?.putString(PREFS_KEY_PUBKEY, pubkey)?.apply() }
        // ── Restore notifications from Room before relay fetch (instant cold-start) ──
        scope.launch(Dispatchers.IO) { loadNotificationsFromRoom(pubkey) }
        // ── Start single profile watcher (replaces N per-event coroutines) ──
        startProfileWatcher()
        // ── Start emit poller (replaces cancel+relaunch debounce) ──
        startEmitPoller()
        // ── Start target fetch and profile batch pollers (dirty-flag based) ──
        startTargetFetchPoller()
        startProfileBatchPoller()
        val stateMachine = RelayConnectionStateMachine.getInstance()

        // ── Phase 1: Deep inbox fetch (immediate) ────────────────────────────
        val inboxUrls = inboxRelayUrls.map { it.trim().removeSuffix("/") }.distinct()
            .ifEmpty { BOOTSTRAP_INBOX_RELAYS }
        val inboxFilters = listOf(
            // High-volume: text, reaction, repost, zap, topic reply, polls — complete history
            Filter(
                kinds = listOf(
                    NOTIFICATION_KIND_TEXT,
                    NOTIFICATION_KIND_REPOST,
                    NOTIFICATION_KIND_GENERIC_REPOST,
                    NOTIFICATION_KIND_REACTION,
                    NOTIFICATION_KIND_ZAP,
                    NOTIFICATION_KIND_TOPIC_REPLY,
                    NOTIFICATION_KIND_BADGE_AWARD,
                    NOTIFICATION_KIND_POLL,
                    NOTIFICATION_KIND_POLL_RESPONSE
                ),
                tags = mapOf("p" to listOf(pubkey)),
                limit = 5000
            ),
            // Lower-volume: reports, highlights
            Filter(
                kinds = listOf(NOTIFICATION_KIND_REPORT, NOTIFICATION_KIND_HIGHLIGHT),
                tags = mapOf("p" to listOf(pubkey)),
                limit = 1000
            ),
        )
        // Signal that Phase 1 EOSE has fired — Phase 2, 3, and re-enrichment chain off this
        val phase1Eose = kotlinx.coroutines.CompletableDeferred<Unit>()

        val inboxHandle = stateMachine.requestTemporarySubscription(
            inboxUrls, inboxFilters, priority = SubscriptionPriority.HIGH,
            onEose = {
                MLog.d(TAG, "Phase 1 EOSE received — unblocking Phase 2/3 + re-enrichment")
                phase1Eose.complete(Unit)
                // Replay is done — unseen count can now reflect real state.
                // emitSorted() already ran for each event, so the watermark auto-marking
                // has already happened. Safe to unmask the badge.
                _replaySettled.value = true
                scope.launch { reEnrichOrphanedNotifications() }
            }
        ) { event -> handleEvent(event) }
        notificationHandles.add(inboxHandle)
        MLog.d(TAG, "Phase 1: Deep inbox fetch on ${inboxUrls.size} relays (${inboxFilters.size} filters, HIGH)")

        // ── Phase 2: Sweep non-inbox relays — starts after Phase 1 EOSE ──────
        val sweepUrls = (outboxRelayUrls + categoryRelayUrls)
            .map { it.trim().removeSuffix("/") }
            .distinct()
            .filter { it !in inboxUrls }
        if (sweepUrls.isNotEmpty()) {
            scope.launch {
                // Await Phase 1 EOSE (or safety-net timeout)
                kotlinx.coroutines.withTimeoutOrNull(SWEEP_FALLBACK_DELAY_MS) { phase1Eose.await() }
                val sweepSince = (System.currentTimeMillis() / 1000) - ONE_MONTH_SEC
                val sweepFilters = listOf(
                    Filter(
                        kinds = listOf(
                            NOTIFICATION_KIND_TEXT,
                            NOTIFICATION_KIND_REPOST,
                            NOTIFICATION_KIND_GENERIC_REPOST,
                            NOTIFICATION_KIND_REACTION,
                            NOTIFICATION_KIND_ZAP,
                            NOTIFICATION_KIND_TOPIC_REPLY,
                            NOTIFICATION_KIND_BADGE_AWARD,
                            NOTIFICATION_KIND_POLL,
                            NOTIFICATION_KIND_POLL_RESPONSE
                        ),
                        tags = mapOf("p" to listOf(pubkey)),
                        since = sweepSince,
                        limit = 500
                    ),
                    Filter(
                        kinds = listOf(NOTIFICATION_KIND_REPORT, NOTIFICATION_KIND_HIGHLIGHT),
                        tags = mapOf("p" to listOf(pubkey)),
                        since = sweepSince,
                        limit = 100
                    ),
                )
                val sweepHandle = stateMachine.requestTemporarySubscription(
                    sweepUrls, sweepFilters, priority = SubscriptionPriority.HIGH,
                    onEose = {
                        MLog.d(TAG, "Phase 2 EOSE received — triggering sweep re-enrichment")
                        scope.launch { reEnrichOrphanedNotifications() }
                    }
                ) { event -> handleEvent(event) }
                notificationHandles.add(sweepHandle)
                MLog.d(TAG, "Phase 2: Sweep ${sweepUrls.size} non-inbox relays (since=1mo, HIGH)")
            }
        }

        MLog.d(
            TAG, "Tiered notification sub started for ${pubkey.take(8)}...: " +
                    "inbox=${inboxUrls.size} (deep), sweep=${sweepUrls.size}"
        )

        // ── Phase 3: Discover user content IDs → thread reply + quote subs ───
        // Chains off Phase 1 EOSE so topic/note IDs are fetched after inbox events arrive
        scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(SWEEP_FALLBACK_DELAY_MS) { phase1Eose.await() }
            val topicIds = fetchUserTopicIds(pubkey, allRelays, null)
            myTopicIds.addAll(topicIds)
            // Reclassify Phase 1 kind-1111 events that were marked as COMMENT because
            // myTopicIds was empty. Now that topic IDs are known, promote them to thread REPLY.
            reclassifyTopicReplies()
            val noteIdsFromRelays = fetchUserNoteIds(pubkey, allRelays, null)
            val noteIds = mergeNoteIdsWithFeedCache(pubkey, noteIdsFromRelays)
            myNoteIds.addAll(noteIds)
            // Reclassify Phase 1 replies that are actually quotes (q-tag couldn't be
            // checked during Phase 1 because myNoteIds was empty at that point)
            reclassifyQuotes()
            val extraFilters = mutableListOf<Filter>()
            if (topicIds.isNotEmpty()) {
                MLog.d(TAG, "Phase 3: Found ${topicIds.size} topics, adding thread replies filter")
                extraFilters.add(
                    Filter(
                        kinds = listOf(NOTIFICATION_KIND_TOPIC_REPLY),
                        tags = mapOf("E" to topicIds),
                        limit = 5000
                    )
                )
            }
            if (noteIds.isNotEmpty()) {
                MLog.d(TAG, "Phase 3: Found ${noteIds.size} notes, adding quotes + comment replies + repost filters")
                extraFilters.add(
                    Filter(
                        kinds = listOf(NOTIFICATION_KIND_TEXT),
                        tags = mapOf("q" to noteIds),
                        limit = 5000
                    )
                )
                // NIP-22: kind-1111 comments can reply to kind-1 notes too (E-tag = root note ID).
                // Phase 1 only catches kind-1111 with p-tag; comments that don't p-tag the
                // root author are missed. Subscribe for E-tag matches on our kind-1 note IDs.
                extraFilters.add(
                    Filter(
                        kinds = listOf(NOTIFICATION_KIND_TOPIC_REPLY),
                        tags = mapOf("E" to noteIds),
                        limit = 5000
                    )
                )
                // NIP-18: kind-6/16 reposts that don't include a p-tag are missed by Phase 1's
                // p-tag relay filter. Subscribe for e-tag matches on our note IDs to catch them.
                extraFilters.add(
                    Filter(
                        kinds = listOf(NOTIFICATION_KIND_REPOST, NOTIFICATION_KIND_GENERIC_REPOST),
                        tags = mapOf("e" to noteIds),
                        limit = 5000
                    )
                )
            }
            if (extraFilters.isNotEmpty()) {
                val extraHandle = stateMachine.requestTemporarySubscription(
                    inboxUrls, extraFilters, priority = SubscriptionPriority.HIGH,
                    onEose = {
                        MLog.d(TAG, "Phase 3 EOSE received — triggering thread reply re-enrichment")
                        scope.launch { reEnrichOrphanedNotifications() }
                    }
                ) { event -> handleEvent(event) }
                notificationHandles.add(extraHandle)
                MLog.d(TAG, "Phase 3: Thread reply + quote filters on ${inboxUrls.size} inbox relays")
            }
        }

        // ── Safety-net: unmask badge after timeout if EOSE never fires ──
        scope.launch {
            delay(SWEEP_FALLBACK_DELAY_MS)
            if (!_replaySettled.value) {
                MLog.d(TAG, "Safety-net: unmasking badge (Phase 1 EOSE not received in ${SWEEP_FALLBACK_DELAY_MS}ms)")
                _replaySettled.value = true
            }
        }

        // ── Continuous Validation Loop ──
        // Periodically sweep for incomplete notifications (missing target notes or unresolved
        // profiles) so that we consistently populate data even if initial fetches fail.
        scope.launch {
            while (true) {
                delay(30_000L) // Verify every 30s
                targetFetchValidationTick++
                // Every 5 minutes (10 sweeps), clear the exhausted list so that permanently
                // failed fetches get another chance to resolve as new relays connect.
                if (targetFetchValidationTick >= 10) {
                    targetFetchValidationTick = 0
                    if (targetFetchExhaustedIds.isNotEmpty()) {
                        MLog.d(TAG, "Continuous validation: clearing ${targetFetchExhaustedIds.size} exhausted fetches for retry")
                        targetFetchExhaustedIds.clear()
                    }
                }
                reEnrichOrphanedNotifications()
            }
        }
    }

    fun stopSubscription() {
        val count = notificationHandles.size
        for (handle in notificationHandles) {
            try {
                handle.cancel()
            } catch (_: Exception) {
            }
        }
        notificationHandles.clear()
        stopProfileWatcher()
        emitPollerJob?.cancel()
        emitPollerJob = null
        targetFetchPollerJob?.cancel()
        targetFetchPollerJob = null
        profileBatchPollerJob?.cancel()
        profileBatchPollerJob = null
        // Flush any pending notification save
        val ctx = appContext
        if (ctx != null && notificationsById.isNotEmpty()) {
            scope.launch(Dispatchers.IO) { saveNotificationsToRoom(ctx) }
        }
        // Reset session state
        firedNotifIds.clear()
        isHistoricalIngestion = false
        MLog.d(TAG, "Notifications subscription stopped ($count handles)")
    }

    private val ACCEPTED_KINDS = setOf(
        NOTIFICATION_KIND_TEXT, NOTIFICATION_KIND_REPOST, NOTIFICATION_KIND_GENERIC_REPOST,
        NOTIFICATION_KIND_REACTION, NOTIFICATION_KIND_ZAP, NOTIFICATION_KIND_TOPIC_REPLY,
        NOTIFICATION_KIND_REPORT, NOTIFICATION_KIND_HIGHLIGHT, NOTIFICATION_KIND_BADGE_AWARD,
        NOTIFICATION_KIND_POLL, NOTIFICATION_KIND_POLL_RESPONSE
    )

    /**
     * Check if a kind-6/16 repost event references one of our notes.
     *
     * NIP-18 reposts don't reliably include a p-tag for the original note author —
     * many clients omit it. So we check:
     * 1. **Fast path**: e-tag target is in [myNoteIds] (O(1) set lookup).
     * 2. **Feed-cache fallback**: Before Phase 3 populates [myNoteIds], check the
     *    live feed cache for the reposted note. If the cached note's author matches
     *    [myPubkeyHex], the repost is relevant.
     * 3. **Embedded JSON fallback**: Parse the repost's content (kind-6 embeds the
     *    original event JSON) to extract the author pubkey.
     */
    private fun isRepostOfOurNote(event: Event, ourPubkey: String): Boolean {
        val repostedNoteId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
            ?: return false
        // Fast path: myNoteIds is populated after Phase 3
        if (repostedNoteId in myNoteIds) return true
        // Feed-cache fallback: Phase 3 hasn't run yet but the note is in the live feed
        val cached = NotesRepository.getInstance().getNoteFromCache(repostedNoteId)
        if (cached != null) {
            val cachedAuthor = normalizeAuthorIdForCache(cached.author.id)
            if (cachedAuthor == ourPubkey) return true
        }
        // Embedded JSON fallback: kind-6 content often contains the original event JSON
        if (event.content.isNotBlank()) {
            val embeddedPubkey = Regex(""""pubkey"\s*:\s*"([a-f0-9]{64})"""").find(event.content)?.groupValues?.get(1)
            if (embeddedPubkey != null && normalizeAuthorIdForCache(embeddedPubkey) == ourPubkey) return true
        }
        return false
    }

    /**
     * Public entry point for cross-pollination: other event pipelines (feed, thread replies)
     * can forward events here so notification state stays up-to-date even when the dedicated
     * BACKGROUND notification subscription is preempted by higher-priority relay slots.
     *
     * Performs a client-side relevance check before forwarding to handleEvent():
     * - p-tag matches our pubkey (replies, reactions, zaps, reposts TO us)
     * - E-tag matches one of our kind-11 topic IDs (kind-1111 thread replies)
     * - q-tag matches one of our kind-1 note IDs (quote posts)
     * - e-tag on kind-6/16 matches one of our note IDs (reposts OF us — p-tag unreliable per NIP-18)
     * This mirrors the relay-side filters used by the dedicated notification subscriptions.
     */
    fun ingestEvent(event: Event) {
        if (event.kind !in ACCEPTED_KINDS) return
        val pubkey = myPubkeyHex ?: return
        // Check relevance: does this event reference us?
        // NIP-22 kind-1111 uses uppercase P for pubkeys; NIP-01 uses lowercase p.
        val hasPTag = event.tags.any { it.size >= 2 && (it[0] == "p" || it[0] == "P") && it[1] == pubkey }
        // NIP-22 kind-1111 uses uppercase E for root; also accept lowercase e for non-compliant clients.
        val hasETag = event.kind == NOTIFICATION_KIND_TOPIC_REPLY &&
                event.tags.any { it.size >= 2 && (it[0] == "E" || it[0] == "e") && (it[1] in myTopicIds || it[1] in myNoteIds) }
        val hasQTag = event.kind == NOTIFICATION_KIND_TEXT &&
                event.tags.any { it.size >= 2 && it[0] == "q" && it[1] in myNoteIds }
        val hasRepostETag = event.kind in setOf(NOTIFICATION_KIND_REPOST, NOTIFICATION_KIND_GENERIC_REPOST) &&
                isRepostOfOurNote(event, pubkey)
        if (!hasPTag && !hasETag && !hasQTag && !hasRepostETag) return
        handleEvent(event)
    }

    /**
     * Ingest a historical event (e.g. from DeepHistoryFetcher) and auto-mark it as seen.
     * The notification populates the list for browsing but does NOT increment the badge
     * or trigger a system notification. This prevents badge flicker during deep fetch.
     */
    fun ingestEventAsHistorical(event: Event) {
        if (event.kind !in ACCEPTED_KINDS) return
        val pubkey = myPubkeyHex ?: return
        val hasPTag = event.tags.any { it.size >= 2 && (it[0] == "p" || it[0] == "P") && it[1] == pubkey }
        val hasETag = event.kind == NOTIFICATION_KIND_TOPIC_REPLY &&
                event.tags.any { it.size >= 2 && (it[0] == "E" || it[0] == "e") && (it[1] in myTopicIds || it[1] in myNoteIds) }
        val hasQTag = event.kind == NOTIFICATION_KIND_TEXT &&
                event.tags.any { it.size >= 2 && it[0] == "q" && it[1] in myNoteIds }
        val hasRepostETag = event.kind in setOf(NOTIFICATION_KIND_REPOST, NOTIFICATION_KIND_GENERIC_REPOST) &&
                isRepostOfOurNote(event, pubkey)
        if (!hasPTag && !hasETag && !hasQTag && !hasRepostETag) return
        // Suppress push notification by pre-marking the event ID as seen
        addSeenId(event.id)
        // Also pre-compute the consolidated ID for aggregated types and mark THAT as seen.
        // Without this, the consolidated notification (like:X, zap:X, repost:X) appears
        // as "unread" in the badge even though the underlying event was marked seen.
        preMarkConsolidatedId(event)
        // Set historical flag so handlers skip push notifications entirely
        isHistoricalIngestion = true
        try {
            handleEvent(event)
        } finally {
            isHistoricalIngestion = false
        }
    }

    /** Pre-compute and mark-as-seen the consolidated notification ID for aggregated event types.
     *  For likes: "like:$eTag:$emoji", for reposts: "repost:$noteId", for zaps: "zap:$eTag". */
    private fun preMarkConsolidatedId(event: Event) {
        when (event.kind) {
            NOTIFICATION_KIND_REACTION -> {
                val eTag = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
                val rawContent = event.content.ifBlank { "+" }
                if (rawContent == "-") return
                val emoji = when {
                    rawContent == "+" -> "❤️"
                    rawContent.startsWith(":") && rawContent.endsWith(":") -> rawContent
                    else -> rawContent
                }
                addSeenId("like:$eTag:$emoji")
            }
            NOTIFICATION_KIND_REPOST, NOTIFICATION_KIND_GENERIC_REPOST -> {
                val noteId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
                addSeenId("repost:$noteId")
            }
            NOTIFICATION_KIND_ZAP -> {
                val eTag = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
                addSeenId("zap:$eTag")
            }
        }
    }

    private fun handleEvent(event: Event) {
        if (event.kind !in ACCEPTED_KINDS) return
        val pubkey = myPubkeyHex ?: return
        // ── STRICT OWNER GATE ──────────────────────────────────────────
        // This instance only processes events for ownerPubkeyHex.
        // Reject events from another account's subscription that leaked here.
        if (pubkey != ownerPubkeyHex) {
            MLog.w(
                TAG,
                "handleEvent REJECTED: myPubkeyHex ${pubkey.take(8)} != owner ${ownerPubkeyHex.take(8)} — stale state"
            )
            return
        }
        // ── Client-side relevance gate ──────────────────────────────────────
        // Relays may return events that don't match our subscription filters
        // (buggy filter implementations, extra events, etc.). Validate that
        // the event actually references us before creating a notification.
        // NIP-22 kind-1111 uses uppercase P for pubkeys; NIP-01 uses lowercase p.
        val hasPTag = event.tags.any { it.size >= 2 && (it[0] == "p" || it[0] == "P") && it[1] == pubkey }
        // NIP-22 kind-1111 uses uppercase E for root; also accept lowercase e for non-compliant clients.
        val hasETag = event.kind == NOTIFICATION_KIND_TOPIC_REPLY &&
                event.tags.any { it.size >= 2 && (it[0] == "E" || it[0] == "e") && (it[1] in myTopicIds || it[1] in myNoteIds) }
        val hasQTag = event.kind == NOTIFICATION_KIND_TEXT &&
                event.tags.any { it.size >= 2 && it[0] == "q" && it[1] in myNoteIds }
        val hasRepostETag = event.kind in setOf(NOTIFICATION_KIND_REPOST, NOTIFICATION_KIND_GENERIC_REPOST) &&
                isRepostOfOurNote(event, pubkey)
        // For kind-1 replies: also accept if user is directly cited in content (mention)
        val isCitedInContent = event.kind == NOTIFICATION_KIND_TEXT &&
                isUserCitedInContent(event.content, pubkey)
        if (!hasPTag && !hasETag && !hasQTag && !hasRepostETag && !isCitedInContent) {
            return
        }
        // Skip notifications from muted/blocked users
        if (MuteListRepository.isHidden(event.pubKey)) return
        val profileIsCached = profileCache.getAuthor(event.pubKey) != null
        val author = profileCache.resolveAuthor(event.pubKey)
        // Dispatch immediately with whatever author info we have.
        // If profile isn't cached, queue it for batched resolution —
        // the profileWatcher coroutine will update all affected notifications when it resolves.
        dispatchEvent(event, author)
        if (!profileIsCached && cacheRelayUrls.isNotEmpty()) {
            queueProfileFetch(event.pubKey)
        }
    }

    private fun dispatchEvent(event: Event, author: Author) {
        val ts = event.createdAt * 1000L
        // ── Auto-seen during initial replay ──────────────────────────────────
        // Before enableAndroidNotifications() is called, sessionStartEpochSec == MAX_VALUE.
        // ALL events arriving during this window are historical relay replays —
        // auto-mark them as seen so the badge doesn't show 100+ "unread" on startup.
        // Only events that arrive AFTER enableAndroidNotifications() sets the cutoff
        // will be treated as genuinely new (unseen) notifications.
        val isConsolidatedKind = event.kind in setOf(
            NOTIFICATION_KIND_REACTION, NOTIFICATION_KIND_REPOST,
            NOTIFICATION_KIND_GENERIC_REPOST, NOTIFICATION_KIND_ZAP
        )
        val isReplayPhase = sessionStartEpochSec == Long.MAX_VALUE
        val watermark = markAllSeenEpochMs
        // Auto-mark as seen if:
        // 1. We're still in the initial replay phase (before enableAndroidNotifications)
        // 2. The event timestamp is at or before the "Read All" watermark
        // 3. The event was created BEFORE the current session started — this is the key
        //    fix for Phase 2/3 events and cross-pollinated feed events that arrive
        //    after enableAndroidNotifications() but are still historical data.
        val isHistoricalEvent = sessionStartEpochSec != Long.MAX_VALUE && event.createdAt < sessionStartEpochSec
        val shouldAutoMark = isReplayPhase || (watermark > 0 && ts <= watermark) || isHistoricalEvent
        if (shouldAutoMark && !isConsolidatedKind) {
            addSeenId(event.id)
        }
        when (event.kind) {
            NOTIFICATION_KIND_REACTION -> handleLike(event, author, ts)
            NOTIFICATION_KIND_TEXT -> {
                // Check if this is a quote: q-tag OR nostr:nevent1/nostr:note1 in content referencing our notes
                val quotedIdFromTag =
                    event.tags.firstOrNull { it.size >= 2 && it[0] == "q" && it[1] in myNoteIds }?.get(1)
                val quotedId = quotedIdFromTag ?: findQuotedNoteIdInContent(event.content)
                if (quotedId != null) {
                    handleQuote(event, author, ts, quotedId)
                } else {
                    handleReply(event, author, ts)
                }
            }

            NOTIFICATION_KIND_TOPIC_REPLY -> handleTopicReply(event, author, ts)
            NOTIFICATION_KIND_REPOST, NOTIFICATION_KIND_GENERIC_REPOST -> handleRepost(event, author, ts)
            NOTIFICATION_KIND_ZAP -> handleZap(event, author, ts)
            NOTIFICATION_KIND_HIGHLIGHT -> handleHighlight(event, author, ts)
            NOTIFICATION_KIND_REPORT -> handleReport(event, author, ts)
            NOTIFICATION_KIND_BADGE_AWARD -> handleBadgeAward(event, author, ts)
            NOTIFICATION_KIND_POLL -> handlePollMention(event, author, ts)
            NOTIFICATION_KIND_POLL_RESPONSE -> handlePollVote(event, author, ts)
            else -> {}
        }
    }

    private fun handleLike(event: Event, author: Author, ts: Long) {
        // NIP-25: last e-tag is the reacted-to event (not first, which may be the root)
        val eTag = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
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
        val customUrls = if (customEmojiUrl != null && emoji.startsWith(":") && emoji.endsWith(":"))
            mapOf(emoji to customEmojiUrl) else emptyMap()
        // ── Consolidated: one notification per (liked note × emoji) ──
        // Each unique emoji gets its own row per target note, so ❤️ and 🔥
        // on the same note appear as separate notification lines.
        val consolidatedId = "like:$eTag:$emoji"
        val shouldAutoMark = (event.id in _seenIdsBacking) ||
                (sessionStartEpochSec == Long.MAX_VALUE) ||
                (markAllSeenEpochMs > 0 && ts <= markAllSeenEpochMs) ||
                (sessionStartEpochSec != Long.MAX_VALUE && event.createdAt < sessionStartEpochSec)
        synchronized(consolidationLock) {
            val existing = notificationsById[consolidatedId]
            if (existing != null) {
                val updatedActors = (existing.actorPubkeys + event.pubKey).distinct()
                val updatedCustomUrls = existing.customEmojiUrls + customUrls
                val updatedTs = maxOf(existing.sortTimestamp, ts)
                val action = if (emoji == "❤️") "liked your post" else "reacted to your post"
                val text = buildActorText(updatedActors, action)
                notificationsById[consolidatedId] = existing.copy(
                    actorPubkeys = updatedActors,
                    customEmojiUrls = updatedCustomUrls,
                    sortTimestamp = updatedTs,
                    text = text,
                    author = author
                )
            } else {
                val action = if (emoji == "❤️") "liked your post" else "reacted to your post"
                val text = "${author.displayName ?: "Someone"} $action"
                notificationsById[consolidatedId] = NotificationData(
                    id = consolidatedId,
                    type = NotificationType.LIKE,
                    text = text,
                    note = null,
                    author = author,
                    targetNoteId = eTag,
                    sortTimestamp = ts,
                    reactionEmoji = emoji,
                    reactionEmojis = listOf(emoji),
                    actorPubkeys = listOf(event.pubKey),
                    customEmojiUrl = customEmojiUrl,
                    customEmojiUrls = customUrls
                )
            }
        }
        if (shouldAutoMark) addSeenId(consolidatedId)
        emitSorted()
        fireAndroidNotification(
            NotificationType.LIKE,
            author.displayName ?: "Someone",
            notificationsById[consolidatedId]?.text ?: "liked your post",
            consolidatedId,
            eventEpochSec = ts / 1000,
            noteId = eTag
        )
        if (notificationsById[consolidatedId]?.targetNote == null) {
            scope.launch { fetchAndSetTargetNote(eTag, consolidatedId) { d -> { note -> d.copy(targetNote = note) } } }
        }
        updateTodaySummary(NotificationType.LIKE, ts, 0L)
        scheduleNotificationSave()
    }

    private fun handleReply(event: Event, author: Author, ts: Long) {
        // Cache event tags so Phase 3 reclassification can retroactively detect q-tags
        kind1EventTags[event.id] = event.tags.map { it.toList() }
        val rootId = getReplyRootNoteId(event)
        val replyToId = getReplyToNoteId(event)
        // Check if user is directly cited in content (nostr:npub1... / nostr:nprofile1...)
        val isDirectlyCitedInContent = myPubkeyHex != null && isUserCitedInContent(event.content, myPubkeyHex!!)
        // No root and not cited in content → not useful
        if (rootId == null && !isDirectlyCitedInContent) return
        val note = eventToNote(event)
        val replyId = event.id
        // Classify: if no root → pure mention (no reply threading context).
        // If root exists → always start as REPLY. The fetchAndSetTargetNote verification
        // will reclassify to MENTION if the parent note turns out not to be ours.
        // This avoids the previous bug where cited-in-content replies were immediately
        // classified as MENTION and skipped parent verification entirely.
        val isPureMention = rootId == null
        val text =
            if (isPureMention) "${author.displayName} mentioned you" else "${author.displayName} replied to your post"
        val notifType = if (isPureMention) NotificationType.MENTION else NotificationType.REPLY
        val parentId = if (rootId != null) (replyToId ?: rootId) else null
        val data = NotificationData(
            id = event.id,
            type = notifType,
            text = text,
            note = note,
            author = author,
            rootNoteId = rootId,
            replyNoteId = replyId,
            targetNoteId = parentId,
            replyKind = NOTIFICATION_KIND_TEXT,
            sortTimestamp = ts,
            rawContent = event.content
        )
        notificationsById[event.id] = data
        emitSorted()
        fireAndroidNotification(
            notifType,
            author.displayName ?: "Someone",
            text,
            event.id,
            eventEpochSec = ts / 1000,
            noteId = event.id,
            rootNoteId = rootId
        )
        if (parentId != null) {
            // Always fetch parent for verification — even if user is cited in content.
            // fetchAndSetTargetNote will reclassify REPLY→MENTION if parent isn't ours,
            // or remove the notification if parent isn't ours AND user isn't cited.
            scope.launch { fetchAndSetTargetNote(parentId, event.id) { d -> { n -> d.copy(targetNote = n) } } }
        }
        // Update today's summary
        updateTodaySummary(notifType, ts, 0L)
        scheduleNotificationSave()
    }

    /** Handle a kind-1 event that quotes one of our notes (q-tag). */
    private fun handleQuote(event: Event, author: Author, ts: Long, quotedNoteId: String) {
        val note = eventToNote(event)
        val text = "${author.displayName} quoted your note"
        val data = NotificationData(
            id = event.id,
            type = NotificationType.QUOTE,
            text = text,
            note = note,
            author = author,
            targetNoteId = quotedNoteId,
            sortTimestamp = ts
        )
        notificationsById[event.id] = data
        emitSorted()
        fireAndroidNotification(
            NotificationType.QUOTE,
            author.displayName ?: "Someone",
            text,
            event.id,
            eventEpochSec = ts / 1000,
            noteId = event.id,
            rootNoteId = quotedNoteId
        )
        // Fetch the quoted note for display
        scope.launch { fetchAndSetTargetNote(quotedNoteId, event.id) { d -> { n -> d.copy(targetNote = n) } } }
    }

    /**
     * Reclassify Phase 1 REPLY/MENTION notifications that are actually quotes.
     * During Phase 1, myNoteIds is empty so q-tag detection fails. After Phase 3
     * populates myNoteIds, this sweep checks cached kind-1 event tags and content
     * for quote references, converting matching notifications to QUOTE type.
     */
    private fun reclassifyQuotes() {
        if (myNoteIds.isEmpty()) return
        var reclassified = 0
        for ((eventId, tags) in kind1EventTags) {
            val existing = notificationsById[eventId] ?: continue
            // Only reclassify REPLY or MENTION type notifications
            if (existing.type != NotificationType.REPLY && existing.type != NotificationType.MENTION) continue
            // Check q-tag first
            val quotedId = tags.firstOrNull { it.size >= 2 && it[0] == "q" && it[1] in myNoteIds }?.get(1)
            // Also check content for nostr:nevent/note references
                ?: existing.rawContent?.let { findQuotedNoteIdInContent(it) }
            if (quotedId != null) {
                val authorName = existing.author?.displayName ?: "Someone"
                notificationsById[eventId] = existing.copy(
                    type = NotificationType.QUOTE,
                    text = "$authorName quoted your note",
                    targetNoteId = quotedId
                )
                // Fetch the quoted note for display if not already fetched
                if (existing.targetNote == null) {
                    scope.launch { fetchAndSetTargetNote(quotedId, eventId) { d -> { n -> d.copy(targetNote = n) } } }
                }
                reclassified++
            }
        }
        if (reclassified > 0) {
            MLog.d(TAG, "reclassifyQuotes: reclassified $reclassified replies → quotes")
            emitSorted()
        }
    }

    /**
     * Phase 3 reclassification: kind-1111 events that arrived during Phase 1 are classified as
     * COMMENT (replyKind=1111) because myTopicIds was empty. After Phase 3 populates myTopicIds,
     * sweep all COMMENT notifications whose rootNoteId is now in myTopicIds and reclassify them
     * as thread REPLY (replyKind=11) so they appear in the Threads tab.
     *
     * Also catches kind-1111 notifications with replyKind=1111 and type=REPLY that were created
     * by handleTopicReply with isOurTopic=false (e.g. events that arrived before myTopicIds was populated).
     */
    private fun reclassifyTopicReplies() {
        if (myTopicIds.isEmpty()) return
        var reclassified = 0
        for ((id, data) in notificationsById) {
            val rootId = data.rootNoteId ?: continue
            if (rootId !in myTopicIds) continue
            // Already correctly classified as thread reply
            if (data.type == NotificationType.REPLY && data.replyKind == 11) continue
            // Reclassify COMMENT → REPLY (thread reply), or REPLY with wrong replyKind
            if (data.type == NotificationType.COMMENT || 
                (data.type == NotificationType.REPLY && data.replyKind != 11 && data.replyKind != null && data.replyKind != 1)) {
                val authorName = data.author?.displayName ?: "Someone"
                notificationsById[id] = data.copy(
                    type = NotificationType.REPLY,
                    replyKind = 11,
                    text = "$authorName replied to your thread"
                )
                reclassified++
            }
        }
        if (reclassified > 0) {
            MLog.d(TAG, "reclassifyTopicReplies: reclassified $reclassified comments → thread replies")
            emitSorted()
        }
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
            } catch (_: Exception) {
            }
        }
        for (match in nprofileRegex.findAll(content)) {
            try {
                val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute("nostr:${match.groupValues[1]}")
                val hex = (parsed?.entity as? com.example.cybin.nip19.NProfile)?.hex
                if (hex != null && normalizeAuthorIdForCache(hex) == myPubkey) return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    /** Find a quoted note ID in content by parsing nostr:nevent1... and nostr:note1... URIs.
     *  Returns the first note ID that matches one of our known notes (myNoteIds), or null.
     *  This complements q-tag detection — many clients embed quotes as nostr: URIs in content. */
    private fun findQuotedNoteIdInContent(content: String): String? {
        if (content.isBlank() || myNoteIds.isEmpty()) return null
        val neventRegex = Regex("nostr:(nevent1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)", RegexOption.IGNORE_CASE)
        val nnoteRegex = Regex("nostr:(note1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)", RegexOption.IGNORE_CASE)
        for (match in neventRegex.findAll(content)) {
            try {
                val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute("nostr:${match.groupValues[1]}")
                val hex = (parsed?.entity as? com.example.cybin.nip19.NEvent)?.hex
                if (hex != null && hex in myNoteIds) return hex
            } catch (_: Exception) {
            }
        }
        for (match in nnoteRegex.findAll(content)) {
            try {
                val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute("nostr:${match.groupValues[1]}")
                val hex = (parsed?.entity as? com.example.cybin.nip19.NNote)?.hex
                if (hex != null && hex in myNoteIds) return hex
            } catch (_: Exception) {
            }
        }
        return null
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
        val rootId = getTopicReplyRootNoteId(event) ?: return
        // Accept if user is p-tagged; otherwise still create the notification and verify
        // via fetchAndSetTargetNote (which removes it if root author isn't us)
        val note = eventToNote(event)
        // If root note is one of our known topics, classify as thread REPLY (replyKind=11)
        // so it appears in the Threads tab. Otherwise classify as COMMENT (kind-1111 on a kind-1 note).
        val isOurTopic = rootId in myTopicIds
        val notifType = if (isOurTopic) NotificationType.REPLY else NotificationType.COMMENT
        val kind = if (isOurTopic) 11 else NOTIFICATION_KIND_TOPIC_REPLY
        val text =
            if (isOurTopic) "${author.displayName} replied to your thread" else "${author.displayName} commented on your post"
        val data = NotificationData(
            id = event.id,
            type = notifType,
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
        fireAndroidNotification(
            notifType,
            author.displayName ?: "Someone",
            text,
            event.id,
            eventEpochSec = ts / 1000,
            noteId = event.id,
            rootNoteId = rootId
        )
        scope.launch { fetchAndSetTargetNote(rootId, event.id) { d -> { n -> d.copy(targetNote = n) } } }
        updateTodaySummary(notifType, ts, 0L)
        scheduleNotificationSave()
    }

    private fun handleHighlight(event: Event, author: Author, ts: Long) {
        val highlightedContent = event.content.take(200)
        val text = "${author.displayName} highlighted your content"
        val data = NotificationData(
            id = event.id,
            type = NotificationType.HIGHLIGHT,
            text = text,
            note = eventToNote(event),
            author = author,
            sortTimestamp = ts
        )
        notificationsById[event.id] = data
        emitSorted()
        fireAndroidNotification(
            NotificationType.HIGHLIGHT,
            author.displayName ?: "Someone",
            text,
            event.id,
            eventEpochSec = ts / 1000,
            noteId = event.id
        )
        scheduleNotificationSave()
    }

    private fun handleReport(event: Event, author: Author, ts: Long) {
        // NIP-56: kind-1984 reports. Only show if we are the DIRECT target:
        // - The report's p-tag with a report-type marker names us as the reported user, OR
        // - The report's e-tag references one of our known note IDs.
        // Without this check, relay-side filter leaks (e.g. our pubkey in a
        // secondary p-tag for threading context) cause spurious "X reported you" noise.
        val pubkey = myPubkeyHex ?: return
        val reportedPubkey = event.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.getOrNull(1)
        val reportedNoteId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.getOrNull(1)
        val targetsUs = reportedPubkey == pubkey
        val targetsOurNote = reportedNoteId != null && reportedNoteId in myNoteIds
        if (!targetsUs && !targetsOurNote) {
            MLog.d(
                TAG,
                "Dropping irrelevant report ${event.id.take(8)}: p=${reportedPubkey?.take(8)}, e=${
                    reportedNoteId?.take(8)
                }, us=${pubkey.take(8)}"
            )
            return
        }
        val reportType = event.tags.firstOrNull { it.size >= 3 && it[0] == "report" }?.get(2)
            ?: event.tags.firstOrNull { it.size >= 3 && it[0] == "p" && it[1] == pubkey }?.getOrNull(2)
            ?: "unknown"
        val text = if (targetsOurNote) "${author.displayName} reported your post ($reportType)"
        else "${author.displayName} reported you ($reportType)"
        val data = NotificationData(
            id = event.id,
            type = NotificationType.REPORT,
            text = text,
            note = eventToNote(event),
            author = author,
            sortTimestamp = ts
        )
        notificationsById[event.id] = data
        emitSorted()
        fireAndroidNotification(
            NotificationType.REPORT,
            author.displayName ?: "Someone",
            text,
            event.id,
            eventEpochSec = ts / 1000,
            noteId = event.id
        )
        scheduleNotificationSave()
    }

    private fun handleBadgeAward(event: Event, author: Author, ts: Long) {
        // Extract badge definition address from a-tag: ["a", "30009:<pubkey>:<d-tag>", ...]
        val aTagValue = event.tags.firstOrNull { it.size >= 2 && it[0] == "a" && it[1].startsWith("30009:") }?.get(1)
        val text = "${author.displayName ?: "Someone"} awarded you a badge"
        val data = NotificationData(
            id = event.id,
            type = NotificationType.BADGE_AWARD,
            text = text,
            author = author,
            sortTimestamp = ts
        )
        notificationsById[event.id] = data
        emitSorted()
        fireAndroidNotification(
            NotificationType.BADGE_AWARD,
            author.displayName ?: "Someone",
            text,
            event.id,
            eventEpochSec = ts / 1000,
            noteId = event.id
        )
        // Asynchronously resolve badge definition for name + image
        if (aTagValue != null && subscriptionRelayUrls.isNotEmpty()) {
            scope.launch { resolveBadgeDefinition(event.id, aTagValue) }
        }
        scheduleNotificationSave()
    }

    /** Fetch kind 30009 badge definition from a-tag and enrich the notification with badge name/image. */
    private suspend fun resolveBadgeDefinition(notificationId: String, aTagValue: String) {
        try {
            val parts = aTagValue.split(":", limit = 3)
            if (parts.size < 3) return
            val defAuthor = parts[1]
            val defDTag = parts[2]
            if (defAuthor.isBlank() || defDTag.isBlank()) return

            val defFilter = Filter(
                kinds = listOf(30009),
                authors = listOf(defAuthor),
                tags = mapOf("d" to listOf(defDTag)),
                limit = 5
            )
            var bestEvent: Event? = null
            RelayConnectionStateMachine.getInstance()
                .awaitOneShotSubscription(
                    subscriptionRelayUrls, defFilter, priority = SubscriptionPriority.NORMAL,
                    settleMs = 300L, maxWaitMs = 3_000L
                ) { ev ->
                    if (ev.kind == 30009 && (bestEvent == null || ev.createdAt > (bestEvent?.createdAt ?: 0))) {
                        bestEvent = ev
                    }
                }

            val defEvent = bestEvent ?: return
            val name = defEvent.tags.firstOrNull { it.size >= 2 && it[0] == "name" }?.get(1)
            val thumb = defEvent.tags.firstOrNull { it.size >= 2 && it[0] == "thumb" }?.get(1)
            val image = defEvent.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1)
            val imageUrl = thumb?.takeIf { it.isNotBlank() } ?: image?.takeIf { it.isNotBlank() }

            val current = notificationsById[notificationId] ?: return
            val enrichedText = if (name != null) {
                "${current.author?.displayName ?: "Someone"} awarded you the \"$name\" badge"
            } else current.text
            notificationsById[notificationId] = current.copy(
                text = enrichedText,
                badgeName = name,
                badgeImageUrl = imageUrl
            )
            emitSorted()
            MLog.d(TAG, "Enriched badge notification $notificationId: name=$name image=${imageUrl?.take(40)}")
        } catch (e: Exception) {
            MLog.e(TAG, "resolveBadgeDefinition failed: ${e.message}", e)
        }
    }

    /** Handle a kind-1068 poll event that mentions this user (p-tag). */
    private fun handlePollMention(event: Event, author: Author, ts: Long) {
        val question = event.content.takeIf { it.isNotBlank() }?.take(120) ?: "a poll"
        val text = "${author.displayName ?: "Someone"} mentioned you in $question"
        val note = eventToNote(event)
        val data = NotificationData(
            id = event.id,
            type = NotificationType.MENTION,
            text = text,
            note = note,
            author = author,
            sortTimestamp = ts,
            rawContent = event.content
        )
        notificationsById[event.id] = data
        emitSorted()
        fireAndroidNotification(
            NotificationType.MENTION,
            author.displayName ?: "Someone",
            text,
            event.id,
            eventEpochSec = ts / 1000,
            noteId = event.id
        )
        updateTodaySummary(NotificationType.MENTION, ts, 0L)
        scheduleNotificationSave()
    }

    private fun handlePollVote(event: Event, author: Author, ts: Long) {
        // Parse e-tag to find which poll was voted on
        val pollId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
        // Parse response tags to find which options were chosen
        val responseCodes = event.tags
            .filter { it.size >= 2 && it[0] == "response" }
            .map { it[1] }
        if (responseCodes.isEmpty()) return

        val text = "${author.displayName ?: "Someone"} voted on your poll"
        val data = NotificationData(
            id = event.id,
            type = NotificationType.POLL_VOTE,
            text = text,
            author = author,
            targetNoteId = pollId,
            pollId = pollId,
            pollOptionCodes = responseCodes,
            sortTimestamp = ts
        )
        notificationsById[event.id] = data
        emitSorted()
        fireAndroidNotification(
            NotificationType.POLL_VOTE,
            author.displayName ?: "Someone",
            text,
            event.id,
            eventEpochSec = ts / 1000,
            noteId = pollId
        )
        // Fetch the poll note so tapping the notification can navigate to it
        scope.launch { fetchAndSetTargetNote(pollId, event.id) { d -> { note -> d.copy(targetNote = note) } } }
        // Asynchronously fetch the poll event to enrich with question + option labels
        if (subscriptionRelayUrls.isNotEmpty()) {
            scope.launch { enrichPollVoteNotification(event.id, pollId, responseCodes) }
        }
        scheduleNotificationSave()
    }

    /** Fetch the kind-1068 poll event and enrich the POLL_VOTE notification with question and option labels. */
    private suspend fun enrichPollVoteNotification(
        notificationId: String,
        pollId: String,
        responseCodes: List<String>
    ) {
        try {
            val filter = Filter(
                ids = listOf(pollId),
                limit = 1
            )
            var pollEvent: Event? = null
            RelayConnectionStateMachine.getInstance()
                .awaitOneShotSubscription(
                    subscriptionRelayUrls, filter, priority = SubscriptionPriority.NORMAL,
                    settleMs = 300L, maxWaitMs = 3_000L
                ) { ev ->
                    if (ev.id == pollId) pollEvent = ev
                }

            val ev = pollEvent ?: return
            val rawQuestion = ev.content.takeIf { it.isNotBlank() }
            val question = rawQuestion?.let { resolveNpubMentions(it) }
            val allOptions = ev.tags
                .filter { it.size >= 3 && it[0] == "option" }
                .map { it[2] }
            val optionLabels = ev.tags
                .filter { it.size >= 3 && it[0] == "option" && it[1] in responseCodes }
                .map { it[2] }
            val isMulti = ev.tags.any { it.size >= 2 && it[0] == "polltype" && it[1] == "multiplechoice" }
            val pollNote = eventToNote(ev)

            val current = notificationsById[notificationId] ?: return
            val enrichedText = buildString {
                append(current.author?.displayName ?: "Someone")
                append(" voted")
                if (optionLabels.isNotEmpty()) {
                    append(" \"${optionLabels.joinToString("\", \"")}\"")
                }
                if (question != null) {
                    append(" on: ${question.take(80)}")
                    if (question.length > 80) append("…")
                }
            }
            notificationsById[notificationId] = current.copy(
                text = enrichedText,
                pollQuestion = question,
                pollOptionLabels = optionLabels,
                pollAllOptions = allOptions,
                pollIsMultipleChoice = isMulti,
                note = pollNote
            )
            emitSorted()
            MLog.d(TAG, "Enriched poll vote notification $notificationId: q=${question?.take(30)} opts=$optionLabels")
        } catch (e: Exception) {
            MLog.e(TAG, "enrichPollVoteNotification failed: ${e.message}", e)
        }
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
            NotificationType.REPLY, NotificationType.COMMENT, NotificationType.MENTION, NotificationType.QUOTE -> _todayReplies.value++
            NotificationType.REPOST -> _todayBoosts.value++
            NotificationType.LIKE, NotificationType.BADGE_AWARD -> _todayReactions.value++
            NotificationType.ZAP -> _todayZapSats.value += zapSats
            else -> {}
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


    private fun handleRepost(event: Event, author: Author, ts: Long) {
        // NIP-18: last e-tag is the reposted event
        val repostedNoteId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
            ?: parseRepostedNoteIdFromContent(event.content)
        if (repostedNoteId == null) return
        val targetNote = parseRepostedNoteFromContent(event.content)
        // ── Consolidated: one notification per reposted note ──
        val consolidatedId = "repost:$repostedNoteId"
        val shouldAutoMark = (event.id in _seenIdsBacking) ||
                (sessionStartEpochSec == Long.MAX_VALUE) ||
                (markAllSeenEpochMs > 0 && ts <= markAllSeenEpochMs) ||
                (sessionStartEpochSec != Long.MAX_VALUE && event.createdAt < sessionStartEpochSec)
        synchronized(consolidationLock) {
            val existing = notificationsById[consolidatedId]
            if (existing != null) {
                val updatedActors = (existing.actorPubkeys + event.pubKey).distinct()
                val updatedTs = maxOf(existing.sortTimestamp, ts)
                val text = buildActorText(updatedActors, "reposted your post")
                notificationsById[consolidatedId] = existing.copy(
                    actorPubkeys = updatedActors,
                    sortTimestamp = updatedTs,
                    text = text,
                    author = author,
                    targetNote = existing.targetNote ?: targetNote
                )
            } else {
                val text = "${author.displayName ?: "Someone"} reposted your post"
                notificationsById[consolidatedId] = NotificationData(
                    id = consolidatedId,
                    type = NotificationType.REPOST,
                    text = text,
                    note = null,
                    author = author,
                    targetNote = targetNote,
                    targetNoteId = repostedNoteId,
                    actorPubkeys = listOf(event.pubKey),
                    sortTimestamp = ts
                )
            }
        }
        if (shouldAutoMark) addSeenId(consolidatedId)
        emitSorted()
        fireAndroidNotification(
            NotificationType.REPOST,
            author.displayName ?: "Someone",
            notificationsById[consolidatedId]?.text ?: "reposted your post",
            consolidatedId,
            eventEpochSec = ts / 1000,
            noteId = repostedNoteId
        )
        // Always attempt to resolve the full target note, even if parseRepostedNoteFromContent
        // extracted a stub. The regex-based parser often produces Notes with empty/garbled content
        // because it can't handle complex JSON escaping in the embedded event. fetchAndSetTargetNote
        // will replace the stub with a properly deserialized Note from cache/Room/relay.
        val storedNote = notificationsById[consolidatedId]?.targetNote
        if (storedNote == null || storedNote.content.isNullOrBlank()) {
            scope.launch { fetchAndSetTargetNote(repostedNoteId, consolidatedId) { d -> { n -> d.copy(targetNote = n) } } }
        }
        updateTodaySummary(NotificationType.REPOST, ts, 0L)
        scheduleNotificationSave()
    }

    private fun parseRepostedNoteFromContent(content: String): Note? {
        if (content.isBlank()) return null
        return try {
            // NIP-18: kind-6 content is the full JSON of the reposted event.
            // Use the proper Event deserializer instead of fragile regexes.
            val event = Event.fromJson(content)
            eventToNote(event)
        } catch (_: Exception) {
            // Fallback: minimal regex extraction for malformed JSON
            val id = Regex(""""id"\s*:\s*"([a-f0-9]{64})"""").find(content)?.groupValues?.get(1) ?: return null
            val pubkey = Regex(""""pubkey"\s*:\s*"([a-f0-9]{64})"""").find(content)?.groupValues?.get(1) ?: return null
            val author = profileCache.resolveAuthor(pubkey)
            Note(
                id = id,
                author = author,
                content = "", // Don't attempt regex content parsing — fetchAndSetTargetNote will resolve properly
                timestamp = 0L,
                likes = 0, shares = 0, comments = 0,
                isLiked = false, hashtags = emptyList(), mediaUrls = emptyList()
            )
        }
    }

    private fun parseRepostedNoteIdFromContent(content: String): String? {
        return Regex(""""id"\s*:\s*"([a-f0-9]{64})"""").find(content)?.groupValues?.get(1)
    }

    private fun handleZap(event: Event, author: Author, ts: Long) {
        // Last e-tag is the zapped event
        val eTag = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        if (eTag == null) return
        val amountSats = parseZapAmountSats(event)
        // Kind-9735 pubkey is the wallet/LNURL service (e.g. Coinos), NOT the actual zapper.
        // The real zapper's pubkey is inside the "description" tag which contains the kind-9734 zap request JSON.
        val realZapperPubkey = parseZapSenderPubkey(event)
        val needsProfileFetch = realZapperPubkey != null && profileCache.getAuthor(realZapperPubkey) == null
        val zapperAuthor = if (realZapperPubkey != null) {
            profileCache.resolveAuthor(realZapperPubkey)
        } else author
        val zapperPk = realZapperPubkey ?: event.pubKey
        // Detect DM zaps: the embedded zap request (kind-9734) may reference a kind-1059 gift-wrap.
        // Also check if the e-tag corresponds to a known gift-wrap in DirectMessageRepository.
        val isDmZap = parseZapTargetKind(event) == 1059 ||
            DirectMessageRepository.isKnownGiftWrapId(eTag)
        // ── Consolidated: one notification per zapped note ──
        val consolidatedId = "zap:$eTag"
        val shouldAutoMark = (event.id in _seenIdsBacking) ||
                (sessionStartEpochSec == Long.MAX_VALUE) ||
                (markAllSeenEpochMs > 0 && ts <= markAllSeenEpochMs) ||
                (sessionStartEpochSec != Long.MAX_VALUE && event.createdAt < sessionStartEpochSec)
        synchronized(consolidationLock) {
            val existing = notificationsById[consolidatedId]
            if (existing != null) {
                val updatedActors = (existing.actorPubkeys + zapperPk).distinct()
                val updatedSats = existing.zapAmountSats + amountSats
                val updatedTs = maxOf(existing.sortTimestamp, ts)
                val satsLabel = if (updatedSats > 0) formatSats(updatedSats) else ""
                val target = if (isDmZap) "your message" else "your post"
                val action = if (satsLabel.isNotEmpty()) "zapped $satsLabel on $target" else "zapped $target"
                val text = buildActorText(updatedActors, action)
                notificationsById[consolidatedId] = existing.copy(
                    actorPubkeys = updatedActors,
                    zapAmountSats = updatedSats,
                    sortTimestamp = updatedTs,
                    text = text,
                    author = zapperAuthor
                )
            } else {
                val satsLabel = if (amountSats > 0) formatSats(amountSats) else ""
                val target = if (isDmZap) "your message" else "your post"
                val text = "${zapperAuthor.displayName ?: "Someone"} ${
                    if (satsLabel.isNotEmpty()) "zapped $satsLabel on $target" else "zapped $target"
                }"
                notificationsById[consolidatedId] = NotificationData(
                    id = consolidatedId,
                    type = NotificationType.ZAP,
                    text = text,
                    note = null,
                    author = zapperAuthor,
                    targetNoteId = if (isDmZap) null else eTag,
                    sortTimestamp = ts,
                    zapAmountSats = amountSats,
                    actorPubkeys = listOf(zapperPk)
                )
            }
        }
        if (shouldAutoMark) addSeenId(consolidatedId)
        emitSorted()
        fireAndroidNotification(
            NotificationType.ZAP,
            zapperAuthor.displayName ?: "Someone",
            notificationsById[consolidatedId]?.text ?: "zapped your post",
            consolidatedId,
            eventEpochSec = ts / 1000,
            noteId = eTag
        )
        // Only fetch target note for non-DM zaps — DM gift wraps are encrypted and not in the feed
        if (!isDmZap && notificationsById[consolidatedId]?.targetNote == null) {
            scope.launch { fetchAndSetTargetNote(eTag, consolidatedId) { d -> { note -> d.copy(targetNote = note) } } }
        }
        updateTodaySummary(NotificationType.ZAP, ts, amountSats)
        scheduleNotificationSave()
        // If the zapper's profile isn't cached yet, queue it for batched resolution.
        // The profileWatcher coroutine will update all affected notifications when it resolves.
        if (needsProfileFetch && realZapperPubkey != null && cacheRelayUrls.isNotEmpty()) {
            queueProfileFetch(realZapperPubkey)
        }
    }

    /** Parse the real zapper's pubkey from the kind-9734 zap request embedded in the "description" tag. */
    private fun parseZapSenderPubkey(event: Event): String? {
        val descTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
            ?: return null
        return try {
            val pubkeyMatch = Regex(""""pubkey"\s*:\s*"([a-f0-9]{64})"""").find(descTag)
            pubkeyMatch?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    /** Parse the target event kind from the embedded zap request (kind-9734) "description" tag.
     *  Returns the kind number (e.g. 1059 for DM gift wraps), or null if not found. */
    private fun parseZapTargetKind(event: Event): Int? {
        val descTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
            ?: return null
        return try {
            val kindMatch = Regex(""""kind"\s*:\s*(\d+)""").find(descTag)
            kindMatch?.groupValues?.get(1)?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** Parse zap amount from bolt11 tag or description tag's bolt11 field. */
    private fun parseZapAmountSats(event: Event): Long {
        // Try bolt11 tag directly
        val bolt11 = event.tags.firstOrNull { it.size >= 2 && it[0] == "bolt11" }?.get(1)
        if (bolt11 != null) {
            val sats = decodeBolt11Amount(bolt11)
            MLog.d(TAG, "parseZapAmount: bolt11 prefix=${bolt11.take(30)} decoded=$sats sats")
            if (sats > 0) return sats
        } else {
            MLog.d(TAG, "parseZapAmount: no bolt11 tag found, tags=${event.tags.map { it.firstOrNull() }}")
        }
        // Try description tag (zap request JSON) which may contain amount
        val descTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
        if (descTag != null) {
            try {
                val amountMatch = Regex(""""amount"\s*:\s*"?(\d+)"?""").find(descTag)
                val milliSats = amountMatch?.groupValues?.get(1)?.toLongOrNull()
                MLog.d(TAG, "parseZapAmount: description amount match=$milliSats (milliSats)")
                if (milliSats != null && milliSats > 0) return milliSats / 1000
            } catch (_: Exception) {
            }
        }
        MLog.d(TAG, "parseZapAmount: returning 0 for event ${event.id.take(8)}")
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
            sats >= 1_000_000 -> {
                val dec = (sats % 1_000_000) / 100_000
                if (dec > 0) "${sats / 1_000_000}.${dec}M sats" else "${sats / 1_000_000}M sats"
            }
            sats >= 1_000 -> {
                val dec = (sats % 1_000) / 100
                if (dec > 0) "${sats / 1_000}.${dec}K sats" else "${sats / 1_000}K sats"
            }
            else -> "$sats sats"
        }
    }

    /**
     * Fetch the user's kind-11 topic event IDs from relays so we can subscribe for kind-1111 replies.
     */
    private suspend fun fetchUserTopicIds(pubkey: String, relayUrls: List<String>, since: Long?): List<String> {
        val topicIds = mutableListOf<String>()
        val stateMachine = RelayConnectionStateMachine.getInstance()
        val filter = Filter(
            kinds = listOf(11),
            authors = listOf(pubkey),
            since = since,
            limit = 500
        )
        stateMachine.awaitOneShotSubscription(
            relayUrls, filter,
            priority = SubscriptionPriority.NORMAL, settleMs = 300L, maxWaitMs = 4_000L,
        ) { ev ->
            if (ev.kind == 11) {
                synchronized(topicIds) { topicIds.add(ev.id) }
            }
        }
        MLog.d(TAG, "fetchUserTopicIds: found ${topicIds.size} topics for ${pubkey.take(8)}...")
        return topicIds.distinct()
    }

    /**
     * Bounded relay REQs may omit some of the user's newest kind-1 ids. Union in kind-1 note ids
     * already present in the main feed cache (populated before Phase 3) so quote / thread-reply
     * filters still match recent posts.
     */
    private fun mergeNoteIdsWithFeedCache(pubkey: String, relayNoteIds: List<String>): List<String> {
        val self = normalizeAuthorIdForCache(pubkey)
        val fromFeed = NotesRepository.getInstance().allNotes.value.asSequence()
            .filter { it.kind == 1 && normalizeAuthorIdForCache(it.author.id) == self }
            .map { it.id }
            .toSet()
        if (fromFeed.isEmpty()) return relayNoteIds.distinct()
        return (relayNoteIds.asSequence() + fromFeed.asSequence()).distinct().toList()
    }

    /** Fetch user's kind-1 note IDs for quote detection (q-tag subscriptions). */
    private suspend fun fetchUserNoteIds(pubkey: String, relayUrls: List<String>, since: Long?): List<String> {
        val noteIds = mutableListOf<String>()
        val stateMachine = RelayConnectionStateMachine.getInstance()
        // Use indexer relays for broader coverage of the user's notes
        val indexerRelays = NotesRepository.getInstance().INDEXER_RELAYS
        val allRelays = (relayUrls + indexerRelays).distinct()
        val filter = Filter(
            kinds = listOf(1),
            authors = listOf(pubkey),
            since = since,
            limit = 5000
        )
        stateMachine.awaitOneShotSubscription(
            allRelays, filter,
            priority = SubscriptionPriority.NORMAL, settleMs = 300L, maxWaitMs = 5_000L,
        ) { ev ->
            if (ev.kind == 1) {
                synchronized(noteIds) { noteIds.add(ev.id) }
            }
        }
        MLog.d(TAG, "fetchUserNoteIds: found ${noteIds.size} notes for ${pubkey.take(8)}...")
        return noteIds.distinct()
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

    /**
     * Mark notifications as dirty and let the polling loop handle emission.
     * Replaces the old cancel+relaunch debounce which created thousands of
     * Job allocations/sec during Phase 1 replay.
     *
     * The polling loop ([startEmitPoller]) checks the dirty flag every 100ms
     * and emits once. Zero allocations per call — just a volatile write.
     *
     * Call [emitSortedImmediate] when the UI must update instantly (e.g. after
     * markAllAsSeen, notification removal, or target note enrichment).
     */
    private fun emitSorted() {
        emitDirty = true
    }

    /** Single long-lived coroutine that polls the dirty flag and emits when set.
     *  Replaces thousands of cancel+relaunch Job allocations with zero-alloc dirty checks. */
    private var emitPollerJob: Job? = null
    private fun startEmitPoller() {
        emitPollerJob?.cancel()
        emitPollerJob = scope.launch {
            while (true) {
                delay(EMIT_DEBOUNCE_MS)
                if (emitDirty) {
                    emitSortedImmediate()
                }
            }
        }
    }

    /** Immediate (non-debounced) emit. Used after critical state changes.
     *
     *  No in-memory cap: with the polling approach (sort runs at most every 100ms),
     *  even 100K+ notifications are feasible — sortedByDescending takes ~10-20ms on
     *  modern hardware. Room persistence handles long-term storage; the in-memory map
     *  is naturally bounded by session lifetime. */
    private fun emitSortedImmediate() {
        emitDirty = false
        val sorted = notificationsById.values
            .filter { it.verificationStatus != social.mycelium.android.data.VerificationStatus.UNVERIFIED }
            .sortedByDescending { it.sortTimestamp }
            .toList()
        _notifications.value = sorted
        // Auto-mark historical notifications:
        // 1. Events at or before the "Read All" watermark (user pressed "Read All")
        // 2. Events created before the current session started (safety net for
        //    Phase 2/3, cross-pollinated, and Room-restored notifications that
        //    weren't caught by the per-event auto-mark in dispatchEvent).
        val watermarkCutoff = markAllSeenEpochMs
        val sessionCutoffMs = if (sessionStartEpochSec != Long.MAX_VALUE) sessionStartEpochSec * 1000L else 0L
        if (watermarkCutoff > 0 || sessionCutoffMs > 0) {
            val autoSeen = sorted.filter { notif ->
                notif.id !in _seenIdsBacking && (
                    (watermarkCutoff > 0 && notif.sortTimestamp <= watermarkCutoff) ||
                    (sessionCutoffMs > 0 && notif.sortTimestamp < sessionCutoffMs)
                )
            }.map { it.id }
            if (autoSeen.isNotEmpty()) {
                addSeenIds(autoSeen)
            }
        }
    }



    // ── Batched profile resolution (Item 2) ──────────────────────────────────
    // Instead of launching individual coroutines per event that each wait 3s,
    // buffer uncached pubkeys and request them in one batch, then update all
    // affected notifications via a single profileUpdated collector.

    private val pendingProfilePubkeys = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile
    private var profileBatchDirty = false
    private var profileBatchPollerJob: Job? = null
    private var profileWatcherJob: Job? = null
    private val PROFILE_BATCH_DELAY_MS = 200L

    /** Queue a pubkey for batched profile resolution. */
    private fun queueProfileFetch(pubkey: String) {
        pendingProfilePubkeys.add(normalizeAuthorIdForCache(pubkey))
        profileBatchDirty = true
    }

    /** Single long-lived coroutine polling the profile batch dirty flag.
     *  Replaces thousands of cancel+relaunch Job allocations during ingestion. */
    private fun startProfileBatchPoller() {
        profileBatchPollerJob?.cancel()
        profileBatchPollerJob = scope.launch {
            while (true) {
                delay(PROFILE_BATCH_DELAY_MS)
                if (profileBatchDirty) {
                    profileBatchDirty = false
                    val batch = synchronized(pendingProfilePubkeys) {
                        pendingProfilePubkeys.toList().also { pendingProfilePubkeys.clear() }
                    }
                    if (batch.isNotEmpty() && cacheRelayUrls.isNotEmpty()) {
                        profileCache.requestProfiles(batch, cacheRelayUrls)
                    }
                }
            }
        }
    }

    /** Single watcher that updates all affected notifications when a profile resolves.
     *  Replaces N individual coroutines (one per event) that each waited up to 3s. */
    private fun startProfileWatcher() {
        profileWatcherJob?.cancel()
        profileWatcherJob = scope.launch {
            profileCache.profileUpdated.collect { pubkey ->
                val normalized = normalizeAuthorIdForCache(pubkey)
                var changed = false
                for ((id, data) in notificationsById) {
                    val authorNorm = data.author?.id?.let { normalizeAuthorIdForCache(it) }
                    if (authorNorm == normalized) {
                        val resolved = profileCache.getAuthor(normalized) ?: continue
                        if (data.author?.displayName != resolved.displayName) {
                            val oldName = data.author?.displayName ?: "Someone"
                            val newName = resolved.displayName ?: "Someone"
                            val newText = data.text.replaceFirst(oldName, newName)
                            notificationsById[id] = data.copy(author = resolved, text = newText)
                            changed = true
                        }
                    }
                }
                if (changed) emitSorted()
            }
        }
    }

    private fun stopProfileWatcher() {
        profileWatcherJob?.cancel()
        profileWatcherJob = null
    }

    // ── Room persistence (Item 3) ────────────────────────────────────────────
    // Persist notifications to Room so cold-start has full notification history
    // without re-fetching from relays.

    private var notifSaveJob: Job? = null
    private val NOTIF_SAVE_DEBOUNCE_MS = 3_000L
    /** Longer debounce during startup replay — hundreds of events arrive in bursts,
     *  no point writing to Room every 3s when the next event will arrive momentarily. */
    private val NOTIF_SAVE_REPLAY_DEBOUNCE_MS = 10_000L
    private val saveLock = Any()

    /** Debounced save of notifications to Room. Called after any notification state change.
     *  Thread-safe: synchronizes the cancel→launch sequence to prevent concurrent callers
     *  from each launching their own save job. */
    private fun scheduleNotificationSave() {
        val ctx = appContext ?: return
        val debounce = if (_replaySettled.value) NOTIF_SAVE_DEBOUNCE_MS else NOTIF_SAVE_REPLAY_DEBOUNCE_MS
        synchronized(saveLock) {
            notifSaveJob?.cancel()
            notifSaveJob = scope.launch(Dispatchers.IO) {
                delay(debounce)
                saveNotificationsToRoom(ctx)
            }
        }
    }

    private suspend fun saveNotificationsToRoom(context: Context) {
        try {
            val pubkey = myPubkeyHex ?: return
            val dao = social.mycelium.android.db.AppDatabase.getInstance(context).notificationDao()
            val notifications = notificationsById.values.toList()
            val entities = notifications.map { it.toEntity(pubkey) }
            dao.upsertAll(entities)
            // Removed trimToNewest to allow unlimited depth of historical events
            MLog.d(TAG, "Saved ${entities.size} notifications to Room")
        } catch (e: Exception) {
            MLog.e(TAG, "saveNotificationsToRoom failed: ${e.message}", e)
        }
    }

    /** Restore notifications from Room on cold start. Called in startSubscription before Phase 1. */
    private suspend fun loadNotificationsFromRoom(pubkey: String) {
        val ctx = appContext ?: return
        try {
            val dao = social.mycelium.android.db.AppDatabase.getInstance(ctx).notificationDao()
            val entities = dao.getForOwner(pubkey)
            if (entities.isEmpty()) {
                MLog.d(TAG, "No cached notifications in Room for ${pubkey.take(8)}")
                return
            }
            var restored = 0
            for (entity in entities) {
                if (!notificationsById.containsKey(entity.id)) {
                    notificationsById[entity.id] = entity.toNotificationData()
                    restored++
                }
            }
            if (restored > 0) {
                emitSortedImmediate()
                MLog.d(TAG, "Restored $restored notifications from Room (${entities.size} total)")
            }
        } catch (e: Exception) {
            MLog.e(TAG, "loadNotificationsFromRoom failed: ${e.message}", e)
        }
    }

    /** Convert NotificationData to Room entity. */
    private fun NotificationData.toEntity(ownerPubkey: String): social.mycelium.android.db.CachedNotificationEntity {
        return social.mycelium.android.db.CachedNotificationEntity(
            id = id,
            ownerPubkey = ownerPubkey,
            type = type.name,
            text = text,
            sortTimestamp = sortTimestamp,
            authorId = author?.id,
            authorDisplayName = author?.displayName,
            authorUsername = author?.username,
            authorAvatarUrl = author?.avatarUrl,
            targetNoteId = targetNoteId ?: targetNote?.id,
            targetNoteAuthorId = targetNote?.author?.id,
            rootNoteId = rootNoteId,
            replyNoteId = replyNoteId,
            replyKind = replyKind,
            reactionEmoji = reactionEmoji,
            reactionEmojisJson = if (reactionEmojis.isNotEmpty()) json.encodeToString(ListSerializer(String.serializer()), reactionEmojis) else null,
            zapAmountSats = zapAmountSats,
            actorPubkeysJson = if (actorPubkeys.isNotEmpty()) json.encodeToString(ListSerializer(String.serializer()), actorPubkeys) else null,
            customEmojiUrlsJson = if (customEmojiUrls.isNotEmpty()) json.encodeToString(MapSerializer(String.serializer(), String.serializer()), customEmojiUrls) else null,
            customEmojiUrl = customEmojiUrl,
            badgeName = badgeName,
            badgeImageUrl = badgeImageUrl,
            pollId = pollId,
            pollQuestion = pollQuestion,
            pollOptionCodesJson = if (pollOptionCodes.isNotEmpty()) json.encodeToString(ListSerializer(String.serializer()), pollOptionCodes) else null,
            pollOptionLabelsJson = if (pollOptionLabels.isNotEmpty()) json.encodeToString(ListSerializer(String.serializer()), pollOptionLabels) else null,
            pollAllOptionsJson = if (pollAllOptions.isNotEmpty()) json.encodeToString(ListSerializer(String.serializer()), pollAllOptions) else null,
            pollIsMultipleChoice = pollIsMultipleChoice,
            rawContent = rawContent,
            noteContent = note?.content,
            targetNoteContent = targetNote?.content,
            verificationStatus = verificationStatus.name,
        )
    }

    /** Convert Room entity to NotificationData. Reconstructs Note stubs from persisted content
     *  so reply text and target note previews are visible immediately on cold start. */
    private fun social.mycelium.android.db.CachedNotificationEntity.toNotificationData(): NotificationData {
        val notifType = try { NotificationType.valueOf(type) } catch (_: Exception) { NotificationType.LIKE }
        val author = if (authorId != null) Author(
            id = authorId,
            username = authorUsername ?: authorId.take(8) + "...",
            displayName = authorDisplayName ?: authorId.take(8) + "...",
            avatarUrl = authorAvatarUrl,
            isVerified = false
        ) else null
        val stringSerializer = String.serializer()
        val listSerializer = ListSerializer(stringSerializer)
        val mapSerializer = MapSerializer(stringSerializer, stringSerializer)
        // Reconstruct Note stubs from persisted content for immediate display
        val noteStub = if (!noteContent.isNullOrBlank() && author != null) {
            Note(
                id = replyNoteId ?: id,
                author = author,
                content = noteContent,
                timestamp = sortTimestamp,
                likes = 0, shares = 0, comments = 0,
                isLiked = false, hashtags = emptyList(), mediaUrls = emptyList(),
                rootNoteId = rootNoteId, replyToId = null, isReply = rootNoteId != null,
                kind = replyKind ?: 1
            )
        } else null
        val targetNoteStub = if (!targetNoteContent.isNullOrBlank() && targetNoteId != null) {
            val authorPk = targetNoteAuthorId
            val targetAuthor = if (authorPk != null) {
                profileCache.getAuthor(normalizeAuthorIdForCache(authorPk)) ?: Author(
                    id = authorPk, username = authorPk.take(8) + "...",
                    displayName = authorPk.take(8) + "...", avatarUrl = null, isVerified = false
                )
            } else {
                Author(
                    id = targetNoteId, username = targetNoteId.take(8) + "...",
                    displayName = targetNoteId.take(8) + "...", avatarUrl = null, isVerified = false
                )
            }
            Note(
                id = targetNoteId,
                author = targetAuthor,
                content = targetNoteContent,
                timestamp = sortTimestamp,
                likes = 0, shares = 0, comments = 0,
                isLiked = false, hashtags = emptyList(), mediaUrls = emptyList()
            )
        } else null
        return NotificationData(
            id = id,
            type = notifType,
            text = text,
            note = noteStub,
            targetNote = targetNoteStub,
            sortTimestamp = sortTimestamp,
            author = author,
            targetNoteId = targetNoteId,
            rootNoteId = rootNoteId,
            replyNoteId = replyNoteId,
            replyKind = replyKind,
            reactionEmoji = reactionEmoji,
            reactionEmojis = reactionEmojisJson?.let { try { json.decodeFromString(listSerializer, it) } catch (_: Exception) { emptyList<String>() } } ?: emptyList(),
            zapAmountSats = zapAmountSats,
            actorPubkeys = actorPubkeysJson?.let { try { json.decodeFromString(listSerializer, it) } catch (_: Exception) { emptyList<String>() } } ?: emptyList(),
            customEmojiUrls = customEmojiUrlsJson?.let { try { json.decodeFromString(mapSerializer, it) } catch (_: Exception) { emptyMap<String, String>() } } ?: emptyMap(),
            customEmojiUrl = customEmojiUrl,
            badgeName = badgeName,
            badgeImageUrl = badgeImageUrl,
            pollId = pollId,
            pollQuestion = pollQuestion,
            pollOptionCodes = pollOptionCodesJson?.let { try { json.decodeFromString(listSerializer, it) } catch (_: Exception) { emptyList<String>() } } ?: emptyList(),
            pollOptionLabels = pollOptionLabelsJson?.let { try { json.decodeFromString(listSerializer, it) } catch (_: Exception) { emptyList<String>() } } ?: emptyList(),
            pollAllOptions = pollAllOptionsJson?.let { try { json.decodeFromString(listSerializer, it) } catch (_: Exception) { emptyList<String>() } } ?: emptyList(),
            pollIsMultipleChoice = pollIsMultipleChoice,
            rawContent = rawContent,
            verificationStatus = try { social.mycelium.android.data.VerificationStatus.valueOf(verificationStatus) } catch (_: Exception) { social.mycelium.android.data.VerificationStatus.PENDING },
        )
    }

    /** Maximum pending target fetches before forcing an immediate flush (prevents starvation). */
    private val MAX_TARGET_FETCH_BATCH = 50

    private fun fetchAndSetTargetNote(
        noteId: String,
        notificationId: String,
        update: (NotificationData) -> (Note?) -> NotificationData
    ) {
        // Fast path: check feed cache immediately — avoids the debounce delay entirely
        // for target notes that are already in the local feed (common for replies/likes to your recent posts).
        val cached = NotesRepository.getInstance().getNoteFromCache(noteId)
        if (cached != null) {
            val current = notificationsById[notificationId]
            if (current != null) {
                val targetAuthorHex = normalizeAuthorIdForCache(cached.author.id)
                val isOurNote = myPubkeyHex != null && targetAuthorHex == myPubkeyHex
                // Verify relevance (same logic as flushTargetFetchBatch Step 3)
                if (current.type == NotificationType.LIKE && !isOurNote) {
                    notificationsById[notificationId] = current.copy(
                        verificationStatus = social.mycelium.android.data.VerificationStatus.UNVERIFIED
                    )
                    emitSorted()
                    return
                }
                // REPOST: don't remove — the event already passed relay p-tag
                // or isRepostOfOurNote validation upstream. Attach the target
                // note for display regardless of ownership verification.
                // (Author format mismatches or cached note variants could fail
                // the isOurNote check even for valid boosts.)
                if (current.type == NotificationType.REPLY && current.replyKind == NOTIFICATION_KIND_TEXT && !isOurNote) {
                    val contentToCheck = current.rawContent ?: current.note?.content ?: ""
                    val isCited = myPubkeyHex != null && contentToCheck.isNotBlank() &&
                            isUserCitedInContent(contentToCheck, myPubkeyHex!!)
                    if (!isCited) {
                        notificationsById[notificationId] = current.copy(
                            verificationStatus = social.mycelium.android.data.VerificationStatus.UNVERIFIED
                        )
                        emitSorted()
                        return
                    }
                    notificationsById[notificationId] = current.copy(
                        type = NotificationType.MENTION,
                        text = "${current.author?.displayName ?: "Someone"} mentioned you"
                    )
                    emitSorted()
                    return
                }
                var updated = update(current)(cached)
                if (current.replyKind == NOTIFICATION_KIND_TOPIC_REPLY && cached.kind == 11 && myPubkeyHex != null) {
                    val rootAuthorHex = normalizeAuthorIdForCache(cached.author.id)
                    if (rootAuthorHex == myPubkeyHex) {
                        updated = updated.copy(
                            type = NotificationType.REPLY,
                            replyKind = 11,
                            text = "${current.author?.displayName ?: "Someone"} replied to your thread"
                        )
                    }
                }
                notificationsById[notificationId] = updated.copy(
                    verificationStatus = social.mycelium.android.data.VerificationStatus.VERIFIED
                )
                emitSorted()
                return
            }
        }
        // Mid path: try Room DB before falling through to batched relay fetch
        val roomContext = appContext
        if (roomContext != null) {
            scope.launch {
                try {
                    val dao = social.mycelium.android.db.AppDatabase.getInstance(roomContext).eventDao()
                    val entities = dao.getByIds(listOf(noteId))
                    if (entities.isNotEmpty()) {
                        val event = Event.fromJson(entities.first().eventJson)
                        val note = eventToNote(event)
                        val current = notificationsById[notificationId]
                        if (current != null) {
                            val targetAuthorHex = normalizeAuthorIdForCache(note.author.id)
                            val isOurNote = myPubkeyHex != null && targetAuthorHex == myPubkeyHex
                            if (current.type == NotificationType.LIKE && !isOurNote) {
                                notificationsById[notificationId] = current.copy(
                                    verificationStatus = social.mycelium.android.data.VerificationStatus.UNVERIFIED
                                ); emitSorted(); return@launch
                            }
                            // REPOST: skip removal — upstream validation already confirmed relevance
                            if (current.type == NotificationType.REPLY && current.replyKind == NOTIFICATION_KIND_TEXT && !isOurNote) {
                                val contentToCheck = current.rawContent ?: current.note?.content ?: ""
                                val isCited = myPubkeyHex != null && contentToCheck.isNotBlank() &&
                                        isUserCitedInContent(contentToCheck, myPubkeyHex!!)
                                if (!isCited) {
                                    notificationsById[notificationId] = current.copy(
                                        verificationStatus = social.mycelium.android.data.VerificationStatus.UNVERIFIED
                                    ); emitSorted(); return@launch
                                }
                                notificationsById[notificationId] = current.copy(
                                    type = NotificationType.MENTION,
                                    text = "${current.author?.displayName ?: "Someone"} mentioned you"
                                )
                                emitSorted(); return@launch
                            }
                            var updated = update(current)(note)
                            // Kind-11 topic reclassification (same as fast path and batch path)
                            if (current.replyKind == NOTIFICATION_KIND_TOPIC_REPLY && note.kind == 11 && myPubkeyHex != null) {
                                val rootAuthorHex = normalizeAuthorIdForCache(note.author.id)
                                if (rootAuthorHex == myPubkeyHex) {
                                    updated = updated.copy(
                                        type = NotificationType.REPLY,
                                        replyKind = 11,
                                        text = "${current.author?.displayName ?: "Someone"} replied to your thread"
                                    )
                                }
                            }
                            notificationsById[notificationId] = updated.copy(
                                verificationStatus = social.mycelium.android.data.VerificationStatus.VERIFIED
                            )
                            emitSorted()
                        }
                        return@launch
                    }
                } catch (_: Exception) {
                }
                // Room miss — fall through to batched relay fetch
                if (subscriptionRelayUrls.isEmpty()) return@launch
                pendingTargetFetches.getOrPut(noteId) { CopyOnWriteArrayList() }
                    .add(PendingTargetFetch(noteId, notificationId, update))
                scheduleTargetFetchFlush()
            }
            return
        }
        if (subscriptionRelayUrls.isEmpty()) return
        // Slow path: buffer for batched relay fetch
        pendingTargetFetches.getOrPut(noteId) { CopyOnWriteArrayList() }
            .add(PendingTargetFetch(noteId, notificationId, update))
        scheduleTargetFetchFlush()
    }

    /** Mark target fetch buffer as dirty — the poller will pick it up within 200ms.
     *  If the buffer exceeds [MAX_TARGET_FETCH_BATCH], the poller triggers immediately
     *  via the dirty flag (no cancel+relaunch overhead). */
    private fun scheduleTargetFetchFlush() {
        targetFetchDirty = true
    }

    /** Single long-lived coroutine polling the target fetch dirty flag.
     *  Replaces hundreds of cancel+relaunch Job allocations during Phase 1. */
    private fun startTargetFetchPoller() {
        targetFetchPollerJob?.cancel()
        targetFetchPollerJob = scope.launch {
            while (true) {
                delay(TARGET_FETCH_BATCH_DELAY_MS)
                if (targetFetchDirty) {
                    targetFetchDirty = false
                    flushTargetFetchBatch()
                }
            }
        }
    }

    /** Flush all pending target note fetches as ONE batched subscription. */
    private suspend fun flushTargetFetchBatch() {
        if (pendingTargetFetches.isEmpty() || subscriptionRelayUrls.isEmpty()) return
        // Mutex ensures only one flush runs at a time — prevents duplicate retry processing
        // when a new flush fires while the previous one is waiting on its one-shot delay.
        targetFetchMutex.withLock {
            if (pendingTargetFetches.isEmpty()) return
            val batch = pendingTargetFetches.toMap()
            pendingTargetFetches.clear()

            val allNoteIds = batch.keys.toList()
            val maxRetry = batch.values.flatten().maxOfOrNull { it.retryCount } ?: 0
            val timeoutMs = if (maxRetry > 0) TARGET_FETCH_RETRY_TIMEOUT_MS else TARGET_FETCH_TIMEOUT_MS
            MLog.d(TAG, "Flushing target note batch: ${allNoteIds.size} notes (retry=$maxRetry, timeout=${timeoutMs}ms)")

            // ── Step 1: Check local caches before hitting relays ──────────────
            val fetched = java.util.concurrent.ConcurrentHashMap<String, Note>()
            val notesRepo = NotesRepository.getInstance()
            val remainingIds = mutableListOf<String>()
            for (noteId in allNoteIds) {
                val cached = notesRepo.getNoteFromCache(noteId)
                if (cached != null) {
                    fetched[noteId] = cached
                } else {
                    remainingIds.add(noteId)
                }
            }
            if (remainingIds.isNotEmpty()) MLog.d(
                TAG,
                "In-memory cache hit ${fetched.size}/${allNoteIds.size}, checking Room for ${remainingIds.size}"
            )

            // ── Step 1b: Check Room DB before hitting relays ────────────────
            // DeepHistoryFetcher and NotesRepository persist events to Room —
            // many target notes are already stored locally from previous sessions.
            if (remainingIds.isNotEmpty()) {
                try {
                    val roomContext = appContext
                    if (roomContext != null) {
                        val dao = social.mycelium.android.db.AppDatabase.getInstance(roomContext).eventDao()
                        val roomEntities = dao.getByIds(remainingIds)
                        for (entity in roomEntities) {
                            try {
                                val event = Event.fromJson(entity.eventJson)
                                fetched[event.id] = eventToNote(event)
                            } catch (_: Exception) {
                            }
                        }
                        val roomHits = remainingIds.count { fetched.containsKey(it) }
                        if (roomHits > 0) {
                            remainingIds.removeAll(fetched.keys)
                            MLog.d(TAG, "Room cache hit $roomHits more, ${remainingIds.size} still need relay fetch")
                        }
                    }
                } catch (e: Exception) {
                    MLog.w(TAG, "Room lookup failed: ${e.message}")
                }
            }

            // ── Step 2: Fetch remaining from relays (EOSE-driven, no blind delay) ──
            if (remainingIds.isNotEmpty()) {
                val filter = Filter(ids = remainingIds, limit = remainingIds.size)
                RelayConnectionStateMachine.getInstance()
                    .awaitOneShotSubscription(
                        subscriptionRelayUrls, filter, priority = SubscriptionPriority.NORMAL,
                        settleMs = 500L, maxWaitMs = timeoutMs
                    ) { ev ->
                        fetched[ev.id] = eventToNote(ev)
                    }
            }

            // ── Step 3: Apply fetched notes; verify reactions are to our notes ─
            var unverifiedCount = 0
            val retryQueue = mutableListOf<PendingTargetFetch>()
            for ((noteId, pendingList) in batch) {
                val note = fetched[noteId]
                for (pending in pendingList) {
                    val current = notificationsById[pending.notificationId] ?: continue
                    if (note == null) {
                        // Target note not fetched. Retry with longer timeout before giving up.
                        if (pending.retryCount < MAX_TARGET_FETCH_RETRIES) {
                            retryQueue.add(pending.copy(retryCount = pending.retryCount + 1))
                            MLog.d(
                                TAG,
                                "Re-queuing ${current.type} ${pending.notificationId.take(8)} for retry (attempt ${pending.retryCount + 1})"
                            )
                        } else {
                            // Max retries exhausted. Keep the notification (it passed relay-side
                            // p-tag filtering so it IS relevant) but without a target note preview.
                            // Previously we deleted likes/reposts here, causing silent notification loss.
                            targetFetchExhaustedIds.add(pending.notificationId)
                            notificationsById[pending.notificationId] = current.copy(
                                verificationStatus = social.mycelium.android.data.VerificationStatus.EXHAUSTED
                            )
                            MLog.d(
                                TAG,
                                "Target fetch exhausted for ${current.type} ${pending.notificationId.take(8)} — keeping without preview"
                            )
                            // If this is a REPLY and user is cited in content, reclassify as MENTION
                            // so it appears in the Mentions tab even without target note resolution.
                            val rawCheck = current.rawContent ?: current.note?.content ?: ""
                            if (current.type == NotificationType.REPLY && current.replyKind == NOTIFICATION_KIND_TEXT &&
                                myPubkeyHex != null && rawCheck.isNotBlank() &&
                                isUserCitedInContent(rawCheck, myPubkeyHex!!)
                            ) {
                                val mentionUpdate = current.copy(
                                    type = NotificationType.MENTION,
                                    text = "${current.author?.displayName ?: "Someone"} mentioned you"
                                )
                                notificationsById[pending.notificationId] = mentionUpdate
                                MLog.d(
                                    TAG,
                                    "Reclassified ${pending.notificationId.take(8)} as MENTION (cited in content, target fetch exhausted)"
                                )
                            }
                        }
                        continue
                    }
                    val targetAuthorHex = normalizeAuthorIdForCache(note.author.id)
                    val isOurNote = myPubkeyHex != null && targetAuthorHex == myPubkeyHex

                    // Kind-7 (like): only show if target note author is the current user
                    if (current.type == NotificationType.LIKE && !isOurNote) {
                        notificationsById[pending.notificationId] = current.copy(
                            verificationStatus = social.mycelium.android.data.VerificationStatus.UNVERIFIED
                        )
                        unverifiedCount++
                        continue
                    }
                    // Kind-6/16 (repost): skip removal — upstream relay p-tag or
                    // isRepostOfOurNote validation already confirmed relevance.
                    // Removing here caused valid boost notifications to be silently
                    // deleted due to author format mismatches in the cache.
                    // Kind-1 reply: verify the parent note is authored by us OR we're cited in content.
                    if (current.type == NotificationType.REPLY && current.replyKind == NOTIFICATION_KIND_TEXT && !isOurNote) {
                        // Use rawContent (original event content with nostr: URIs intact) for cite check.
                        // note.content has nostr:npub→@displayName resolved, which breaks the regex.
                        val contentToCheck = current.rawContent ?: current.note?.content ?: ""
                        val isCitedInContent = myPubkeyHex != null && contentToCheck.isNotBlank() &&
                                isUserCitedInContent(contentToCheck, myPubkeyHex!!)
                        if (!isCitedInContent) {
                            notificationsById[pending.notificationId] = current.copy(
                                verificationStatus = social.mycelium.android.data.VerificationStatus.UNVERIFIED
                            )
                            unverifiedCount++
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
                            updated = updated.copy(
                                type = NotificationType.REPLY,
                                replyKind = 11,
                                text = "${current.author?.displayName ?: "Someone"} replied to your thread"
                            )
                        }
                    }
                    notificationsById[pending.notificationId] = updated.copy(
                        verificationStatus = social.mycelium.android.data.VerificationStatus.VERIFIED
                    )
                }
            }
            if (unverifiedCount > 0) MLog.d(TAG, "Marked $unverifiedCount false-positive notifications as UNVERIFIED after target fetch")
            emitSorted()
            // Re-queue unfetched reactions/reposts for a retry with longer timeout
            if (retryQueue.isNotEmpty()) {
                MLog.d(TAG, "Re-queuing ${retryQueue.size} notifications for target fetch retry")
                for (pending in retryQueue) {
                    pendingTargetFetches.getOrPut(pending.noteId) { CopyOnWriteArrayList() }.add(pending)
                }
                scheduleTargetFetchFlush()
            }
        } // end mutex
    }

    /** Notification IDs whose target fetch has been exhausted (MAX_TARGET_FETCH_RETRIES reached).
     *  Prevents reEnrichOrphanedNotifications from re-queuing them indefinitely. */
    private val targetFetchExhaustedIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * Phase 4: Re-enrich notifications that were created during cold start before relays
     * were fully connected. Finds notifications with a targetNoteId but no targetNote
     * and re-queues them for a fresh fetch now that relays should be available.
     */
    private fun reEnrichOrphanedNotifications() {
        val orphans = notificationsById.values.filter { data ->
            data.targetNoteId != null && data.targetNote == null &&
                    !targetFetchExhaustedIds.contains(data.id) && // Skip already-exhausted
                    !pendingTargetFetches.containsKey(data.targetNoteId) && // Skip already-pending
                    data.type in listOf(
                NotificationType.LIKE, NotificationType.REPOST, NotificationType.REPLY,
                NotificationType.COMMENT, NotificationType.QUOTE, NotificationType.POLL_VOTE, NotificationType.ZAP
            )
        }
        if (orphans.isEmpty()) {
            // Even if no target note orphans, still sweep zap sender profiles
            reEnrichZapSenderProfiles()
            return
        }
        MLog.d(TAG, "Phase 4: Re-enriching ${orphans.size} orphaned notifications (missing targetNote)")
        for (orphan in orphans) {
            val targetId = orphan.targetNoteId ?: continue
            fetchAndSetTargetNote(targetId, orphan.id) { d -> { note -> d.copy(targetNote = note) } }
        }
        // Also sweep zap sender profiles after target notes
        reEnrichZapSenderProfiles()
    }

    /**
     * Phase 4b: Re-enrich zap notifications whose sender still shows a placeholder
     * (wallet service name like "CoinOS" or hex prefix like "a1b2c3d4...").
     * Collects unique unresolved sender pubkeys, requests profiles in one batch,
     * then updates notification text as profiles resolve.
     */
    private fun reEnrichZapSenderProfiles() {
        if (cacheRelayUrls.isEmpty()) return
        // Find zap notifications where the author looks like a placeholder (hex prefix ending with ...)
        val zapOrphans = notificationsById.values.filter { data ->
            data.type == NotificationType.ZAP &&
                    data.author?.displayName?.endsWith("...") == true &&
                    data.author?.id?.length == 64
        }
        if (zapOrphans.isEmpty()) return
        val uniquePubkeys = zapOrphans.mapNotNull { it.author?.id?.lowercase() }.distinct()
        val uncached = uniquePubkeys.filter { profileCache.getAuthor(it) == null }
        if (uncached.isEmpty()) {
            // Profiles are cached but notifications weren't updated yet — update them now
            var updated = 0
            for (data in zapOrphans) {
                val authorId = data.author?.id ?: continue
                val resolved = profileCache.getAuthor(authorId) ?: continue
                if (resolved.displayName == data.author?.displayName) continue
                val newName = resolved.displayName ?: "Someone"
                val oldName = data.author?.displayName ?: ""
                val newText = data.text.replaceFirst(oldName, newName)
                notificationsById[data.id] = data.copy(text = newText, author = resolved)
                updated++
            }
            if (updated > 0) {
                emitSorted()
                MLog.d(TAG, "Phase 4b: Updated $updated zap sender names from cache")
            }
            return
        }
        MLog.d(TAG, "Phase 4b: Re-enriching ${zapOrphans.size} zap sender profiles (${uncached.size} unique pubkeys)")
        val uncachedSet = uncached.map { it.lowercase() }.toSet()
        scope.launch {
            profileCache.requestProfiles(uncached, cacheRelayUrls)
            // Wait for profiles to resolve (up to 15s), then update notifications
            val resolvedPubkeys = mutableSetOf<String>()
            kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                profileCache.profileUpdated
                    .filter { it.lowercase() in uncachedSet }
                    .collect { pk ->
                        resolvedPubkeys.add(pk.lowercase())
                        if (resolvedPubkeys.size >= uncached.size) {
                            // All resolved
                            throw kotlinx.coroutines.CancellationException("all resolved")
                        }
                    }
            }
            // Update all zap notifications with newly resolved profiles
            var updated = 0
            for (data in zapOrphans) {
                val authorId = data.author?.id ?: continue
                val resolved = profileCache.getAuthor(authorId) ?: continue
                if (resolved.displayName == data.author?.displayName) continue
                val newName = resolved.displayName ?: "Someone"
                val oldName = data.author?.displayName ?: ""
                val newText = data.text.replaceFirst(oldName, newName)
                notificationsById[data.id] = data.copy(text = newText, author = resolved)
                updated++
            }
            if (updated > 0) {
                emitSorted()
                MLog.d(TAG, "Phase 4b: Updated $updated zap sender display names")
            }
        }
    }

    private fun eventToNote(event: Event): Note {
        val author = profileCache.resolveAuthor(event.pubKey)
        val hashtags = event.tags.toList()
            .filter { it.size >= 2 && it[0] == "t" }
            .mapNotNull { it.getOrNull(1) }
        // Resolve nostr:npub1... mentions to @displayName for cleaner notification previews
        val resolvedContent = resolveNpubMentions(event.content)
        val mediaUrls = social.mycelium.android.utils.UrlDetector.findUrls(event.content)
            .filter {
                social.mycelium.android.utils.UrlDetector.isImageUrl(it) || social.mycelium.android.utils.UrlDetector.isVideoUrl(
                    it
                )
            }
            .distinct()
        val quotedRefs = social.mycelium.android.utils.Nip19QuoteParser.extractQuotedEventRefs(event.content)
        val quotedEventIds = quotedRefs.map { it.eventId }
        quotedRefs.forEach { ref ->
            if (ref.relayHints.isNotEmpty()) social.mycelium.android.repository.cache.QuotedNoteCache.putRelayHints(
                ref.eventId,
                ref.relayHints
            )
        }
        // Parse NIP-10 reply context so tapping notifications can open the full thread
        val rootId = social.mycelium.android.utils.Nip10ReplyDetector.getRootId(event)
        val replyToId = social.mycelium.android.utils.Nip10ReplyDetector.getReplyToId(event)
        val isReply = social.mycelium.android.utils.Nip10ReplyDetector.isReply(event)
        // NIP-88 poll data (kind 1068)
        val tags = event.tags.map { it.toList() }
        val pollData = if (event.kind == 1068) social.mycelium.android.data.PollData.parseFromTags(tags) else null
        // Zap poll data (kind 6969)
        val zapPollData = if (event.kind == 6969) social.mycelium.android.data.ZapPollData.parseFromTags(tags) else null
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
            isReply = isReply,
            rootNoteId = rootId,
            replyToId = replyToId,
            kind = event.kind,
            pollData = pollData,
            zapPollData = zapPollData
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
                    val name =
                        author.displayName.takeIf { !it.endsWith("...") && it != author.username } ?: author.username
                    result = result.replaceRange(match.range, "@$name")
                }
            } catch (_: Exception) {
            }
        }
        // Resolve npub
        npubRegex.findAll(result).toList().reversed().forEach { match ->
            try {
                val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute("nostr:${match.groupValues[1]}")
                val hex = (parsed?.entity as? com.example.cybin.nip19.NPub)?.hex
                if (hex != null && hex.length == 64) {
                    val author = profileCache.resolveAuthor(hex)
                    val name =
                        author.displayName.takeIf { !it.endsWith("...") && it != author.username } ?: author.username
                    result = result.replaceRange(match.range, "@$name")
                }
            } catch (_: Exception) {
            }
        }
        return result
    }
}
