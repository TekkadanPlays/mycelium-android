package social.mycelium.android.repository

import android.content.Context
import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import com.example.cybin.signer.NostrSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.mycelium.android.relay.RelayConnectionStateMachine

/**
 * Coordinates phased startup so relay subscriptions don't all fire at once.
 *
 * ## Phases
 *
 * **Phase 0 — Settings** (CRITICAL priority, blocks everything)
 *   Kind-30078 settings sync. Must complete (or timeout) before any other
 *   subscriptions. On fresh install the user's compact-media / theme / accent
 *   preferences come from this event. Failure here = broken UI.
 *
 * **Phase 1 — User State** (HIGH priority, starts after Phase 0)
 *   Kind-3 follow list + Kind-10000 mute list. Needed so the feed filter
 *   and content filter are ready before notes arrive.
 *
 * **Phase 2 — Feed** (HIGH priority, starts after Phase 1)
 *   Main kind-1 subscription + self NIP-65 (kind-10002).
 *   DashboardScreen drives this via loadNotesFromFavoriteCategory().
 *
 * **Phase 3 — Enrichment** (NORMAL priority, starts after Phase 2 EOSE or 5s)
 *   Outbox feed, notifications, profile batch, counts, bookmarks, emoji packs,
 *   anchor subscriptions.
 *
 * **Phase 4 — Background** (LOW priority, starts after Phase 3 or 15s)
 *   DMs (kind-1059), background account notification subs.
 *
 * Each phase emits a [StartupPhase] via [currentPhase]. Consumers gate their
 * work on the phase being >= their required phase.
 */
object StartupOrchestrator {

    private const val TAG = "StartupOrchestrator"

    enum class StartupPhase {
        IDLE,
        SETTINGS,       // Phase 0: settings fetch in progress
        USER_STATE,     // Phase 1: follow list + mute list
        FEED,           // Phase 2: main feed subscription
        ENRICHMENT,     // Phase 3: outbox, notifs, counts, etc.
        BACKGROUND,     // Phase 4: DMs, bg account notifs
        COMPLETE,       // All phases done
    }

    private val _currentPhase = MutableStateFlow(StartupPhase.IDLE)
    val currentPhase: StateFlow<StartupPhase> = _currentPhase.asStateFlow()

    /** True once Phase 0 (settings) has completed or timed out. */
    private val _settingsReady = MutableStateFlow(false)
    val settingsReady: StateFlow<Boolean> = _settingsReady.asStateFlow()

    /** True once Phase 1 (follow list + mute list) has completed or timed out. */
    private val _userStateReady = MutableStateFlow(false)
    val userStateReady: StateFlow<Boolean> = _userStateReady.asStateFlow()

    /** True once Phase 2 (feed) has started — enrichment can begin. */
    private val _feedStarted = MutableStateFlow(false)
    val feedStarted: StateFlow<Boolean> = _feedStarted.asStateFlow()

    /** True once Phase 3 (enrichment) has started — background can begin. */
    private val _enrichmentStarted = MutableStateFlow(false)
    val enrichmentStarted: StateFlow<Boolean> = _enrichmentStarted.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Guard against double-execution when LaunchedEffect re-fires. */
    @Volatile
    private var phase1Started = false
    @Volatile
    private var phase0Started = false

    /** Deferred that completes when Phase 1 is done. Callers can await it. */
    @Volatile
    private var phase1Deferred: CompletableDeferred<Unit>? = null

    /** Tracks the currently active account pubkey. Used by Phase 5 to detect stale
     *  detached coroutines from a previous account that survived an account switch. */
    @Volatile
    private var activePubkey: String? = null

    /** Reset for account switch. */
    fun reset() {
        // Stop any in-progress deep history fetch from the previous account
        DeepHistoryFetcher.stop()
        RelayCategorySyncRepository.clearAll()
        activePubkey = null
        _currentPhase.value = StartupPhase.IDLE
        _settingsReady.value = false
        _userStateReady.value = false
        _feedStarted.value = false
        _enrichmentStarted.value = false
        phase0Started = false
        phase1Started = false
        phase1Deferred = null
        Log.d(TAG, "Reset — all phases cleared (deep fetch stopped)")
    }

    /** Skip Phase 0 when settings are already applied or no signer is available. */
    fun skipPhase0() {
        _settingsReady.value = true
        _currentPhase.value = StartupPhase.USER_STATE
        Log.d(TAG, "Phase 0: skipped → advancing to Phase 1")
    }

    // ── Phase 0: Settings ───────────────────────────────────────────────────

    /**
     * Run Phase 0: fetch settings with CRITICAL priority.
     * Runs **concurrently** with Phase 1 — does NOT block the feed.
     * Settings are cosmetic (compact media, theme, accent) and apply retroactively
     * via Compose recomposition when the StateFlow/SharedPreferences update.
     * The feed only waits for [userStateReady] (follow list + mute list).
     */
    fun runPhase0Settings(
        signer: NostrSigner,
        userPubkey: String,
        relayUrls: List<String>,
    ) {
        if (phase0Started) return // Guard against LaunchedEffect re-fire
        phase0Started = true

        if (relayUrls.isEmpty()) {
            Log.w(TAG, "Phase 0: no relays — skipping settings fetch")
            _settingsReady.value = true
            return
        }

        _currentPhase.value = StartupPhase.SETTINGS
        Log.d(TAG, "Phase 0: fetching settings from ${relayUrls.size} relays (CRITICAL, non-blocking)")

        // Fire-and-forget: settings apply reactively via SharedPreferences → Compose recomposition
        scope.launch {
            try {
                val filter = Filter(
                    kinds = listOf(30078),
                    authors = listOf(userPubkey),
                    tags = mapOf("d" to listOf("MyceliumSettings")),
                    limit = 1
                )

                var latestEvent: com.example.cybin.core.Event? = null
                val stateMachine = RelayConnectionStateMachine.getInstance()
                stateMachine.awaitOneShotSubscription(
                    relayUrls, filter,
                    priority = SubscriptionPriority.CRITICAL,
                    settleMs = 800L,
                    maxWaitMs = 8_000L
                ) { event ->
                    if (latestEvent == null || event.createdAt > (latestEvent?.createdAt ?: 0)) {
                        latestEvent = event
                    }
                }

                val event = latestEvent
                if (event != null) {
                    Log.d(TAG, "Phase 0: found settings event ${event.id.take(8)}, decrypting...")
                    try {
                        val plaintext = if (signer is com.example.cybin.nip55.NostrSignerExternal) {
                            signer.nip44DecryptBackgroundOnly(event.content, userPubkey)
                                ?: signer.nip44Decrypt(event.content, userPubkey)
                        } else {
                            signer.nip44Decrypt(event.content, userPubkey)
                        }
                        val remoteSettings = social.mycelium.android.data.SyncedSettings.fromJson(plaintext)
                        SettingsSyncManager.applyRemoteSettings(remoteSettings)
                        Log.d(TAG, "Phase 0: settings applied (compactMedia=${remoteSettings.compactMedia})")
                    } catch (e: Exception) {
                        Log.e(TAG, "Phase 0: decrypt/apply failed: ${e.message}", e)
                    }
                } else {
                    Log.d(TAG, "Phase 0: no settings event found — using local defaults")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Phase 0: settings fetch failed: ${e.message}", e)
            } finally {
                _settingsReady.value = true
                Log.d(TAG, "Phase 0: complete")
            }
        }
    }

    // ── Phase 1: User State ─────────────────────────────────────────────────

    /**
     * Run Phase 1: fetch follow list + mute list with HIGH priority.
     * Runs on the orchestrator's own scope so it survives LaunchedEffect
     * recomposition. Returns a Deferred that completes when Phase 1 is done.
     */
    fun runPhase1UserState(
        userPubkey: String,
        followRelayUrls: List<String>,
        allUserRelayUrls: List<String>,
        signer: com.example.cybin.signer.NostrSigner? = null,
        context: Context? = null,
    ): CompletableDeferred<Unit> {
        // Guard against double-execution when LaunchedEffect re-fires
        phase1Deferred?.let { return it }
        if (phase1Started) return CompletableDeferred<Unit>().also { it.complete(Unit) }
        phase1Started = true
        activePubkey = userPubkey

        val deferred = CompletableDeferred<Unit>()
        phase1Deferred = deferred

        _currentPhase.value = StartupPhase.USER_STATE
        Log.d(TAG, "Phase 1: fetching follow list + mute list")

        scope.launch {
            try {
                // Follow list and mute list in parallel — both use one-shot subs
                val followJob = scope.launch {
                    try {
                        ContactListRepository.fetchFollowList(userPubkey, followRelayUrls, forceRefresh = false)
                        Log.d(TAG, "Phase 1: follow list fetched")
                    } catch (e: Exception) {
                        Log.e(TAG, "Phase 1: follow list failed: ${e.message}")
                    }
                }
                val muteJob = scope.launch {
                    try {
                        MuteListRepository.fetchMuteList(userPubkey, allUserRelayUrls)
                        Log.d(TAG, "Phase 1: mute list fetched")
                    } catch (e: Exception) {
                        Log.e(TAG, "Phase 1: mute list failed: ${e.message}")
                    }
                }

                // Wait for both with a timeout
                val deadline = System.currentTimeMillis() + 6_000L
                while (System.currentTimeMillis() < deadline) {
                    if (followJob.isCompleted && muteJob.isCompleted) break
                    delay(100)
                }
                if (!followJob.isCompleted) Log.w(TAG, "Phase 1: follow list timed out")
                if (!muteJob.isCompleted) Log.w(TAG, "Phase 1: mute list timed out")

                // Fire-and-forget: people lists + hashtag interests + relay sets + indexer list (LOW priority, don't block feed)
                PeopleListRepository.fetchPeopleLists(userPubkey, allUserRelayUrls, signer)
                PeopleListRepository.fetchHashtagList(userPubkey, allUserRelayUrls)
                if (context != null) {
                    RelayCategorySyncRepository.fetchRelaySets(userPubkey, allUserRelayUrls, context)
                    RelayCategorySyncRepository.fetchIndexerList(userPubkey, allUserRelayUrls, context)
                }
                Log.d(TAG, "Phase 1: people lists + hashtag interests + relay sets + indexer list fetch launched (non-blocking)")
            } catch (e: Exception) {
                Log.e(TAG, "Phase 1: failed: ${e.message}", e)
            } finally {
                _userStateReady.value = true
                _currentPhase.value = StartupPhase.FEED
                deferred.complete(Unit)
                Log.d(TAG, "Phase 1: complete → advancing to Phase 2 (feed)")
            }
        }
        return deferred
    }

    // ── Phase 2: Feed ───────────────────────────────────────────────────────

    /**
     * Signal that the feed subscription has been applied.
     * Called by DashboardScreen after loadNotesFromFavoriteCategory().
     */
    fun markFeedStarted() {
        if (_feedStarted.value) return
        _feedStarted.value = true
        _currentPhase.value = StartupPhase.ENRICHMENT
        Log.d(TAG, "Phase 2: feed subscription started → advancing to Phase 3")

        // Auto-advance to enrichment after a brief delay for EOSE
        scope.launch {
            delay(3_000L)
            if (!_enrichmentStarted.value) {
                _enrichmentStarted.value = true
                Log.d(TAG, "Phase 3: enrichment gate opened (3s after feed start)")
            }
        }
    }

    // ── Phase 3: Enrichment ─────────────────────────────────────────────────

    /**
     * Run Phase 3: all enrichment subscriptions.
     * Called from MyceliumNavigation after feedStarted or a timeout.
     */
    fun runPhase3Enrichment(
        context: Context,
        userPubkey: String,
        inboxUrls: List<String>,
        outboxUrls: List<String>,
        categoryUrls: List<String>,
        indexerUrls: List<String>,
        allUserRelayUrls: List<String>,
        followedPubkeys: Set<String>,
        feedRelayUrls: List<String>,
    ) {
        _enrichmentStarted.value = true
        _currentPhase.value = StartupPhase.ENRICHMENT
        Log.d(TAG, "Phase 3: starting enrichment subscriptions")

        // Notifications
        NotificationsRepository.setCacheRelayUrls(indexerUrls)
        NotificationsRepository.startSubscription(userPubkey, inboxUrls, outboxUrls, categoryUrls)
        Log.d(TAG, "Phase 3: notifications started")

        // Outbox feed (NIP-65 discovery for followed users)
        if (followedPubkeys.isNotEmpty() && indexerUrls.isNotEmpty()) {
            NotesRepository.getInstance().startOutboxFeed(followedPubkeys, indexerUrls)
            Log.d(TAG, "Phase 3: outbox feed started (${followedPubkeys.size} followed)")
        }

        // Bookmarks, emoji packs, anchor subs
        BookmarkRepository.fetchBookmarks(userPubkey, allUserRelayUrls)
        EmojiPackRepository.setUserRelays(allUserRelayUrls)
        EmojiPackSelectionRepository.start(userPubkey, allUserRelayUrls)
        Log.d(TAG, "Phase 3: bookmarks + emoji packs started")

        // Self NIP-65
        if (indexerUrls.isNotEmpty()) {
            Nip65RelayListRepository.fetchRelayList(userPubkey, indexerUrls)
        }

        // Profile fetch for self
        scope.launch { ProfileMetadataCache.getInstance().requestProfiles(listOf(userPubkey), indexerUrls) }

        // Enable push notifications after replay settles
        scope.launch {
            delay(4_000L)
            NotificationsRepository.enableAndroidNotifications()
            Log.d(TAG, "Phase 3: push notifications enabled")
        }

        // Auto-advance to Phase 4 after 10s
        scope.launch {
            delay(10_000L)
            if (_currentPhase.value != StartupPhase.BACKGROUND && _currentPhase.value != StartupPhase.COMPLETE) {
                _currentPhase.value = StartupPhase.BACKGROUND
                Log.d(TAG, "Phase 4: background gate opened (10s after enrichment)")
            }
        }
    }

    // ── Phase 4: Background ─────────────────────────────────────────────────

    /**
     * Run Phase 4: background subscriptions (DMs, bg account notifs).
     * Called from MyceliumNavigation after enrichment has settled.
     */
    fun runPhase4Background(
        userPubkey: String,
        signer: NostrSigner,
        inboxUrls: List<String>,
        outboxUrls: List<String>,
        followedPubkeys: Set<String>,
        indexerUrls: List<String>,
    ) {
        _currentPhase.value = StartupPhase.BACKGROUND
        Log.d(TAG, "Phase 4: starting background subscriptions")

        // DMs (fetch-only, no Amber interaction until user visits DM page)
        DirectMessageRepository.startSubscription(userPubkey, signer, inboxUrls, outboxUrls)
        Log.d(TAG, "Phase 4: DM subscription started")

        // Fetch kind-10050 DM relay lists for self + followed network.
        // Results are stored in DirectMessageRepository for the DM system to use.
        scope.launch {
            fetchDmRelayLists(userPubkey, followedPubkeys, inboxUrls + outboxUrls, indexerUrls)
        }

        scope.launch {
            delay(2_000L)
            _currentPhase.value = StartupPhase.COMPLETE
            Log.d(TAG, "Startup complete — all phases done")
        }
    }

    /**
     * Fetch the signed-in user's and their followed peers' kind-10050 DM relay lists.
     * NIP-17 specifies kind-10050 as the relay list specifically for DMs.
     * Results are stored in [DirectMessageRepository] for use when communicating.
     */
    private suspend fun fetchDmRelayLists(
        userPubkey: String,
        followedPubkeys: Set<String>,
        fallbackRelays: List<String>,
        indexerUrls: List<String>,
    ) {
        val availableRelays = (fallbackRelays + indexerUrls).filter { it.startsWith("ws") }.distinct().take(10)
        if (availableRelays.isEmpty()) {
            Log.d(TAG, "Phase 4: no relays for kind-10050 fetch, skipping")
            return
        }

        val allPubkeys = (listOf(userPubkey) + followedPubkeys).distinct()
        Log.d(TAG, "Phase 4: fetching kind-10050 DM relay lists for ${userPubkey.take(8)} and ${followedPubkeys.size} followed")

        allPubkeys.chunked(200).forEach { chunk ->
            val filter = Filter(
                kinds = listOf(10050),
                authors = chunk,
                limit = chunk.size
            )

            val eventsByAuthor = mutableMapOf<String, Event>()
            val settleWaitMs = 1500L
            val lastSeen = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

            try {
                val handle = RelayConnectionStateMachine.getInstance().requestTemporarySubscriptionWithRelay(
                    availableRelays, filter,
                    priority = SubscriptionPriority.LOW
                ) { event, _ ->
                    if (event.kind == 10050 && event.pubKey in chunk) {
                        val current = eventsByAuthor[event.pubKey]
                        if (current == null || event.createdAt > current.createdAt) {
                            eventsByAuthor[event.pubKey] = event
                        }
                        lastSeen.set(System.currentTimeMillis())
                    }
                }
                
                // wait for settle
                var passed = 0L
                while (passed < 10000L) {
                    kotlinx.coroutines.delay(200)
                    passed += 200
                    if (System.currentTimeMillis() - lastSeen.get() > settleWaitMs) {
                        break
                    }
                }
                handle.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Phase 4: kind-10050 batch fetch failed: ${e.message}")
            }

            eventsByAuthor.forEach { (pubkey, event) ->
                val dmRelayUrls = event.tags
                    .filter { it.size >= 2 && it[0] == "relay" }
                    .map { it[1].trim().removeSuffix("/") }
                    .filter { it.startsWith("wss://") || it.startsWith("ws://") }
                    .distinct()

                if (dmRelayUrls.isNotEmpty()) {
                    if (pubkey == userPubkey) {
                        DirectMessageRepository.setDmRelayUrls(dmRelayUrls)
                        Log.d(TAG, "Phase 4: kind-10050 self found — ${dmRelayUrls.size} DM relays")
                    } else {
                        DirectMessageRepository.setPeerDmRelayUrls(pubkey, dmRelayUrls)
                    }
                }
            }
        }
    }

    // ── Phase 5: Deep History ─────────────────────────────────────────────────

    /**
     * Run Phase 5: deep history fetch (LOW priority, background batches).
     * Walks backward through the user's relay history in 30-day windows,
     * persisting events to Room. Resumable across app restarts.
     * Called automatically 5s after Phase 4 completes.
     */
    fun runPhase5DeepHistory(
        context: android.content.Context,
        userPubkey: String,
        followedPubkeys: Set<String>,
        relayUrls: List<String>,
    ) {
        // Guard: if the active account changed since this coroutine was launched
        // (detached scope survives LaunchedEffect cancellation on account switch),
        // skip the deep fetch to avoid fetching history for the wrong account.
        if (activePubkey != userPubkey) {
            Log.w(
                TAG,
                "Phase 5: skipping — account changed (active=${activePubkey?.take(8)}, requested=${userPubkey.take(8)})"
            )
            return
        }
        scope.launch {
            Log.d(TAG, "Phase 5: starting deep history fetch for ${userPubkey.take(8)}")
            DeepHistoryFetcher.start(context, userPubkey, followedPubkeys, relayUrls)
        }
    }
}
