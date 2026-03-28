package social.mycelium.android.ui.components.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Adds top padding that respects display cutouts (camera notch) while
 * providing a comfortable minimum so controls aren't jammed against the
 * top edge on devices without a cutout.
 *
 * Use this instead of [statusBarsPadding] when the status bar is hidden
 * and content draws into the cutout area.
 */
@Composable
fun Modifier.cutoutPadding(): Modifier {
    val density = LocalDensity.current
    val cutoutTop = with(density) { WindowInsets.displayCutout.getTop(this).toDp() }
    return this.padding(top = cutoutTop)
}
