package social.mycelium.android.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Custom icon definitions copied from Material Icons Extended
// to avoid including the entire large library

val Icons.Outlined.ArrowUpward: ImageVector
    get() {
        if (_arrowUpward != null) {
            return _arrowUpward!!
        }
        _arrowUpward = materialIcon(name = "Outlined.ArrowUpward") {
            materialPath {
                moveTo(4.0f, 12.0f)
                lineToRelative(1.41f, 1.41f)
                lineTo(11.0f, 7.83f)
                verticalLineTo(20.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(7.83f)
                lineToRelative(5.58f, 5.59f)
                lineTo(20.0f, 12.0f)
                lineToRelative(-8.0f, -8.0f)
                lineToRelative(-8.0f, 8.0f)
                close()
            }
        }
        return _arrowUpward!!
    }

private var _arrowUpward: ImageVector? = null

val Icons.Outlined.ArrowDownward: ImageVector
    get() {
        if (_arrowDownward != null) {
            return _arrowDownward!!
        }
        _arrowDownward = materialIcon(name = "Outlined.ArrowDownward") {
            materialPath {
                moveTo(20.0f, 12.0f)
                lineToRelative(-1.41f, -1.41f)
                lineTo(13.0f, 16.17f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(12.17f)
                lineToRelative(-5.58f, -5.59f)
                lineTo(4.0f, 12.0f)
                lineToRelative(8.0f, 8.0f)
                lineToRelative(8.0f, -8.0f)
                close()
            }
        }
        return _arrowDownward!!
    }

private var _arrowDownward: ImageVector? = null

val Icons.Outlined.ChatBubble: ImageVector
    get() {
        if (_chatBubble != null) {
            return _chatBubble!!
        }
        _chatBubble = materialIcon(name = "Outlined.ChatBubble") {
            materialPath {
                moveTo(20.0f, 2.0f)
                horizontalLineTo(4.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(18.0f)
                lineToRelative(4.0f, -4.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(20.0f, 16.0f)
                horizontalLineTo(6.0f)
                lineToRelative(-2.0f, 2.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(12.0f)
                close()
            }
        }
        return _chatBubble!!
    }

private var _chatBubble: ImageVector? = null

val Icons.Outlined.Bolt: ImageVector
    get() {
        if (_bolt != null) {
            return _bolt!!
        }
        _bolt = materialIcon(name = "Outlined.Bolt") {
            materialPath {
                moveTo(11.0f, 21.0f)
                horizontalLineToRelative(-1.0f)
                lineToRelative(1.0f, -7.0f)
                horizontalLineTo(7.5f)
                curveToRelative(-0.88f, 0.0f, -0.33f, -0.75f, -0.31f, -0.78f)
                curveTo(8.48f, 10.94f, 10.42f, 7.54f, 13.0f, 3.0f)
                horizontalLineToRelative(1.0f)
                lineToRelative(-1.0f, 7.0f)
                horizontalLineToRelative(3.51f)
                curveToRelative(0.4f, 0.0f, 0.62f, 0.19f, 0.4f, 0.66f)
                curveTo(12.97f, 17.55f, 11.0f, 21.0f, 11.0f, 21.0f)
                close()
            }
        }
        return _bolt!!
    }

private var _bolt: ImageVector? = null

val Icons.Outlined.Bookmark: ImageVector
    get() {
        if (_bookmark != null) {
            return _bookmark!!
        }
        _bookmark = materialIcon(name = "Outlined.Bookmark") {
            materialPath {
                moveTo(17.0f, 3.0f)
                horizontalLineTo(7.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(16.0f)
                lineToRelative(7.0f, -3.0f)
                lineToRelative(7.0f, 3.0f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(17.0f, 18.0f)
                lineToRelative(-5.0f, -2.18f)
                lineTo(7.0f, 18.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(13.0f)
                close()
            }
        }
        return _bookmark!!
    }

private var _bookmark: ImageVector? = null

val Icons.Outlined.ChatBubbleOutline: ImageVector
    get() {
        if (_chatBubbleOutline != null) {
            return _chatBubbleOutline!!
        }
        _chatBubbleOutline = materialIcon(name = "Outlined.ChatBubbleOutline") {
            materialPath {
                moveTo(20.0f, 2.0f)
                horizontalLineTo(4.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(18.0f)
                lineToRelative(4.0f, -4.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(20.0f, 16.0f)
                horizontalLineTo(6.0f)
                lineToRelative(-2.0f, 2.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(12.0f)
                close()
            }
        }
        return _chatBubbleOutline!!
    }

private var _chatBubbleOutline: ImageVector? = null

val Icons.Outlined.Reply: ImageVector
    get() {
        if (_reply != null) {
            return _reply!!
        }
        _reply = materialIcon(name = "Outlined.Reply") {
            materialPath {
                moveTo(10.0f, 9.0f)
                verticalLineTo(5.0f)
                lineToRelative(-7.0f, 7.0f)
                lineToRelative(7.0f, 7.0f)
                verticalLineToRelative(-4.0f)
                curveToRelative(3.31f, 0.0f, 6.0f, 2.69f, 6.0f, 6.0f)
                curveToRelative(0.0f, 1.01f, -0.25f, 1.97f, -0.7f, 2.8f)
                lineToRelative(1.46f, 1.46f)
                curveTo(18.97f, 15.17f, 20.0f, 13.21f, 20.0f, 11.0f)
                curveToRelative(0.0f, -4.42f, -3.58f, -8.0f, -8.0f, -8.0f)
                close()
            }
        }
        return _reply!!
    }

private var _reply: ImageVector? = null

/**
 * Amethyst-style NIP-05 verification icon: a checkmark inside an open circle with a tail
 * (resembling a stylized '@'). Path data from Amethyst's nip_05.xml.
 * Uses Color.Unspecified tint — call site should NOT apply a tint.
 */
val Icons.Outlined.Nip05Verified: ImageVector
    get() {
        if (_nip05Verified != null) {
            return _nip05Verified!!
        }
        _nip05Verified = ImageVector.Builder(
            name = "Nip05Verified",
            defaultWidth = 30.0f.dp,
            defaultHeight = 30.0f.dp,
            viewportWidth = 30.0f,
            viewportHeight = 30.0f
        ).apply {
            // Checkmark path
            addPath(
                pathData = addPathNodes(
                    "m15.408,21.113 l-5.57,-5.026c-0.679,-0.679 -0.815,-1.766 -0.136,-2.445 0.679,-0.679 1.766,-0.815 2.445,-0.136L15,16.087 19.619,10.924c0.679,-0.679 1.766,-0.815 2.445,-0.136 0.679,0.679 0.815,1.766 0.136,2.445z"
                ),
                fill = androidx.compose.ui.graphics.SolidColor(Nip05Purple)
            )
            // Circle-with-tail path
            addPath(
                pathData = addPathNodes(
                    "M15,0.056C6.849,0.056 0.056,6.713 0.056,15c0,8.287 6.657,14.944 14.944,14.944 0.951,0 1.766,-0.815 1.766,-1.766 0,-0.951 -0.815,-1.766 -1.766,-1.766 -6.249,0 -11.411,-5.162 -11.411,-11.411 0,-6.249 5.162,-11.411 11.411,-11.411 6.249,0 11.411,5.162 11.411,11.411 0,2.717 -1.494,5.298 -3.396,6.113 -1.223,0.543 -2.445,0.136 -3.668,-0.951l0,0l-2.309,2.581c2.174,2.038 4.755,2.717 7.336,1.63C27.634,23.015 29.944,19.211 29.944,15 29.808,6.713 23.151,0.056 15,0.056Z"
                ),
                fill = androidx.compose.ui.graphics.SolidColor(Nip05Purple)
            )
        }.build()
        return _nip05Verified!!
    }

private var _nip05Verified: ImageVector? = null

/** NIP-05 icon for dark theme (muted purple). */
val Icons.Outlined.Nip05VerifiedDark: ImageVector
    get() {
        if (_nip05VerifiedDark != null) {
            return _nip05VerifiedDark!!
        }
        _nip05VerifiedDark = ImageVector.Builder(
            name = "Nip05VerifiedDark",
            defaultWidth = 30.0f.dp,
            defaultHeight = 30.0f.dp,
            viewportWidth = 30.0f,
            viewportHeight = 30.0f
        ).apply {
            addPath(
                pathData = addPathNodes(
                    "m15.408,21.113 l-5.57,-5.026c-0.679,-0.679 -0.815,-1.766 -0.136,-2.445 0.679,-0.679 1.766,-0.815 2.445,-0.136L15,16.087 19.619,10.924c0.679,-0.679 1.766,-0.815 2.445,-0.136 0.679,0.679 0.815,1.766 0.136,2.445z"
                ),
                fill = androidx.compose.ui.graphics.SolidColor(Nip05PurpleDark)
            )
            addPath(
                pathData = addPathNodes(
                    "M15,0.056C6.849,0.056 0.056,6.713 0.056,15c0,8.287 6.657,14.944 14.944,14.944 0.951,0 1.766,-0.815 1.766,-1.766 0,-0.951 -0.815,-1.766 -1.766,-1.766 -6.249,0 -11.411,-5.162 -11.411,-11.411 0,-6.249 5.162,-11.411 11.411,-11.411 6.249,0 11.411,5.162 11.411,11.411 0,2.717 -1.494,5.298 -3.396,6.113 -1.223,0.543 -2.445,0.136 -3.668,-0.951l0,0l-2.309,2.581c2.174,2.038 4.755,2.717 7.336,1.63C27.634,23.015 29.944,19.211 29.944,15 29.808,6.713 23.151,0.056 15,0.056Z"
                ),
                fill = androidx.compose.ui.graphics.SolidColor(Nip05PurpleDark)
            )
        }.build()
        return _nip05VerifiedDark!!
    }

private var _nip05VerifiedDark: ImageVector? = null

/** Amethyst's NIP-05 purple: light theme. */
private val Nip05Purple = androidx.compose.ui.graphics.Color(0xFFa770f3)
/** Amethyst's NIP-05 purple: dark theme. */
private val Nip05PurpleDark = androidx.compose.ui.graphics.Color(0xFF6e5490)

