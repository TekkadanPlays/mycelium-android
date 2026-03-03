package social.mycelium.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Picture-in-Picture overlay for live streams.
 * - Freely draggable anywhere; bounces back to visible edges on release.
 * - Pinch to resize between 120dp and 300dp wide (16:9 aspect maintained).
 * - Trash zone at bottom-center appears during drag; drop to dismiss with shrink animation.
 * - Tap to return to the full stream screen.
 */
@Composable
fun PipStreamOverlay(
    onTapToReturn: (String) -> Unit,
    onVideoTapToReturn: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val pipState by PipStreamManager.pipState.collectAsState()
    val isVisible = pipState != null

    // Shrink-away dismiss state
    var isDismissing by remember { mutableStateOf(false) }
    val dismissScale by animateFloatAsState(
        targetValue = if (isDismissing) 0f else 1f,
        animationSpec = tween(250),
        label = "dismissScale",
        finishedListener = {
            if (isDismissing) {
                isDismissing = false
                PipStreamManager.dismiss()
            }
        }
    )

    AnimatedVisibility(
        visible = isVisible && !isDismissing,
        enter = fadeIn() + scaleIn(initialScale = 0.6f),
        exit = fadeOut(tween(100)),
        modifier = modifier.fillMaxSize()
    ) {
        val state = pipState ?: return@AnimatedVisibility
        val density = LocalDensity.current
        val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
        val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
        val coroutineScope = rememberCoroutineScope()

        // PiP sizing — pinch to resize
        val minWidth = 120.dp
        val maxWidth = 300.dp
        val defaultWidth = 180.dp
        var pipWidthDp by remember { mutableStateOf(defaultWidth) }
        val pipHeightDp = pipWidthDp * 9f / 16f

        val screenWidthPx = with(density) { screenWidthDp.toPx() }
        val screenHeightPx = with(density) { screenHeightDp.toPx() }
        val edgePadding = with(density) { 8.dp.toPx() }

        // Start position: top-right with padding
        val startX = with(density) { (screenWidthDp - pipWidthDp - 12.dp).toPx() }
        val startY = with(density) { 60.dp.toPx() }

        // Animatable offsets for smooth bounce-back
        val animOffsetX = remember { Animatable(startX) }
        val animOffsetY = remember { Animatable(startY) }

        var isDragging by remember { mutableStateOf(false) }
        var isOverTrash by remember { mutableStateOf(false) }

        // Trash zone geometry — raised well above home bar
        val trashZoneSize = 80.dp
        val trashZoneSizeAnimated by animateDpAsState(
            targetValue = if (isOverTrash) 88.dp else trashZoneSize,
            animationSpec = tween(150),
            label = "trashSize"
        )
        val trashCenterXPx = screenWidthPx / 2f
        val trashCenterYPx = with(density) { (screenHeightDp - 140.dp).toPx() }
        val trashRadiusPx = with(density) { 72.dp.toPx() }

        Box(modifier = Modifier.fillMaxSize()) {
            // Trash zone — only visible while dragging
            AnimatedVisibility(
                visible = isDragging,
                enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.5f, animationSpec = tween(200)),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.5f, animationSpec = tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(trashZoneSizeAnimated)
                        .clip(CircleShape)
                        .background(
                            if (isOverTrash) Color.Red.copy(alpha = 0.85f)
                            else Color.DarkGray.copy(alpha = 0.7f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Dismiss",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Background playback toggle state
            val continueInBg by PipStreamManager.continueInBackground.collectAsState()

            // PiP window
            Box(
                modifier = Modifier
                    .offset { IntOffset(animOffsetX.value.roundToInt(), animOffsetY.value.roundToInt()) }
                    .size(pipWidthDp, pipHeightDp)
                    .graphicsLayer {
                        scaleX = dismissScale
                        scaleY = dismissScale
                        alpha = dismissScale
                    }
                    .shadow(8.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            isDragging = true
                            var hasDragged = false
                            do {
                                val event = awaitPointerEvent()
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()

                                // Pinch to resize
                                if (zoom != 1f) {
                                    val newWidth = pipWidthDp * zoom
                                    pipWidthDp = newWidth.coerceIn(minWidth, maxWidth)
                                }

                                // Drag
                                if (pan != Offset.Zero) {
                                    hasDragged = true
                                    coroutineScope.launch {
                                        animOffsetX.snapTo(animOffsetX.value + pan.x)
                                        animOffsetY.snapTo(animOffsetY.value + pan.y)
                                    }
                                    // Check if PiP center is over the trash zone
                                    val pipWPx = with(density) { pipWidthDp.toPx() }
                                    val pipHPx = with(density) { pipHeightDp.toPx() }
                                    val pipCenterX = animOffsetX.value + pipWPx / 2
                                    val pipCenterY = animOffsetY.value + pipHPx / 2
                                    val dx = pipCenterX - trashCenterXPx
                                    val dy = pipCenterY - trashCenterYPx
                                    isOverTrash = (dx * dx + dy * dy) < (trashRadiusPx * trashRadiusPx)
                                }

                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } while (event.changes.any { it.pressed })

                            // Drag ended
                            isDragging = false
                            if (isOverTrash) {
                                isOverTrash = false
                                // Shrink-away animation then dismiss
                                isDismissing = true
                            } else if (hasDragged) {
                                // Bounce back into visible bounds
                                val pipWPx = with(density) { pipWidthDp.toPx() }
                                val pipHPx = with(density) { pipHeightDp.toPx() }
                                val clampedX = animOffsetX.value.coerceIn(
                                    edgePadding,
                                    screenWidthPx - pipWPx - edgePadding
                                )
                                val clampedY = animOffsetY.value.coerceIn(
                                    edgePadding,
                                    screenHeightPx - pipHPx - edgePadding
                                )
                                coroutineScope.launch {
                                    launch { animOffsetX.animateTo(clampedX, tween(200)) }
                                    launch { animOffsetY.animateTo(clampedY, tween(200)) }
                                }
                            } else {
                                // No drag — treat as tap
                                if (state.isVideo) {
                                    state.videoUrl?.let { onVideoTapToReturn(it) }
                                } else {
                                    onTapToReturn(state.addressableId)
                                }
                            }
                        }
                    }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            this.player = state.player
                        }
                    },
                    update = { view ->
                        view.player = state.player
                    }
                )

                // Background playback toggle — top-left corner
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable {
                            PipStreamManager.setContinueInBackground(!continueInBg)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (continueInBg) Icons.Default.Headphones else Icons.Default.HeadsetOff,
                        contentDescription = if (continueInBg) "Background play on" else "Background play off",
                        tint = if (continueInBg) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
