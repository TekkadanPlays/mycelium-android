package social.mycelium.android.debug

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight pipeline telemetry singleton.
 *
 * Tracks throughput across the three pipeline stages:
 *   Relay → handleEvent (ingest)
 *   flushKind1Events  (domain conversion + UI emit)
 *   flushEventStore   (Room DB writes)
 *
 * All counters are lock-free (AtomicLong). Call [logSummary] from any
 * debug-guarded block; [snapshot] is available for programmatic reads.
 */
object PipelineDiagnostics {

    // ── Counters ─────────────────────────────────────────────────────────────

    /** Total events that passed dedup and were dispatched downstream. */
    private val eventsIngestedTotal = AtomicLong(0L)

    /** Total kind-1 batch flushes that have completed. */
    private val batchFlushesTotal = AtomicLong(0L)

    /** Total events processed across all kind-1 flushes. */
    private val batchEventsTotal = AtomicLong(0L)

    /** Sum of flush latencies in ms (for average calculation). */
    private val batchLatencyMsTotal = AtomicLong(0L)

    /** Total Room batch commits. */
    private val dbCommitsTotal = AtomicLong(0L)

    /** Total rows written to cached_events. */
    private val dbRowsTotal = AtomicLong(0L)

    // ── Sliding-window throughput (1-second bucket) ───────────────────────────

    /** Epoch-second of the current sliding window. */
    private val windowSec = AtomicLong(0L)
    /** Events ingested in the current 1-second window. */
    private val windowCount = AtomicLong(0L)
    /** Last observed events/sec. Updated when window rotates. */
    @Volatile private var lastEventsPerSec: Long = 0L

    // ── Public recording API ──────────────────────────────────────────────────

    /**
     * Record a single event successfully passing the dedup gate.
     * Called from [SubscriptionMultiplexer.handleEvent] – must be wait-free.
     */
    fun recordEventIngested() {
        eventsIngestedTotal.incrementAndGet()
        val nowSec = System.currentTimeMillis() / 1_000L
        val prev = windowSec.get()
        if (nowSec != prev && windowSec.compareAndSet(prev, nowSec)) {
            lastEventsPerSec = windowCount.getAndSet(1L)
        } else {
            windowCount.incrementAndGet()
        }
    }

    /**
     * Record a completed kind-1 batch flush.
     *
     * @param batchSize number of events processed in this flush cycle
     * @param elapsedMs wall-clock time the flush took in milliseconds
     */
    fun recordBatch(batchSize: Int, elapsedMs: Long) {
        batchFlushesTotal.incrementAndGet()
        batchEventsTotal.addAndGet(batchSize.toLong())
        batchLatencyMsTotal.addAndGet(elapsedMs)
    }

    /**
     * Record a completed Room DB commit.
     *
     * @param rowCount number of [CachedEventEntity] rows submitted to insertAll
     */
    fun recordDbCommit(rowCount: Int) {
        dbCommitsTotal.incrementAndGet()
        dbRowsTotal.addAndGet(rowCount.toLong())
    }

    // ── Snapshot & logging ────────────────────────────────────────────────────

    data class DiagnosticsSnapshot(
        val eventsIngested: Long,
        val eventsPerSec: Long,
        val batchFlushes: Long,
        val batchEvents: Long,
        val avgFlushLatencyMs: Long,
        val dbCommits: Long,
        val dbRows: Long,
    )

    fun snapshot(): DiagnosticsSnapshot {
        val flushes = batchFlushesTotal.get()
        val latency = batchLatencyMsTotal.get()
        return DiagnosticsSnapshot(
            eventsIngested    = eventsIngestedTotal.get(),
            eventsPerSec      = lastEventsPerSec,
            batchFlushes      = flushes,
            batchEvents       = batchEventsTotal.get(),
            avgFlushLatencyMs = if (flushes > 0) latency / flushes else 0L,
            dbCommits         = dbCommitsTotal.get(),
            dbRows            = dbRowsTotal.get(),
        )
    }

    /**
     * Emit a single logcat line summarising pipeline health.
     * Guard with `if (BuildConfig.DEBUG)` at the call site.
     */
    fun logSummary(tag: String = "PipelineDiagnostics") {
        val s = snapshot()
        Log.d(tag,
            "📊 Pipeline │ ingested=${s.eventsIngested} (${s.eventsPerSec}/s) " +
            "│ flushes=${s.batchFlushes} avg=${s.avgFlushLatencyMs}ms " +
            "│ db commits=${s.dbCommits} rows=${s.dbRows}"
        )
        // Also log subscription budget health
        social.mycelium.android.pipeline.EnrichmentBudget.logSummary(tag)
    }

    /** Reset all counters (useful on account switch / logout). */
    fun reset() {
        eventsIngestedTotal.set(0)
        batchFlushesTotal.set(0)
        batchEventsTotal.set(0)
        batchLatencyMsTotal.set(0)
        dbCommitsTotal.set(0)
        dbRowsTotal.set(0)
        windowSec.set(0)
        windowCount.set(0)
        lastEventsPerSec = 0
    }
}
