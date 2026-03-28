package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.cybin.nip19.NAddress
import com.example.cybin.nip19.NEvent
import com.example.cybin.nip19.NNote
import com.example.cybin.nip19.NPub
import com.example.cybin.nip19.NProfile
import com.example.cybin.nip19.Nip19Parser
import com.halilibo.richtext.commonmark.CommonmarkAstNodeParser
import com.halilibo.richtext.markdown.BasicMarkdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle
import social.mycelium.android.data.Note
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.ui.components.common.ProfilePicture

/**
 * Full-length article reader for NIP-23 long-form content (kind 30023).
 * Renders the article content as markdown with cover image, title, author header,
 * and full body text — similar to Amethyst's article view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleViewScreen(
    note: Note,
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit = {},
    onNoteClick: (Note) -> Unit = {},
) {
    val title = note.topicTitle ?: "Untitled"
    val coverImage = note.imageUrl
    val wordCount = note.content.split(Regex("\\s+")).size
    val readMinutes = (wordCount / 200).coerceAtLeast(1)

    val profileCache = ProfileMetadataCache.getInstance()
    val displayAuthor = remember(note.author.id) {
        profileCache.resolveAuthor(note.author.id)
    }
    val authorLabel = displayAuthor.displayName.ifBlank { displayAuthor.username }
        .ifBlank { note.author.id.take(8) + "\u2026" }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Article", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // ── Cover image ──
            if (coverImage != null) {
                AsyncImage(
                    model = coverImage,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 280.dp)
                )
            }

            // ── Title ──
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
            )

            // ── Author row + read time ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfilePicture(
                    author = displayAuthor,
                    size = 36.dp,
                    onClick = { onProfileClick(note.author.id) }
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = authorLabel,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val formattedTime = remember(note.timestamp) { formatArticleTime(note.timestamp) }
                        Text(
                            text = "$formattedTime \u2022 $readMinutes min read",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Hashtags ──
            if (note.hashtags.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    note.hashtags.take(5).forEach { tag ->
                        Text(
                            text = "#$tag",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // ── Article body (markdown) ──
            val processedContent = remember(note.content) {
                preprocessNostrReferences(note.content, profileCache)
            }
            val astNode = remember(processedContent) {
                CommonmarkAstNodeParser().parse(processedContent)
            }

            val linkColor = MaterialTheme.colorScheme.primary
            val articleRichTextStyle = remember(linkColor) {
                RichTextStyle(
                    stringStyle = RichTextStringStyle(
                        linkStyle = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline
                            )
                        )
                    )
                )
            }

            RichText(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = articleRichTextStyle
            ) {
                BasicMarkdown(astNode)
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

/**
 * Pre-process article markdown to convert nostr: NIP-19 references into markdown links.
 * - nostr:npub1... / nostr:nprofile1... → [@DisplayName](nostr:npub1...)
 * - nostr:note1... / nostr:nevent1... → [Referenced note](nostr:note1...)
 * - nostr:naddr1... → [Referenced article](nostr:naddr1...) or similar
 */
private val nostrRefPattern = Regex(
    "(nostr:)(npub1|nprofile1|note1|nevent1|naddr1)([qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)",
    RegexOption.IGNORE_CASE
)

private fun preprocessNostrReferences(
    content: String,
    profileCache: ProfileMetadataCache
): String {
    return nostrRefPattern.replace(content) { match ->
        val fullUri = match.value
        try {
            val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@replace fullUri
            when (val entity = parsed.entity) {
                is NPub -> {
                    val author = profileCache.resolveAuthor(entity.hex)
                    val name = author.displayName.ifBlank { author.username }
                        .ifBlank { entity.hex.take(8) + "\u2026" }
                    "[@$name]($fullUri)"
                }
                is NProfile -> {
                    val author = profileCache.resolveAuthor(entity.hex)
                    val name = author.displayName.ifBlank { author.username }
                        .ifBlank { entity.hex.take(8) + "\u2026" }
                    "[@$name]($fullUri)"
                }
                is NNote -> {
                    "[Referenced note]($fullUri)"
                }
                is NEvent -> {
                    "[Referenced note]($fullUri)"
                }
                is NAddress -> {
                    val label = when (entity.kind) {
                        30023 -> "Referenced article"
                        34550 -> "Community"
                        30030 -> "Emoji pack"
                        else -> "Referenced event"
                    }
                    "[$label]($fullUri)"
                }
                else -> fullUri
            }
        } catch (_: Exception) {
            fullUri
        }
    }
}

private fun formatArticleTime(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestampMs
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 30 -> {
            val sdf = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestampMs))
        }
        days > 0 -> "${days}d ago"
        hours > 0 -> "${hours}h ago"
        minutes > 0 -> "${minutes}m ago"
        else -> "just now"
    }
}
