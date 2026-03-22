package social.mycelium.android.repository

import android.content.Context
import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import social.mycelium.android.db.AppDatabase
import social.mycelium.android.db.CachedEmojiPackEntity
import social.mycelium.android.relay.RelayConnectionStateMachine
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for NIP-30 Emoji Pack events (kind 30030).
 *
 * Emoji packs are addressable (parameterized replaceable) events with:
 * - kind: 30030
 * - d-tag: unique identifier for the pack
 * - emoji tags: ["emoji", "shortcode", "url"]
 *
 * Notes reference emoji packs via naddr (nostr:naddr1...) pointing to kind 30030.
 * This repository fetches those events on demand and caches the shortcode→URL maps.
 */
object EmojiPackRepository {

    private const val TAG = "EmojiPackRepo"
    private const val KIND_EMOJI_PACK = 30030

    data class EmojiPack(
        val name: String,
        val author: String,
        val dTag: String,
        val emojis: Map<String, String>, // shortcode → URL
        val createdAt: Long
    )

    /** Cache keyed by "kind:author:dtag" address coordinate. */
    private val cache = ConcurrentHashMap<String, EmojiPack>()

    /** Observable map of all fetched packs. */
    private val _packs = MutableStateFlow<Map<String, EmojiPack>>(emptyMap())
    val packs: StateFlow<Map<String, EmojiPack>> = _packs.asStateFlow()

    /** Addresses currently being fetched (dedup). */
    private val pendingFetches = ConcurrentHashMap.newKeySet<String>()

    /** User's relay URLs for broadened pack fetching. Set via [setUserRelays]. */
    @Volatile
    private var userRelayUrls: List<String> = emptyList()

    fun setUserRelays(relays: List<String>) { userRelayUrls = relays }

    /** App context for Room DB access. Set via [init]. */
    @Volatile
    private var appContext: Context? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initialize Room persistence. Call once from startup.
     * Loads any previously-cached packs from Room for instant availability.
     */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch { loadFromRoom() }
    }

    private suspend fun loadFromRoom() {
        val ctx = appContext ?: return
        withContext(Dispatchers.IO) {
            try {
                val dao = AppDatabase.getInstance(ctx).emojiPackDao()
                val entities = dao.getAll()
                if (entities.isEmpty()) return@withContext
                var loaded = 0
                for (entity in entities) {
                    val emojis = deserializeEmojis(entity.emojisJson)
                    val pack = EmojiPack(
                        name = entity.name,
                        author = entity.author,
                        dTag = entity.dTag,
                        emojis = emojis,
                        createdAt = entity.createdAt
                    )
                    cache[entity.address] = pack
                    loaded++
                }
                _packs.value = cache.toMap()
                Log.d(TAG, "Loaded $loaded emoji packs from Room cache")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load emoji packs from Room: ${e.message}")
            }
        }
    }

    private fun persistToRoom(address: String, pack: EmojiPack) {
        val ctx = appContext ?: return
        scope.launch {
            try {
                val dao = AppDatabase.getInstance(ctx).emojiPackDao()
                val entity = CachedEmojiPackEntity(
                    address = address,
                    name = pack.name,
                    author = pack.author,
                    dTag = pack.dTag,
                    emojisJson = serializeEmojis(pack.emojis),
                    createdAt = pack.createdAt
                )
                dao.insert(entity)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist emoji pack to Room: ${e.message}")
            }
        }
    }

    private fun serializeEmojis(emojis: Map<String, String>): String {
        val json = JSONObject()
        for ((k, v) in emojis) json.put(k, v)
        return json.toString()
    }

    private fun deserializeEmojis(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val obj = JSONObject(json)
            for (key in obj.keys()) result[key] = obj.getString(key)
        } catch (_: Exception) { }
        return result
    }

    /**
     * Get a cached emoji pack by its address coordinate ("30030:pubkey:dtag").
     * Returns null if not yet fetched.
     */
    fun getCached(address: String): EmojiPack? = cache[address]

    /**
     * Fetch an emoji pack by its address coordinate if not already cached.
     * @param author pubkey of the pack author
     * @param dTag d-tag of the pack
     * @param relayHints optional relay hints from the naddr
     */
    fun fetchIfNeeded(author: String, dTag: String, relayHints: List<String> = emptyList()) {
        val address = "$KIND_EMOJI_PACK:$author:$dTag"
        if (cache.containsKey(address)) return
        if (!pendingFetches.add(address)) return // already fetching

        scope.launch {
            try {
                fetchEmojiPack(author, dTag, relayHints, address)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch emoji pack $address: ${e.message}")
            } finally {
                pendingFetches.remove(address)
            }
        }
    }

    private val FALLBACK_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
        "wss://purplepag.es",
    )

    private fun fetchEmojiPack(author: String, dTag: String, relayHints: List<String>, address: String) {
        // Always combine: relay hints + author outbox relays + user relays + fallbacks.
        // Previously only hints OR fallbacks were used, causing packs to silently fail
        // when the hint relay was offline.
        val authorOutbox = Nip65RelayListRepository.getCachedOutboxRelays(author).orEmpty()
        val relays = social.mycelium.android.relay.RelayHealthTracker.filterBlocked(
            (relayHints + authorOutbox + userRelayUrls + FALLBACK_RELAYS).distinct()
        ).orEmpty()

        val filter = Filter(
            kinds = listOf(KIND_EMOJI_PACK),
            authors = listOf(author),
            tags = mapOf("d" to listOf(dTag)),
            limit = 1
        )

        Log.d(TAG, "Fetching emoji pack: author=${author.take(8)}, dTag=$dTag on ${relays.size} relays (hints=${relayHints.size}, outbox=${authorOutbox.size})")

        val stateMachine = RelayConnectionStateMachine.getInstance()
        stateMachine.requestOneShotSubscription(
            relayUrls = relays,
            filter = filter,
            priority = SubscriptionPriority.LOW,
            onEvent = { event -> processEmojiPackEvent(event, address) }
        )
    }

    private fun processEmojiPackEvent(event: Event, address: String) {
        if (event.kind != KIND_EMOJI_PACK) return

        val emojis = mutableMapOf<String, String>()
        var name = ""
        var dTag = ""

        for (tag in event.tags) {
            when {
                tag.size >= 3 && tag[0] == "emoji" -> {
                    val shortcode = tag[1]
                    val url = tag[2]
                    if (shortcode.isNotBlank() && url.isNotBlank()) {
                        emojis[":$shortcode:"] = url
                    }
                }
                tag.size >= 2 && tag[0] == "d" -> dTag = tag[1]
                tag.size >= 2 && tag[0] == "name" -> name = tag[1]
                tag.size >= 2 && tag[0] == "title" -> if (name.isBlank()) name = tag[1]
            }
        }

        if (name.isBlank()) name = dTag.ifBlank { "Emoji Pack" }

        val pack = EmojiPack(
            name = name,
            author = event.pubKey,
            dTag = dTag,
            emojis = emojis,
            createdAt = event.createdAt
        )

        // Only keep newest version (addressable events are replaceable)
        val existing = cache[address]
        if (existing != null && existing.createdAt >= pack.createdAt) return

        cache[address] = pack
        _packs.value = cache.toMap()
        persistToRoom(address, pack)
        Log.d(TAG, "Cached emoji pack '$name' ($address) with ${emojis.size} emojis")
    }

    /**
     * Extract all emoji URLs from a cached pack.
     */
    fun getEmojiUrls(address: String): Map<String, String> {
        return cache[address]?.emojis ?: emptyMap()
    }
}
