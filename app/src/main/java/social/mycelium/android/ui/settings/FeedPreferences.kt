package social.mycelium.android.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import social.mycelium.android.repository.SettingsSyncManager

/**
 * Local state for feed-related preferences that sync across devices via NIP-78 (kind 30078).
 *
 * Values:
 * - **defaultFeedView**: Which feed tab opens on cold start ("HOME" or "TOPICS")
 * - **defaultSortOrder**: Default sort order for both feeds ("LATEST" or "POPULAR")
 * - **defaultListDTag**: NIP-51 people list d-tag to pre-select on cold start (null = Following)
 */
object FeedPreferences {

    private val _defaultFeedView = MutableStateFlow("HOME")
    val defaultFeedView: StateFlow<String> = _defaultFeedView.asStateFlow()

    private val _defaultSortOrder = MutableStateFlow("LATEST")
    val defaultSortOrder: StateFlow<String> = _defaultSortOrder.asStateFlow()

    private val _defaultListDTag = MutableStateFlow<String?>(null)
    val defaultListDTag: StateFlow<String?> = _defaultListDTag.asStateFlow()

    /** Auto-save drafts while composing (timer-based). Default: enabled. */
    private val _autoSaveDrafts = MutableStateFlow(true)
    val autoSaveDrafts: StateFlow<Boolean> = _autoSaveDrafts.asStateFlow()

    fun setDefaultFeedView(value: String) {
        if (_defaultFeedView.value != value) {
            _defaultFeedView.value = value
            SettingsSyncManager.notifySettingChanged()
        }
    }

    fun setDefaultSortOrder(value: String) {
        if (_defaultSortOrder.value != value) {
            _defaultSortOrder.value = value
            SettingsSyncManager.notifySettingChanged()
        }
    }

    fun setDefaultListDTag(value: String?) {
        if (_defaultListDTag.value != value) {
            _defaultListDTag.value = value
            SettingsSyncManager.notifySettingChanged()
        }
    }

    fun setAutoSaveDrafts(enabled: Boolean) {
        _autoSaveDrafts.value = enabled
    }
}
