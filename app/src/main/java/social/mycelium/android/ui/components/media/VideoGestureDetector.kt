package social.mycelium.android.ui.components.media

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * Full-screen gesture layer for video playback.
 * Handles: single tap (toggle controls), double-tap (seek ±10s), long-press (2x speed),
 * horizontal drag (scrub seek), vertical drag (volume/brightness), pinch-to-zoom.
 *
 * Inspired by Next Player's PlayerGestures.kt, rewritten in pure Kotlin/Compose
 * without any external dependencies.
 */
@Composable
fun VideoGestureLayer(
    modifier: Modifier = Modifier,
    seekState: SeekGestureState,
    doubleTapState: DoubleTapState,
    volumeBrightnessState: VolumeAndBrightnessGestureState,
    zoomState: VideoZoomState,
    onToggleControls: () -> Unit,
) {
    BoxWithConstraints {
        val constraintsSize = constraints
        Box(
            modifier = modifier
                .fillMaxSize()
                // Layer 1: Tap gestures (single tap, double-tap, long-press)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (doubleTapState.seekMillis != 0L) return@detectTapGestures
                            onToggleControls()
                        },
                        onDoubleTap = { offset ->
                            doubleTapState.handleDoubleTap(offset = offset, size = size)
                        },
                        onPress = {
                            tryAwaitRelease()
                            doubleTapState.handleLongPressRelease()
                        },
                        onLongPress = {
                            doubleTapState.handleLongPress()
                        },
                    )
                }
                // Layer 2: Horizontal drag → seek
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = seekState::onDragStart,
                        onHorizontalDrag = seekState::onDrag,
                        onDragEnd = seekState::onDragEnd,
                        onDragCancel = seekState::onDragEnd,
                    )
                }
                // Layer 3: Vertical drag → volume / brightness
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            volumeBrightnessState.onDragStart(offset, size)
                        },
                        onVerticalDrag = volumeBrightnessState::onDrag,
                        onDragEnd = volumeBrightnessState::onDragEnd,
                        onDragCancel = volumeBrightnessState::onDragEnd,
                    )
                }
                // Layer 4: Pinch-to-zoom (two-finger transform)
                .pointerInput(Unit) {
                    detectPinchToZoom(
                        onGesture = { panChange, zoomChange ->
                            if (doubleTapState.isLongPressActive) return@detectPinchToZoom
                            zoomState.onZoomPanGesture(
                                maxWidth = constraintsSize.maxWidth,
                                maxHeight = constraintsSize.maxHeight,
                                panChange = panChange,
                                zoomChange = zoomChange,
                            )
                        },
                        onGestureEnd = {
                            zoomState.onGestureEnd()
                        },
                    )
                }
        )
    }
}

// ─── Custom horizontal drag detection (single-finger only) ──────────────────

private suspend fun PointerInputScope.detectHorizontalDragGestures(
    onDragStart: (offset: androidx.compose.ui.geometry.Offset) -> Unit,
    onHorizontalDrag: (change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var overSlop = 0f
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            overSlop = over
        }
        if (drag != null && currentEvent.changes.count { it.pressed } == 1) {
            onDragStart(drag.position)
            onHorizontalDrag(drag, overSlop)
            if (
                horizontalDrag(drag.id) {
                    onHorizontalDrag(it, it.positionChange().x)
                    it.consume()
                }
            ) {
                onDragEnd()
            } else {
                onDragCancel()
            }
        }
    }
}

// ─── Custom vertical drag detection (single-finger only) ────────────────────

private suspend fun PointerInputScope.detectVerticalDragGestures(
    onDragStart: (offset: androidx.compose.ui.geometry.Offset) -> Unit,
    onVerticalDrag: (change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var overSlop = 0f
        val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            overSlop = over
        }
        if (drag != null && currentEvent.changes.count { it.pressed } == 1) {
            onDragStart(drag.position)
            onVerticalDrag(drag, overSlop)
            if (
                verticalDrag(drag.id) {
                    onVerticalDrag(it, it.positionChange().y)
                    it.consume()
                }
            ) {
                onDragEnd()
            } else {
                onDragCancel()
            }
        }
    }
}

// ─── Pinch-to-zoom detection (two-finger transform) ─────────────────────────

private suspend fun PointerInputScope.detectPinchToZoom(
    onGesture: (pan: androidx.compose.ui.geometry.Offset, zoom: Float) -> Unit,
    onGestureEnd: () -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = androidx.compose.ui.geometry.Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop
        var gestureStarted = false

        awaitFirstDown(requireUnconsumed = false)

        do {
            val event = awaitPointerEvent()
            val pointerCount = event.changes.count { it.pressed }
            val canceled = event.changes.any { it.isConsumed } || pointerCount < 2

            if (!canceled) {
                gestureStarted = true
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()

                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    pan += panChange
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1 - zoom) * centroidSize
                    val panMotion = pan.getDistance()
                    if (zoomMotion > touchSlop || panMotion > touchSlop) {
                        pastTouchSlop = true
                    }
                }

                if (pastTouchSlop) {
                    if (zoomChange != 1f || panChange != androidx.compose.ui.geometry.Offset.Zero) {
                        onGesture(panChange, zoomChange)
                    }
                    event.changes.forEach {
                        if (it.positionChanged()) it.consume()
                    }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })

        if (gestureStarted) {
            onGestureEnd()
        }
    }
}
