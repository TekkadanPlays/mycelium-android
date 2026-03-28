package social.mycelium.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import social.mycelium.android.ui.components.common.cutoutPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Full-screen QR code for the current user's npub (nostr:npub1...).
 * Mycelium-themed: dark surface, primary/light modules.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrCodeScreen(
    npub: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val qrContent = if (!npub.isNullOrBlank()) "nostr:$npub" else ""

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text("My QR Code") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (qrContent.isEmpty()) {
                Text(
                    text = "Sign in to show your QR code",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Scaffold
            }
            Spacer(modifier = Modifier.height(24.dp))
            // QR on dark rounded surface, light modules (Mycelium theme)
            Surface(
                modifier = Modifier
                    .size(280.dp)
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    QrCodeBitmap(
                        content = qrContent,
                        modifier = Modifier.fillMaxSize(),
                        moduleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        backgroundColor = Color.Transparent
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Scan to add me",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Renders a QR code from [content] using ZXing. [moduleColor] for modules, [backgroundColor] for background.
 */
@Composable
private fun QrCodeBitmap(
    content: String,
    modifier: Modifier = Modifier,
    moduleColor: Color = Color.Black,
    backgroundColor: Color = Color.White
) {
    val matrix = rememberQrMatrix(content)
    if (matrix == null) return

    Canvas(modifier = modifier) {
        val width = size.minDimension
        val height = size.minDimension
        val dimension = matrix.width.coerceAtMost(matrix.height)
        if (dimension <= 0) return@Canvas
        val cellSize = width / dimension

        drawRect(backgroundColor)
        for (y in 0 until dimension) {
            for (x in 0 until dimension) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = moduleColor,
                        topLeft = Offset(x * cellSize, y * cellSize),
                        size = androidx.compose.ui.geometry.Size(cellSize, cellSize)
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberQrMatrix(content: String): com.google.zxing.common.BitMatrix? {
    return androidx.compose.runtime.remember(content) {
        if (content.isEmpty()) return@remember null
        try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 256, 256, hints)
        } catch (_: Exception) {
            null
        }
    }
}
