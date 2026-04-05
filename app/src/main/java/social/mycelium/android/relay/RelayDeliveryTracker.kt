package social.mycelium.android.relay

import android.content.Context
import android.content.SharedPreferences
import social.mycelium.android.debug.MLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Thread-local java.util.Random for Gaussian sampling (kotlin.random.Random lacks nextGaussian). */
private val javaRandom = java.util.Random()

/**
 * Tracks per-relay delivery outcomes for outbox relay selection using Thompson Sampling.
 *
 * ## How it works
 * Each relay accumulates `delivered` (successes) and `expected` (total trials) counts.
 * When the outbox manager needs to rank relays, it calls [sampleScore] which draws from
 * a Beta distribution parameterized by (delivered + 1, expected - delivered + 1). Relays
 * that consistently deliver events score higher; relays that don't get deprioritized
 * naturally — no manual thresholds needed.
 *
 * ## Persistence
 * Stats are persisted to SharedPreferences as JSON. Data survives app restarts so the
 * algorithm learns across sessions. Stats decay over time via [decayAll] to prevent
 * stale data from dominating (called on each outbox session start).
 *
 * ## Why Thompson Sampling?
 * The nostrability/outbox report shows that learning from delivery is the single highest
 * impact improvement (+60-70pp recall at 1yr). Thompson Sampling balances exploration
 * (trying relays with uncertain quality) vs exploitation (preferring known-good relays)
 * without any tunable parameters.
 */
object RelayDeliveryTracker {

    private const val TAG = "RelayDeliveryTracker"
    private const val PREFS_NAME = "relay_delivery_stats_v1"
    private const val KEY_STATS = "stats"
    private const val KEY_AUTHOR_MISSES = "author_misses"

    /** Decay factor applied per session start. 0.9 = 10% decay per session. */
    private const val DECAY_FACTOR = 0.9

    /** Minimum trials before a relay's stats influence scoring (cold start protection). */
    private const val MIN_TRIALS_FOR_INFLUENCE = 3

    /** Maximum entries to persist (LRU by last update). */
    private const val MAX_ENTRIES = 500

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Volatile
    private var prefs: SharedPreferences? = null

    /** In-memory stats map. Loaded lazily from SharedPreferences. */
    private val stats = mutableMapOf<String, RelayStats>()
    private val lock = Any()

    /** Per-author consecutive miss count. Persisted to detect chronically uncovered authors. */
    private val authorMisses = mutableMapOf<String, Int>()
    private val authorMissLock = Any()

    /** Consecutive sessions without delivery before an author is flagged as missed. */
    private const val MISS_THRESHOLD = 2

    /** Max author miss entries to persist (prevent unbounded growth). */
    private const val MAX_AUTHOR_MISS_ENTRIES = 1000

    @Serializable
    data class RelayStats(
        val delivered: Double = 0.0,
        val expected: Double = 0.0,
        val lastUpdated: Long = System.currentTimeMillis()
    ) {
        val successRate: Double get() = if (expected > 0) delivered / expected else 0.0
    }

    /**
     * Initialize with application context. Call once from MainActivity.
     */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromDisk()
    }

    /**
     * Record that a relay was expected to deliver events (it was selected for subscription).
     * Call once per relay per outbox session.
     */
    fun recordExpected(relayUrl: String) {
        synchronized(lock) {
            val current = stats.getOrDefault(relayUrl, RelayStats())
            stats[relayUrl] = current.copy(
                expected = current.expected + 1,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }

    /**
     * Record that a relay successfully delivered at least one event.
     * Call once per relay per outbox session (binary: delivered or not).
     */
    fun recordDelivered(relayUrl: String) {
        synchronized(lock) {
            val current = stats.getOrDefault(relayUrl, RelayStats())
            stats[relayUrl] = current.copy(
                delivered = current.delivered + 1,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }

    /**
     * Sample a score for a relay using Thompson Sampling (Beta distribution approximation).
     * Higher scores indicate relays more likely to deliver events.
     *
     * For relays with insufficient history ([MIN_TRIALS_FOR_INFLUENCE]), returns a mildly
     * optimistic prior (0.5 + noise) to encourage exploration.
     *
     * @param relayUrl The relay URL to score
     * @param authorCount Number of followed authors on this relay (popularity factor)
     * @return A score combining delivery quality with popularity
     */
    fun sampleScore(relayUrl: String, authorCount: Int): Double {
        val stat = synchronized(lock) { stats[relayUrl] }

        val deliveryScore = if (stat == null || stat.expected < MIN_TRIALS_FOR_INFLUENCE) {
            // Cold start: mildly optimistic with exploration noise
            0.5 + Random.nextDouble() * 0.3
        } else {
            // Thompson Sampling: draw from Beta(alpha, beta)
            val alpha = stat.delivered + 1.0
            val beta = stat.expected - stat.delivered + 1.0
            sampleBeta(alpha, beta)
        }

        // Combine delivery quality with popularity (log-scaled to avoid pure popularity dominance)
        val score = deliveryScore * (1.0 + ln(authorCount.toDouble().coerceAtLeast(1.0)))
        // Guard against NaN/Infinity — TimSort requires a total order (transitivity).
        // NaN breaks compareTo and causes "Comparison method violates its general contract!"
        return if (score.isFinite()) score else 0.0
    }

    /**
     * Apply decay to all stats. Called at the start of each outbox session to prevent
     * stale data from dominating. Old successes/failures gradually lose influence,
     * allowing the algorithm to adapt to relay changes.
     */
    fun decayAll() {
        synchronized(lock) {
            val iter = stats.iterator()
            while (iter.hasNext()) {
                val (url, stat) = iter.next()
                val decayed = stat.copy(
                    delivered = stat.delivered * DECAY_FACTOR,
                    expected = stat.expected * DECAY_FACTOR
                )
                // Prune entries that have decayed to near-zero
                if (decayed.expected < 0.5) {
                    iter.remove()
                } else {
                    stats[url] = decayed
                }
            }
            MLog.d(TAG, "Decayed ${stats.size} relay stats (factor=$DECAY_FACTOR)")
        }
    }

    /**
     * Persist current stats to disk. Call after recording outcomes.
     */
    fun saveToDisk() {
        val snapshot = synchronized(lock) {
            // LRU eviction: keep only MAX_ENTRIES most recently updated
            if (stats.size > MAX_ENTRIES) {
                val sorted = stats.entries.sortedByDescending { it.value.lastUpdated }
                stats.clear()
                sorted.take(MAX_ENTRIES).forEach { (k, v) -> stats[k] = v }
            }
            stats.toMap()
        }
        val missSnapshot = synchronized(authorMissLock) { authorMisses.toMap() }
        try {
            val editor = prefs?.edit() ?: return
            editor.putString(KEY_STATS, json.encodeToString(snapshot))
            editor.putString(KEY_AUTHOR_MISSES, json.encodeToString(missSnapshot))
            editor.apply()
            MLog.d(TAG, "Saved ${snapshot.size} relay stats + ${missSnapshot.size} author misses to disk")
        } catch (e: Exception) {
            MLog.e(TAG, "Failed to save relay delivery stats: ${e.message}")
        }
    }

    /**
     * Get a snapshot of all stats for diagnostics/UI.
     */
    fun getStats(): Map<String, RelayStats> = synchronized(lock) { stats.toMap() }

    // ── Author miss tracking (Phase 5: self-healing) ──

    /**
     * Record delivery outcomes for authors. For each followed author who was assigned
     * to at least one outbox relay, record whether any events were received.
     * Authors with zero delivery get their miss count incremented; others get reset.
     */
    fun recordAuthorOutcomes(assignedAuthors: Set<String>, deliveredAuthors: Set<String>) {
        synchronized(authorMissLock) {
            for (pubkey in assignedAuthors) {
                if (pubkey in deliveredAuthors) {
                    // Delivered — reset miss counter
                    authorMisses.remove(pubkey)
                } else {
                    // Missed — increment counter
                    authorMisses[pubkey] = (authorMisses[pubkey] ?: 0) + 1
                }
            }
            // LRU prune
            if (authorMisses.size > MAX_AUTHOR_MISS_ENTRIES) {
                val toRemove = authorMisses.entries
                    .sortedBy { it.value }
                    .take(authorMisses.size - MAX_AUTHOR_MISS_ENTRIES)
                    .map { it.key }
                toRemove.forEach { authorMisses.remove(it) }
            }
        }
    }

    /**
     * Get authors who have been missed for [MISS_THRESHOLD] or more consecutive sessions.
     * These authors need fallback relay coverage (e.g., indexer relays).
     */
    fun getMissedAuthors(): Set<String> {
        synchronized(authorMissLock) {
            return authorMisses.filter { it.value >= MISS_THRESHOLD }.keys.toSet()
        }
    }

    // ── Internal ──

    private fun loadFromDisk() {
        try {
            val raw = prefs?.getString(KEY_STATS, null)
            if (raw != null) {
                val loaded = json.decodeFromString<Map<String, RelayStats>>(raw)
                synchronized(lock) {
                    stats.clear()
                    stats.putAll(loaded)
                }
                MLog.d(TAG, "Loaded ${loaded.size} relay delivery stats from disk")
            }
            val missRaw = prefs?.getString(KEY_AUTHOR_MISSES, null)
            if (missRaw != null) {
                val loadedMisses = json.decodeFromString<Map<String, Int>>(missRaw)
                synchronized(authorMissLock) {
                    authorMisses.clear()
                    authorMisses.putAll(loadedMisses)
                }
                MLog.d(TAG, "Loaded ${loadedMisses.size} author miss records from disk")
            }
        } catch (e: Exception) {
            MLog.e(TAG, "Failed to load relay delivery stats: ${e.message}", e)
        }
    }

    /**
     * Approximate Beta distribution sampling using the Jöhnk algorithm.
     * For our use case (small alpha/beta, low call frequency), this is fast enough.
     */
    private fun sampleBeta(alpha: Double, beta: Double): Double {
        // For alpha, beta >= 1 use the simple ratio-of-gammas approximation
        // via the Marsaglia-Tsang method for gamma sampling
        val x = sampleGamma(alpha)
        val y = sampleGamma(beta)
        return if (x + y > 0) x / (x + y) else 0.5
    }

    /**
     * Sample from Gamma(shape, 1) using Marsaglia-Tsang method for shape >= 1,
     * and rejection for shape < 1.
     */
    private fun sampleGamma(shape: Double): Double {
        if (shape < 1.0) {
            // Gamma(shape) = Gamma(shape+1) * U^(1/shape)
            val g = sampleGamma(shape + 1.0)
            val u = Random.nextDouble()
            return g * Math.pow(u, 1.0 / shape)
        }

        // Marsaglia-Tsang for shape >= 1
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = javaRandom.nextGaussian()
                v = 1.0 + c * x
            } while (v <= 0)
            v = v * v * v
            val u = Random.nextDouble()
            if (u < 1.0 - 0.0331 * (x * x) * (x * x)) return d * v
            if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }
}
