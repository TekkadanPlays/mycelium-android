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
        val videoUrl: String? = null,
        /** Instance key for the SharedPlayerPool entry this player came from. */
        val instanceKey: String? = null
    ) {
        val isVideo: Boolean get() = videoUrl != null
    }

    private val _pipState = MutableStateFlow<PipState?>(null)
    val pipState: StateFlow<PipState?> = _pipState.asStateFlow()

    /** Whether PiP should keep playing when the app is backgrounded. Default false. */
    private val _continueInBackground = MutableStateFlow(false)

    /**
     * Video URL reserved by PiP, persists through PiP→fullscreen→PiP transitions.
     * The feed checks this to avoid creating a competing player while the video
     * is transitioning between PiP and fullscreen. Only cleared on explicit dismiss/kill.
     */
    @Volatile
    var reservedVideoUrl: String? = null
        private set
    val continueInBackground: StateFlow<Boolean> = _continueInBackground.asStateFlow()

    fun setContinueInBackground(enabled: Boolean) {
        _continueInBackground.value = enabled
    }

    /** Start PiP — called by LiveStreamScreen when the user navigates back. */
    fun startPip(player: ExoPlayer, addressableId: String, title: String?, hostName: String?) {
        // Release any existing PiP player first
        killCurrentPip()
        player.play() // Ensure playback continues in PiP
        _pipState.value = PipState(player, addressableId, title, hostName)
        Log.d(TAG, "PiP started for live stream $addressableId")
    }

    /** Start PiP for a regular video URL. Removes the player from SharedPlayerPool
     *  so the feed can't reacquire the same ExoPlayer and steal the video surface. */
    fun startVideoPip(player: ExoPlayer, url: String, instanceKey: String) {
        killCurrentPip()
        // Orphan the player from the pool — PipStreamManager now owns its lifecycle.
        // This prevents FeedVideoPlayer.acquire() from getting the same instance back.
        SharedPlayerPool.steal(instanceKey)
        // Unmute for PiP — user expects audio from the mini-player.
        // They can tap PiP → fullscreen → mute if they want silence.
        player.volume = 1f
        player.play() // Ensure playback continues in PiP (may have been paused by fullscreen dispose)
        VideoMuteCache.set(url, false) // Sync cache so fullscreen inherits unmuted state
        reservedVideoUrl = url // Reserve URL through PiP→fullscreen→PiP cycle
        _pipState.value = PipState(player, addressableId = url, title = null, hostName = null, videoUrl = url, instanceKey = instanceKey)
        Log.d(TAG, "PiP started for video $url (instance=$instanceKey)")
    }

    private fun killCurrentPip() {
        _pipState.value?.let { old ->
            Log.d(TAG, "Releasing previous PiP for ${old.addressableId}")
            old.player.stop()
            old.player.release()
        }
        _pipState.value = null
    }

    /** Reclaim the player when the user taps PiP to return to the stream screen. */
    fun reclaimPlayer(): PipState? {
        val state = _pipState.value
        _pipState.value = null
        // Clear the video surface so the PiP overlay's PlayerView releases the
        // decoder output, allowing the next screen's PlayerView to attach cleanly.
        state?.player?.clearVideoSurface()
        Log.d(TAG, "PiP reclaimed for ${state?.addressableId}")
        return state
    }

    /** Dismiss PiP and release the player (user swiped it away or killed). */
    fun dismiss() {
        _pipState.value?.let {
            Log.d(TAG, "PiP dismissed for ${it.addressableId}")
            it.player.stop()
            it.player.release()
        }
        _pipState.value = null
        reservedVideoUrl = null // Fully release — feed can re-acquire
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

    /** Check if PiP is active or reserved for a given video URL.
     *  Returns true during PiP→fullscreen→PiP transitions so the feed doesn't create a competing player. */
    fun isVideoFor(url: String): Boolean =
        _pipState.value?.videoUrl == url || reservedVideoUrl == url

    /** Check if PiP is active for a given instance key. */
    fun isInstanceActive(instanceKey: String): Boolean =
        _pipState.value?.instanceKey == instanceKey

    /** Whether PiP is currently active at all. */
    val isActive: Boolean get() = _pipState.value != null
}
