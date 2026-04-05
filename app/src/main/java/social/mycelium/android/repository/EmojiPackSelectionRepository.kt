package social.mycelium.android.repository

import social.mycelium.android.debug.MLog
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.mycelium.android.relay.RelayConnectionStateMachine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Repository for the user's NIP-30 emoji pack selection list (kind 10030).
 *
 * Kind-10030 is a replaceable event whose `a`-tags reference kind-30030 emoji packs
 * the user has "saved" for quick access. This mirrors Amethyst's EmojiPackSelectionEvent.
 *
 * Lifecycle:
 * - Call [start] after login with the user's pubkey and relay URLs.
 * - Saved packs are fetched automatically; individual pack contents are fetched via [EmojiPackRepository].
 * - Call [addPack] / [removePack] to update the user's selection (publishes a new kind-10030).
 */
object EmojiPackSelectionRepository {

    private const val TAG = "EmojiPackSelectionRepo"
    private const val KIND_EMOJI_PACK_SELECTION = 10030
    private const val KIND_EMOJI_PACK = 30030
    private const val SETTLE_QUIET_MS = 3000L
    private const val MAX_WAIT_MS = 10000L

    /**
     * An address reference to a kind-30030 emoji pack: "30030:pubkey:dtag".
     */
    data class PackAddress(
        val author: String,
        val dTag: String,
        val relayHint: String? = null
    ) {
        val coordinate: String get() = "$KIND_EMOJI_PACK:$author:$dTag"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Public state ────────────────────────────────────────────────────
    /** The user's saved pack addresses (parsed from kind-10030 a-tags). */
    private val _savedPacks = MutableStateFlow<List<PackAddress>>(emptyList())
    val savedPacks: StateFlow<List<PackAddress>> = _savedPacks.asStateFlow()

    /** All emojis from all saved packs, merged: shortcode → URL. */
    private val _allSavedEmojis = MutableStateFlow<Map<String, String>>(emptyMap())
    val allSavedEmojis: StateFlow<Map<String, String>> = _allSavedEmojis.asStateFlow()

    /** Whether initial fetch is in progress. */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ── Internal state ──────────────────────────────────────────────────
    /** The raw kind-10030 event (needed to build updates that preserve existing tags). */
    @Volatile
    private var currentEvent: Event? = null

    /** The user's pubkey (set on start). */
    @Volatile
    private var userPubkey: String? = null

    /** Relay URLs for fetching/publishing. */
    @Volatile
    private var relayUrls: List<String> = emptyList()

    /** Set of pack coordinates already being fetched. */
    private val fetchingPacks = ConcurrentHashMap.newKeySet<String>()

    /**
     * Start fetching the user's kind-10030 selection event.
     * Call once after login.
     */
    fun start(pubkey: String, relays: List<String>) {
        userPubkey = pubkey
        relayUrls = relays
        if (relays.isEmpty()) {
            MLog.w(TAG, "No relays provided for emoji pack selection fetch")
            return
        }

        _isLoading.value = true
        scope.launch {
            fetchSelectionEvent(pubkey, relays)
            _isLoading.value = false
        }
    }

    // ── Global action callbacks (set by AccountStateViewModel, invoked by EmojiPackGrid) ──
    @Volatile
    var onAddPackAction: ((author: String, dTag: String, relayHint: String?) -> Unit)? = null
    @Volatile
    var onRemovePackAction: ((author: String, dTag: String) -> Unit)? = null

    /** Clear state (call on logout). */
    fun reset() {
        onAddPackAction = null
        onRemovePackAction = null
        userPubkey = null
        relayUrls = emptyList()
        currentEvent = null
        _savedPacks.value = emptyList()
        _allSavedEmojis.value = emptyMap()
        fetchingPacks.clear()
    }

    /**
     * Check if a pack is saved by the user.
     */
    fun isSaved(author: String, dTag: String): Boolean {
        val coord = "$KIND_EMOJI_PACK:$author:$dTag"
        return _savedPacks.value.any { it.coordinate == coord }
    }

    /**
     * Build a kind-10030 event template that adds the given pack.
     * Returns the tags array for the new event, or null if already saved.
     */
    fun buildAddPackTags(author: String, dTag: String, relayHint: String? = null): Array<Array<String>>? {
        if (isSaved(author, dTag)) return null

        val existing = currentEvent
        val existingTags = existing?.tags?.toList() ?: emptyList()

        // Build new a-tag: ["a", "30030:author:dtag", "relay_hint"]
        val aTagValue = "$KIND_EMOJI_PACK:$author:$dTag"
        val newATag = if (relayHint != null) {
            arrayOf("a", aTagValue, relayHint)
        } else {
            arrayOf("a", aTagValue)
        }

        return (existingTags + arrayOf(newATag)).toTypedArray()
    }

    /**
     * Build a kind-10030 event template that removes the given pack.
     * Returns the tags array for the new event, or null if not saved.
     */
    fun buildRemovePackTags(author: String, dTag: String): Array<Array<String>>? {
        if (!isSaved(author, dTag)) return null

        val existing = currentEvent
        val existingTags = existing?.tags?.toList() ?: return null

        val aTagValue = "$KIND_EMOJI_PACK:$author:$dTag"
        val filtered = existingTags.filter { tag ->
            !(tag.size >= 2 && tag[0] == "a" && tag[1] == aTagValue)
        }

        return filtered.toTypedArray()
    }

    /**
     * Called after a kind-10030 event is successfully published to update local state.
     */
    fun onPublished(event: Event) {
        processSelectionEvent(event)
        MLog.d(TAG, "Updated selection after publish: ${_savedPacks.value.size} packs")
    }

    // ── Internal: fetch and parse ───────────────────────────────────────

    private fun fetchSelectionEvent(pubkey: String, relays: List<String>) {
        val filter = Filter(
            kinds = listOf(KIND_EMOJI_PACK_SELECTION),
            authors = listOf(pubkey),
            limit = 1
        )

        MLog.d(TAG, "Fetching kind-10030 for ${pubkey.take(8)}… on ${relays.size} relays")
        val lastEventAt = AtomicLong(0L)

        val fallbackRelays = (relays + listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://purplepag.es",
            "wss://relay.nostr.band",
        )).distinct()

        RelayConnectionStateMachine.getInstance().requestOneShotSubscription(
            relayUrls = fallbackRelays,
            filter = filter,
            priority = SubscriptionPriority.HIGH,
            onEvent = { event ->
                if (event.kind == KIND_EMOJI_PACK_SELECTION && event.pubKey == pubkey) {
                    lastEventAt.set(System.currentTimeMillis())
                    // Only keep the newest version (replaceable event)
                    val existing = currentEvent
                    if (existing == null || event.createdAt > existing.createdAt) {
                        processSelectionEvent(event)
                    }
                }
            }
        )

        // Wait for events to settle
        scope.launch {
            var waited = 0L
            while (waited < MAX_WAIT_MS) {
                delay(200)
                waited += 200
                val lastAt = lastEventAt.get()
                if (lastAt > 0 && System.currentTimeMillis() - lastAt >= SETTLE_QUIET_MS) break
            }
            // After settling, fetch all saved pack contents
            fetchAllSavedPackContents()
        }
    }

    private fun processSelectionEvent(event: Event) {
        currentEvent = event
        val packs = mutableListOf<PackAddress>()

        for (tag in event.tags) {
            if (tag.size >= 2 && tag[0] == "a") {
                val parts = tag[1].split(":")
                if (parts.size >= 3 && parts[0] == KIND_EMOJI_PACK.toString()) {
                    val author = parts[1]
                    val dTag = parts.drop(2).joinToString(":") // d-tag may contain colons
                    val relayHint = tag.getOrNull(2)?.takeIf { it.isNotBlank() }
                    packs.add(PackAddress(author, dTag, relayHint))
                }
            }
        }

        _savedPacks.value = packs
        MLog.d(TAG, "Parsed ${packs.size} saved emoji packs from kind-10030")
    }

    /**
     * Fetch all saved packs' kind-30030 content via [EmojiPackRepository] and
     * build the merged emoji map.
     */
    private fun fetchAllSavedPackContents() {
        val packs = _savedPacks.value
        if (packs.isEmpty()) return

        for (pack in packs) {
            val address = pack.coordinate
            if (EmojiPackRepository.getCached(address) != null) continue
            if (!fetchingPacks.add(address)) continue

            val hints = listOfNotNull(pack.relayHint) + relayUrls
            EmojiPackRepository.fetchIfNeeded(pack.author, pack.dTag, hints)
        }

        // Poll for pack fetch completion and rebuild merged emoji map
        scope.launch {
            var attempts = 0
            while (attempts < 30) { // up to ~15 seconds
                delay(500)
                attempts++
                rebuildMergedEmojis()
                // Check if all packs are loaded
                val allLoaded = packs.all { EmojiPackRepository.getCached(it.coordinate) != null }
                if (allLoaded) break
            }
            rebuildMergedEmojis()
            fetchingPacks.clear()
            MLog.d(TAG, "All saved pack contents fetched: ${_allSavedEmojis.value.size} total emojis")
        }
    }

    /** Rebuild the merged shortcode→URL map from all saved packs. */
    fun rebuildMergedEmojis() {
        val packs = _savedPacks.value
        val merged = mutableMapOf<String, String>()
        for (pack in packs) {
            val cached = EmojiPackRepository.getCached(pack.coordinate)
            if (cached != null) {
                merged.putAll(cached.emojis)
            }
        }
        _allSavedEmojis.value = merged
    }

    /**
     * Get the saved packs with their resolved emoji content (for UI display).
     * Returns list of (PackAddress, EmojiPack?) pairs.
     */
    fun getSavedPacksWithContent(): List<Pair<PackAddress, EmojiPackRepository.EmojiPack?>> {
        return _savedPacks.value.map { addr ->
            addr to EmojiPackRepository.getCached(addr.coordinate)
        }
    }
}
