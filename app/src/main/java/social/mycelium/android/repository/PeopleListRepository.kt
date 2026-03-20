package social.mycelium.android.repository

import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import com.example.cybin.signer.NostrSigner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle

/**
 * Repository for NIP-51 people lists (kind 30000) and hashtag interest list (kind 10015).
 *
 * People lists are addressable events keyed by a "d" tag. Each list contains
 * p-tags (pubkeys) and optionally a "title" tag for display.
 *
 * The hashtag interest list (kind 10015) is a replaceable event containing
 * t-tags for hashtags the user wants to follow in their home feed.
 *
 * Both are fetched during startup (Phase 1) from the user's outbox relays.
 */
object PeopleListRepository {

    private const val TAG = "PeopleListRepo"
    private const val KIND_PEOPLE_LIST = 30000
    private const val KIND_HASHTAG_LIST = 10015

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Coroutine failed: ${t.message}", t)
        }
    )

    // ── Data classes ─────────────────────────────────────────────────────────

    /** A single people list (kind 30000) parsed from a relay event. */
    data class PeopleList(
        val eventId: String,
        val dTag: String,
        val title: String,
        val description: String?,
        val image: String?,
        val pubkeys: Set<String>,
        val createdAt: Long,
        val rawEvent: Event,
    )

    // ── State ────────────────────────────────────────────────────────────────

    /** All people lists for the current user, keyed by d-tag for dedup. */
    private val _peopleLists = MutableStateFlow<List<PeopleList>>(emptyList())
    val peopleLists: StateFlow<List<PeopleList>> = _peopleLists.asStateFlow()

    /** Subscribed hashtags from kind-10015. Lowercased. */
    private val _subscribedHashtags = MutableStateFlow<Set<String>>(emptySet())
    val subscribedHashtags: StateFlow<Set<String>> = _subscribedHashtags.asStateFlow()

    /** Raw kind-10015 event (needed for mutations: add/remove then re-sign). */
    private var latestHashtagEvent: Event? = null

    /** Raw kind-30000 events keyed by d-tag. */
    private val latestPeopleEvents = mutableMapOf<String, Event>()

    private var peopleHandle: TemporarySubscriptionHandle? = null
    private var hashtagHandle: TemporarySubscriptionHandle? = null
    private var userPubkey: String? = null

    // ── Fetch ────────────────────────────────────────────────────────────────

    /**
     * Fetch the user's people lists (kind 30000) from relays.
     * Called during Phase 1 startup.
     */
    fun fetchPeopleLists(pubkey: String, relayUrls: List<String>) {
        if (relayUrls.isEmpty()) return
        userPubkey = pubkey

        val filter = Filter(
            kinds = listOf(KIND_PEOPLE_LIST),
            authors = listOf(pubkey),
            limit = 50
        )
        peopleHandle?.cancel()
        val stateMachine = RelayConnectionStateMachine.getInstance()
        val collected = mutableMapOf<String, Event>()

        peopleHandle = stateMachine.requestOneShotSubscription(
            relayUrls, filter, priority = SubscriptionPriority.LOW,
            settleMs = 800L, maxWaitMs = 8_000L,
        ) { event ->
            if (event.kind == KIND_PEOPLE_LIST && event.pubKey.equals(pubkey, ignoreCase = true)) {
                val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return@requestOneShotSubscription
                // Skip the "mute" d-tag — that's the NIP-51 block list, not a people list
                if (dTag == "mute") return@requestOneShotSubscription
                val existing = collected[dTag]
                if (existing == null || event.createdAt > existing.createdAt) {
                    collected[dTag] = event
                }
            }
        }

        // Parse after EOSE settles — the handle's onComplete fires when done
        scope.launch {
            // Give the one-shot subscription time to settle
            kotlinx.coroutines.delay(9_000L)
            if (collected.isEmpty()) {
                Log.d(TAG, "No people lists found for ${pubkey.take(8)}")
                return@launch
            }
            latestPeopleEvents.clear()
            latestPeopleEvents.putAll(collected)
            val lists = collected.values.mapNotNull { parsePeopleList(it) }
            _peopleLists.value = lists.sortedBy { it.title.lowercase() }
            Log.d(TAG, "Fetched ${lists.size} people lists for ${pubkey.take(8)}: ${lists.map { "${it.title}(${it.pubkeys.size})" }}")
        }

        Log.d(TAG, "Fetching people lists for ${pubkey.take(8)} from ${relayUrls.size} relays")
    }

    /**
     * Fetch the user's hashtag interest list (kind 10015) from relays.
     * Called during Phase 1 startup.
     */
    fun fetchHashtagList(pubkey: String, relayUrls: List<String>) {
        if (relayUrls.isEmpty()) return
        userPubkey = pubkey

        val filter = Filter(
            kinds = listOf(KIND_HASHTAG_LIST),
            authors = listOf(pubkey),
            limit = 1
        )
        hashtagHandle?.cancel()
        val stateMachine = RelayConnectionStateMachine.getInstance()

        hashtagHandle = stateMachine.requestOneShotSubscription(
            relayUrls, filter, priority = SubscriptionPriority.LOW,
            settleMs = 500L, maxWaitMs = 6_000L,
        ) { event ->
            if (event.kind == KIND_HASHTAG_LIST && event.pubKey.equals(pubkey, ignoreCase = true)) {
                val current = latestHashtagEvent
                if (current == null || event.createdAt > current.createdAt) {
                    latestHashtagEvent = event
                    parseHashtagList(event)
                }
            }
        }
        Log.d(TAG, "Fetching hashtag list for ${pubkey.take(8)} from ${relayUrls.size} relays")
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private fun parsePeopleList(event: Event): PeopleList? {
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return null
        val title = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)
            ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "name" }?.get(1)
            ?: dTag
        val description = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
        val image = event.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1)
        val pubkeys = event.tags.filter { it.size >= 2 && it[0] == "p" && it[1].length == 64 }
            .map { it[1].lowercase() }
            .toSet()

        return PeopleList(
            eventId = event.id,
            dTag = dTag,
            title = title,
            description = description,
            image = image,
            pubkeys = pubkeys,
            createdAt = event.createdAt,
            rawEvent = event,
        )
    }

    private fun parseHashtagList(event: Event) {
        val hashtags = event.tags
            .filter { it.size >= 2 && it[0] == "t" }
            .map { it[1].lowercase() }
            .toSet()
        _subscribedHashtags.value = hashtags
        Log.d(TAG, "Parsed hashtag list: ${hashtags.size} hashtags — $hashtags")
    }

    // ── Mutations ────────────────────────────────────────────────────────────

    /**
     * Create a new people list and publish to relays.
     */
    fun createPeopleList(
        title: String,
        description: String? = null,
        initialPubkeys: Set<String> = emptySet(),
        signer: NostrSigner,
        relayUrls: Set<String>,
    ) {
        scope.launch {
            try {
                val dTag = java.util.UUID.randomUUID().toString()
                val template = Event.build(KIND_PEOPLE_LIST) {
                    add(arrayOf("d", dTag))
                    add(arrayOf("title", title))
                    if (description != null) add(arrayOf("description", description))
                    initialPubkeys.forEach { pk -> add(arrayOf("p", pk.lowercase())) }
                }
                val signed = signer.sign(template)
                RelayConnectionStateMachine.getInstance().send(signed, relayUrls)
                latestPeopleEvents[dTag] = signed
                parsePeopleList(signed)?.let { newList ->
                    _peopleLists.value = (_peopleLists.value + newList).sortedBy { it.title.lowercase() }
                }
                Log.d(TAG, "Created people list '$title' (d=$dTag) with ${initialPubkeys.size} members")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create people list: ${e.message}", e)
            }
        }
    }

    /**
     * Add a pubkey to an existing people list.
     */
    fun addToPeopleList(
        dTag: String,
        pubkey: String,
        signer: NostrSigner,
        relayUrls: Set<String>,
    ) {
        scope.launch {
            try {
                val existing = latestPeopleEvents[dTag] ?: run {
                    Log.w(TAG, "No existing event for d=$dTag")
                    return@launch
                }
                val existingTags = existing.tags.toList()
                val alreadyPresent = existingTags.any { it.size >= 2 && it[0] == "p" && it[1].equals(pubkey, ignoreCase = true) }
                if (alreadyPresent) return@launch

                val newTags = existingTags + listOf(arrayOf("p", pubkey.lowercase()))
                val template = Event.build(KIND_PEOPLE_LIST, existing.content, existing.createdAt + 1) {
                    newTags.forEach { add(it) }
                }
                val signed = signer.sign(template)
                RelayConnectionStateMachine.getInstance().send(signed, relayUrls)
                latestPeopleEvents[dTag] = signed
                refreshListsFromCache()
                Log.d(TAG, "Added ${pubkey.take(8)} to list d=$dTag")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add to people list: ${e.message}", e)
            }
        }
    }

    /**
     * Remove a pubkey from an existing people list.
     */
    fun removeFromPeopleList(
        dTag: String,
        pubkey: String,
        signer: NostrSigner,
        relayUrls: Set<String>,
    ) {
        scope.launch {
            try {
                val existing = latestPeopleEvents[dTag] ?: return@launch
                val newTags = existing.tags.filter {
                    !(it.size >= 2 && it[0] == "p" && it[1].equals(pubkey, ignoreCase = true))
                }
                val template = Event.build(KIND_PEOPLE_LIST, existing.content, existing.createdAt + 1) {
                    newTags.forEach { add(it) }
                }
                val signed = signer.sign(template)
                RelayConnectionStateMachine.getInstance().send(signed, relayUrls)
                latestPeopleEvents[dTag] = signed
                refreshListsFromCache()
                Log.d(TAG, "Removed ${pubkey.take(8)} from list d=$dTag")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove from people list: ${e.message}", e)
            }
        }
    }

    /**
     * Delete a people list by publishing an empty replacement with the same d-tag.
     */
    fun deletePeopleList(
        dTag: String,
        signer: NostrSigner,
        relayUrls: Set<String>,
    ) {
        scope.launch {
            try {
                // Publish a kind-5 deletion event referencing the addressable coordinate
                val pubkey = userPubkey ?: return@launch
                val aTag = "$KIND_PEOPLE_LIST:$pubkey:$dTag"
                val template = Event.build(5) {
                    add(arrayOf("a", aTag))
                }
                val signed = signer.sign(template)
                RelayConnectionStateMachine.getInstance().send(signed, relayUrls)
                latestPeopleEvents.remove(dTag)
                _peopleLists.value = _peopleLists.value.filter { it.dTag != dTag }
                Log.d(TAG, "Deleted people list d=$dTag")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete people list: ${e.message}", e)
            }
        }
    }

    /**
     * Add a hashtag to the user's interest list (kind 10015).
     */
    fun followHashtag(
        hashtag: String,
        signer: NostrSigner,
        relayUrls: Set<String>,
    ) {
        scope.launch {
            try {
                val normalized = hashtag.lowercase().removePrefix("#")
                if (normalized in _subscribedHashtags.value) return@launch

                val existingTags = latestHashtagEvent?.tags?.toList() ?: emptyList()
                val newTags = existingTags + listOf(arrayOf("t", normalized))
                val template = Event.build(KIND_HASHTAG_LIST) {
                    newTags.forEach { add(it) }
                }
                val signed = signer.sign(template)
                RelayConnectionStateMachine.getInstance().send(signed, relayUrls)
                latestHashtagEvent = signed
                _subscribedHashtags.value = _subscribedHashtags.value + normalized
                Log.d(TAG, "Followed hashtag #$normalized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to follow hashtag: ${e.message}", e)
            }
        }
    }

    /**
     * Remove a hashtag from the user's interest list (kind 10015).
     */
    fun unfollowHashtag(
        hashtag: String,
        signer: NostrSigner,
        relayUrls: Set<String>,
    ) {
        scope.launch {
            try {
                val normalized = hashtag.lowercase().removePrefix("#")
                val existingTags = latestHashtagEvent?.tags?.toList() ?: emptyList()
                val newTags = existingTags.filter {
                    !(it.size >= 2 && it[0] == "t" && it[1].equals(normalized, ignoreCase = true))
                }
                val template = Event.build(KIND_HASHTAG_LIST) {
                    newTags.forEach { add(it) }
                }
                val signed = signer.sign(template)
                RelayConnectionStateMachine.getInstance().send(signed, relayUrls)
                latestHashtagEvent = signed
                _subscribedHashtags.value = _subscribedHashtags.value - normalized
                Log.d(TAG, "Unfollowed hashtag #$normalized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unfollow hashtag: ${e.message}", e)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun refreshListsFromCache() {
        val lists = latestPeopleEvents.values.mapNotNull { parsePeopleList(it) }
        _peopleLists.value = lists.sortedBy { it.title.lowercase() }
    }

    /** Get a people list by its d-tag. */
    fun getListByDTag(dTag: String): PeopleList? =
        _peopleLists.value.firstOrNull { it.dTag == dTag }

    /** Get the set of pubkeys in a specific people list. */
    fun getPubkeysForList(dTag: String): Set<String> =
        getListByDTag(dTag)?.pubkeys ?: emptySet()

    fun clearAll() {
        peopleHandle?.cancel()
        hashtagHandle?.cancel()
        peopleHandle = null
        hashtagHandle = null
        latestHashtagEvent = null
        latestPeopleEvents.clear()
        _peopleLists.value = emptyList()
        _subscribedHashtags.value = emptySet()
        userPubkey = null
    }
}
