package social.mycelium.android.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle

/**
 * Renders note content text with click handling and long-press "Copy link" for URL spans.
 * Uses [Text] with [onTextLayout] and gesture detection to resolve tap/long-press position to character offset.
 */
@Composable
fun ClickableNoteContent(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    onClick: (Int) -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val clipboardManager = LocalClipboardManager.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var showCopyLinkMenu by remember { mutableStateOf(false) }
    var urlToCopy by remember { mutableStateOf<String?>(null) }

    Text(
        text = text,
        style = style,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier.pointerInput(text) {
            detectTapGestures(
                onTap = { offset: Offset ->
                    layoutResult?.getOffsetForPosition(offset)?.let { idx ->
                        onClick(idx)
                    }
                },
                onLongPress = { offset: Offset ->
                    layoutResult?.getOffsetForPosition(offset)?.let { idx ->
                        val url = text.getStringAnnotations(tag = "URL", start = idx, end = idx).firstOrNull()?.item
                        if (url != null) {
                            urlToCopy = url
                            showCopyLinkMenu = true
                        } else {
                            onLongPress?.invoke()
                        }
                    } ?: onLongPress?.invoke()
                }
            )
        },
        onTextLayout = { layoutResult = it }
    )

    DropdownMenu(
        expanded = showCopyLinkMenu,
        onDismissRequest = { showCopyLinkMenu = false; urlToCopy = null }
    ) {
        DropdownMenuItem(
            text = { Text("Copy link") },
            onClick = {
                urlToCopy?.let { clipboardManager.setText(AnnotatedString(it)) }
                showCopyLinkMenu = false
                urlToCopy = null
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}
