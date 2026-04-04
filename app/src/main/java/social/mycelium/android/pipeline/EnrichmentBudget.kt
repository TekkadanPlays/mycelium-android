package social.mycelium.android.pipeline

import android.util.Log
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralized concurrency governor for the enrichment pipeline.
 *
 * Prevents subscription storms by limiting the total number of concurrent
 * outbound relay interactions across all subsystems (quoted notes, counts,
 * profiles, URL previews, NIP-11, etc.).
 *
 * Each subsystem acquires a permit before initiating a relay subscription.
 * When capacity is reached, callers suspend until a slot frees up —
 * natural backpressure instead of unbounded fan-out.
 *
 * Thread-safety: all state is lock-free (atomics) or coroutine-safe (Semaphore).
 */
object EnrichmentBudget {

    private const val TAG = "EnrichmentBudget"

    // ── Concurrency limits ────────────────────────────────────────────────

    /**
     * Max concurrent one-shot relay subscriptions for quoted note fetches.
     * Prevents the exponential fan-out from depth-1 + depth-2 prefetch chains.
     * 3 is aggressive but safe: each subscription can batch up to 20 IDs,
     * so 3 concurrent subs can resolve 60 quoted notes simultaneously.
     */
    const val MAX_CONCURRENT_QUOTE_FETCHES = 3
    val quoteFetchSemaphore = Semaphore(MAX_CONCURRENT_QUOTE_FETCHES)

    /**
     * Max concurrent one-shot relay subscriptions for profile metadata fetches.
     * Profile requests are already batched by ProfileMetadataCache.requestProfiles,
     * so this limits network-level concurrency rather than request count.
     */
    const val MAX_CONCURRENT_PROFILE_FETCHES = 2
    val profileFetchSemaphore = Semaphore(MAX_CONCURRENT_PROFILE_FETCHES)

    /**
     * Max concurrent URL preview HTTP fetches.
     * Already semaphore-capped at 3 in NotesRepository.scheduleUrlPreviewPrefetch;
     * this is the global fallback for other call sites.
     */
    const val MAX_CONCURRENT_URL_PREVIEWS = 3
    val urlPreviewSemaphore = Semaphore(MAX_CONCURRENT_URL_PREVIEWS)

    // ── Telemetry (lock-free counters) ─────────────────────────────────────

    /** Active one-shot subscriptions right now. */
    val activeOneShotSubs = AtomicInteger(0)

    /** Active temporary (long-lived) subscriptions right now. */
    val activeTempSubs = AtomicInteger(0)

    /** Total subscription create operations since reset. */
    val totalSubCreates = AtomicLong(0)

    /** Total subscription cancel operations since reset. */
    val totalSubCancels = AtomicLong(0)

    /** Subscription creates in the current 1-second window (for rate detection). */
    private val windowSec = AtomicLong(0)
    private val windowCreates = AtomicLong(0)
    @Volatile
    var subsCreatedPerSec: Long = 0L
        private set

    /** Track a subscription creation. */
    fun recordSubCreate() {
        totalSubCreates.incrementAndGet()
        val nowSec = System.currentTimeMillis() / 1000L
        val prev = windowSec.get()
        if (nowSec != prev && windowSec.compareAndSet(prev, nowSec)) {
            subsCreatedPerSec = windowCreates.getAndSet(1L)
        } else {
            windowCreates.incrementAndGet()
        }
    }

    /** Track a subscription cancellation. */
    fun recordSubCancel() {
        totalSubCancels.incrementAndGet()
    }

    // ── Diagnostics ────────────────────────────────────────────────────────

    data class BudgetSnapshot(
        val activeOneShot: Int,
        val activeTemp: Int,
        val totalCreates: Long,
        val totalCancels: Long,
        val createsPerSec: Long,
        val quoteSlotsAvailable: Int,
        val profileSlotsAvailable: Int,
    )

    fun snapshot(): BudgetSnapshot = BudgetSnapshot(
        activeOneShot = activeOneShotSubs.get(),
        activeTemp = activeTempSubs.get(),
        totalCreates = totalSubCreates.get(),
        totalCancels = totalSubCancels.get(),
        createsPerSec = subsCreatedPerSec,
        quoteSlotsAvailable = MAX_CONCURRENT_QUOTE_FETCHES - activeOneShotSubs.get().coerceAtMost(MAX_CONCURRENT_QUOTE_FETCHES),
        profileSlotsAvailable = MAX_CONCURRENT_PROFILE_FETCHES - 0, // profiles don't track active count yet
    )

    fun logSummary(tag: String = TAG) {
        val s = snapshot()
        Log.d(tag,
            "🎛️ Budget │ oneShot=${s.activeOneShot} temp=${s.activeTemp} " +
            "│ creates=${s.totalCreates} (${s.createsPerSec}/s) cancels=${s.totalCancels} " +
            "│ quoteSlots=${s.quoteSlotsAvailable}/${MAX_CONCURRENT_QUOTE_FETCHES}"
        )
    }

    /** Reset all counters (account switch / logout). */
    fun reset() {
        activeOneShotSubs.set(0)
        activeTempSubs.set(0)
        totalSubCreates.set(0)
        totalSubCancels.set(0)
        windowSec.set(0)
        windowCreates.set(0)
        subsCreatedPerSec = 0
    }
}
