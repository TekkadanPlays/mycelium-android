package social.mycelium.android.relay

import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.CybinRelayPool
import com.example.cybin.relay.CybinSubscription
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import social.mycelium.android.debug.PipelineDiagnostics

/**
 * Best-in-class Flow-based subscription multiplexer for Nostr.
 *
 * Sits on top of [CybinRelayPool] and provides:
 * - **Filter merging**: identical filters from multiple consumers share one relay subscription
 * - **Ref-counting**: relay subscription is CLOSEd only when the last consumer unsubscribes
 * - **Debounced REQ flush**: rapid subscription changes are batched (50ms debounce)
 * - **EOSE tracking**: consumers can observe when stored events are exhausted
 * - **Flow-based event emission**: each subscription returns a `Flow<Event>`
 * - **Deduplication**: events are deduped by event ID across relays
 * - **Backpressure handling**: uses SharedFlow with configurable buffer
 *
 * Architecture:
 * ```
 * UI / Feature
 *      ↓
 * SubscriptionMultiplexer (filter merge, ref-count, debounce)
 *      ↓
 * CybinRelayPool (per-relay scheduling, priority preemption, reconnect)
 *      ↓
 * RelayConnection (Ktor WebSocket)
 * ```
 */
class SubscriptionMultiplexer private constructor(
    private val pool: CybinRelayPool,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    // ── Core state ──────────────────────────────────────────────────────────

    /** All active consumer subscriptions keyed by consumer handle ID. */
    private val consumers = ConcurrentHashMap<String, ConsumerSubscription>()

    /** Merged relay subscriptions keyed by [FilterKey] (hash of merged filter + relays). */
    private val mergedSubs = ConcurrentHashMap<FilterKey, MergedSubscription>()

    /** Global event flow — used only by Flow-based subscribe() consumers. */
    private val _events = MutableSharedFlow<MultiplexedEvent>(extraBufferCapacity = 500)

    /**
     * Direct callback dispatch: filterKey → list of callbacks.
     * handleEvent dispatches directly to registered callbacks via O(1) lookup
     * instead of broadcasting to a global flow filtered by every consumer.
     */
    private val eventCallbacks = ConcurrentHashMap<FilterKey, MutableList<(Event, String) -> Unit>>()

    /** Max dedup entries per merged subscription. */
    private val maxSeenIdsPerSub = 10_000

    /** EOSE tracking: set of consumer IDs that have received EOSE. */
    private val _eoseReceived = MutableStateFlow<Set<String>>(emptySet())
    val eoseReceived: StateFlow<Set<String>> = _eoseReceived.asStateFlow()

    /**
     * Per-merged-sub EOSE tracking: maps CybinSubscription ID → set of relay URLs
     * that have sent EOSE. When all relays for a merged sub have EOSE'd, all its
     * consumers are marked as EOSE'd.
     */
    private val eoseByRelay = ConcurrentHashMap<String, MutableSet<String>>()

    /** Reverse lookup: CybinSubscription ID → FilterKey for O(1) EOSE dispatch. */
    private val cybinSubToFilterKey = ConcurrentHashMap<String, FilterKey>()

    /** Debounce job for batching REQ changes. */
    private var flushJob: Job? = null
    private val pendingChanges = MutableStateFlow(0L) // monotonic counter to trigger flush

    init {
        // Register EOSE listener on the pool to track per-relay EOSE for merged subs
        pool.addListener(object : com.example.cybin.relay.RelayConnectionListener {
            override fun onEose(url: String, subscriptionId: String) {
                handleEose(subscriptionId, url)
            }
        })
    }

    /** Consumer ID generator. */
    private val nextConsumerId = AtomicLong(0)

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Subscribe to events matching [filters] on [relayUrls].
     *
     * Returns a [MultiplexHandle] with:
     * - `events: Flow<Event>` — filtered event stream for this consumer
     * - `eose: Flow<Boolean>` — emits true when EOSE is received
     * - `cancel()` — unsubscribe (decrements ref-count, CLOSEs relay sub when last)
     *
     * If another consumer already has an identical filter+relay combination,
     * this consumer piggybacks on the existing relay subscription (ref-counted).
     */
    suspend fun subscribe(
        relayUrls: List<String>,
        filters: List<Filter>,
        priority: SubscriptionPriority = SubscriptionPriority.NORMAL,
        isOneShot: Boolean = false,
    ): MultiplexHandle {
        val consumerId = "mux-${nextConsumerId.incrementAndGet()}"
        val filterKey = FilterKey.of(relayUrls, filters)

        mutex.withLock {
            val consumer = ConsumerSubscription(
                id = consumerId,
                filterKey = filterKey,
                relayUrls = relayUrls,
                filters = filters,
                priority = priority,
            )
            consumers[consumerId] = consumer

            // Find or create merged subscription
            val merged = mergedSubs.getOrPut(filterKey) {
                MergedSubscription(
                    filterKey = filterKey,
                    relayUrls = relayUrls,
                    filters = filters,
                    priority = priority,
                    isOneShot = isOneShot,
                )
            }
            merged.consumerIds.add(consumerId)
            merged.flowConsumerIds.add(consumerId) // Flow-based consumer needs SharedFlow emission

            // Upgrade priority if this consumer is higher
            if (priority.level > merged.priority.level) {
                merged.priority = priority
                merged.needsRefresh = true
            }

            scheduleDebouncedFlush()
        }

        val eventFlow = _events
            .filter { it.consumerId == consumerId || it.filterKey == filterKey }
            .let { flow ->
                // Map to just the Event
                object : Flow<Event> {
                    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Event>) {
                        flow.collect { collector.emit(it.event) }
                    }
                }
            }

        val eoseFlow = _eoseReceived
            .let { stateFlow ->
                object : Flow<Boolean> {
                    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Boolean>) {
                        stateFlow.collect { set -> collector.emit(consumerId in set) }
                    }
                }
            }

        return MultiplexHandle(
            id = consumerId,
            events = eventFlow,
            eose = eoseFlow,
            onCancel = { unsubscribe(consumerId) },
        )
    }

    /**
     * Convenience: subscribe with a single filter.
     */
    suspend fun subscribe(
        relayUrls: List<String>,
        filter: Filter,
        priority: SubscriptionPriority = SubscriptionPriority.NORMAL,
        isOneShot: Boolean = false,
    ): MultiplexHandle = subscribe(relayUrls, listOf(filter), priority, isOneShot)

    /**
     * Callback-based subscribe for incremental migration from direct relay subscriptions.
     * Events are forwarded to [onEvent] as they arrive (deduped, ref-counted).
     * Returns a [TemporarySubscriptionHandle] compatible with existing cancel/pause patterns.
     */
    fun subscribeCallback(
        relayUrls: List<String>,
        filters: List<Filter>,
        priority: SubscriptionPriority = SubscriptionPriority.NORMAL,
        onEvent: (Event) -> Unit,
        onEose: (() -> Unit)? = null,
    ): TemporarySubscriptionHandle {
        val consumerId = "mux-cb-${nextConsumerId.incrementAndGet()}"
        val filterKey = FilterKey.of(relayUrls, filters)
        // Register direct callback for O(1) dispatch
        val wrappedCb: (Event, String) -> Unit = { event, _ -> onEvent(event) }
        eventCallbacks.getOrPut(filterKey) { java.util.concurrent.CopyOnWriteArrayList() }.add(wrappedCb)
        var eoseJob: Job? = null
        val job = scope.launch {
            mutex.withLock {
                val consumer = ConsumerSubscription(consumerId, filterKey, relayUrls, filters, priority)
                consumers[consumerId] = consumer
                val merged = mergedSubs.getOrPut(filterKey) {
                    MergedSubscription(filterKey, relayUrls, filters, priority)
                }
                merged.consumerIds.add(consumerId)
                if (priority.level > merged.priority.level) {
                    merged.priority = priority; merged.needsRefresh = true
                }
                scheduleDebouncedFlush()
            }
            if (onEose != null) {
                eoseJob = scope.launch {
                    _eoseReceived.first { consumerId in it }
                    onEose()
                }
            }
        }
        return object : TemporarySubscriptionHandle {
            override fun cancel() {
                eoseJob?.cancel()
                job.cancel()
                eventCallbacks[filterKey]?.remove(wrappedCb)
                scope.launch { unsubscribe(consumerId) }
            }
            override fun pause() {}
            override fun resume() {}
            override val isPaused: Boolean get() = false
        }
    }

    /**
     * Callback-based subscribe with relay URL passthrough.
     * Use when the caller needs to know which relay delivered each event.
     */
    fun subscribeCallbackWithRelay(
        relayUrls: List<String>,
        filters: List<Filter>,
        priority: SubscriptionPriority = SubscriptionPriority.NORMAL,
        onEvent: (Event, String) -> Unit,
    ): TemporarySubscriptionHandle {
        val consumerId = "mux-cbr-${nextConsumerId.incrementAndGet()}"
        val filterKey = FilterKey.of(relayUrls, filters)
        // Register direct callback for O(1) dispatch
        eventCallbacks.getOrPut(filterKey) { java.util.concurrent.CopyOnWriteArrayList() }.add(onEvent)
        scope.launch {
            mutex.withLock {
                val consumer = ConsumerSubscription(consumerId, filterKey, relayUrls, filters, priority)
                consumers[consumerId] = consumer
                val merged = mergedSubs.getOrPut(filterKey) {
                    MergedSubscription(filterKey, relayUrls, filters, priority)
                }
                merged.consumerIds.add(consumerId)
                if (priority.level > merged.priority.level) {
                    merged.priority = priority; merged.needsRefresh = true
                }
                scheduleDebouncedFlush()
            }
        }
        return object : TemporarySubscriptionHandle {
            override fun cancel() {
                eventCallbacks[filterKey]?.remove(onEvent)
                scope.launch { unsubscribe(consumerId) }
            }
            override fun pause() {}
            override fun resume() {}
            override val isPaused: Boolean get() = false
        }
    }

    /**
     * Per-relay filter map subscription (outbox model).
     * Each relay gets its own set of filters. Returns a callback-based handle.
     */
    fun subscribePerRelayCallback(
        relayFilters: Map<String, List<Filter>>,
        priority: SubscriptionPriority = SubscriptionPriority.NORMAL,
        onEvent: (Event) -> Unit,
    ): TemporarySubscriptionHandle {
        if (relayFilters.isEmpty()) return NoOpTemporaryHandle
        val consumerId = "mux-pr-${nextConsumerId.incrementAndGet()}"
        val filterKey = FilterKey.ofPerRelay(relayFilters)
        // Register direct callback for O(1) dispatch
        val wrappedCb: (Event, String) -> Unit = { event, _ -> onEvent(event) }
        eventCallbacks.getOrPut(filterKey) { java.util.concurrent.CopyOnWriteArrayList() }.add(wrappedCb)
        scope.launch {
            mutex.withLock {
                val consumer = ConsumerSubscription(consumerId, filterKey, emptyList(), emptyList(), priority, relayFilters)
                consumers[consumerId] = consumer
                val merged = mergedSubs.getOrPut(filterKey) {
                    MergedSubscription(filterKey, emptyList(), emptyList(), priority, relayFilterMap = relayFilters)
                }
                merged.consumerIds.add(consumerId)
                if (priority.level > merged.priority.level) {
                    merged.priority = priority; merged.needsRefresh = true
                }
                scheduleDebouncedFlush()
            }
        }
        return object : TemporarySubscriptionHandle {
            override fun cancel() {
                eventCallbacks[filterKey]?.remove(wrappedCb)
                scope.launch { unsubscribe(consumerId) }
            }
            override fun pause() {}
            override fun resume() {}
            override val isPaused: Boolean get() = false
        }
    }

    /**
     * Per-relay filter map subscription with relay URL passthrough.
     */
    fun subscribePerRelayCallbackWithRelay(
        relayFilters: Map<String, List<Filter>>,
        priority: SubscriptionPriority = SubscriptionPriority.NORMAL,
        onEvent: (Event, String) -> Unit,
    ): TemporarySubscriptionHandle {
        if (relayFilters.isEmpty()) return NoOpTemporaryHandle
        val consumerId = "mux-prr-${nextConsumerId.incrementAndGet()}"
        val filterKey = FilterKey.ofPerRelay(relayFilters)
        // Register direct callback for O(1) dispatch
        eventCallbacks.getOrPut(filterKey) { java.util.concurrent.CopyOnWriteArrayList() }.add(onEvent)
        scope.launch {
            mutex.withLock {
                val consumer = ConsumerSubscription(consumerId, filterKey, emptyList(), emptyList(), priority, relayFilters)
                consumers[consumerId] = consumer
                val merged = mergedSubs.getOrPut(filterKey) {
                    MergedSubscription(filterKey, emptyList(), emptyList(), priority, relayFilterMap = relayFilters)
                }
                merged.consumerIds.add(consumerId)
                if (priority.level > merged.priority.level) {
                    merged.priority = priority; merged.needsRefresh = true
                }
                scheduleDebouncedFlush()
            }
        }
        return object : TemporarySubscriptionHandle {
            override fun cancel() {
                eventCallbacks[filterKey]?.remove(onEvent)
                scope.launch { unsubscribe(consumerId) }
            }
            override fun pause() {}
            override fun resume() {}
            override val isPaused: Boolean get() = false
        }
    }

    /**
     * EOSE-based one-shot subscription. Opens REQ, collects events, auto-CLOSEs
     * after all relays send EOSE (+ settle window), or after hard timeout.
     */
    fun subscribeOneShotCallback(
        relayUrls: List<String>,
        filters: List<Filter>,
        priority: SubscriptionPriority = SubscriptionPriority.LOW,
        settleMs: Long = 500L,
        maxWaitMs: Long = 8_000L,
        onEvent: (Event) -> Unit,
    ): TemporarySubscriptionHandle {
        return subscribeOneShotCallbackWithRelay(relayUrls, filters, priority, settleMs, maxWaitMs) { event, _ -> onEvent(event) }
    }

    /**
     * EOSE-based one-shot subscription with relay URL passthrough.
     * Same lifecycle as [subscribeOneShotCallback] but the callback receives
     * (Event, relayUrl) so callers know which relay delivered each event.
     */
    fun subscribeOneShotCallbackWithRelay(
        relayUrls: List<String>,
        filters: List<Filter>,
        priority: SubscriptionPriority = SubscriptionPriority.LOW,
        settleMs: Long = 500L,
        maxWaitMs: Long = 8_000L,
        onEvent: (Event, String) -> Unit,
    ): TemporarySubscriptionHandle {
        if (relayUrls.isEmpty() || filters.isEmpty()) return NoOpTemporaryHandle
        val consumerId = "mux-os-${nextConsumerId.incrementAndGet()}"
        val filterKey = FilterKey.of(relayUrls, filters)

        // One-shot subs are NOT merged — each gets its own relay subscription
        // because they auto-close and the lifecycle is per-caller
        val collectJob = scope.launch {
            val h = subscribe(relayUrls, filters, priority, isOneShot = true)

            // Collect events with relay URL from the full MultiplexedEvent flow
            val eventJob = launch {
                _events
                    .filter { it.consumerId == consumerId || it.filterKey == filterKey }
                    .collect { onEvent(it.event, it.relayUrl) }
            }

            // Wait for EOSE or timeout
            val eoseJob = launch {
                h.eose.first { it }
                delay(settleMs)
            }

            // Hard timeout
            val timeoutJob = launch { delay(maxWaitMs) }

            // Whichever finishes first
            kotlinx.coroutines.selects.select<Unit> {
                eoseJob.onJoin {}
                timeoutJob.onJoin {}
            }

            eventJob.cancel()
            eoseJob.cancel()
            timeoutJob.cancel()
            h.cancel()
        }

        return object : TemporarySubscriptionHandle {
            override fun cancel() { collectJob.cancel() }
            override fun pause() {}
            override fun resume() {}
            override val isPaused: Boolean get() = false
        }
    }

    /**
     * Suspending one-shot: opens REQ, collects events via [onEvent], suspends until
     * all relays EOSE (+ settle) or hard timeout, then auto-CLOSEs and returns.
     * Callers can simply `awaitOneShotSubscription(...)` without any delay.
     */
    suspend fun awaitOneShotSubscription(
        relayUrls: List<String>,
        filters: List<Filter>,
        priority: SubscriptionPriority = SubscriptionPriority.LOW,
        settleMs: Long = 500L,
        maxWaitMs: Long = 8_000L,
        onEvent: (Event) -> Unit,
    ) {
        awaitOneShotSubscriptionWithRelay(relayUrls, filters, priority, settleMs, maxWaitMs) { event, _ -> onEvent(event) }
    }

    /**
     * Suspending one-shot with relay URL passthrough: same lifecycle as
     * [awaitOneShotSubscription] but the callback receives (Event, relayUrl)
     * so callers know which relay delivered each event.
     */
    suspend fun awaitOneShotSubscriptionWithRelay(
        relayUrls: List<String>,
        filters: List<Filter>,
        priority: SubscriptionPriority = SubscriptionPriority.LOW,
        settleMs: Long = 500L,
        maxWaitMs: Long = 8_000L,
        onEvent: (Event, String) -> Unit,
    ) {
        if (relayUrls.isEmpty() || filters.isEmpty()) return
        val filterKey = FilterKey.of(relayUrls, filters)
        val h = subscribe(relayUrls, filters, priority, isOneShot = true)

        // Collect from the full MultiplexedEvent flow to get relay URL
        val eventJob = scope.launch {
            _events
                .filter { it.filterKey == filterKey }
                .collect { onEvent(it.event, it.relayUrl) }
        }

        val eoseJob = scope.launch {
            h.eose.first { it }
            kotlinx.coroutines.delay(settleMs)
        }

        val timeoutJob = scope.launch { kotlinx.coroutines.delay(maxWaitMs) }

        kotlinx.coroutines.selects.select<Unit> {
            eoseJob.onJoin {}
            timeoutJob.onJoin {}
        }

        eventJob.cancel()
        eoseJob.cancel()
        timeoutJob.cancel()
        h.cancel()
    }

    /**
     * Clear all subscriptions and state. Call on account switch / logout.
     */
    fun clear() {
        scope.launch {
            mutex.withLock {
                for ((_, merged) in mergedSubs) {
                    merged.cybinSub?.close()
                }
                mergedSubs.clear()
                consumers.clear()
                eventCallbacks.clear()
                cybinSubToFilterKey.clear()
                _eoseReceived.value = emptySet()
                eoseByRelay.clear()
                Log.d(TAG, "Cleared all multiplexed subscriptions")
            }
        }
    }

    /**
     * Get current stats for diagnostics.
     */
    fun stats(): MultiplexerStats = MultiplexerStats(
        consumerCount = consumers.size,
        mergedSubCount = mergedSubs.size,
        seenEventCount = mergedSubs.values.sumOf { it.seenEventIds.size },
    )

    // ── Internal ────────────────────────────────────────────────────────────

    private suspend fun unsubscribe(consumerId: String) {
        mutex.withLock {
            val consumer = consumers.remove(consumerId) ?: return
            val merged = mergedSubs[consumer.filterKey] ?: return
            merged.consumerIds.remove(consumerId)
            merged.flowConsumerIds.remove(consumerId)

            // Remove EOSE state
            _eoseReceived.value = _eoseReceived.value - consumerId

            if (merged.consumerIds.isEmpty()) {
                // Last consumer — close the relay subscription
                mergedSubs.remove(consumer.filterKey)
                eventCallbacks.remove(consumer.filterKey)
                merged.cybinSub?.let { cybinSubToFilterKey.remove(it.id) }
                merged.cybinSub?.close()
                merged.cybinSub = null
                Log.d(TAG, "Closed merged sub ${consumer.filterKey.hash.take(8)} (no consumers left)")
            } else {
                // Recalculate priority from remaining consumers
                val maxPriority = merged.consumerIds
                    .mapNotNull { consumers[it]?.priority }
                    .maxByOrNull { it.level }
                    ?: SubscriptionPriority.NORMAL
                if (maxPriority.level != merged.priority.level) {
                    merged.priority = maxPriority
                    merged.needsRefresh = true
                    scheduleDebouncedFlush()
                }
            }
        }
    }

    /**
     * Schedule a debounced flush of pending subscription changes.
     * Multiple rapid subscribe/unsubscribe calls within 50ms are batched.
     */
    private fun scheduleDebouncedFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(DEBOUNCE_MS)
            flushSubscriptions()
        }
    }

    /**
     * Flush all pending subscription changes to the relay pool.
     * Creates new CybinSubscriptions for merged subs that don't have one,
     * and refreshes subs whose priority changed.
     */
    private suspend fun flushSubscriptions() {
        mutex.withLock {
            for ((key, merged) in mergedSubs) {
                if (merged.cybinSub == null || merged.needsRefresh) {
                    // Close existing if refreshing
                    if (merged.needsRefresh) merged.cybinSub?.close()

                    // Create relay sub — route to per-relay or uniform variant
                    val sub = if (merged.relayFilterMap != null) {
                        pool.subscribe(
                            relayFilters = merged.relayFilterMap,
                            priority = merged.priority,
                            isOneShot = merged.isOneShot,
                        ) { event, relayUrl -> handleEvent(key, event, relayUrl) }
                    } else {
                        pool.subscribe(
                            relayUrls = merged.relayUrls,
                            filters = merged.filters,
                            priority = merged.priority,
                            isOneShot = merged.isOneShot,
                        ) { event, relayUrl -> handleEvent(key, event, relayUrl) }
                    }
                    merged.cybinSub = sub
                    merged.needsRefresh = false
                    // Populate reverse lookup for O(1) EOSE dispatch
                    cybinSubToFilterKey[sub.id] = key

                    val relayCount = merged.relayFilterMap?.size ?: merged.relayUrls.size
                    val label = if (merged.relayFilterMap != null) "per-relay" else "uniform"
                    Log.d(TAG, "${if (merged.needsRefresh) "Refreshed" else "Created"} merged sub ${key.hash.take(8)} " +
                        "($label, ${merged.consumerIds.size} consumers, ${merged.priority.name}, $relayCount relays)")
                }
            }
            pool.connect()
        }
    }

    /**
     * Handle an event from the relay pool. Dedup, then dispatch to registered
     * callbacks (O(1) lookup) and emit to Flow-based consumers.
     *
     * Dedup is **lock-free**: backed by a [ConcurrentHashMap] where the value is a
     * monotonically increasing insertion-order counter. [putIfAbsent] is a single
     * atomic CAS — no monitor acquisition on the ingest thread, eliminating the
     * contention bottleneck under high multi-relay throughput.
     */
    private fun handleEvent(filterKey: FilterKey, event: Event, relayUrl: String) {
        val merged = mergedSubs[filterKey] ?: return
        // Lock-free dedup per merged subscription.
        // putIfAbsent returns null  → brand-new event (pass through)
        //              returns value → already seen   (drop)
        val ordinal = merged.seenInsertCounter.incrementAndGet()
        val isNew = merged.seenEventIds.putIfAbsent(event.id, ordinal) == null
        if (!isNew) return

        // Evict oldest 20% when the cap is reached — remove entries with the
        // smallest counter values (true insertion-order LRU without a linked list).
        if (merged.seenEventIds.size > maxSeenIdsPerSub) {
            val evictCount = maxSeenIdsPerSub / 5
            merged.seenEventIds.entries
                .sortedBy { it.value }
                .take(evictCount)
                .forEach { merged.seenEventIds.remove(it.key, it.value) }
        }

        // Track event against source relay for health stats
        RelayHealthTracker.recordEventReceived(relayUrl)
        PipelineDiagnostics.recordEventIngested()

        // Direct dispatch to registered callbacks (O(1) lookup by filterKey)
        eventCallbacks[filterKey]?.let { callbacks ->
            for (cb in callbacks) {
                try { cb(event, relayUrl) } catch (e: Exception) {
                    Log.e(TAG, "Callback error for ${filterKey.hash.take(8)}: ${e.message}")
                }
            }
        }

        // Emit to SharedFlow only for Flow-based consumers (skip when all are callback-based)
        val flowIds = merged.flowConsumerIds
        if (flowIds.isNotEmpty()) {
            for (consumerId in flowIds) {
                _events.tryEmit(MultiplexedEvent(consumerId, filterKey, event, relayUrl))
            }
        }
    }

    /**
     * Handle EOSE from a relay for a CybinSubscription ID.
     * Tracks per-relay EOSE and marks all consumers of the merged sub as EOSE'd
     * when every relay in the subscription has sent EOSE.
     */
    private fun handleEose(cybinSubId: String, relayUrl: String) {
        // O(1) reverse lookup instead of linear scan
        val filterKey = cybinSubToFilterKey[cybinSubId]
        val merged = if (filterKey != null) mergedSubs[filterKey]
            else mergedSubs.values.firstOrNull { it.cybinSub?.id == cybinSubId }
        if (merged == null) return

        val relaySet = eoseByRelay.getOrPut(cybinSubId) {
            java.util.Collections.synchronizedSet(mutableSetOf())
        }
        relaySet.add(relayUrl)

        // Check if all relays have EOSE'd
        val totalRelays = merged.relayFilterMap?.size ?: merged.relayUrls.size
        if (totalRelays > 0 && relaySet.size >= totalRelays) {
            // All relays caught up — mark all consumers as EOSE'd
            val consumerIds = merged.consumerIds.toSet()
            _eoseReceived.value = _eoseReceived.value + consumerIds
            Log.d(TAG, "EOSE complete for merged sub ${merged.filterKey.hash.take(8)} " +
                "(${relaySet.size}/$totalRelays relays, ${consumerIds.size} consumers)")
        }
    }

    // ── Data classes ────────────────────────────────────────────────────────

    /**
     * Unique key for a set of filters + relay URLs. Used to merge identical subscriptions.
     * Two consumers with the same filters on the same relays share one relay subscription.
     */
    data class FilterKey private constructor(val hash: String) {
        companion object {
            fun of(relayUrls: List<String>, filters: List<Filter>): FilterKey {
                val relayPart = relayUrls.sorted().joinToString("|")
                val filterPart = filters.joinToString("|") { it.toJson() }
                val combined = "$relayPart::$filterPart"
                val hash = combined.hashCode().toUInt().toString(16).padStart(8, '0')
                return FilterKey(hash)
            }

            /** Key for per-relay filter maps (outbox model). */
            fun ofPerRelay(relayFilters: Map<String, List<Filter>>): FilterKey {
                val parts = relayFilters.entries.sortedBy { it.key }.joinToString(";") { (url, filters) ->
                    "$url=${filters.joinToString(",") { it.toJson() }}"
                }
                val hash = parts.hashCode().toUInt().toString(16).padStart(8, '0')
                return FilterKey(hash)
            }
        }
    }

    /** Internal: a merged relay subscription shared by multiple consumers. */
    private class MergedSubscription(
        val filterKey: FilterKey,
        val relayUrls: List<String>,
        val filters: List<Filter>,
        var priority: SubscriptionPriority,
        var cybinSub: CybinSubscription? = null,
        val consumerIds: MutableSet<String> = mutableSetOf(),
        /** Consumer IDs that use the Flow-based subscribe() path and need SharedFlow emission.
         *  Callback-only consumers are NOT in this set — they get events via eventCallbacks. */
        val flowConsumerIds: MutableSet<String> = mutableSetOf(),
        var needsRefresh: Boolean = false,
        /** Per-relay filter map (outbox model). When set, relayUrls/filters are ignored. */
        val relayFilterMap: Map<String, List<Filter>>? = null,
        /** Per-subscription lock-free dedup map: event ID → insertion-order counter.
         *  Different subscriptions (e.g. feed vs thread replies) can independently
         *  receive the same event; this only deduplicates within one merged sub
         *  (i.e. the same event arriving from multiple relays simultaneously). */
        val seenEventIds: java.util.concurrent.ConcurrentHashMap<String, Long> = java.util.concurrent.ConcurrentHashMap(),
        /** Monotonically increasing counter for insertion-order eviction. */
        val seenInsertCounter: AtomicLong = AtomicLong(0L),
        /** One-shot subs auto-free their relay slot on EOSE instead of holding for stale reaping. */
        val isOneShot: Boolean = false,
    )

    /** Internal: a single consumer's subscription request. */
    private data class ConsumerSubscription(
        val id: String,
        val filterKey: FilterKey,
        val relayUrls: List<String>,
        val filters: List<Filter>,
        val priority: SubscriptionPriority,
        val relayFilterMap: Map<String, List<Filter>>? = null,
    )

    /** Internal: event routed to a specific consumer. */
    private data class MultiplexedEvent(
        val consumerId: String,
        val filterKey: FilterKey,
        val event: Event,
        val relayUrl: String,
    )

    companion object {
        private const val TAG = "SubscriptionMultiplexer"
        private const val DEBOUNCE_MS = 50L

        @Volatile
        private var instance: SubscriptionMultiplexer? = null

        fun getInstance(): SubscriptionMultiplexer {
            return instance ?: synchronized(this) {
                instance ?: SubscriptionMultiplexer(
                    RelayConnectionStateMachine.getInstance().relayPool
                ).also { instance = it }
            }
        }
    }
}

/**
 * Handle returned to a consumer when subscribing via [SubscriptionMultiplexer].
 *
 * - [events]: Flow of events matching the subscription filters (deduped)
 * - [eose]: Flow that emits true when EOSE is received for this subscription
 * - [cancel]: Unsubscribe — decrements ref-count, CLOSEs relay sub when last consumer leaves
 */
class MultiplexHandle(
    val id: String,
    val events: Flow<Event>,
    val eose: Flow<Boolean>,
    private val onCancel: suspend () -> Unit,
) {
    /** Unsubscribe this consumer. Safe to call multiple times. */
    suspend fun cancel() { onCancel() }
}

/**
 * Diagnostic stats for the multiplexer.
 */
data class MultiplexerStats(
    val consumerCount: Int,
    val mergedSubCount: Int,
    val seenEventCount: Int = 0,
)
