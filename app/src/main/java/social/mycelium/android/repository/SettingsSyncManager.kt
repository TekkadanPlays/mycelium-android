package social.mycelium.android.repository

import android.content.Context
import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import com.example.cybin.signer.NostrSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import social.mycelium.android.data.SyncedSettings
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.services.EventPublisher
import social.mycelium.android.services.PublishResult

/**
 * Manages NIP-78 (kind 30078) settings sync.
 *
 * Publishes user preferences as a replaceable event with d-tag "MyceliumSettings".
 * Content is NIP-44 encrypted to self so only the user can read their own settings.
 *
 * Flow:
 *   On setting change → snapshot local prefs → encrypt → publish kind 30078
 *   On login/startup → subscribe kind 30078 → decrypt → merge with local
 */
object SettingsSyncManager {

    private const val TAG = "SettingsSyncManager"
    private const val KIND_APP_SPECIFIC = 30078
    private const val D_TAG = "MyceliumSettings"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Debounce: avoid rapid-fire publishes when multiple settings change at once. */
    @Volatile
    private var pendingPublishJob: kotlinx.coroutines.Job? = null
    private const val DEBOUNCE_MS = 2000L

    /** Track whether we're currently applying remote settings to avoid publish loops. */
    @Volatile
    private var isApplyingRemote = false

    /**
     * Stored publish callback — set once on login by AccountStateViewModel.
     * Preference singletons call [notifySettingChanged] which invokes this.
     */
    @Volatile
    private var publishCallback: (() -> Unit)? = null

    /**
     * Register a callback that will publish settings when invoked.
     * Called by AccountStateViewModel after login with captured signer/relay context.
     */
    fun registerPublishCallback(callback: () -> Unit) {
        publishCallback = callback
    }

    /** Clear callback on logout. */
    fun clearPublishCallback() {
        publishCallback = null
        pendingPublishJob?.cancel()
    }

    /**
     * Called by preference setters after updating a local value.
     * Triggers a debounced publish of all settings to relays.
     */
    fun notifySettingChanged() {
        if (isApplyingRemote) return
        publishCallback?.invoke()
    }

    /**
     * Publish current local settings to relays as an encrypted kind 30078 event.
     * Debounced: waits [DEBOUNCE_MS] after the last call before actually publishing.
     */
    fun publishSettings(
        context: Context,
        signer: NostrSigner,
        userPubkey: String,
        relayUrls: Set<String>
    ) {
        if (isApplyingRemote) return // Don't re-publish while applying remote

        pendingPublishJob?.cancel()
        pendingPublishJob = scope.launch {
            delay(DEBOUNCE_MS)
            try {
                val settings = SyncedSettings.fromLocalPreferences()
                val plaintext = settings.toJson()
                val encrypted = signer.nip44Encrypt(plaintext, userPubkey)

                val result = EventPublisher.publish(
                    context = context,
                    signer = signer,
                    relayUrls = relayUrls,
                    kind = KIND_APP_SPECIFIC,
                    content = encrypted
                ) {
                    add(arrayOf("d", D_TAG))
                }

                when (result) {
                    is PublishResult.Success ->
                        Log.d(TAG, "Settings published: ${result.eventId.take(8)}")
                    is PublishResult.Error ->
                        Log.w(TAG, "Settings publish failed: ${result.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Settings publish error: ${e.message}", e)
            }
        }
    }

    /**
     * Fetch the latest kind 30078 settings event for this user from relays.
     * Decrypts with NIP-44 and merges into local preferences.
     *
     * Called once on login/startup after account is available.
     */
    fun fetchAndApplySettings(
        signer: NostrSigner,
        userPubkey: String,
        relayUrls: List<String>
    ) {
        if (relayUrls.isEmpty()) {
            Log.w(TAG, "No relays for settings fetch")
            return
        }

        scope.launch {
            try {
                val filter = Filter(
                    kinds = listOf(KIND_APP_SPECIFIC),
                    authors = listOf(userPubkey),
                    tags = mapOf("d" to listOf(D_TAG)),
                    limit = 1
                )

                var latestEvent: Event? = null
                val stateMachine = RelayConnectionStateMachine.getInstance()
                val handle = stateMachine.requestTemporarySubscriptionWithRelay(
                    relayUrls, filter, priority = SubscriptionPriority.NORMAL
                ) { event, _ ->
                    // Keep the newest event (highest created_at)
                    if (latestEvent == null || event.createdAt > (latestEvent?.createdAt ?: 0)) {
                        latestEvent = event
                    }
                }

                // Wait for responses to settle
                delay(5000)
                handle.cancel()

                val event = latestEvent
                if (event == null) {
                    Log.d(TAG, "No settings event found — using local defaults")
                    return@launch
                }

                Log.d(TAG, "Found settings event: ${event.id.take(8)}, created=${event.createdAt}")

                // Decrypt content (NIP-44 encrypted to self)
                val plaintext = signer.nip44Decrypt(event.content, userPubkey)
                val remoteSettings = SyncedSettings.fromJson(plaintext)

                // Apply to local preferences (with guard to prevent re-publish)
                isApplyingRemote = true
                try {
                    SyncedSettings.applyToLocalPreferences(remoteSettings)
                    Log.d(TAG, "Remote settings applied successfully")
                } finally {
                    isApplyingRemote = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Settings fetch/apply error: ${e.message}", e)
            }
        }
    }
}
