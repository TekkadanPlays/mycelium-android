package social.mycelium.android.ui.components.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Global loading animation for Mycelium
 * Simple circular indicator matching the dynamic sage color theme
 *
 * Usage:
 * - Full screen loading: LoadingAnimation(indicatorSize = 48.dp)
 * - Inline loading: LoadingAnimation(indicatorSize = 24.dp)
 * - Button loading: LoadingAnimation(indicatorSize = 16.dp)
 */

// Sage-inspired color gradient for the loading animation (legacy — prefer themed variant)
val SageLoadingColors = listOf(
    Color(0xFF8B9D83), // Sage green
    Color(0xFF6B7F66), // Darker sage
    Color(0xFF4A5E48), // Deep sage
    Color(0xFF7A8C74), // Medium sage
    Color(0xFF9AAD93), // Light sage
    Color(0xFF8B9D83), // Back to start for smooth loop
)

/**
 * Animated loading indicator with sage color theme
 *
 * @param indicatorSize Size of the loading indicator
 * @param circleWidth Stroke width of the circular border
 * @param animationDuration Duration of one full rotation in milliseconds
 * @param modifier Modifier for the component
 */
@Composable
fun LoadingAnimation(
    indicatorSize: Dp = 32.dp,
    circleWidth: Dp = 3.dp,
    animationDuration: Int = 1200,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "LoadingRotation")

    val rotateAnimation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = animationDuration,
                easing = LinearEasing,
            ),
        ),
        label = "LoadingRotationAnimation",
    )

    val primary = MaterialTheme.colorScheme.primary
    val themedColors = remember(primary) {
        listOf(
            primary,
            primary.copy(alpha = 0.7f),
            primary.copy(alpha = 0.3f),
            primary.copy(alpha = 0.1f),
            primary.copy(alpha = 0.3f),
            primary.copy(alpha = 0.7f),
            primary,
        )
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier
                .size(size = indicatorSize)
                .rotate(degrees = rotateAnimation)
                .border(
                    width = circleWidth,
                    brush = Brush.sweepGradient(themedColors),
                    shape = CircleShape,
                ),
            color = MaterialTheme.colorScheme.background,
            strokeWidth = 1.dp,
            trackColor = ProgressIndicatorDefaults.circularDeterminateTrackColor,
        )
    }
}

/**
 * Simple circular progress indicator matching Material 3 theme
 * Use this for minimal loading states where the sage gradient isn't needed
 *
 * @param size Size of the indicator
 * @param strokeWidth Width of the progress stroke
 * @param modifier Modifier for the component
 */
@Composable
fun SimpleLoadingIndicator(
    size: Dp = 24.dp,
    strokeWidth: Dp = 2.dp,
    modifier: Modifier = Modifier
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        strokeWidth = strokeWidth,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * Themed loading indicator with sage color
 * Simpler alternative to LoadingAnimation without rotation animation
 *
 * @param size Size of the indicator
 * @param strokeWidth Width of the progress stroke
 * @param modifier Modifier for the component
 */
@Composable
fun SageLoadingIndicator(
    size: Dp = 24.dp,
    strokeWidth: Dp = 2.dp,
    modifier: Modifier = Modifier
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        strokeWidth = strokeWidth,
        color = MaterialTheme.colorScheme.primary
    )
}
