package social.mycelium.android.ui.components

import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton that holds an ExoPlayer instance and stream metadata for
 * Picture-in-Picture playback when the user navigates away from a live stream.
 *
 * The LiveStreamScreen hands off its player here on back-press instead of
 * releasing it, so playback continues in a mini overlay.
 */
object PipStreamManager {

    private const val TAG = "PipStreamManager"

    data class PipState(
        val player: ExoPlayer,
        val addressableId: String,
        val title: String?,
        val hostName: String?,
        /** Non-null when PiP is for a regular video (not a live stream). */
        val videoUrl: String? = null
    ) {
        val isVideo: Boolean get() = videoUrl != null
    }

    private val _pipState = MutableStateFlow<PipState?>(null)
    val pipState: StateFlow<PipState?> = _pipState.asStateFlow()

    /** Whether PiP should keep playing when the app is backgrounded. Default false. */
    private val _continueInBackground = MutableStateFlow(false)
    val continueInBackground: StateFlow<Boolean> = _continueInBackground.asStateFlow()

    fun setContinueInBackground(enabled: Boolean) {
        _continueInBackground.value = enabled
    }

    /** Start PiP — called by LiveStreamScreen when the user navigates back. */
    fun startPip(player: ExoPlayer, addressableId: String, title: String?, hostName: String?) {
        // Release any existing PiP player first
        killCurrentPip()
        _pipState.value = PipState(player, addressableId, title, hostName)
        Log.d(TAG, "PiP started for live stream $addressableId")
    }

    /** Start PiP for a regular video URL. The player stays in SharedPlayerPool. */
    fun startVideoPip(player: ExoPlayer, url: String) {
        killCurrentPip()
        _pipState.value = PipState(player, addressableId = url, title = null, hostName = null, videoUrl = url)
        Log.d(TAG, "PiP started for video $url")
    }

    private fun killCurrentPip() {
        _pipState.value?.let { old ->
            Log.d(TAG, "Releasing previous PiP for ${old.addressableId}")
            if (old.isVideo) {
                // Video PiP: detach from pool (don't release — pool manages lifecycle)
                old.videoUrl?.let { SharedPlayerPool.detach(it) }
            } else {
                old.player.release()
            }
        }
        _pipState.value = null
    }

    /** Reclaim the player when the user taps PiP to return to the stream screen. */
    fun reclaimPlayer(): PipState? {
        val state = _pipState.value
        _pipState.value = null
        Log.d(TAG, "PiP reclaimed for ${state?.addressableId}")
        return state
    }

    /** Dismiss PiP and release the player (user swiped it away or killed). */
    fun dismiss() {
        _pipState.value?.let {
            Log.d(TAG, "PiP dismissed for ${it.addressableId}")
            if (it.isVideo) {
                it.videoUrl?.let { url -> SharedPlayerPool.release(url) }
            } else {
                it.player.release()
            }
        }
        _pipState.value = null
    }

    /**
     * Kill PiP unconditionally — used when:
     * - A new live broadcast is opened (prevents double-play)
     * - Any media goes fullscreen
     */
    fun kill() {
        if (_pipState.value != null) {
            Log.d(TAG, "PiP killed")
            dismiss()
        }
    }

    /**
     * Pause PiP playback (e.g. when app is backgrounded and continueInBackground is false).
     * Does NOT release the player — just pauses it so it can resume.
     */
    fun pauseIfActive() {
        _pipState.value?.player?.let { player ->
            if (player.isPlaying) {
                player.pause()
                Log.d(TAG, "PiP paused (app backgrounded)")
            }
        }
    }

    /** Resume PiP playback (e.g. when app returns to foreground). */
    fun resumeIfActive() {
        _pipState.value?.player?.let { player ->
            player.play()
            Log.d(TAG, "PiP resumed (app foregrounded)")
        }
    }

    /** Check if PiP is active for a given addressableId. */
    fun isActiveFor(addressableId: String): Boolean =
        _pipState.value?.addressableId == addressableId

    /** Check if PiP is active for a given video URL. */
    fun isVideoFor(url: String): Boolean =
        _pipState.value?.videoUrl == url

    /** Whether PiP is currently active at all. */
    val isActive: Boolean get() = _pipState.value != null
}
