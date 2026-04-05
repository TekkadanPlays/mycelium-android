package social.mycelium.android.repository.sync

import android.content.Context
import social.mycelium.android.debug.MLog
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
                        MLog.d(TAG, "Settings published: ${result.eventId.take(8)}")
                    is PublishResult.Error ->
                        MLog.w(TAG, "Settings publish failed: ${result.message}")
                }
            } catch (e: Exception) {
                MLog.e(TAG, "Settings publish error: ${e.message}", e)
            }
        }
    }

    /**
     * Apply already-decrypted remote settings to local preferences.
     * Called by [StartupOrchestrator] which handles the fetch + decrypt itself.
     */
    fun applyRemoteSettings(remoteSettings: SyncedSettings) {
        isApplyingRemote = true
        try {
            SyncedSettings.applyToLocalPreferences(remoteSettings)
            MLog.d(TAG, "Remote settings applied via orchestrator")
        } finally {
            isApplyingRemote = false
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
            MLog.w(TAG, "No relays for settings fetch")
            return
        }
        MLog.d(TAG, "fetchAndApplySettings: ${relayUrls.size} relays: ${relayUrls.take(3)}")

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
                stateMachine.awaitOneShotSubscription(
                    relayUrls, filter, priority = SubscriptionPriority.NORMAL,
                    settleMs = 500L, maxWaitMs = 5_000L
                ) { event ->
                    // Keep the newest event (highest created_at)
                    if (latestEvent == null || event.createdAt > (latestEvent?.createdAt ?: 0)) {
                        latestEvent = event
                    }
                }
                MLog.d(TAG, "fetchAndApplySettings: subscription done, latestEvent=${latestEvent?.id?.take(8)}")

                val event = latestEvent
                if (event == null) {
                    MLog.d(TAG, "No settings event found — using local defaults")
                    return@launch
                }

                MLog.d(TAG, "Found settings event: ${event.id.take(8)}, created=${event.createdAt}")

                // Decrypt content (NIP-44 encrypted to self).
                // Try background-only first to avoid flashing Amber's visible activity;
                // fall back to foreground decrypt so settings are restored on fresh installs.
                val plaintext = if (signer is com.example.cybin.nip55.NostrSignerExternal) {
                    signer.nip44DecryptBackgroundOnly(event.content, userPubkey)
                        ?: run {
                            MLog.d(TAG, "Background decrypt unavailable — trying foreground decrypt")
                            signer.nip44Decrypt(event.content, userPubkey)
                        }
                } else {
                    signer.nip44Decrypt(event.content, userPubkey)
                }
                val remoteSettings = SyncedSettings.fromJson(plaintext)
                MLog.d(TAG, "fetchAndApplySettings: parsed settings — compactMedia=${remoteSettings.compactMedia}, theme=${remoteSettings.theme}, accent=${remoteSettings.accent}")

                // Apply to local preferences (with guard to prevent re-publish)
                isApplyingRemote = true
                try {
                    SyncedSettings.applyToLocalPreferences(remoteSettings)
                    MLog.d(TAG, "Remote settings applied successfully")
                } finally {
                    isApplyingRemote = false
                }
            } catch (e: Exception) {
                MLog.e(TAG, "Settings fetch/apply error: ${e.message}", e)
            }
        }
    }
}
