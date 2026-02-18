package social.mycelium.android.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.unit.IntOffset

/**
 * Material Design 3 Motion System
 * Based on https://m3.material.io/styles/motion/easing-and-duration
 * 
 * This provides the official Material motion patterns:
 * - Container Transform: For card-to-detail transitions
 * - Shared Axis: For spatial/navigational relationships
 * - Fade Through: For top-level destinations
 * - Fade: For overlays and dialogs
 */
object MaterialMotion {
    
    // Material 3 Durations (in milliseconds)
    // Based on ?attr/motionDuration* theme attributes
    const val DURATION_SHORT_1 = 50
    const val DURATION_SHORT_2 = 100
    const val DURATION_SHORT_3 = 150
    const val DURATION_SHORT_4 = 200
    const val DURATION_MEDIUM_1 = 250
    const val DURATION_MEDIUM_2 = 300
    const val DURATION_MEDIUM_3 = 350
    const val DURATION_MEDIUM_4 = 400
    const val DURATION_LONG_1 = 450
    const val DURATION_LONG_2 = 500
    const val DURATION_LONG_3 = 550
    const val DURATION_LONG_4 = 600
    
    // Material 3 Easing Curves
    // Based on ?attr/motionEasing* theme attributes
    
    /**
     * Standard easing - for utility focused animations (begin and end on screen)
     * cubic-bezier(0.2, 0, 0, 1)
     */
    val EasingStandard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    
    /**
     * Standard decelerate - for animations entering the screen
     * cubic-bezier(0, 0, 0, 1)
     */
    val EasingStandardDecelerate = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)
    
    /**
     * Standard accelerate - for animations exiting the screen
     * cubic-bezier(0.3, 0, 1, 1)
     */
    val EasingStandardAccelerate = CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)
    
    /**
     * Emphasized easing - for common M3 animations (begin and end on screen)
     * This is the signature M3 easing curve
     * cubic-bezier(0.2, 0, 0, 1) approximation
     */
    val EasingEmphasized = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    
    /**
     * Emphasized decelerate - for M3 animations entering the screen
     * cubic-bezier(0.05, 0.7, 0.1, 1)
     */
    val EasingEmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    
    /**
     * Emphasized accelerate - for M3 animations exiting the screen
     * cubic-bezier(0.3, 0, 0.8, 0.15)
     */
    val EasingEmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    
    /**
     * Linear easing - for simple, non-stylized motion
     * cubic-bezier(0, 0, 1, 1)
     */
    val EasingLinear = LinearEasing
    
    // ========================================
    // MATERIAL MOTION PATTERNS
    // ========================================
    
    /**
     * CONTAINER TRANSFORM
     * For transitions between UI elements that include a container
     * Example: Card → Detail screen, FAB → Bottom sheet
     * 
     * Duration: motionDurationLong1 (300ms entering, 250ms exiting)
     * Easing: motionEasingStandard
     */
    object ContainerTransform {
        private const val DURATION_ENTER = DURATION_MEDIUM_2 // 300ms
        private const val DURATION_EXIT = DURATION_MEDIUM_1 // 250ms
        
        fun enterTransition(): EnterTransition {
            return fadeIn(
                animationSpec = tween(
                    durationMillis = DURATION_ENTER,
                    easing = EasingStandardDecelerate
                )
            ) + scaleIn(
                initialScale = 0.8f,
                animationSpec = tween(
                    durationMillis = DURATION_ENTER,
                    easing = EasingEmphasizedDecelerate
                )
            )
        }
        
        fun exitTransition(): ExitTransition {
            return fadeOut(
                animationSpec = tween(
                    durationMillis = DURATION_EXIT,
                    easing = EasingStandardAccelerate
                )
            ) + scaleOut(
                targetScale = 0.95f,
                animationSpec = tween(
                    durationMillis = DURATION_EXIT,
                    easing = EasingEmphasizedAccelerate
                )
            )
        }
        
        fun popEnterTransition(): EnterTransition {
            return fadeIn(
                animationSpec = tween(
                    durationMillis = DURATION_ENTER,
                    easing = EasingStandardDecelerate
                )
            ) + scaleIn(
                initialScale = 0.95f,
                animationSpec = tween(
                    durationMillis = DURATION_ENTER,
                    easing = EasingEmphasizedDecelerate
                )
            )
        }
        
        fun popExitTransition(): ExitTransition {
            return fadeOut(
                animationSpec = tween(
                    durationMillis = DURATION_EXIT,
                    easing = EasingStandardAccelerate
                )
            ) + scaleOut(
                targetScale = 0.8f,
                animationSpec = tween(
                    durationMillis = DURATION_EXIT,
                    easing = EasingEmphasizedAccelerate
                )
            )
        }
    }
    
    /**
     * SHARED AXIS (X-axis)
     * For transitions between UI elements with spatial relationship
     * Example: Horizontal pager, Forward/backward navigation
     * 
     * Duration: motionDurationLong1 (300ms)
     * Easing: motionEasingStandard
     */
    object SharedAxisX {
        private const val DURATION = DURATION_MEDIUM_2 // 300ms
        private const val SLIDE_DISTANCE = 30 // dp, approximate Material spec
        
        fun enterTransition(forward: Boolean = true): EnterTransition {
            return slideInHorizontally(
                initialOffsetX = { if (forward) it else -it },
                animationSpec = tween(
                    durationMillis = DURATION,
                    easing = EasingStandardDecelerate
                )
            ) + fadeIn(
                animationSpec = tween(
                    durationMillis = DURATION,
                    easing = EasingStandardDecelerate
                )
            )
        }
        
        fun exitTransition(forward: Boolean = true): ExitTransition {
            return slideOutHorizontally(
                targetOffsetX = { if (forward) -it else it },
                animationSpec = tween(
                    durationMillis = DURATION,
                    easing = EasingStandardAccelerate
                )
            ) + fadeOut(
                animationSpec = tween(
                    durationMillis = DURATION,
                    easing = EasingStandardAccelerate
                )
            )
        }
    }
    
    /**
     * SHARED AXIS (Z-axis)
     * For transitions showing parent-child relationships
     * Example: Parent screen → Child screen (drill down)
     * 
     * Duration: motionDurationLong1 (300ms)
     * Easing: motionEasingStandard
     */
    object SharedAxisZ {
        private const val DURATION = DURATION_MEDIUM_2 // 300ms
        
        fun enterTransition(forward: Boolean = true): EnterTransition {
            return fadeIn(
                animationSpec = tween(
                    durationMillis = DURATION,
                    easing = EasingStandardDecelerate
                )
            ) + scaleIn(
                initialScale = if (forward) 0.8f else 1.1f,
                animationSpec = tween(
                    durationMillis = DURATION,
                    easing = EasingEmphasizedDecelerate
                )
            )
        }
        
        fun exitTransition(forward: Boolean = true): ExitTransition {
            return fadeOut(
                animationSpec = tween(
                    durationMillis = DURATION,
                    easing = EasingStandardAccelerate
                )
            ) + scaleOut(
                targetScale = if (forward) 1.1f else 0.8f,
                animationSpec = tween(
                    durationMillis = DURATION,
                    easing = EasingEmphasizedAccelerate
                )
            )
        }
    }
    
    /**
     * FADE THROUGH
     * For transitions between UI elements without strong relationship
     * Example: Bottom nav destinations, Tab switching
     * 
     * Duration: motionDurationLong1 (300ms)
     * Easing: motionEasingStandard
     */
    object FadeThrough {
        private const val DURATION = DURATION_MEDIUM_2 // 300ms
        
        fun enterTransition(): EnterTransition {
            return fadeIn(
                animationSpec = tween(
                    durationMillis = DURATION,
                    delayMillis = DURATION / 2, // Enter after exit starts
                    easing = EasingStandardDecelerate
                )
            ) + scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(
                    durationMillis = DURATION,
                    delayMillis = DURATION / 2,
                    easing = EasingStandardDecelerate
                )
            )
        }
        
        fun exitTransition(): ExitTransition {
            return fadeOut(
                animationSpec = tween(
                    durationMillis = DURATION / 2, // Exit faster
                    easing = EasingStandardAccelerate
                )
            )
        }
    }
    
    /**
     * FADE
     * For UI elements that enter/exit within screen bounds
     * Example: Dialogs, Menus, FABs, Snackbars
     * 
     * Duration: motionDurationShort2 (150ms entering, 75ms exiting)
     * Easing: motionEasingLinear
     */
    object Fade {
        private const val DURATION_ENTER = DURATION_SHORT_3 // 150ms
        private const val DURATION_EXIT = DURATION_SHORT_1 // 75ms
        
        fun enterTransition(): EnterTransition {
            return fadeIn(
                animationSpec = tween(
                    durationMillis = DURATION_ENTER,
                    easing = EasingLinear
                )
            ) + scaleIn(
                initialScale = 0.8f,
                animationSpec = tween(
                    durationMillis = DURATION_ENTER,
                    easing = EasingLinear
                )
            )
        }
        
        fun exitTransition(): ExitTransition {
            return fadeOut(
                animationSpec = tween(
                    durationMillis = DURATION_EXIT,
                    easing = EasingLinear
                )
            ) + scaleOut(
                targetScale = 0.8f,
                animationSpec = tween(
                    durationMillis = DURATION_EXIT,
                    easing = EasingLinear
                )
            )
        }
    }
}

