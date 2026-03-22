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
        /** All members (public + private). Use this for feed filtering. */
        val pubkeys: Set<String>,
        /** Members visible to everyone (in event tags). */
        val publicPubkeys: Set<String>,
        /** Members only visible to the list owner (encrypted in event content). */
        val privatePubkeys: Set<String>,
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

    /** Signer cached from fetchPeopleLists — used for decrypt/re-encrypt during mutations. */
    private var cachedSigner: NostrSigner? = null

    /**
     * Fetch the user's people lists (kind 30000) from relays.
     * Called during Phase 1 startup.
     *
     * @param signer Required to decrypt NIP-51 private tags (encrypted in event.content).
     *               Without a signer, only public members are visible.
     */
    fun fetchPeopleLists(pubkey: String, relayUrls: List<String>, signer: NostrSigner? = null) {
        if (relayUrls.isEmpty()) return
        userPubkey = pubkey
        if (signer != null) cachedSigner = signer

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
                // Skip lists we've marked as deleted (empty replacement with "deleted" tag)
                val isDeleted = event.tags.any { it.size >= 2 && it[0] == "deleted" }
                if (isDeleted) return@requestOneShotSubscription
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
            val effectiveSigner = signer ?: cachedSigner
            val lists = collected.values.mapNotNull { parsePeopleList(it, effectiveSigner) }
            _peopleLists.value = lists.sortedBy { it.title.lowercase() }
            Log.d(TAG, "Fetched ${lists.size} people lists for ${pubkey.take(8)}: ${lists.map { "${it.title}(pub=${it.publicPubkeys.size},priv=${it.privatePubkeys.size})" }}")
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

    /**
     * Parse a people list event, decrypting NIP-51 private tags from content.
     *
     * NIP-51 specifies that kind-30000 events may have encrypted content containing
     * additional tags (p-tags for private members). The content is NIP-44 encrypted
     * to the event author's own key. Amethyst stores private list members this way.
     */
    private suspend fun parsePeopleList(event: Event, signer: NostrSigner? = null): PeopleList? {
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return null
        val title = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)
            ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "name" }?.get(1)
            ?: dTag
        val description = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
        val image = event.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1)
        val publicPubkeys = event.tags.filter { it.size >= 2 && it[0] == "p" && it[1].length == 64 }
            .map { it[1].lowercase() }
            .toSet()

        // Decrypt private tags from NIP-44 encrypted content
        var privatePubkeys = emptySet<String>()
        if (signer != null && event.content.isNotBlank()) {
            try {
                val decryptedJson = signer.nip44Decrypt(event.content, signer.pubKey)
                if (decryptedJson.isNotBlank()) {
                    // Content is a JSON array of tag arrays: [["p","hex"],["p","hex"],...]
                    val privateTags = parseTagArrayJson(decryptedJson)
                    privatePubkeys = privateTags
                        .filter { it.size >= 2 && it[0] == "p" && it[1].length == 64 }
                        .map { it[1].lowercase() }
                        .toSet()
                    if (privatePubkeys.isNotEmpty()) {
                        Log.d(TAG, "Decrypted ${privatePubkeys.size} private members for list '$title' (d=$dTag)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt private tags for list '$title' (d=$dTag): ${e.message}")
            }
        }

        return PeopleList(
            eventId = event.id,
            dTag = dTag,
            title = title,
            description = description,
            image = image,
            pubkeys = publicPubkeys + privatePubkeys,
            publicPubkeys = publicPubkeys,
            privatePubkeys = privatePubkeys,
            createdAt = event.createdAt,
            rawEvent = event,
        )
    }

    /**
     * Parse a JSON array of tag arrays: [["p","hex"],["t","tag"],...]
     * Returns List<Array<String>>.
     */
    private fun parseTagArrayJson(json: String): List<Array<String>> {
        return try {
            val trimmed = json.trim()
            if (!trimmed.startsWith("[")) return emptyList()
            val result = mutableListOf<Array<String>>()
            val org = org.json.JSONArray(trimmed)
            for (i in 0 until org.length()) {
                val inner = org.optJSONArray(i) ?: continue
                val tag = Array(inner.length()) { j -> inner.optString(j, "") }
                if (tag.isNotEmpty()) result.add(tag)
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse private tag JSON: ${e.message}")
            emptyList()
        }
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
     *
     * @param isPrivate If true, members are encrypted in the content field (NIP-51 private tags).
     */
    fun createPeopleList(
        title: String,
        description: String? = null,
        initialPubkeys: Set<String> = emptySet(),
        isPrivate: Boolean = false,
        signer: NostrSigner,
        relayUrls: Set<String>,
    ) {
        scope.launch {
            try {
                val dTag = java.util.UUID.randomUUID().toString()
                val privateTags = if (isPrivate && initialPubkeys.isNotEmpty()) {
                    initialPubkeys.map { pk -> arrayOf("p", pk.lowercase()) }
                } else emptyList()
                val encryptedContent = if (privateTags.isNotEmpty()) {
                    val json = org.json.JSONArray().apply {
                        privateTags.forEach { tag -> put(org.json.JSONArray(tag)) }
                    }.toString()
                    signer.nip44Encrypt(json, signer.pubKey)
                } else ""

                val template = Event.build(KIND_PEOPLE_LIST, encryptedContent) {
                    add(arrayOf("d", dTag))
                    add(arrayOf("title", title))
                    if (description != null) add(arrayOf("description", description))
                    if (!isPrivate) {
                        initialPubkeys.forEach { pk -> add(arrayOf("p", pk.lowercase())) }
                    }
                }
                val signed = signer.sign(template)
                RelayConnectionStateMachine.getInstance().send(signed, relayUrls)
                latestPeopleEvents[dTag] = signed
                parsePeopleList(signed, signer)?.let { newList ->
                    _peopleLists.value = (_peopleLists.value + newList).sortedBy { it.title.lowercase() }
                }
                Log.d(TAG, "Created people list '$title' (d=$dTag) with ${initialPubkeys.size} members (private=$isPrivate)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create people list: ${e.message}", e)
            }
        }
    }

    /**
     * Add a pubkey to an existing people list.
     *
     * @param isPrivate If true, the member is added as a private (encrypted) member.
     */
    fun addToPeopleList(
        dTag: String,
        pubkey: String,
        isPrivate: Boolean = false,
        signer: NostrSigner,
        relayUrls: Set<String>,
    ) {
        scope.launch {
            try {
                val existing = latestPeopleEvents[dTag] ?: run {
                    Log.w(TAG, "No existing event for d=$dTag")
                    return@launch
                }
                // Check both public and private members for duplicates
                val currentList = _peopleLists.value.firstOrNull { it.dTag == dTag }
                if (currentList != null && pubkey.lowercase() in currentList.pubkeys) return@launch

                // Decrypt existing private tags
                val existingPrivateTags = decryptPrivateTags(existing, signer)
                val existingPublicTags = existing.tags.toList()

                val newPublicTags: List<Array<String>>
                val newPrivateTags: List<Array<String>>
                if (isPrivate) {
                    newPublicTags = existingPublicTags
                    newPrivateTags = existingPrivateTags + listOf(arrayOf("p", pubkey.lowercase()))
                } else {
                    newPublicTags = existingPublicTags + listOf(arrayOf("p", pubkey.lowercase()))
                    newPrivateTags = existingPrivateTags
                }

                val encryptedContent = encryptPrivateTags(newPrivateTags, signer)
                val template = Event.build(KIND_PEOPLE_LIST, encryptedContent, existing.createdAt + 1) {
                    newPublicTags.forEach { add(it) }
                }
                val signed = signer.sign(template)
                RelayConnectionStateMachine.getInstance().send(signed, relayUrls)
                latestPeopleEvents[dTag] = signed
                refreshListsFromCache(signer)
                Log.d(TAG, "Added ${pubkey.take(8)} to list d=$dTag (private=$isPrivate)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add to people list: ${e.message}", e)
            }
        }
    }

    /**
     * Remove a pubkey from an existing people list (from both public and private members).
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
                // Remove from public tags
                val newPublicTags = existing.tags.filter {
                    !(it.size >= 2 && it[0] == "p" && it[1].equals(pubkey, ignoreCase = true))
                }
                // Remove from private tags
                val existingPrivateTags = decryptPrivateTags(existing, signer)
                val newPrivateTags = existingPrivateTags.filter {
                    !(it.size >= 2 && it[0] == "p" && it[1].equals(pubkey, ignoreCase = true))
                }

                val encryptedContent = encryptPrivateTags(newPrivateTags, signer)
                val template = Event.build(KIND_PEOPLE_LIST, encryptedContent, existing.createdAt + 1) {
                    newPublicTags.forEach { add(it) }
                }
                val signed = signer.sign(template)
                RelayConnectionStateMachine.getInstance().send(signed, relayUrls)
                latestPeopleEvents[dTag] = signed
                refreshListsFromCache(signer)
                Log.d(TAG, "Removed ${pubkey.take(8)} from list d=$dTag")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove from people list: ${e.message}", e)
            }
        }
    }

    /**
     * Delete a people list by publishing an empty replacement AND a kind-5 deletion.
     *
     * Many relays ignore kind-5 for addressable events, so the list reappears on next fetch.
     * The proper approach is two-pronged:
     * 1. Publish a kind-5 deletion event referencing the addressable coordinate.
     * 2. Publish an empty replacement event (same d-tag, no p-tags, empty content)
     *    with a newer timestamp. This overwrites the list on relays that don't honor kind-5.
     */
    fun deletePeopleList(
        dTag: String,
        signer: NostrSigner,
        relayUrls: Set<String>,
    ) {
        scope.launch {
            try {
                val pubkey = userPubkey ?: return@launch
                val aTag = "$KIND_PEOPLE_LIST:$pubkey:$dTag"
                val existing = latestPeopleEvents[dTag]
                val newTimestamp = (existing?.createdAt ?: (System.currentTimeMillis() / 1000)) + 1

                // Prong 1: Kind-5 deletion event (Amethyst-style: e + a + k + p tags)
                val deleteTemplate = Event.build(5) {
                    if (existing != null) add(arrayOf("e", existing.id))
                    add(arrayOf("a", aTag))
                    add(arrayOf("k", KIND_PEOPLE_LIST.toString()))
                    add(arrayOf("p", pubkey))
                }
                val deleteSigned = signer.sign(deleteTemplate)
                RelayConnectionStateMachine.getInstance().send(deleteSigned, relayUrls)

                // Prong 2: Empty replacement (overwrites the list on relays that ignore kind-5)
                val emptyTemplate = Event.build(KIND_PEOPLE_LIST, "", newTimestamp) {
                    add(arrayOf("d", dTag))
                    add(arrayOf("deleted", "true"))
                }
                val emptySigned = signer.sign(emptyTemplate)
                RelayConnectionStateMachine.getInstance().send(emptySigned, relayUrls)

                latestPeopleEvents.remove(dTag)
                _peopleLists.value = _peopleLists.value.filter { it.dTag != dTag }
                Log.d(TAG, "Deleted people list d=$dTag (kind-5 + empty replacement)")
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

    private fun refreshListsFromCache(signer: NostrSigner? = null) {
        val effectiveSigner = signer ?: cachedSigner
        scope.launch {
            val lists = latestPeopleEvents.values.mapNotNull { parsePeopleList(it, effectiveSigner) }
            _peopleLists.value = lists.sortedBy { it.title.lowercase() }
        }
    }

    /**
     * Decrypt private tags from an event's NIP-44 encrypted content.
     * Returns an empty list if content is blank or decryption fails.
     */
    private suspend fun decryptPrivateTags(event: Event, signer: NostrSigner): List<Array<String>> {
        if (event.content.isBlank()) return emptyList()
        return try {
            val json = signer.nip44Decrypt(event.content, signer.pubKey)
            if (json.isNotBlank()) parseTagArrayJson(json) else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt private tags: ${e.message}")
            emptyList()
        }
    }

    /**
     * Encrypt private tags into NIP-44 content for publishing.
     * Returns empty string if no private tags.
     */
    private suspend fun encryptPrivateTags(tags: List<Array<String>>, signer: NostrSigner): String {
        if (tags.isEmpty()) return ""
        val json = org.json.JSONArray().apply {
            tags.forEach { tag -> put(org.json.JSONArray(tag)) }
        }.toString()
        return signer.nip44Encrypt(json, signer.pubKey)
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
