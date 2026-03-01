package social.mycelium.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Overlay composables for video gesture feedback.
 * These are layered on top of the video surface inside FullVideoPlayer.
 */

// ─── Seek gesture overlay (center pill showing ±time) ───────────────────────

@Composable
fun BoxScope.SeekGestureOverlay(seekState: SeekGestureState) {
    AnimatedVisibility(
        visible = seekState.isSeeking,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center)
    ) {
        Column(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = seekState.seekAmountFormatted,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = seekState.seekToPositionFormatted,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─── Double-tap seek overlay (left/right indicator) ─────────────────────────

@Composable
fun BoxScope.DoubleTapSeekOverlay(doubleTapState: DoubleTapState) {
    val isActive = doubleTapState.seekMillis != 0L
    val side = doubleTapState.lastTapSide

    AnimatedVisibility(
        visible = isActive && side != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(
            if (side == TapSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd
        )
    ) {
        val icon = if (side == TapSide.LEFT) Icons.Default.FastRewind else Icons.Default.FastForward
        val absSeconds = abs(doubleTapState.seekMillis) / 1000

        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = "${absSeconds}s",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ─── Long-press speed indicator ─────────────────────────────────────────────

@Composable
fun BoxScope.LongPressSpeedOverlay(doubleTapState: DoubleTapState) {
    AnimatedVisibility(
        visible = doubleTapState.isLongPressActive,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Speed,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "2×",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ─── Volume / Brightness vertical bar overlay ───────────────────────────────

@Composable
fun BoxScope.VolumeBrightnessOverlay(state: VolumeAndBrightnessGestureState) {
    val gesture = state.activeGesture

    AnimatedVisibility(
        visible = gesture != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(
            if (gesture == VerticalGestureType.BRIGHTNESS) Alignment.CenterStart else Alignment.CenterEnd
        ).padding(horizontal = 24.dp)
    ) {
        val icon: ImageVector
        val value: Int
        when (gesture) {
            VerticalGestureType.VOLUME -> {
                icon = Icons.AutoMirrored.Filled.VolumeUp
                value = state.volumePercentage
            }
            VerticalGestureType.BRIGHTNESS -> {
                icon = Icons.Default.BrightnessHigh
                value = state.brightnessPercentage
            }
            null -> return@AnimatedVisibility
        }

        VerticalProgressBar(
            value = value,
            icon = icon,
        )
    }
}

@Composable
private fun VerticalProgressBar(
    value: Int,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val barWidth = 32.dp
    val fillFraction by animateFloatAsState(
        targetValue = value.coerceIn(0, 100) / 100f,
        label = "progress"
    )

    Column(
        modifier = modifier
            .heightIn(max = 200.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Percentage text
        Box(
            modifier = Modifier.size(barWidth),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$value",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        // Fill bar
        Box(
            modifier = Modifier
                .weight(1f)
                .width(barWidth)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.2f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .fillMaxHeight(fillFraction)
                    .background(Color.White),
            )
        }
        // Icon
        Box(
            modifier = Modifier.size(barWidth),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ─── Zoom level indicator ───────────────────────────────────────────────────

@Composable
fun BoxScope.ZoomIndicatorOverlay(zoomState: VideoZoomState) {
    AnimatedVisibility(
        visible = zoomState.isZooming && zoomState.zoom != 1f,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)
    ) {
        Text(
            text = "${(zoomState.zoom * 100).toInt()}%",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
