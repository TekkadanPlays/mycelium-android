package social.mycelium.android.ui.components.media

import android.app.Activity
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.content.getSystemService
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─── Seek gesture (horizontal swipe) ────────────────────────────────────────

@Composable
fun rememberSeekGestureState(player: ExoPlayer?, sensitivity: Float = 0.5f): SeekGestureState {
    return remember { SeekGestureState(sensitivity) }.also { it.player = player }
}

@Stable
class SeekGestureState(private val sensitivity: Float = 0.5f) {
    var player: ExoPlayer? = null

    var isSeeking: Boolean by mutableStateOf(false)
        private set
    var seekStartPosition: Long by mutableLongStateOf(0L)
        private set
    var seekAmount: Long by mutableLongStateOf(0L)
        private set

    private var seekStartX = 0f

    fun onDragStart(offset: Offset) {
        val p = player ?: return
        if (p.duration == C.TIME_UNSET) return
        if (!p.isCurrentMediaItemSeekable) return

        isSeeking = true
        seekStartX = offset.x
        seekStartPosition = p.currentPosition
        seekAmount = 0L
    }

    fun onDrag(change: PointerInputChange, dragAmount: Float) {
        val p = player ?: return
        if (!isSeeking) return
        if (p.duration == C.TIME_UNSET) return
        if (change.isConsumed) return

        val newPosition = seekStartPosition + ((change.position.x - seekStartX) * (sensitivity * 100)).toLong()
        seekAmount = (newPosition - seekStartPosition).coerceIn(
            -seekStartPosition,
            p.duration - seekStartPosition
        )
        p.seekTo(newPosition.coerceIn(0L, p.duration))
    }

    fun onDragEnd() {
        player?.let { /* seek already applied in onDrag */ }
        isSeeking = false
        seekStartPosition = 0L
        seekAmount = 0L
        seekStartX = 0f
    }

    val seekAmountFormatted: String
        get() {
            if (seekAmount == 0L && !isSeeking) return ""
            val sign = if (seekAmount < 0) "-" else "+"
            val absMs = abs(seekAmount)
            val s = absMs / 1000
            val m = s / 60
            return "$sign${m}:${"%02d".format(s % 60)}"
        }

    val seekToPositionFormatted: String
        get() {
            if (!isSeeking) return ""
            val pos = (seekStartPosition + seekAmount).coerceAtLeast(0L)
            val s = pos / 1000
            val m = s / 60
            return "${m}:${"%02d".format(s % 60)}"
        }
}

// ─── Double-tap seek (±10s) ─────────────────────────────────────────────────

@Composable
fun rememberDoubleTapState(player: ExoPlayer?, seekIncrementMs: Long = 10_000L): DoubleTapState {
    val scope = rememberCoroutineScope()
    return remember { DoubleTapState(seekIncrementMs, scope) }.also { it.player = player }
}

@Stable
class DoubleTapState(
    private val seekIncrementMs: Long,
    private val scope: CoroutineScope,
) {
    var player: ExoPlayer? = null

    var seekMillis: Long by mutableLongStateOf(0L)
        private set
    var lastTapSide: TapSide? by mutableStateOf(null)
        private set
    var isLongPressActive: Boolean by mutableStateOf(false)
        private set

    private var resetJob: Job? = null
    private var savedSpeed: Float = 1f

    fun handleDoubleTap(offset: Offset, size: IntSize) {
        val p = player ?: return
        if (!p.isCurrentMediaItemSeekable) return

        val viewCenterX = size.width / 2
        if (offset.x < viewCenterX) {
            // Rewind
            p.seekTo((p.currentPosition - seekIncrementMs).coerceAtLeast(0L))
            if (seekMillis > 0L) seekMillis = 0L
            seekMillis -= seekIncrementMs
            lastTapSide = TapSide.LEFT
        } else {
            // Fast-forward
            p.seekTo((p.currentPosition + seekIncrementMs).coerceAtMost(p.duration))
            if (seekMillis < 0L) seekMillis = 0L
            seekMillis += seekIncrementMs
            lastTapSide = TapSide.RIGHT
        }
        scheduleReset()
    }

    fun handleLongPress() {
        val p = player ?: return
        if (!p.isPlaying) return
        isLongPressActive = true
        savedSpeed = p.playbackParameters.speed
        p.setPlaybackSpeed(2f)
    }

    fun handleLongPressRelease() {
        if (isLongPressActive) {
            isLongPressActive = false
            player?.setPlaybackSpeed(savedSpeed)
        }
    }

    private fun scheduleReset() {
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(750)
            seekMillis = 0L
            lastTapSide = null
        }
    }
}

enum class TapSide { LEFT, RIGHT }

// ─── Volume & Brightness gesture (vertical swipe) ───────────────────────────

@Composable
fun rememberVolumeAndBrightnessState(): VolumeAndBrightnessGestureState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember {
        val audioManager = context.getSystemService<AudioManager>()!!
        val activity = context as? Activity
        VolumeAndBrightnessGestureState(audioManager, activity, scope)
    }
}

@Stable
class VolumeAndBrightnessGestureState(
    private val audioManager: AudioManager,
    private val activity: Activity?,
    private val scope: CoroutineScope,
) {
    var activeGesture: VerticalGestureType? by mutableStateOf(null)
        private set
    var volumePercentage: Int by mutableIntStateOf(currentVolumePercent())
        private set
    var brightnessPercentage: Int by mutableIntStateOf(currentBrightnessPercent())
        private set

    private var startingY = 0f
    private var startVolumePercent = 0
    private var startBrightnessPercent = 0
    private var hideJob: Job? = null

    private val maxVolume: Int get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun onDragStart(offset: Offset, size: IntSize) {
        hideJob?.cancel()
        val centerX = size.width / 2
        activeGesture = if (offset.x < centerX) VerticalGestureType.BRIGHTNESS else VerticalGestureType.VOLUME
        startingY = offset.y
        startVolumePercent = currentVolumePercent()
        startBrightnessPercent = currentBrightnessPercent()
        volumePercentage = startVolumePercent
        brightnessPercentage = startBrightnessPercent
    }

    fun onDrag(change: PointerInputChange, dragAmount: Float) {
        val gesture = activeGesture ?: return
        if (change.isConsumed) return

        when (gesture) {
            VerticalGestureType.VOLUME -> {
                val delta = ((startingY - change.position.y) * 0.3f).toInt()
                val newPercent = (startVolumePercent + delta).coerceIn(0, 100)
                volumePercentage = newPercent
                val newVol = (newPercent * maxVolume / 100).coerceIn(0, maxVolume)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
            }
            VerticalGestureType.BRIGHTNESS -> {
                val delta = ((startingY - change.position.y) * 0.3f).toInt()
                val newPercent = (startBrightnessPercent + delta).coerceIn(0, 100)
                brightnessPercentage = newPercent
                activity?.let { act ->
                    val lp = act.window.attributes
                    lp.screenBrightness = newPercent / 100f
                    act.window.attributes = lp
                }
            }
        }
    }

    fun onDragEnd() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(1000)
            activeGesture = null
        }
    }

    private fun currentVolumePercent(): Int {
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (maxVolume > 0) (cur * 100 / maxVolume) else 0
    }

    private fun currentBrightnessPercent(): Int {
        val brightness = activity?.window?.attributes?.screenBrightness ?: -1f
        return if (brightness >= 0f) (brightness * 100).toInt().coerceIn(0, 100) else 50
    }
}

enum class VerticalGestureType { VOLUME, BRIGHTNESS }

// ─── Pinch-to-zoom ──────────────────────────────────────────────────────────

@Composable
fun rememberVideoZoomState(): VideoZoomState {
    return remember { VideoZoomState() }
}

@Stable
class VideoZoomState {
    var zoom: Float by mutableFloatStateOf(1f)
        private set
    var offset: Offset by mutableStateOf(Offset.Zero)
        private set
    var isZooming: Boolean by mutableStateOf(false)
        private set

    fun onZoomPanGesture(maxWidth: Int, maxHeight: Int, panChange: Offset, zoomChange: Float) {
        isZooming = true
        zoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)

        val extraWidth = (zoom - 1) * maxWidth
        val extraHeight = (zoom - 1) * maxHeight
        val maxX = abs(extraWidth / 2)
        val maxY = abs(extraHeight / 2)

        offset = Offset(
            x = (offset.x + zoom * panChange.x).coerceIn(-maxX, maxX),
            y = (offset.y + zoom * panChange.y).coerceIn(-maxY, maxY),
        )
    }

    fun onGestureEnd() {
        isZooming = false
        // Snap back to 1x if close
        if (zoom < 1.05f) {
            zoom = 1f
            offset = Offset.Zero
        }
    }

    fun reset() {
        zoom = 1f
        offset = Offset.Zero
        isZooming = false
    }

    companion object {
        private const val MIN_ZOOM = 0.5f
        private const val MAX_ZOOM = 4f
    }
}
