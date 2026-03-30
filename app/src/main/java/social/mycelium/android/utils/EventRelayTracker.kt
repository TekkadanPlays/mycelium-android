package social.mycelium.android.utils

import java.util.concurrent.ConcurrentHashMap

/**
 * Global relay accumulator — tracks which relays have delivered each event.
 *
 * Modeled after Amethyst's `Note.addRelay()`: every time any event arrives from any relay,
 * the relay URL is appended to this tracker regardless of whether the event is new or a duplicate.
 * This ensures relay orbs reflect all relays that actually carry the event, not just the first
 * relay that delivered it.
 *
 * Thread-safe: ConcurrentHashMap + synchronized inner sets.
 */
object EventRelayTracker {
    private val relaysByEvent = ConcurrentHashMap<String, MutableSet<String>>()

    /** Maximum tracked events to prevent unbounded growth.
     *  Must exceed MAX_NOTES_IN_MEMORY (5000) + topics + thread replies + deep history
     *  to avoid evicting relay data for notes still visible in the feed. */
    private const val MAX_TRACKED = 20_000
    private const val EVICT_BATCH = 4_000

    /**
     * Record that [relayUrl] delivered event [eventId].
     * Called from every event handler — even for duplicate/already-processed events.
     */
    fun addRelay(eventId: String, relayUrl: String) {
        if (relayUrl.isBlank()) return
        val normalized = normalizeRelayUrl(relayUrl)
        val set = relaysByEvent.getOrPut(eventId) {
            java.util.Collections.synchronizedSet(mutableSetOf())
        }
        set.add(normalized)
        // Evict oldest entries if map grows too large
        if (relaysByEvent.size > MAX_TRACKED) {
            val iter = relaysByEvent.keys.iterator()
            var evicted = 0
            while (iter.hasNext() && evicted < EVICT_BATCH) {
                iter.next()
                iter.remove()
                evicted++
            }
        }
    }

    /** Get all known relay URLs for an event. Returns empty list if unknown. */
    fun getRelays(eventId: String): List<String> =
        relaysByEvent[eventId]?.toList() ?: emptyList()

    /** Merge tracked relays into a note's existing relay URLs list. */
    fun enrichRelayUrls(eventId: String, existing: List<String>): List<String> {
        val tracked = relaysByEvent[eventId] ?: return existing
        val existingNorm = existing.map { normalizeRelayUrl(it) }.toSet()
        val merged = existing.toMutableList()
        synchronized(tracked) {
            for (url in tracked) {
                if (url !in existingNorm) {
                    merged.add(url)
                }
            }
        }
        return merged
    }

    /** Clear all tracked data (e.g., on account switch). */
    fun clear() {
        relaysByEvent.clear()
    }
}
