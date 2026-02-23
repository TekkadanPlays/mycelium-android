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
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for NIP-51 mute list (kind 10000) and local block list.
 *
 * Mute list: replaceable event kind 10000. Contains p-tags for muted pubkeys,
 * t-tags for muted hashtags, word-tags for muted words, e-tags for muted threads.
 *
 * Block list: local-only for now (not published to relays). Blocked users'
 * notes are completely hidden rather than just muted.
 */
object MuteListRepository {

    private const val TAG = "MuteListRepo"
    private const val KIND_MUTE_LIST = 10000

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Coroutine failed: ${t.message}", t)
        }
    )

    /** Set of muted pubkeys (from kind-10000 p-tags). */
    private val _mutedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val mutedPubkeys: StateFlow<Set<String>> = _mutedPubkeys.asStateFlow()

    /** Set of muted hashtags (from kind-10000 t-tags). */
    private val _mutedHashtags = MutableStateFlow<Set<String>>(emptySet())
    val mutedHashtags: StateFlow<Set<String>> = _mutedHashtags.asStateFlow()

    /** Set of muted words (from kind-10000 word-tags). */
    private val _mutedWords = MutableStateFlow<Set<String>>(emptySet())
    val mutedWords: StateFlow<Set<String>> = _mutedWords.asStateFlow()

    /** Locally blocked pubkeys (notes completely hidden). */
    private val _blockedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val blockedPubkeys: StateFlow<Set<String>> = _blockedPubkeys.asStateFlow()

    private var muteHandle: TemporarySubscriptionHandle? = null
    private var latestMuteEvent: Event? = null
    private var userPubkey: String? = null

    /**
     * Fetch the user's mute list from relays.
     */
    fun fetchMuteList(pubkey: String, relayUrls: List<String>) {
        userPubkey = pubkey
        val filter = Filter(
            kinds = listOf(KIND_MUTE_LIST),
            authors = listOf(pubkey),
            limit = 1
        )
        muteHandle?.cancel()
        val stateMachine = RelayConnectionStateMachine.getInstance()
        muteHandle = stateMachine.requestOneShotSubscription(
            relayUrls, filter, priority = SubscriptionPriority.LOW,
            settleMs = 500L, maxWaitMs = 6_000L,
        ) { event ->
            if (event.kind == KIND_MUTE_LIST && event.pubKey.equals(pubkey, ignoreCase = true)) {
                // Keep the latest version (highest created_at)
                val current = latestMuteEvent
                if (current == null || event.createdAt > current.createdAt) {
                    latestMuteEvent = event
                    parseMuteList(event)
                }
            }
        }
        Log.d(TAG, "Fetching mute list (one-shot) for ${pubkey.take(8)} on ${relayUrls.size} relays")
    }

    private fun parseMuteList(event: Event) {
        val pubkeys = mutableSetOf<String>()
        val hashtags = mutableSetOf<String>()
        val words = mutableSetOf<String>()

        for (tag in event.tags) {
            when (tag.firstOrNull()) {
                "p" -> tag.getOrNull(1)?.let { pubkeys.add(it.lowercase()) }
                "t" -> tag.getOrNull(1)?.let { hashtags.add(it.lowercase()) }
                "word" -> tag.getOrNull(1)?.let { words.add(it.lowercase()) }
            }
        }

        _mutedPubkeys.value = pubkeys
        _mutedHashtags.value = hashtags
        _mutedWords.value = words
        Log.d(TAG, "Parsed mute list: ${pubkeys.size} pubkeys, ${hashtags.size} hashtags, ${words.size} words")
    }

    /**
     * Mute a user by publishing an updated kind-10000 event.
     */
    fun muteUser(targetPubkey: String, signer: NostrSigner, relayUrls: Set<String>) {
        scope.launch {
            try {
                val current = _mutedPubkeys.value
                if (targetPubkey.lowercase() in current) {
                    Log.d(TAG, "Already muted ${targetPubkey.take(8)}")
                    return@launch
                }

                val updated = current + targetPubkey.lowercase()
                publishMuteList(updated, _mutedHashtags.value, _mutedWords.value, signer, relayUrls)
                _mutedPubkeys.value = updated
                Log.d(TAG, "Muted ${targetPubkey.take(8)}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mute user: ${e.message}", e)
            }
        }
    }

    /**
     * Unmute a user by publishing an updated kind-10000 event.
     */
    fun unmuteUser(targetPubkey: String, signer: NostrSigner, relayUrls: Set<String>) {
        scope.launch {
            try {
                val updated = _mutedPubkeys.value - targetPubkey.lowercase()
                publishMuteList(updated, _mutedHashtags.value, _mutedWords.value, signer, relayUrls)
                _mutedPubkeys.value = updated
                Log.d(TAG, "Unmuted ${targetPubkey.take(8)}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unmute user: ${e.message}", e)
            }
        }
    }

    /**
     * Block a user locally (not published to relays).
     */
    fun blockUser(targetPubkey: String) {
        _blockedPubkeys.value = _blockedPubkeys.value + targetPubkey.lowercase()
        Log.d(TAG, "Blocked ${targetPubkey.take(8)} locally")
    }

    /**
     * Unblock a user locally.
     */
    fun unblockUser(targetPubkey: String) {
        _blockedPubkeys.value = _blockedPubkeys.value - targetPubkey.lowercase()
        Log.d(TAG, "Unblocked ${targetPubkey.take(8)} locally")
    }

    /**
     * Check if a pubkey is muted or blocked.
     */
    fun isHidden(pubkey: String): Boolean {
        val lower = pubkey.lowercase()
        return lower in _mutedPubkeys.value || lower in _blockedPubkeys.value
    }

    private suspend fun publishMuteList(
        pubkeys: Set<String>,
        hashtags: Set<String>,
        words: Set<String>,
        signer: NostrSigner,
        relayUrls: Set<String>
    ) {
        val template = com.example.cybin.core.Event.build(KIND_MUTE_LIST) {
            pubkeys.forEach { add(arrayOf("p", it)) }
            hashtags.forEach { add(arrayOf("t", it)) }
            words.forEach { add(arrayOf("word", it)) }
        }
        val signed = signer.sign(template)
        RelayConnectionStateMachine.getInstance().send(signed, relayUrls)
        latestMuteEvent = signed
        Log.d(TAG, "Published mute list with ${pubkeys.size} pubkeys")
    }

    fun clearAll() {
        muteHandle?.cancel()
        muteHandle = null
        latestMuteEvent = null
        _mutedPubkeys.value = emptySet()
        _mutedHashtags.value = emptySet()
        _mutedWords.value = emptySet()
        _blockedPubkeys.value = emptySet()
        userPubkey = null
    }
}
