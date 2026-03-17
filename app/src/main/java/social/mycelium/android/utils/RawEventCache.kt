package social.mycelium.android.utils

/**
 * Lightweight LRU cache for raw Nostr event JSON strings, keyed by event ID.
 *
 * When the user boosts (kind-6 repost), NIP-18 requires the `content` field to contain
 * the full JSON of the original signed event. We cache the JSON at ingest time so it's
 * available when the user taps "Boost".
 *
 * Capacity is capped to avoid unbounded memory growth; oldest entries are evicted first.
 */
object RawEventCache {
    private const val MAX_ENTRIES = 500

    private val map = object : LinkedHashMap<String, String>(MAX_ENTRIES + 16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun put(eventId: String, json: String) {
        map[eventId] = json
    }

    @Synchronized
    fun get(eventId: String): String? = map[eventId]

    @Synchronized
    fun remove(eventId: String) {
        map.remove(eventId)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}
