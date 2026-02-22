package social.mycelium.android.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import social.mycelium.android.data.DiscoveredRelay
import social.mycelium.android.data.RelayDiscoveryEvent
import social.mycelium.android.data.RelayMonitorAnnouncement
import social.mycelium.android.data.RelayType
import social.mycelium.android.relay.RelayConnectionStateMachine
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import social.mycelium.android.network.MyceliumHttpClient

/**
 * NIP-66 Relay Discovery and Liveness Monitoring.
 *
 * Fetches kind 30166 (relay discovery) events from relay monitors to build
 * a catalog of discovered relays with their types, supported NIPs, latency,
 * and other metadata. Also fetches kind 10166 (monitor announcements) to
 * discover active monitors.
 *
 * The `T` tag on kind 30166 events provides the relay type from the official
 * nomenclature (Search, PublicOutbox, PublicInbox, etc.), replacing any need
 * for hardcoded relay lists or heuristic guessing.
 */
object Nip66RelayDiscoveryRepository {

    private const val TAG = "Nip66Discovery"
    private const val KIND_RELAY_DISCOVERY = 30166
    private const val KIND_MONITOR_ANNOUNCEMENT = 10166
    private const val FETCH_TIMEOUT_MS = 12_000L
    private const val CACHE_PREFS = "nip66_discovery_cache_v2"
    private const val CACHE_KEY_RELAYS = "discovered_relays"
    private const val CACHE_KEY_MONITORS = "known_monitors"
    private const val CACHE_KEY_TIMESTAMP = "last_fetch"
    private const val CACHE_EXPIRY_MS = 6 * 60 * 60 * 1000L // 6 hours

    /** rstate REST API base URL (proxied via Hono on mycelium.social). */
    private const val RSTATE_BASE_URL = "https://mycelium.social/relays"

    private val httpClient = MyceliumHttpClient.instance

    /** Well-known relays where NIP-66 monitors publish kind 30166 events. */
    val MONITOR_RELAYS = listOf(
        "wss://relay.nostr.watch",
        "wss://history.nostr.watch",
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band"
    )

    private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })

    /** All discovered relays keyed by normalized URL. */
    private val _discoveredRelays = MutableStateFlow<Map<String, DiscoveredRelay>>(emptyMap())
    val discoveredRelays: StateFlow<Map<String, DiscoveredRelay>> = _discoveredRelays.asStateFlow()

    /** Known relay monitors (pubkeys that publish kind 30166). */
    private val _monitors = MutableStateFlow<List<RelayMonitorAnnouncement>>(emptyList())
    val monitors: StateFlow<List<RelayMonitorAnnouncement>> = _monitors.asStateFlow()

    /** Whether a fetch is currently in progress. */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Whether we've completed at least one fetch. */
    private val _hasFetched = MutableStateFlow(false)
    val hasFetched: StateFlow<Boolean> = _hasFetched.asStateFlow()

    @Volatile
    private var fetchHandle: TemporarySubscriptionHandle? = null
    @Volatile
    private var monitorFetchHandle: TemporarySubscriptionHandle? = null
    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Initialize with context for persistent caching.
     */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            loadFromDisk()
        }
    }

    /**
     * Get the NIP-66 relay type(s) for a given relay URL, or empty set if unknown.
     */
    fun getRelayTypes(relayUrl: String): Set<RelayType> {
        return _discoveredRelays.value[normalizeUrl(relayUrl)]?.types ?: emptySet()
    }

    /**
     * Check if a relay is categorized as a Search/Indexer relay by NIP-66 monitors.
     */
    fun isSearchRelay(relayUrl: String): Boolean {
        return _discoveredRelays.value[normalizeUrl(relayUrl)]?.isSearch == true
    }

    /**
     * Get all discovered relays of a specific type.
     */
    fun getRelaysByType(type: RelayType): List<DiscoveredRelay> {
        return _discoveredRelays.value.values.filter { type in it.types }
    }

    // ── REST API (primary path) ──

    /**
     * Fetch relay data from the rstate REST API at mycelium.social.
     * This is the primary discovery path — a single HTTPS POST replaces
     * the 12-second WebSocket bootstrap to multiple monitor relays.
     *
     * @param nipFilter Optional NIP numbers to filter by (e.g. [50] for search relays).
     *                  Pass null/empty for all relays.
     * @param limit     Max results per page (API max 500).
     * @return true if the REST call succeeded and populated data, false otherwise.
     */
    private suspend fun fetchFromRestApi(
        nipFilter: List<Int>? = null,
        limit: Int = 500
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val bodyMap = mutableMapOf<String, Any>()
            bodyMap["limit"] = limit
            bodyMap["format"] = "detailed"
            if (!nipFilter.isNullOrEmpty()) {
                bodyMap["filter"] = mapOf("nips" to nipFilter)
            }

            val bodyJson = buildRestRequestBody(bodyMap)
            Log.d(TAG, "REST API request: POST $RSTATE_BASE_URL/search body=$bodyJson")

            val response = httpClient.post("$RSTATE_BASE_URL/search") {
                setBody(TextContent(bodyJson, ContentType.Application.Json))
            }
            if (!response.status.isSuccess()) {
                Log.w(TAG, "REST API returned ${response.status}")
                return@withContext false
            }

            val responseBody = response.bodyAsText()
            if (responseBody.isBlank()) {
                Log.w(TAG, "REST API returned empty body")
                return@withContext false
            }

            val parsed = JSON.parseToJsonElement(responseBody).jsonObject
            val relaysArray = parsed["relays"]?.jsonArray ?: run {
                Log.w(TAG, "REST API response missing 'relays' array")
                return@withContext false
            }
            val total = parsed["total"]?.jsonPrimitive?.int ?: 0
            Log.d(TAG, "REST API returned ${relaysArray.size} relays (total=$total)")

            val relays = mutableMapOf<String, DiscoveredRelay>()
            for (element in relaysArray) {
                val relay = parseRestRelayState(element.jsonObject) ?: continue
                relays[normalizeUrl(relay.url)] = relay
            }

            if (relays.isNotEmpty()) {
                // Merge with existing data (REST results take precedence)
                val merged = _discoveredRelays.value.toMutableMap()
                merged.putAll(relays)
                _discoveredRelays.value = merged
                saveToDisk(merged)
                val searchCount = merged.values.count { RelayType.SEARCH in it.types }
                val searchRelays = merged.values.filter { RelayType.SEARCH in it.types }
                val withRtt = searchRelays.count { it.bestRtt != null }
                Log.d(TAG, "REST API: merged ${relays.size} relays (total now ${merged.size}, SEARCH: $searchCount, withRTT: $withRtt/${searchRelays.size})")
                if (searchRelays.isNotEmpty()) {
                    searchRelays.sortedBy { it.bestRtt ?: Int.MAX_VALUE }.take(5).forEach { r ->
                        Log.d(TAG, "  SEARCH relay: ${r.url} bestRtt=${r.bestRtt} (read=${r.avgRttRead} open=${r.avgRttOpen})")
                    }
                }
            }

            _hasFetched.value = true
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "REST API fetch failed: ${e.message}", e)
            return@withContext false
        }
    }

    /**
     * Build a JSON request body string from a map.
     * Handles nested maps and lists for the rstate search endpoint.
     */
    private fun buildRestRequestBody(map: Map<String, Any>): String {
        val entries = map.entries.joinToString(",") { (key, value) ->
            "\"$key\":${valueToJson(value)}"
        }
        return "{$entries}"
    }

    @Suppress("UNCHECKED_CAST")
    private fun valueToJson(value: Any): String = when (value) {
        is String -> "\"$value\""
        is Number -> value.toString()
        is Boolean -> value.toString()
        is List<*> -> "[${value.joinToString(",") { valueToJson(it!!) }}]"
        is Map<*, *> -> buildRestRequestBody(value as Map<String, Any>)
        else -> "\"$value\""
    }

    /**
     * Parse a single relay object from the rstate REST API "detailed" format
     * (CompactRelayState) into a DiscoveredRelay.
     */
    private fun parseRestRelayState(obj: JsonObject): DiscoveredRelay? {
        val relayUrl = obj["relayUrl"]?.jsonPrimitive?.content ?: return null

        // Network
        val network = obj["network"]?.jsonObject?.get("value")?.jsonPrimitive?.content

        // Software
        val softwareObj = obj["software"]?.jsonObject
        val software = softwareObj?.get("family")?.jsonObject?.get("value")?.jsonPrimitive?.content
        val version = softwareObj?.get("version")?.jsonObject?.get("value")?.jsonPrimitive?.content

        // RTT — rstate API nests as rtt.open.value, rtt.read.value, rtt.write.value
        // but some responses use flat integers: rtt.open = 123 (not rtt.open.value = 123)
        val rttObj = obj["rtt"]?.jsonObject
        val rttOpen = rttObj?.get("open")?.let { el ->
            (el as? JsonObject)?.get("value")?.jsonPrimitive?.int
                ?: (el as? JsonPrimitive)?.content?.toIntOrNull()
        }
        val rttRead = rttObj?.get("read")?.let { el ->
            (el as? JsonObject)?.get("value")?.jsonPrimitive?.int
                ?: (el as? JsonPrimitive)?.content?.toIntOrNull()
        }
        val rttWrite = rttObj?.get("write")?.let { el ->
            (el as? JsonObject)?.get("value")?.jsonPrimitive?.int
                ?: (el as? JsonPrimitive)?.content?.toIntOrNull()
        }
        if (rttObj != null && rttRead == null) {
            Log.w(TAG, "RTT parse miss for $relayUrl: rtt=$rttObj")
        }

        // NIPs
        val nipsObj = obj["nips"]?.jsonObject
        val nipsList = nipsObj?.get("list")?.jsonArray?.mapNotNull {
            it.jsonPrimitive.int
        }?.toSet() ?: emptySet()

        // Requirements
        val reqsObj = obj["requirements"]?.jsonObject
        val paymentRequired = reqsObj?.get("payment")?.jsonObject?.get("value")?.jsonPrimitive?.boolean ?: false
        val authRequired = reqsObj?.get("auth")?.jsonObject?.get("value")?.jsonPrimitive?.boolean ?: false

        // Labels
        val labelsObj = obj["labels"]?.jsonObject
        val countryCode = labelsObj?.get("countryCode")?.jsonArray
            ?.firstOrNull { el -> el.jsonPrimitive.content.length == 2 && el.jsonPrimitive.content.all { it.isLetter() } }
            ?.jsonPrimitive?.content?.uppercase()
        val isp = labelsObj?.get("host.isp")?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
        val nip11Version = labelsObj?.get("nip11.version")?.jsonArray?.firstOrNull()?.jsonPrimitive?.content

        // Meta
        val observationCount = obj["observationCount"]?.jsonPrimitive?.int ?: 0
        val lastSeenAt = obj["lastSeenAt"]?.jsonPrimitive?.long ?: 0L
        val updatedAt = obj["updated_at"]?.jsonPrimitive?.long ?: 0L

        // Geo
        val geoObj = obj["geo"]?.jsonObject

        // Infer relay types from NIPs (rstate doesn't return T tags directly)
        val types = mutableSetOf<RelayType>()
        if (50 in nipsList) {
            types.add(RelayType.SEARCH)
            Log.d(TAG, "Found SEARCH relay: $relayUrl (NIPs: ${nipsList.joinToString()})")
        }
        if (nipsList.isNotEmpty()) {
            if (65 in nipsList && (1 in nipsList || 2 in nipsList)) types.add(RelayType.PUBLIC_OUTBOX)
            if (4 in nipsList || 44 in nipsList) types.add(RelayType.PUBLIC_INBOX)
            if (96 in nipsList) types.add(RelayType.BLOB)
            if (types.isEmpty() && (1 in nipsList || 2 in nipsList)) types.add(RelayType.PUBLIC_OUTBOX)
        }

        // Determine hasNip11 from presence of software or nip11.version label
        val hasNip11 = software != null || nip11Version != null

        return DiscoveredRelay(
            url = relayUrl,
            types = types,
            supportedNips = nipsList,
            network = network,
            avgRttOpen = rttOpen,
            avgRttRead = rttRead,
            avgRttWrite = rttWrite,
            monitorCount = observationCount,
            lastSeen = lastSeenAt.takeIf { it > 0 } ?: (updatedAt / 1000),
            software = software,
            version = nip11Version ?: version,
            hasNip11 = hasNip11,
            paymentRequired = paymentRequired,
            authRequired = authRequired,
            countryCode = countryCode,
            isp = isp
        )
    }

    // ── Discovery (HTTPS primary, WebSocket fallback) ──

    /**
     * Fetch relay discovery data. Tries the rstate REST API first (fast, single
     * HTTPS call). Falls back to WebSocket NIP-66 bootstrap if the REST API is
     * unreachable.
     *
     * @param discoveryRelays Relays to query for kind 30166 events (fallback only)
     * @param monitorPubkeys Optional: specific monitor pubkeys to trust (fallback only)
     */
    fun fetchRelayDiscovery(
        discoveryRelays: List<String> = emptyList(),
        monitorPubkeys: List<String> = emptyList()
    ) {
        // Skip if cache is fresh
        val lastFetch = prefs?.getLong(CACHE_KEY_TIMESTAMP, 0L) ?: 0L
        if (_hasFetched.value && System.currentTimeMillis() - lastFetch < CACHE_EXPIRY_MS) {
            Log.d(TAG, "Discovery cache is fresh (${_discoveredRelays.value.size} relays), skipping fetch")
            return
        }

        fetchHandle?.cancel()
        _isLoading.value = true

        scope.launch {
            // ── Primary: rstate REST API ──
            val restSuccess = try {
                fetchFromRestApi()
            } catch (e: Exception) {
                Log.w(TAG, "REST API primary fetch threw: ${e.message}")
                false
            }

            if (restSuccess) {
                Log.d(TAG, "REST API succeeded, skipping WebSocket fallback")
                _isLoading.value = false
                return@launch
            }

            // ── Fallback: WebSocket NIP-66 bootstrap ──
            Log.d(TAG, "REST API unavailable, falling back to WebSocket NIP-66")
            fetchRelayDiscoveryViaWebSocket(discoveryRelays, monitorPubkeys)
        }
    }

    /**
     * Original WebSocket-based NIP-66 discovery. Now used only as a fallback
     * when the rstate REST API is unreachable.
     */
    private suspend fun fetchRelayDiscoveryViaWebSocket(
        discoveryRelays: List<String>,
        monitorPubkeys: List<String>
    ) {
        val allRelays = (MONITOR_RELAYS + discoveryRelays).distinct()
        if (allRelays.isEmpty()) {
            Log.w(TAG, "No relays to fetch discovery events from")
            _isLoading.value = false
            return
        }

        try {
            Log.d(TAG, "Fetching kind $KIND_RELAY_DISCOVERY from ${allRelays.size} relays")

            val rawEvents = mutableListOf<Event>()

            val filter = if (monitorPubkeys.isNotEmpty()) {
                Filter(
                    kinds = listOf(KIND_RELAY_DISCOVERY),
                    authors = monitorPubkeys,
                    limit = 500
                )
            } else {
                Filter(
                    kinds = listOf(KIND_RELAY_DISCOVERY),
                    limit = 500
                )
            }

            val handle = RelayConnectionStateMachine.getInstance()
                .requestTemporarySubscription(allRelays, filter, priority = SubscriptionPriority.BACKGROUND) { event ->
                    if (event.kind == KIND_RELAY_DISCOVERY) {
                        synchronized(rawEvents) { rawEvents.add(event) }
                    }
                }
            fetchHandle = handle

            delay(FETCH_TIMEOUT_MS)
            handle.cancel()
            fetchHandle = null

            val events = synchronized(rawEvents) { rawEvents.toList() }
            Log.d(TAG, "Received ${events.size} kind-$KIND_RELAY_DISCOVERY events")

            if (events.isNotEmpty()) {
                val parsed = events.mapNotNull { parseDiscoveryEvent(it) }
                val aggregated = aggregateDiscoveryEvents(parsed)
                _discoveredRelays.value = aggregated
                saveToDisk(aggregated)
                Log.d(TAG, "Discovered ${aggregated.size} relays from ${parsed.size} monitor events")
            }

            _hasFetched.value = true
            _isLoading.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch relay discovery: ${e.message}", e)
            _isLoading.value = false
            _hasFetched.value = true
        }
    }

    /**
     * Fetch relay monitor announcements (kind 10166) to discover active monitors.
     */
    fun fetchMonitors(discoveryRelays: List<String>) {
        if (discoveryRelays.isEmpty()) return
        monitorFetchHandle?.cancel()

        scope.launch {
            try {
                Log.d(TAG, "Fetching kind $KIND_MONITOR_ANNOUNCEMENT from ${discoveryRelays.size} relays")

                val rawEvents = mutableListOf<Event>()
                val filter = Filter(
                    kinds = listOf(KIND_MONITOR_ANNOUNCEMENT),
                    limit = 50
                )

                val lastEventAt = java.util.concurrent.atomic.AtomicLong(0)
                val handle = RelayConnectionStateMachine.getInstance()
                    .requestTemporarySubscription(discoveryRelays, filter, priority = SubscriptionPriority.BACKGROUND) { event ->
                        if (event.kind == KIND_MONITOR_ANNOUNCEMENT) {
                            synchronized(rawEvents) { rawEvents.add(event) }
                            lastEventAt.set(System.currentTimeMillis())
                        }
                    }
                monitorFetchHandle = handle

                // Settle-based wait: break early when stream goes quiet (1.5s no new events)
                val deadline = System.currentTimeMillis() + 8_000L
                while (System.currentTimeMillis() < deadline) {
                    delay(200)
                    val lastAt = lastEventAt.get()
                    if (lastAt > 0) {
                        val quietMs = System.currentTimeMillis() - lastAt
                        if (quietMs >= 1_500L) break
                    }
                }
                handle.cancel()
                monitorFetchHandle = null

                val events = synchronized(rawEvents) { rawEvents.toList() }
                val monitors = events.mapNotNull { parseMonitorAnnouncement(it) }
                    .distinctBy { it.pubkey }
                _monitors.value = monitors
                Log.d(TAG, "Discovered ${monitors.size} relay monitors")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch monitors: ${e.message}", e)
            }
        }
    }

    // ── Parsing ──

    /**
     * Parse a kind 30166 event into a RelayDiscoveryEvent.
     */
    private fun parseDiscoveryEvent(event: Event): RelayDiscoveryEvent? {
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
        if (dTag.isNullOrBlank()) return null

        val relayUrl = dTag.trim()
        val types = mutableListOf<RelayType>()
        val nips = mutableListOf<Int>()
        val requirements = mutableListOf<String>()
        val topics = mutableListOf<String>()
        var network: String? = null
        var rttOpen: Int? = null
        var rttRead: Int? = null
        var rttWrite: Int? = null
        var geohash: String? = null
        // l-tag metadata (nostr.watch monitors publish these)
        var countryCode: String? = null
        var isp: String? = null
        var asNumber: String? = null
        var asName: String? = null

        event.tags.forEach { tag ->
            if (tag.size < 2) return@forEach
            when (tag[0]) {
                "T" -> RelayType.fromTag(tag[1])?.let { types.add(it) }
                "N" -> tag[1].toIntOrNull()?.let { nips.add(it) }
                "R" -> requirements.add(tag[1])
                "n" -> network = tag[1]
                "t" -> topics.add(tag[1])
                "g" -> geohash = tag[1]
                "rtt-open" -> rttOpen = tag[1].toIntOrNull()
                "rtt-read" -> rttRead = tag[1].toIntOrNull()
                "rtt-write" -> rttWrite = tag[1].toIntOrNull()
                "l" -> {
                    // l-tags carry labeled metadata: ["l", value, namespace]
                    if (tag.size >= 3) {
                        val value = tag[1]
                        val ns = tag[2]
                        val nsLower = ns.lowercase()
                        when {
                            // ngeotags emits ISO-3166-1 tags: alpha-2 (US), alpha-3 (USA), numeric (840)
                            // We want only the 2-letter alpha-2 code for flag emoji + country name lookup
                            nsLower == "iso-3166-1" -> {
                                if (value.length == 2 && value.all { it.isLetter() }) {
                                    countryCode = value.uppercase()
                                }
                            }
                            // Fallback: some monitors may use a countryCode namespace directly
                            nsLower.contains("countrycode") -> {
                                if (countryCode == null) countryCode = value.uppercase()
                            }
                            nsLower.contains("isp") || nsLower == "host.isp" -> isp = value
                            nsLower == "host.as" -> asNumber = value
                            nsLower == "host.asn" -> asName = value
                        }
                    }
                }
            }
        }

        return RelayDiscoveryEvent(
            relayUrl = relayUrl,
            monitorPubkey = event.pubKey,
            createdAt = event.createdAt,
            relayTypes = types,
            supportedNips = nips,
            requirements = requirements,
            network = network,
            rttOpen = rttOpen,
            rttRead = rttRead,
            rttWrite = rttWrite,
            topics = topics,
            geohash = geohash,
            nip11Content = event.content.takeIf { it.isNotBlank() },
            countryCode = countryCode,
            isp = isp,
            asNumber = asNumber,
            asName = asName
        )
    }

    /**
     * Parse a kind 10166 event into a RelayMonitorAnnouncement.
     */
    private fun parseMonitorAnnouncement(event: Event): RelayMonitorAnnouncement? {
        var frequency = 3600
        val checks = mutableListOf<String>()
        val timeouts = mutableMapOf<String, Int>()
        var geohash: String? = null

        event.tags.forEach { tag ->
            if (tag.size < 2) return@forEach
            when (tag[0]) {
                "frequency" -> tag[1].toIntOrNull()?.let { frequency = it }
                "c" -> checks.add(tag[1])
                "g" -> geohash = tag[1]
                "timeout" -> {
                    if (tag.size >= 3) {
                        val testType = tag[1]
                        tag[2].toIntOrNull()?.let { timeouts[testType] = it }
                    }
                }
            }
        }

        return RelayMonitorAnnouncement(
            pubkey = event.pubKey,
            frequencySeconds = frequency,
            checks = checks,
            timeouts = timeouts,
            geohash = geohash
        )
    }

    // ── Aggregation ──

    /**
     * Aggregate multiple monitor events for the same relay into a single DiscoveredRelay.
     * Takes the union of types, NIPs, requirements, and averages RTT values.
     */
    private fun aggregateDiscoveryEvents(
        events: List<RelayDiscoveryEvent>
    ): Map<String, DiscoveredRelay> {
        return events.groupBy { normalizeUrl(it.relayUrl) }
            .mapValues { (url, relayEvents) ->
                val types = relayEvents.flatMap { it.relayTypes }.toSet()
                val nips = relayEvents.flatMap { it.supportedNips }.toSet()
                val reqs = relayEvents.flatMap { it.requirements }.toSet()
                val topics = relayEvents.flatMap { it.topics }.toSet()
                val network = relayEvents.mapNotNull { it.network }.firstOrNull()
                val nip11 = relayEvents.mapNotNull { it.nip11Content }.firstOrNull()
                val lastSeen = relayEvents.maxOf { it.createdAt }
                val monitorPubkeys = relayEvents.map { it.monitorPubkey }.distinct().toSet()

                // l-tag metadata: take first non-null from any monitor
                val countryCode = relayEvents.mapNotNull { it.countryCode }.firstOrNull()
                val ispValue = relayEvents.mapNotNull { it.isp }.firstOrNull()

                val avgRttOpen = relayEvents.mapNotNull { it.rttOpen }.takeIf { it.isNotEmpty() }
                    ?.let { it.sum() / it.size }
                val avgRttRead = relayEvents.mapNotNull { it.rttRead }.takeIf { it.isNotEmpty() }
                    ?.let { it.sum() / it.size }
                val avgRttWrite = relayEvents.mapNotNull { it.rttWrite }.takeIf { it.isNotEmpty() }
                    ?.let { it.sum() / it.size }

                // Parse NIP-11 JSON content for structured fields
                var software: String? = null
                var version: String? = null
                var relayName: String? = null
                var description: String? = null
                var icon: String? = null
                var banner: String? = null
                var paymentRequired = false
                var authRequired = false
                var restrictedWrites = false
                var hasNip11 = false
                var operatorPubkey: String? = null
                var nip11Nips = emptySet<Int>()

                if (nip11 != null) {
                    try {
                        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(nip11)
                        val obj = parsed as? kotlinx.serialization.json.JsonObject
                        if (obj != null && obj.isNotEmpty()) {
                            hasNip11 = true
                            software = obj["software"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            version = obj["version"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            relayName = obj["name"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            description = obj["description"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            icon = obj["icon"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            banner = obj["banner"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            operatorPubkey = obj["pubkey"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

                            val limitation = obj["limitation"] as? kotlinx.serialization.json.JsonObject
                            if (limitation != null) {
                                paymentRequired = (limitation["payment_required"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                                authRequired = (limitation["auth_required"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                                restrictedWrites = (limitation["restricted_writes"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                            }

                            nip11Nips = obj["supported_nips"]
                                ?.let { it as? kotlinx.serialization.json.JsonArray }
                                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                                ?.toSet() ?: emptySet()
                        }
                    } catch (_: Exception) { /* malformed NIP-11 JSON */ }
                }

                // Merge NIPs from N tags and NIP-11 content
                val allNips = if (nips.isNotEmpty()) nips + nip11Nips else nip11Nips

                // Fallback heuristic: infer type from supported NIPs when T tags are absent
                val effectiveTypes = if (types.isEmpty()) {
                    val inferred = mutableSetOf<RelayType>()
                    if (allNips.isNotEmpty()) {
                        if (50 in allNips) inferred.add(RelayType.SEARCH)
                        if (65 in allNips && (1 in allNips || 2 in allNips)) inferred.add(RelayType.PUBLIC_OUTBOX)
                        if (4 in allNips || 44 in allNips) inferred.add(RelayType.PUBLIC_INBOX)
                        if (96 in allNips) inferred.add(RelayType.BLOB)
                        if (inferred.isEmpty() && (1 in allNips || 2 in allNips)) inferred.add(RelayType.PUBLIC_OUTBOX)
                    }
                    inferred
                } else types

                DiscoveredRelay(
                    url = url,
                    types = effectiveTypes,
                    supportedNips = allNips,
                    requirements = reqs,
                    network = network,
                    avgRttOpen = avgRttOpen,
                    avgRttRead = avgRttRead,
                    avgRttWrite = avgRttWrite,
                    topics = topics,
                    monitorCount = monitorPubkeys.size,
                    lastSeen = lastSeen,
                    nip11Json = nip11,
                    software = software,
                    version = version,
                    name = relayName,
                    description = description,
                    icon = icon,
                    banner = banner,
                    paymentRequired = paymentRequired,
                    authRequired = authRequired,
                    restrictedWrites = restrictedWrites,
                    hasNip11 = hasNip11,
                    operatorPubkey = operatorPubkey,
                    countryCode = countryCode,
                    isp = ispValue,
                    seenByMonitors = monitorPubkeys
                )
            }
    }

    // ── Persistence ──

    private fun saveToDisk(relays: Map<String, DiscoveredRelay>) {
        try {
            val entries = relays.values.map { relay ->
                JsonObject(mapOf(
                    "url" to JsonPrimitive(relay.url),
                    "types" to JsonArray(relay.types.map { JsonPrimitive(it.tag) }),
                    "nips" to JsonArray(relay.supportedNips.map { JsonPrimitive(it) }),
                    "reqs" to JsonArray(relay.requirements.map { JsonPrimitive(it) }),
                    "network" to JsonPrimitive(relay.network ?: ""),
                    "rttOpen" to JsonPrimitive(relay.avgRttOpen ?: -1),
                    "rttRead" to JsonPrimitive(relay.avgRttRead ?: -1),
                    "rttWrite" to JsonPrimitive(relay.avgRttWrite ?: -1),
                    "topics" to JsonArray(relay.topics.map { JsonPrimitive(it) }),
                    "monitors" to JsonPrimitive(relay.monitorCount),
                    "lastSeen" to JsonPrimitive(relay.lastSeen),
                    "software" to JsonPrimitive(relay.software ?: ""),
                    "version" to JsonPrimitive(relay.version ?: ""),
                    "name" to JsonPrimitive(relay.name ?: ""),
                    "description" to JsonPrimitive(relay.description ?: ""),
                    "icon" to JsonPrimitive(relay.icon ?: ""),
                    "banner" to JsonPrimitive(relay.banner ?: ""),
                    "paymentRequired" to JsonPrimitive(relay.paymentRequired),
                    "authRequired" to JsonPrimitive(relay.authRequired),
                    "restrictedWrites" to JsonPrimitive(relay.restrictedWrites),
                    "hasNip11" to JsonPrimitive(relay.hasNip11),
                    "operatorPubkey" to JsonPrimitive(relay.operatorPubkey ?: ""),
                    "countryCode" to JsonPrimitive(relay.countryCode ?: ""),
                    "isp" to JsonPrimitive(relay.isp ?: ""),
                    "seenByMonitors" to JsonArray(relay.seenByMonitors.map { JsonPrimitive(it) })
                ))
            }
            val json = JsonArray(entries).toString()
            prefs?.edit()
                ?.putString(CACHE_KEY_RELAYS, json)
                ?.putLong(CACHE_KEY_TIMESTAMP, System.currentTimeMillis())
                ?.apply()
            Log.d(TAG, "Saved ${relays.size} discovered relays to disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save discovery cache: ${e.message}")
        }
    }

    private fun loadFromDisk() {
        try {
            val json = prefs?.getString(CACHE_KEY_RELAYS, null) ?: return
            val lastFetch = prefs?.getLong(CACHE_KEY_TIMESTAMP, 0L) ?: 0L
            if (System.currentTimeMillis() - lastFetch > CACHE_EXPIRY_MS) {
                Log.d(TAG, "Discovery cache expired, will re-fetch")
                return
            }

            val parsed = JSON.parseToJsonElement(json).jsonArray
            val relays = mutableMapOf<String, DiscoveredRelay>()

            for (element in parsed) {
                val entry = element.jsonObject
                val url = entry["url"]?.jsonPrimitive?.content ?: continue
                val typeStrings = entry["types"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val types = typeStrings.mapNotNull { RelayType.fromTag(it) }.toSet()
                val nips = entry["nips"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet() ?: emptySet()
                val reqs = entry["reqs"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
                val network = entry["network"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val rttOpen = entry["rttOpen"]?.jsonPrimitive?.int?.takeIf { it >= 0 }
                val rttRead = entry["rttRead"]?.jsonPrimitive?.int?.takeIf { it >= 0 }
                val rttWrite = entry["rttWrite"]?.jsonPrimitive?.int?.takeIf { it >= 0 }
                val topics = entry["topics"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
                val monitors = entry["monitors"]?.jsonPrimitive?.int ?: 0
                val lastSeen = entry["lastSeen"]?.jsonPrimitive?.long ?: 0L

                val softwareVal = entry["software"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val versionVal = entry["version"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val nameVal = entry["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val descriptionVal = entry["description"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val iconVal = entry["icon"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val bannerVal = entry["banner"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val paymentRequired = entry["paymentRequired"]?.jsonPrimitive?.boolean ?: false
                val authRequired = entry["authRequired"]?.jsonPrimitive?.boolean ?: false
                val restrictedWrites = entry["restrictedWrites"]?.jsonPrimitive?.boolean ?: false
                val hasNip11 = entry["hasNip11"]?.jsonPrimitive?.boolean ?: false
                val operatorPubkey = entry["operatorPubkey"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val countryCode = entry["countryCode"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val ispVal = entry["isp"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val seenByMonitors = entry["seenByMonitors"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()

                relays[url] = DiscoveredRelay(
                    url = url,
                    types = types,
                    supportedNips = nips,
                    requirements = reqs,
                    network = network,
                    avgRttOpen = rttOpen,
                    avgRttRead = rttRead,
                    avgRttWrite = rttWrite,
                    topics = topics,
                    monitorCount = monitors,
                    lastSeen = lastSeen,
                    software = softwareVal,
                    version = versionVal,
                    name = nameVal,
                    description = descriptionVal,
                    icon = iconVal,
                    banner = bannerVal,
                    paymentRequired = paymentRequired,
                    authRequired = authRequired,
                    restrictedWrites = restrictedWrites,
                    hasNip11 = hasNip11,
                    operatorPubkey = operatorPubkey,
                    countryCode = countryCode,
                    isp = ispVal,
                    seenByMonitors = seenByMonitors
                )
            }

            if (relays.isNotEmpty()) {
                _discoveredRelays.value = relays
                _hasFetched.value = true
                Log.d(TAG, "Loaded ${relays.size} discovered relays from disk cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load discovery cache: ${e.message}")
        }
    }

    /**
     * Refresh NIP-66 data if the cache is stale. Call from lifecycle ON_RESUME
     * to keep data fresh without blocking the UI. No-op if cache is fresh or
     * a fetch is already in-flight.
     */
    fun refreshIfStale() {
        if (_isLoading.value) return
        val lastFetch = prefs?.getLong(CACHE_KEY_TIMESTAMP, 0L) ?: 0L
        if (System.currentTimeMillis() - lastFetch < CACHE_EXPIRY_MS) return
        Log.d(TAG, "Cache stale, refreshing in background")
        fetchRelayDiscovery()
    }

    /**
     * Cancel in-flight fetches. NIP-66 data is global (shared across accounts)
     * so this does NOT clear the discovered relays or disk cache.
     */
    fun cancelFetches() {
        fetchHandle?.cancel()
        monitorFetchHandle?.cancel()
        fetchHandle = null
        monitorFetchHandle = null
    }

    /**
     * Full reset — clear all cached data and disk. Only for debug/settings.
     */
    fun clearAll() {
        cancelFetches()
        _discoveredRelays.value = emptyMap()
        _monitors.value = emptyList()
        _hasFetched.value = false
        _isLoading.value = false
        prefs?.edit()?.clear()?.apply()
    }

    private fun normalizeUrl(url: String): String {
        return url.trim().removeSuffix("/").lowercase()
    }
}
