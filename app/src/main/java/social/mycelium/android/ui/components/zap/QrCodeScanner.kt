package social.mycelium.android.ui.components.zap

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Launches the embedded ZXing QR-code scanner Activity.
 *
 * Fires [onScan] with the decoded string when a QR code is read,
 * or `null` if the user cancels / no code is found.
 *
 * Usage:
 * ```
 * if (qrScanning) {
 *     SimpleQrCodeScanner { result ->
 *         qrScanning = false
 *         result?.let { handleNwcUri(it) }
 *     }
 * }
 * ```
 */
@Composable
fun SimpleQrCodeScanner(onScan: (String?) -> Unit) {
    val qrLauncher =
        rememberLauncherForActivityResult(ScanContract()) {
            if (it.contents != null) {
                onScan(it.contents)
            } else {
                onScan(null)
            }
        }

    val scanOptions =
        ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Point at an NWC QR code")
            setBeepEnabled(false)
            setOrientationLocked(false)
            addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN)
        }

    DisposableEffect(Unit) {
        qrLauncher.launch(scanOptions)
        onDispose {}
    }
}
