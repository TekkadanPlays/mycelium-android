package social.mycelium.android.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import social.mycelium.android.repository.SettingsSyncManager

/**
 * Preferences for the DM (Direct Message) system.
 *
 * ## Auto-decrypt setting
 * - **Off by default**: Amber decryption requires explicit user confirmation each session.
 *   The user must tap "Decrypt Messages" on the Conversations screen.
 * - **On**: Decrypts incoming gift wraps automatically when the DM page is visited,
 *   without prompting for confirmation. Useful for power users who trust the signer.
 *
 * This setting syncs via NIP-78 (kind 30078) alongside other user preferences.
 */
object DmPreferences {

    /** When true, automatically decrypt DMs when user visits the messages screen.
     *  When false (default), the user must tap a "Decrypt" button to trigger Amber. */
    private val _autoDecryptDMs = MutableStateFlow(false)
    val autoDecryptDMs: StateFlow<Boolean> = _autoDecryptDMs.asStateFlow()

    fun setAutoDecryptDMs(enabled: Boolean) {
        if (_autoDecryptDMs.value == enabled) return
        _autoDecryptDMs.value = enabled
        SettingsSyncManager.notifySettingChanged()
    }

    /** Called during remote settings apply — skips publish notification. */
    fun applyAutoDecryptDMs(enabled: Boolean) {
        _autoDecryptDMs.value = enabled
    }
}
