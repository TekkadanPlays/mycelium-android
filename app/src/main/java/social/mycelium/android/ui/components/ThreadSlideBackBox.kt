package social.mycelium.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch

/**
 * Wraps thread (or any full-screen) content so the user can slide it back in the direction it came from.
 * - Drag right (LTR): content follows finger; a "reveal" layer fades in behind.
 * - Release past threshold: animates off and [onBack] is called.
 * - Release before threshold: animates back to zero.
 * Edge-swipe back and back button still work; this adds in-place drag-to-dismiss.
 *
 * Also acts as a NestedScrollConnection parent so child scrollable containers
 * (e.g. HorizontalPager on first page) pass through rightward overscroll,
 * enabling slide-to-dismiss even when a media gallery is showing.
 */
@Composable
fun ThreadSlideBackBox(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val offsetPx = remember { Animatable(0f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val commitThresholdPx = widthPx * 0.25f
        val sign = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f

        // NestedScrollConnection: receives horizontal overscroll from child
        // scrollables (HorizontalPager at page 0 can't scroll right → overscroll
        // propagates here and drives the slide-back offset).
        val nestedScrollConnection = remember(widthPx, sign) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource
                ): Offset {
                    // available.x > 0 in LTR means rightward overscroll (child couldn't consume it)
                    val backDelta = available.x * sign
                    if (backDelta > 0f) {
                        val newOffset = (offsetPx.value + backDelta).coerceIn(0f, widthPx)
                        scope.launch { offsetPx.snapTo(newOffset) }
                        return Offset(available.x, 0f) // consumed
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    val current = offsetPx.value
                    if (current > 0f) {
                        if (current >= commitThresholdPx) {
                            offsetPx.animateTo(widthPx, animationSpec = tween(200))
                            onBack()
                        } else {
                            offsetPx.animateTo(0f, animationSpec = tween(200))
                        }
                        return available // consumed
                    }
                    return Velocity.Zero
                }
            }
        }

        // Reveal layer: no solid fill so the content behind (dashboard/feed) is visible as the thread slides.
        // Scrim only over the exposed strip: darkest when barely slid, fades out as more feed shows.
        val progress = (offsetPx.value / widthPx).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .align(if (layoutDirection == LayoutDirection.Ltr) Alignment.CenterStart else Alignment.CenterEnd),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = 1f - progress }
                        .background(Color.Black.copy(alpha = 0.55f))
                )
            }
        }

        // Content that tracks the finger; slides right (LTR) or left (RTL) for "back".
        // pointerInput handles direct drag on non-scrollable areas;
        // nestedScroll handles overscroll from child HorizontalPagers.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetPx.value * sign
                }
                .nestedScroll(nestedScrollConnection)
                .pointerInput(widthPx, commitThresholdPx, layoutDirection) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                val newOffset = (offsetPx.value + dragAmount * sign).coerceIn(0f, widthPx)
                                offsetPx.snapTo(newOffset)
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                val current = offsetPx.value
                                if (current >= commitThresholdPx) {
                                    offsetPx.animateTo(widthPx, animationSpec = tween(200))
                                    onBack()
                                } else {
                                    offsetPx.animateTo(0f, animationSpec = tween(200))
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetPx.animateTo(0f, animationSpec = tween(200))
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }
}
