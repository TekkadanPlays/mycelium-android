package social.mycelium.android.ui.components.preview

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import social.mycelium.android.data.SampleData
import social.mycelium.android.data.UrlPreviewInfo
import social.mycelium.android.ui.components.note.ModernNoteCard

/**
 * Test component to verify URL preview functionality
 */
@Composable
fun UrlPreviewTest() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "URL Preview Test",
            style = MaterialTheme.typography.headlineMedium
        )
        
        // Test with sample note that has URL previews
        val sampleNote = SampleData.sampleNotes.first()
        
        Text(
            text = "Sample Note with URL Preview:",
            style = MaterialTheme.typography.titleMedium
        )
        
        ModernNoteCard(
            note = sampleNote,
            onLike = {},
            onShare = {},
            onComment = {},
            onProfileClick = {},
            onNoteClick = {},
            onZap = { _, _ -> },
            onCustomZap = {},
            onZapSettings = {}
        )
        
        // Test standalone URL preview card
        Text(
            text = "Standalone URL Preview Card:",
            style = MaterialTheme.typography.titleMedium
        )
        
        UrlPreviewCard(
            previewInfo = UrlPreviewInfo(
                url = "https://example.com",
                title = "Example Website",
                description = "This is an example website with a comprehensive description that demonstrates how URL previews work in the Mycelium app.",
                imageUrl = "https://picsum.photos/400/200",
                siteName = "example.com"
            )
        )
    }
}

@Preview(showBackground = true)
@Composable
fun UrlPreviewTestPreview() {
    MaterialTheme {
        UrlPreviewTest()
    }
}










