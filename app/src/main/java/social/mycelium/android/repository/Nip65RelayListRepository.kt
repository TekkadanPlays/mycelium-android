package social.mycelium.android.repository

import android.util.Log
import com.example.cybin.relay.SubscriptionPriority
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.RelayHealthTracker
import social.mycelium.android.relay.TemporarySubscriptionHandle
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * NIP-65 Relay List Metadata (kind 10002).
 * Fetches the user's advertised relay list from indexer relays and exposes
 * read/write relay URLs. Used for outbox model: read relays are where
 * the user reads from (inbox), write relays are where the user publishes to (outbox).
 *
 * Also provides dedicated indexer relay URLs for counts subscriptions
 * (kind-7 reactions, kind-9735 zap receipts) so we don't burden the
 * main feed relays with counts filters.
 */
object Nip65RelayListRepository {

    private const val TAG = "Nip65RelayListRepo"
    private const val KIND_RELAY_LIST = 10002
    private const val FETCH_TIMEOUT_MS = 5_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })

    /** Room database for NIP-65 persistence. Set via [init]. */
    @Volatile private var db: social.mycelium.android.db.AppDatabase? = null
    private var roomSaveJob: kotlinx.coroutines.Job? = null
    private const val ROOM_SAVE_DEBOUNCE_MS = 3000L

    /**
     * Initialize Room-backed persistence. Call once from MainActivity.onCreate.
     * Loads cached NIP-65 relay lists so outbox resolution works immediately on cold start.
     */
    fun init(context: android.content.Context) {
        if (db != null) return
        db = social.mycelium.android.db.AppDatabase.getInstance(context.applicationContext)
        scope.launch {
            try {
                val database = db ?: return@launch
                val allEntities = database.nip65Dao().getAll()
                var restored = 0
                for (entity in allEntities) {
                    val pk = entity.pubkey
                    if (!authorOutboxCache.containsKey(pk)) {
                        val writeRelays = entity.writeRelays.split(",").filter { it.isNotBlank() }
                        val readRelays = entity.readRelays.split(",").filter { it.isNotBlank() }
                        if (writeRelays.isNotEmpty() || readRelays.isNotEmpty()) {
                            authorOutboxCache[pk] = writeRelays
                            authorRelayCache[pk] = AuthorRelayList(pk, readRelays, writeRelays)
                            restored++
                        }
                    }
                }
                if (restored > 0) {
                    emitAuthorRelaySnapshot()
                    Log.d(TAG, "Restored $restored NIP-65 relay lists from Room DB")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load NIP-65 from Room: ${e.message}", e)
            }
        }
    }

    /** Debounced save of NIP-65 cache to Room DB. */
    private fun scheduleRoomSave() {
        val database = db ?: return
        roomSaveJob?.cancel()
        roomSaveJob = scope.launch {
            delay(ROOM_SAVE_DEBOUNCE_MS)
            try {
                val snapshot = authorRelayCache.toMap()
                val entities = snapshot.map { (pk, relay) ->
                    social.mycelium.android.db.CachedNip65Entity(
                        pubkey = pk,
                        writeRelays = relay.writeRelays.joinToString(","),
                        readRelays = relay.readRelays.joinToString(",")
                    )
                }
                if (entities.isNotEmpty()) {
                    database.nip65Dao().upsertAll(entities)
                }
                // Prune stale entries
                val pruneMs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                database.nip65Dao().deleteOlderThan(pruneMs)
                Log.d(TAG, "Saved ${entities.size} NIP-65 relay lists to Room DB")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save NIP-65 to Room: ${e.message}", e)
            }
        }
    }

    /** Read relays from the user's kind-10002 (where the user reads / inbox). */
    private val _readRelays = MutableStateFlow<List<String>>(emptyList())
    val readRelays: StateFlow<List<String>> = _readRelays.asStateFlow()

    /** Write relays from the user's kind-10002 (where the user publishes / outbox). */
    private val _writeRelays = MutableStateFlow<List<String>>(emptyList())
    val writeRelays: StateFlow<List<String>> = _writeRelays.asStateFlow()

    /** All relays from the user's kind-10002 (read + write + both). */
    private val _allRelays = MutableStateFlow<List<String>>(emptyList())
    val allRelays: StateFlow<List<String>> = _allRelays.asStateFlow()

    /** Whether we've fetched the relay list at least once for the current user. */
    private val _hasFetched = MutableStateFlow(false)
    val hasFetched: StateFlow<Boolean> = _hasFetched.asStateFlow()

    /** True while a fetch is in-flight (guards against duplicate concurrent fetches). */
    @Volatile private var isFetching = false

    /** Which relay the NIP-65 event was found on (for diagnostics / UI). */
    private val _sourceRelayUrl = MutableStateFlow<String?>(null)
    val sourceRelayUrl: StateFlow<String?> = _sourceRelayUrl.asStateFlow()

    /** created_at of the NIP-65 event (unix timestamp). */
    private val _eventCreatedAt = MutableStateFlow<Long?>(null)
    val eventCreatedAt: StateFlow<Long?> = _eventCreatedAt.asStateFlow()

    // ── Multi-source NIP-65 search (used by onboarding) ──

    /**
     * Result from a single indexer's NIP-65 lookup.
     * Each indexer may return a different version of the user's kind-10002.
     */
    data class Nip65SourceResult(
        val indexerUrl: String,
        val createdAt: Long,
        val writeRelays: List<String>,
        val readRelays: List<String>,
        val allRelays: List<String>,
        val rTagCount: Int,
        /** The raw signed event — kept so we can re-send to outdated relays without re-signing. */
        val rawEvent: Event? = null
    )

    /** Status of a single indexer query during multi-source search. */
    enum class IndexerQueryStatus { PENDING, SUCCESS, NO_DATA, FAILED, TIMEOUT }

    /** Per-indexer query status entry for UI display. */
    data class IndexerQueryState(
        val url: String,
        val status: IndexerQueryStatus,
        val result: Nip65SourceResult? = null,
        val errorMessage: String? = null
    )

    /** Per-indexer results as they arrive — UI can show live progress. */
    private val _multiSourceResults = MutableStateFlow<List<Nip65SourceResult>>(emptyList())
    val multiSourceResults: StateFlow<List<Nip65SourceResult>> = _multiSourceResults.asStateFlow()

    /** Per-indexer query status (pending/success/fail/timeout) for UI. */
    private val _multiSourceStatuses = MutableStateFlow<List<IndexerQueryState>>(emptyList())
    val multiSourceStatuses: StateFlow<List<IndexerQueryState>> = _multiSourceStatuses.asStateFlow()

    /** Whether the multi-source search has completed. */
    private val _multiSourceDone = MutableStateFlow(false)
    val multiSourceDone: StateFlow<Boolean> = _multiSourceDone.asStateFlow()

    /** Number of indexers queried (for progress display). */
    private val _multiSourceTotal = MutableStateFlow(0)
    val multiSourceTotal: StateFlow<Int> = _multiSourceTotal.asStateFlow()

    /** Which pubkey the current multi-source results belong to. */
    @Volatile
    var multiSourcePubkey: String? = null
        private set

    @Volatile
    var currentPubkey: String? = null
        private set
    @Volatile
    private var fetchHandle: TemporarySubscriptionHandle? = null
    @Volatile
    private var multiSourceHandles: List<TemporarySubscriptionHandle> = emptyList()

    /**
     * Get the best indexer relay URLs from NIP-66 discovery.
     * Uses trust signals + geo affinity (NOT monitor RTT, which is misleading).
     * Returns empty list if NIP-66 hasn't loaded yet — callers should wait for NIP-66 data.
     */
    fun getIndexerRelayUrls(limit: Int = 5): List<String> {
        val userCountry = java.util.Locale.getDefault().country.takeIf { it.length == 2 }
        val ranked = Nip66RelayDiscoveryRepository.getRankedIndexers(userCountry, limit)
        if (ranked.isNotEmpty()) {
            Log.d("Nip65Repo", "getIndexerRelayUrls: returning ${ranked.size} ranked indexers (trust+geo):")
            ranked.forEach { r ->
                Log.d("Nip65Repo", "  → ${r.url} country=${r.countryCode} monitors=${r.monitorCount}")
            }
            return ranked.map { it.url }
        }
        Log.d("Nip65Repo", "getIndexerRelayUrls: no discovered relays yet")
        return emptyList()
    }

    /**
     * Get the best relay URLs for counts subscriptions (kind-7, kind-9735).
     * Prefers the user's NIP-65 read relays (where reactions/zaps are sent to them),
     * supplemented by NIP-66 discovered indexer relays for broader coverage.
     */
    fun getCountsRelayUrls(): List<String> {
        val nip65Read = _readRelays.value
        val indexers = getIndexerRelayUrls()
        val combined = (nip65Read + indexers).distinct()
        return if (combined.isNotEmpty()) combined else indexers
    }

    /**
     * Fetch kind-10002 for the given pubkey from indexer relays.
     * Call once after login or when the user changes.
     */
    fun fetchRelayList(pubkeyHex: String, indexerRelayUrls: List<String>) {
        if (indexerRelayUrls.isEmpty()) {
            Log.w(TAG, "No indexer relays to fetch kind-10002")
            return
        }
        // Reset state when switching to a different pubkey
        if (pubkeyHex != currentPubkey) {
            fetchHandle?.cancel()
            fetchHandle = null
            _hasFetched.value = false
            _readRelays.value = emptyList()
            _writeRelays.value = emptyList()
            _allRelays.value = emptyList()
        }
        // Already completed or already in-flight for this pubkey — skip
        if (pubkeyHex == currentPubkey && (_hasFetched.value || isFetching)) {
            Log.d(TAG, "Kind-10002 fetch already ${if (_hasFetched.value) "completed" else "in-flight"} for ${pubkeyHex.take(8)}, skipping")
            return
        }
        currentPubkey = pubkeyHex
        isFetching = true

        scope.launch {
            try {
                Log.d(TAG, "Fetching kind-10002 for ${pubkeyHex.take(8)}... from ${indexerRelayUrls.size} indexer relays")

                val filter = Filter(
                    kinds = listOf(KIND_RELAY_LIST),
                    authors = listOf(pubkeyHex),
                    limit = 1
                )

                var bestEvent: Event? = null
                var bestEventSourceRelay: String? = null
                val lastEventAt = java.util.concurrent.atomic.AtomicLong(0)
                var eventCount = 0
                val handle = RelayConnectionStateMachine.getInstance()
                    .requestTemporarySubscriptionWithRelay(indexerRelayUrls, filter, priority = SubscriptionPriority.BACKGROUND) { event, relayUrl ->
                        if (event.kind == KIND_RELAY_LIST && event.pubKey == pubkeyHex) {
                            eventCount++
                            lastEventAt.set(System.currentTimeMillis())
                            val current = bestEvent
                            if (current == null || event.createdAt > current.createdAt) {
                                bestEvent = event
                                bestEventSourceRelay = relayUrl
                                Log.d(TAG, "Best kind-10002 from $relayUrl: createdAt=${event.createdAt}, rTags=${event.tags.count { it.size >= 2 && it[0] == "r" }}")
                            }
                        }
                    }
                fetchHandle = handle

                // Settle-based wait: break early when stream goes quiet (1s no new events)
                val deadline = System.currentTimeMillis() + FETCH_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    delay(200)
                    val lastAt = lastEventAt.get()
                    if (lastAt > 0) {
                        val quietMs = System.currentTimeMillis() - lastAt
                        if (quietMs >= 1_000L) break
                    }
                }
                handle.cancel()
                fetchHandle = null

                val event = bestEvent
                if (event != null) {
                    parseRelayListEvent(event)
                    _sourceRelayUrl.value = bestEventSourceRelay
                    _eventCreatedAt.value = event.createdAt
                    Log.d(TAG, "Kind-10002 for ${pubkeyHex.take(8)}: ${_writeRelays.value.size} write, ${_readRelays.value.size} read (from $bestEventSourceRelay, $eventCount events)")
                    Log.d(TAG, "  tags: ${event.tags.map { it.toList() }}")
                } else {
                    Log.d(TAG, "No kind-10002 found for ${pubkeyHex.take(8)}..., using fallback indexer relays")
                    _readRelays.value = emptyList()
                    _writeRelays.value = emptyList()
                    _allRelays.value = emptyList()
                }
                _hasFetched.value = true
                isFetching = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch kind-10002: ${e.message}", e)
                _hasFetched.value = true
                isFetching = false
            }
        }
    }

    /**
     * Multi-source NIP-65 search: query each indexer independently and stream
     * per-source results to [multiSourceResults] as they arrive.
     *
     * Unlike [fetchRelayList] which picks the single best event across all relays,
     * this method preserves per-indexer provenance so the UI can show which indexers
     * agree, detect stale copies, and let the user verify before we apply anything.
     *
     * Call from onboarding after NIP-66 indexer discovery. Does NOT mutate the
     * singleton read/write/allRelays state — the caller applies the chosen result
     * explicitly via [applyMultiSourceResult].
     *
     * @param pubkeyHex  The user's hex pubkey
     * @param indexerUrls Indexer relay URLs to query (from NIP-66 discovery)
     * @param timeoutMs  Per-indexer timeout (default 6s)
     */
    /**
     * Max concurrent relay connections. Uses a semaphore so fast relays don't
     * wait for slow ones — as soon as one finishes, the next starts immediately.
     * 20 concurrent connections balances throughput vs network contention.
     */
    private const val MAX_CONCURRENT = 20

    /** Once this many indexers return results, signal early completion. */
    private const val MIN_RESULTS_FOR_EARLY_DONE = 3

    fun fetchRelayListMultiSource(
        pubkeyHex: String,
        indexerUrls: List<String>,
        timeoutMs: Long = 3_000L
    ) {
        if (indexerUrls.isEmpty()) {
            Log.w(TAG, "No indexers for multi-source NIP-65 search")
            _multiSourceDone.value = true
            return
        }

        // Cancel any prior multi-source search
        multiSourceHandles.forEach { it.cancel() }
        multiSourceHandles = emptyList()
        _multiSourceResults.value = emptyList()
        _multiSourceDone.value = false
        _multiSourceTotal.value = indexerUrls.size
        multiSourcePubkey = pubkeyHex

        // Initialize all relays as PENDING
        _multiSourceStatuses.value = indexerUrls.map { url ->
            IndexerQueryState(url, IndexerQueryStatus.PENDING)
        }

        Log.d(TAG, "Multi-source NIP-65 search for ${pubkeyHex.take(8)} across ${indexerUrls.size} indexers (concurrency=$MAX_CONCURRENT, timeout=${timeoutMs}ms)")

        val filter = Filter(
            kinds = listOf(KIND_RELAY_LIST),
            authors = listOf(pubkeyHex),
            limit = 1
        )

        // Semaphore-based rolling concurrency: launch ALL relays immediately but
        // only MAX_CONCURRENT run at a time. As soon as one relay finishes (success,
        // no-data, fail, or timeout), the next one starts. No batch boundaries means
        // fast relays never wait for slow ones in the same batch.
        scope.launch {
            val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT)
            val earlyDone = kotlinx.coroutines.CompletableDeferred<Unit>()
            val jobs = indexerUrls.map { indexerUrl ->
                scope.launch {
                    semaphore.acquire()
                    try {
                        queryOneIndexer(pubkeyHex, indexerUrl, filter, timeoutMs)
                        // Check for early completion after each successful result
                        if (_multiSourceResults.value.size >= MIN_RESULTS_FOR_EARLY_DONE) {
                            earlyDone.complete(Unit)
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }

            // Race: wait for ALL relays OR early completion (enough results arrived)
            scope.launch {
                jobs.forEach { it.join() }
                earlyDone.complete(Unit) // all done naturally
            }
            earlyDone.await()

            // Cancel remaining in-flight jobs
            jobs.forEach { it.cancel() }

            // Mark any still-PENDING as TIMEOUT
            synchronized(_multiSourceStatuses) {
                _multiSourceStatuses.value = _multiSourceStatuses.value.map { state ->
                    if (state.status == IndexerQueryStatus.PENDING)
                        state.copy(status = IndexerQueryStatus.TIMEOUT)
                    else state
                }
            }

            // Cancel all subscription handles
            synchronized(multiSourceHandles) {
                multiSourceHandles.forEach { it.cancel() }
                multiSourceHandles = emptyList()
            }

            // CRITICAL: Disconnect ALL relay connections. The relay pool
            // keeps WebSocket connections alive even after subscriptions
            // are destroyed. Without this full disconnect, hundreds of indexer
            // connections persist and bleed into feed/notification subscriptions.
            // The feed will re-establish only the connections it actually needs.
            RelayConnectionStateMachine.getInstance().requestDisconnect()

            _multiSourceDone.value = true
            Log.d(TAG, "Multi-source search complete: ${_multiSourceResults.value.size}/${indexerUrls.size} indexers returned results (early=${_multiSourceResults.value.size >= MIN_RESULTS_FOR_EARLY_DONE})")
        }
    }

    /**
     * Query a single indexer relay for kind-10002. Cancels the subscription
     * immediately when an event arrives — no polling, no lingering connections.
     * Uses CompletableDeferred for instant signal on first event.
     */
    private suspend fun queryOneIndexer(
        pubkeyHex: String,
        indexerUrl: String,
        filter: Filter,
        timeoutMs: Long
    ) {
        try {
            var bestEvent: Event? = null
            val gotEvent = kotlinx.coroutines.CompletableDeferred<Unit>()
            val startTime = System.currentTimeMillis()
            RelayHealthTracker.recordConnectionAttempt(indexerUrl)

            val handle = RelayConnectionStateMachine.getInstance()
                .requestTemporarySubscriptionWithRelay(listOf(indexerUrl), filter, priority = SubscriptionPriority.LOW) { event, _ ->
                    if (event.kind == KIND_RELAY_LIST && event.pubKey == pubkeyHex) {
                        val current = bestEvent
                        if (current == null || event.createdAt > current.createdAt) {
                            bestEvent = event
                        }
                        // Signal immediately — don't wait for more events
                        gotEvent.complete(Unit)
                    }
                }
            synchronized(multiSourceHandles) {
                multiSourceHandles = multiSourceHandles + handle
            }

            // Wait for event OR timeout — whichever comes first
            try {
                kotlinx.coroutines.withTimeout(timeoutMs) {
                    gotEvent.await()
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                // Timeout — no event arrived
            }

            // Immediately disconnect this indexer relay
            handle.cancel()
            val elapsed = System.currentTimeMillis() - startTime

            val event = bestEvent
            if (event != null) {
                RelayHealthTracker.recordConnectionSuccess(indexerUrl)
                val result = parseEventToSourceResult(event, indexerUrl)
                synchronized(_multiSourceResults) {
                    _multiSourceResults.value = _multiSourceResults.value + result
                }
                updateIndexerStatus(indexerUrl, IndexerQueryStatus.SUCCESS, result = result)
                Log.d(TAG, "  $indexerUrl: ${result.writeRelays.size}w/${result.readRelays.size}r, created=${result.createdAt} (${elapsed}ms)")
            } else {
                // Check if the relay actually connected or had a connection error.
                // RelayHealthTracker records failures with the actual error message.
                val health = RelayHealthTracker.getHealth(indexerUrl)
                val lastError = health?.lastError
                val failedDuringQuery = health != null && health.lastFailedAt >= startTime
                if (failedDuringQuery && lastError != null) {
                    // Relay had a connection error — report it as FAILED with the real reason
                    val shortError = lastError.take(80)
                    RelayHealthTracker.recordConnectionFailure(indexerUrl, shortError)
                    updateIndexerStatus(indexerUrl, IndexerQueryStatus.FAILED, errorMessage = shortError)
                    Log.d(TAG, "  $indexerUrl: connect failed: $shortError (${elapsed}ms)")
                } else if (elapsed >= timeoutMs - 500) {
                    // Timed out without connecting or receiving data
                    RelayHealthTracker.recordConnectionFailure(indexerUrl, "Timeout")
                    updateIndexerStatus(indexerUrl, IndexerQueryStatus.TIMEOUT)
                    Log.d(TAG, "  $indexerUrl: timeout (${elapsed}ms)")
                } else {
                    // Relay connected but genuinely has no kind-10002 for this pubkey
                    updateIndexerStatus(indexerUrl, IndexerQueryStatus.NO_DATA)
                    Log.d(TAG, "  $indexerUrl: no data (${elapsed}ms)")
                }
            }
        } catch (e: Exception) {
            val isCancellation = e is kotlinx.coroutines.CancellationException
            if (!isCancellation) {
                RelayHealthTracker.recordConnectionFailure(indexerUrl, e.message)
                updateIndexerStatus(indexerUrl, IndexerQueryStatus.FAILED, errorMessage = e.message)
                Log.e(TAG, "  $indexerUrl: failed: ${e.message}")
            }
        }
    }

    /** Update the status of a single indexer in the statuses list. */
    private fun updateIndexerStatus(
        url: String,
        status: IndexerQueryStatus,
        result: Nip65SourceResult? = null,
        errorMessage: String? = null
    ) {
        synchronized(_multiSourceStatuses) {
            _multiSourceStatuses.value = _multiSourceStatuses.value.map { state ->
                if (state.url == url) IndexerQueryState(url, status, result, errorMessage)
                else state
            }
        }
    }

    /**
     * Re-query a single indexer that previously returned no data, failed, or timed out.
     * Updates the statuses and results flows in-place. Safe to call while a search is
     * in progress or after it completes — does not interfere with the main search.
     */
    fun rePingIndexer(indexerUrl: String) {
        val pubkey = multiSourcePubkey ?: return
        updateIndexerStatus(indexerUrl, IndexerQueryStatus.PENDING)
        val filter = Filter(
            kinds = listOf(KIND_RELAY_LIST),
            authors = listOf(pubkey),
            limit = 1
        )
        scope.launch {
            queryOneIndexer(pubkey, indexerUrl, filter, 6_000L)
        }
    }

    /**
     * Re-query all indexers that returned NO_DATA, FAILED, or TIMEOUT.
     * Useful as a "retry all" action after the initial search completes.
     */
    fun rePingAllFailed() {
        val statuses = _multiSourceStatuses.value
        val retryable = statuses.filter {
            it.status in setOf(IndexerQueryStatus.NO_DATA, IndexerQueryStatus.FAILED, IndexerQueryStatus.TIMEOUT)
        }
        if (retryable.isEmpty()) return
        Log.d(TAG, "Re-pinging ${retryable.size} failed/no-data indexers")
        retryable.forEach { rePingIndexer(it.url) }
    }

    /**
     * Apply a chosen multi-source result to the singleton state (read/write/allRelays).
     * Call after the user has reviewed and confirmed the relay configuration.
     */
    fun applyMultiSourceResult(result: Nip65SourceResult) {
        _writeRelays.value = result.writeRelays
        _readRelays.value = result.readRelays
        _allRelays.value = result.allRelays
        _sourceRelayUrl.value = result.indexerUrl
        _eventCreatedAt.value = result.createdAt
        _hasFetched.value = true
        Log.d(TAG, "Applied NIP-65 from ${result.indexerUrl}: ${result.writeRelays.size}w/${result.readRelays.size}r")
    }

    /**
     * Re-send an already-signed NIP-65 event to relays that have outdated versions.
     * No re-signing needed — the event was already signed by the user's key.
     *
     * @param correctResult The result with the latest/correct event (must have rawEvent)
     * @param outdatedRelayUrls The relay URLs that have stale versions
     * @return Map of relay URL → success/failure
     */
    fun publishToOutdatedRelays(
        correctResult: Nip65SourceResult,
        outdatedRelayUrls: List<String>
    ): Map<String, Boolean> {
        val event = correctResult.rawEvent
        if (event == null) {
            Log.e(TAG, "Cannot publish to outdated relays: no raw event available")
            return outdatedRelayUrls.associateWith { false }
        }
        if (outdatedRelayUrls.isEmpty()) {
            Log.w(TAG, "No outdated relays to publish to")
            return emptyMap()
        }

        Log.d(TAG, "Publishing NIP-65 event ${event.id.take(8)} (created_at=${event.createdAt}) to ${outdatedRelayUrls.size} outdated relays")
        val results = mutableMapOf<String, Boolean>()

        // Send to each relay individually so we can track per-relay success/failure
        outdatedRelayUrls.forEach { url ->
            try {
                val normalized = com.example.cybin.relay.RelayUrlNormalizer.normalizeOrNull(url)
                if (normalized != null) {
                    RelayConnectionStateMachine.getInstance().send(event, setOf(normalized.url))
                    results[url] = true
                    Log.d(TAG, "  ✓ Sent to $url")
                } else {
                    results[url] = false
                    Log.w(TAG, "  ✗ Invalid URL: $url")
                }
            } catch (e: Exception) {
                results[url] = false
                Log.e(TAG, "  ✗ Failed to send to $url: ${e.message}")
            }
        }

        val ok = results.count { it.value }
        val fail = results.count { !it.value }
        Log.d(TAG, "Publish complete: $ok sent, $fail failed")
        return results
    }

    /** Parse a kind-10002 event into a [Nip65SourceResult] without mutating singleton state. */
    private fun parseEventToSourceResult(event: Event, sourceUrl: String): Nip65SourceResult {
        val readUrls = mutableListOf<String>()
        val writeUrls = mutableListOf<String>()
        val allUrls = mutableListOf<String>()

        event.tags.forEach { tag ->
            if (tag.size >= 2 && tag[0] == "r" && tag[1].isNotBlank()) {
                val url = tag[1].trim()
                val marker = tag.getOrNull(2)?.lowercase()?.trim()
                allUrls.add(url)
                when (marker) {
                    "read" -> readUrls.add(url)
                    "write" -> writeUrls.add(url)
                    else -> { readUrls.add(url); writeUrls.add(url) }
                }
            }
        }

        return Nip65SourceResult(
            indexerUrl = sourceUrl,
            createdAt = event.createdAt,
            writeRelays = writeUrls.distinct(),
            readRelays = readUrls.distinct(),
            allRelays = allUrls.distinct(),
            rTagCount = event.tags.count { it.size >= 2 && it[0] == "r" },
            rawEvent = event
        )
    }

    /**
     * Parse a kind-10002 event's "r" tags into read/write relay lists.
     * Tag format: ["r", "wss://relay.example.com", "read"|"write"|""]
     * No marker or empty = both read and write.
     */
    private fun parseRelayListEvent(event: Event) {
        val readUrls = mutableListOf<String>()
        val writeUrls = mutableListOf<String>()
        val allUrls = mutableListOf<String>()

        event.tags.forEach { tag ->
            if (tag.size >= 2 && tag[0] == "r" && tag[1].isNotBlank()) {
                val url = tag[1].trim()
                val marker = tag.getOrNull(2)?.lowercase()?.trim()
                allUrls.add(url)
                when (marker) {
                    "read" -> readUrls.add(url)
                    "write" -> writeUrls.add(url)
                    else -> {
                        // No marker = both
                        readUrls.add(url)
                        writeUrls.add(url)
                    }
                }
            }
        }

        _readRelays.value = readUrls.distinct()
        _writeRelays.value = writeUrls.distinct()
        _allRelays.value = allUrls.distinct()
    }

    // --- Per-author relay list cache (read + write) for relay directory ---

    /** Full relay list (read + write) for an author. */
    data class AuthorRelayList(
        val pubkey: String,
        val readRelays: List<String>,
        val writeRelays: List<String>
    )

    /** Cache of other authors' full relay lists (read + write). LRU, max 500 entries. */
    private val authorRelayCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, AuthorRelayList>(500, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AuthorRelayList>): Boolean = size > 500
        }
    )

    /** Observable snapshot of all cached author relay lists for UI. */
    private val _authorRelaySnapshot = MutableStateFlow<Map<String, AuthorRelayList>>(emptyMap())
    val authorRelaySnapshot: StateFlow<Map<String, AuthorRelayList>> = _authorRelaySnapshot.asStateFlow()

    /** Get cached full relay list for an author, or null if not yet fetched. */
    fun getCachedAuthorRelays(pubkeyHex: String): AuthorRelayList? = authorRelayCache[pubkeyHex]

    /** Get cached inbox (read) relays for an author, or null if not yet fetched. */
    fun getCachedInboxRelays(pubkeyHex: String): List<String>? = authorRelayCache[pubkeyHex]?.readRelays

    /** Get all cached author relay lists (snapshot). */
    fun getAllCachedAuthorRelays(): Map<String, AuthorRelayList> = authorRelayCache.toMap()

    // --- Outbox relay lookup for other authors (quoted note preloading) ---

    /** Cache of other authors' write (outbox) relays. LRU, max 500 entries. */
    private val authorOutboxCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, List<String>>(500, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean = size > 500
        }
    )

    /** Debounced batch state for individual outbox lookups. */
    private val pendingOutboxPubkeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var pendingOutboxRelays: List<String> = emptyList()
    private var outboxBatchJob: kotlinx.coroutines.Job? = null
    private val OUTBOX_BATCH_DEBOUNCE_MS = 300L

    /**
     * Get cached outbox (write) relays for an author, or null if not yet fetched.
     */
    fun getCachedOutboxRelays(pubkeyHex: String): List<String>? = authorOutboxCache[pubkeyHex]

    /**
     * Queue an author's outbox relay lookup into a debounced batch.
     * Instead of creating one subscription per author (which floods relays),
     * pubkeys are accumulated and flushed as a single batched subscription
     * after a short debounce window.
     */
    fun fetchOutboxRelaysForAuthor(pubkeyHex: String, discoveryRelays: List<String>) {
        if (pubkeyHex.isBlank() || discoveryRelays.isEmpty()) return
        if (authorOutboxCache.containsKey(pubkeyHex)) return
        if (!pendingOutboxPubkeys.add(pubkeyHex)) return // already pending

        if (discoveryRelays.isNotEmpty()) pendingOutboxRelays = discoveryRelays
        scheduleOutboxBatchFlush()
    }

    private fun scheduleOutboxBatchFlush() {
        outboxBatchJob?.cancel()
        outboxBatchJob = scope.launch {
            delay(OUTBOX_BATCH_DEBOUNCE_MS)
            flushOutboxBatch()
        }
    }

    private suspend fun flushOutboxBatch() {
        val pubkeys = pendingOutboxPubkeys.toList()
        val relays = pendingOutboxRelays
        pendingOutboxPubkeys.clear()
        if (pubkeys.isEmpty() || relays.isEmpty()) return

        // Filter out already-cached
        val needed = pubkeys.filter { !authorOutboxCache.containsKey(it) }
        if (needed.isEmpty()) return

        Log.d(TAG, "Outbox batch flush: ${needed.size} pubkeys on ${relays.size} relays")

        // Fetch in chunks of 50 to stay within relay filter size limits
        needed.chunked(50).forEachIndexed { chunkIdx, chunk ->
            try {
                val filter = Filter(
                    kinds = listOf(KIND_RELAY_LIST),
                    authors = chunk,
                    limit = chunk.size
                )
                val collected = java.util.concurrent.ConcurrentHashMap<String, Event>()
                val lastEventAt = java.util.concurrent.atomic.AtomicLong(0)
                val handle = RelayConnectionStateMachine.getInstance()
                    .requestTemporarySubscription(relays, filter, priority = SubscriptionPriority.BACKGROUND) { event ->
                        if (event.kind == KIND_RELAY_LIST) {
                            val existing = collected[event.pubKey]
                            if (existing == null || event.createdAt > existing.createdAt) {
                                collected[event.pubKey] = event
                            }
                            lastEventAt.set(System.currentTimeMillis())
                        }
                    }

                // Settle-based wait: break early when stream goes quiet
                val deadline = System.currentTimeMillis() + BATCH_CHUNK_MAX_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    delay(200)
                    val lastAt = lastEventAt.get()
                    if (lastAt > 0) {
                        val quietMs = System.currentTimeMillis() - lastAt
                        if (quietMs >= BATCH_CHUNK_SETTLE_MS) break
                    }
                    if (collected.size >= chunk.size) break
                }
                handle.cancel()

                // Parse results
                chunk.forEach { pk ->
                    val event = collected[pk]
                    if (event != null) {
                        val writeUrls = mutableListOf<String>()
                        val readUrls = mutableListOf<String>()
                        event.tags.forEach { tag ->
                            if (tag.size >= 2 && tag[0] == "r" && tag[1].isNotBlank()) {
                                val url = tag[1].trim()
                                val marker = tag.getOrNull(2)?.lowercase()?.trim()
                                when (marker) {
                                    "write" -> writeUrls.add(url)
                                    "read" -> readUrls.add(url)
                                    null, "" -> { writeUrls.add(url); readUrls.add(url) }
                                }
                            }
                        }
                        authorOutboxCache[pk] = writeUrls.distinct()
                        authorRelayCache[pk] = AuthorRelayList(pk, readUrls.distinct(), writeUrls.distinct())
                        Log.d(TAG, "Relays for ${pk.take(8)}: ${writeUrls.size} write, ${readUrls.size} read")
                        scheduleRoomSave()
                    } else {
                        authorOutboxCache[pk] = emptyList()
                        authorRelayCache[pk] = AuthorRelayList(pk, emptyList(), emptyList())
                        Log.d(TAG, "No kind-10002 for ${pk.take(8)}")
                    }
                }
                emitAuthorRelaySnapshot()
            } catch (e: Exception) {
                Log.e(TAG, "Outbox batch chunk $chunkIdx failed: ${e.message}")
                chunk.forEach { pk -> authorOutboxCache.putIfAbsent(pk, emptyList()) }
            }
        }
    }

    /**
     * Batch-fetch kind-10002 for a list of pubkeys (e.g. the user's follow list).
     * Fetches in chunks to avoid overwhelming relays. Results populate authorRelayCache.
     */
    /** Max time to wait per batch chunk for kind-10002 events. */
    private const val BATCH_CHUNK_MAX_TIMEOUT_MS = 8_000L
    /** After last event arrives, wait this long for more before declaring chunk done. */
    private const val BATCH_CHUNK_SETTLE_MS = 800L

    fun batchFetchRelayLists(pubkeys: List<String>, discoveryRelays: List<String>) {
        if (pubkeys.isEmpty() || discoveryRelays.isEmpty()) return
        val uncached = pubkeys.filter { !authorRelayCache.containsKey(it) }
        if (uncached.isEmpty()) {
            Log.d(TAG, "All ${pubkeys.size} pubkeys already cached")
            return
        }
        Log.d(TAG, "Batch-fetching kind-10002 for ${uncached.size} pubkeys (${pubkeys.size - uncached.size} already cached)")
        // Process chunks sequentially — each chunk settles before the next starts,
        // so fast relays don't wait for slow ones across chunks.
        scope.launch {
            uncached.chunked(50).forEachIndexed { chunkIdx, chunk ->
                try {
                    val filter = Filter(
                        kinds = listOf(KIND_RELAY_LIST),
                        authors = chunk,
                        limit = chunk.size
                    )
                    val collected = java.util.concurrent.ConcurrentHashMap<String, Event>()
                    val lastEventAt = java.util.concurrent.atomic.AtomicLong(0)
                    val handle = RelayConnectionStateMachine.getInstance()
                        .requestTemporarySubscription(discoveryRelays, filter, priority = SubscriptionPriority.BACKGROUND) { event ->
                            if (event.kind == KIND_RELAY_LIST) {
                                val existing = collected[event.pubKey]
                                if (existing == null || event.createdAt > existing.createdAt) {
                                    collected[event.pubKey] = event
                                }
                                lastEventAt.set(System.currentTimeMillis())
                            }
                        }

                    // Settle-based wait: break early when stream goes quiet
                    val deadline = System.currentTimeMillis() + BATCH_CHUNK_MAX_TIMEOUT_MS
                    while (System.currentTimeMillis() < deadline) {
                        delay(200)
                        val lastAt = lastEventAt.get()
                        if (lastAt > 0) {
                            val quietMs = System.currentTimeMillis() - lastAt
                            if (quietMs >= BATCH_CHUNK_SETTLE_MS) break
                        }
                        // Early exit: all pubkeys in this chunk have been resolved
                        if (collected.size >= chunk.size) break
                    }
                    handle.cancel()

                    // Parse results
                    var found = 0
                    chunk.forEach { pk ->
                        val event = collected[pk]
                        if (event != null) {
                            val writeUrls = mutableListOf<String>()
                            val readUrls = mutableListOf<String>()
                            event.tags.forEach { tag ->
                                if (tag.size >= 2 && tag[0] == "r" && tag[1].isNotBlank()) {
                                    val url = tag[1].trim()
                                    val marker = tag.getOrNull(2)?.lowercase()?.trim()
                                    when (marker) {
                                        "write" -> writeUrls.add(url)
                                        "read" -> readUrls.add(url)
                                        null, "" -> { writeUrls.add(url); readUrls.add(url) }
                                    }
                                }
                            }
                            authorRelayCache[pk] = AuthorRelayList(pk, readUrls.distinct(), writeUrls.distinct())
                            authorOutboxCache[pk] = writeUrls.distinct()
                            found++
                        } else {
                            authorRelayCache[pk] = AuthorRelayList(pk, emptyList(), emptyList())
                        }
                    }
                    emitAuthorRelaySnapshot()
                    Log.d(TAG, "Batch chunk $chunkIdx: found kind-10002 for $found/${chunk.size} pubkeys (settled in ${System.currentTimeMillis() - (deadline - BATCH_CHUNK_MAX_TIMEOUT_MS)}ms)")
                } catch (e: Exception) {
                    Log.e(TAG, "Batch fetch chunk $chunkIdx failed: ${e.message}")
                }
            }
        }
    }

    private fun emitAuthorRelaySnapshot() {
        _authorRelaySnapshot.value = authorRelayCache.toMap()
    }

    /**
     * Publish a NIP-65 kind-10002 event with the user's relay list.
     *
     * Per the spec:
     * - Relays in both outbox AND inbox → r tag with NO marker (both read+write)
     * - Outbox-only relays → r tag with "write" marker
     * - Inbox-only relays → r tag with "read" marker
     * - content is empty string
     *
     * Publishes to all configured relays + indexer relays for discoverability.
     */
    suspend fun publishNip65(
        context: android.content.Context,
        outboxUrls: List<String>,
        inboxUrls: List<String>,
        signer: com.example.cybin.signer.NostrSigner
    ) {
        val outboxSet = outboxUrls.map { it.trim().removeSuffix("/") }.toSet()
        val inboxSet = inboxUrls.map { it.trim().removeSuffix("/") }.toSet()
        val bothSet = outboxSet.intersect(inboxSet)
        val writeOnly = outboxSet - bothSet
        val readOnly = inboxSet - bothSet

        // Build all relay URLs to publish to (outbox + inbox + indexers for discoverability)
        val publishRelays = (outboxSet + inboxSet + getIndexerRelayUrls()).toSet()

        val result = social.mycelium.android.services.EventPublisher.publish(
            context = context,
            signer = signer,
            relayUrls = publishRelays,
            kind = 10002,
            content = "",
            tags = {
                // Both read+write — no marker
                bothSet.forEach { url -> add(arrayOf("r", url)) }
                // Write-only (outbox)
                writeOnly.forEach { url -> add(arrayOf("r", url, "write")) }
                // Read-only (inbox)
                readOnly.forEach { url -> add(arrayOf("r", url, "read")) }
            }
        )

        when (result) {
            is social.mycelium.android.services.PublishResult.Success ->
                Log.d(TAG, "NIP-65 published: ${result.eventId.take(8)} (${bothSet.size} both, ${writeOnly.size} write, ${readOnly.size} read)")
            is social.mycelium.android.services.PublishResult.Error ->
                Log.e(TAG, "NIP-65 publish failed: ${result.message}")
        }
    }

    /**
     * Supplement the author outbox cache with relay hints extracted from event e-tags.
     * Only adds data for authors that don't already have NIP-65 outbox relays —
     * hints are weaker than kind-10002 data and should not overwrite it.
     * Called from convertEventToNote for p-tags that include relay hints.
     * O(1) per call, no network requests.
     */
    fun addRelayHintsForAuthor(pubkeyHex: String, relayUrls: List<String>) {
        if (pubkeyHex.isBlank() || relayUrls.isEmpty()) return
        // Don't overwrite authoritative NIP-65 data with weaker hints
        val existing = authorOutboxCache[pubkeyHex]
        if (existing != null && existing.isNotEmpty()) return
        authorOutboxCache[pubkeyHex] = relayUrls.distinct()
    }

    /**
     * Clear state (e.g. on logout or account switch).
     */
    fun clear() {
        fetchHandle?.cancel()
        fetchHandle = null
        isFetching = false
        multiSourceHandles.forEach { it.cancel() }
        multiSourceHandles = emptyList()
        _multiSourceResults.value = emptyList()
        _multiSourceDone.value = false
        _multiSourceTotal.value = 0
        multiSourcePubkey = null
        currentPubkey = null
        _readRelays.value = emptyList()
        _writeRelays.value = emptyList()
        _allRelays.value = emptyList()
        _hasFetched.value = false
        _sourceRelayUrl.value = null
        _eventCreatedAt.value = null
        authorRelayCache.clear()
        _authorRelaySnapshot.value = emptyMap()
    }
}
