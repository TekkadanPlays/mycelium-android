package social.mycelium.android.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Report
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.repository.Nip05Verifier
import social.mycelium.android.ui.icons.Nip05Verified
import social.mycelium.android.ui.icons.Nip05VerifiedDark

/**
 * Displays a NIP-05 identifier with a verification badge icon.
 * Triggers background verification on first composition if not already cached.
 *
 * @param nip05 The NIP-05 identifier string (e.g. "user@domain.com").
 * @param pubkeyHex The author's hex pubkey to verify against.
 * @param showFullIdentifier If true, shows the full nip05 text; if false, shows only the domain.
 */
@Composable
fun Nip05Badge(
    nip05: String,
    pubkeyHex: String,
    showFullIdentifier: Boolean = true,
    modifier: Modifier = Modifier
) {
    val status = Nip05Verifier.getStatus(pubkeyHex)

    LaunchedEffect(pubkeyHex, nip05) {
        Nip05Verifier.verify(pubkeyHex, nip05)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        when (status) {
            Nip05Verifier.VerificationStatus.VERIFIED -> {
                val isDark = isSystemInDarkTheme()
                Icon(
                    imageVector = if (isDark) Icons.Outlined.Nip05VerifiedDark else Icons.Outlined.Nip05Verified,
                    contentDescription = "NIP-05 verified",
                    modifier = Modifier.size(14.dp),
                    tint = Color.Unspecified
                )
            }
            Nip05Verifier.VerificationStatus.VERIFYING -> {
                Icon(
                    imageVector = Icons.Default.Downloading,
                    contentDescription = "Verifying NIP-05",
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFFFFC107)
                )
            }
            Nip05Verifier.VerificationStatus.FAILED -> {
                Icon(
                    imageVector = Icons.Default.Report,
                    contentDescription = "NIP-05 verification failed",
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFFEF5350)
                )
            }
            Nip05Verifier.VerificationStatus.UNKNOWN -> {
                // No icon shown yet
            }
        }

        if (status != Nip05Verifier.VerificationStatus.UNKNOWN) {
            Spacer(Modifier.width(4.dp))
        }

        val parts = nip05.split("@")
        val displayText = when {
            // _@domain.com → show only domain (Amethyst convention)
            parts.size == 2 && parts[0] == "_" -> parts[1]
            // Non-_ with showFullIdentifier → show full nip05
            showFullIdentifier -> nip05
            // Non-_ without showFullIdentifier → show domain only
            parts.size == 2 -> parts[1]
            else -> nip05
        }

        Text(
            text = displayText,
            fontSize = 13.sp,
            color = if (status == Nip05Verifier.VerificationStatus.VERIFIED)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
