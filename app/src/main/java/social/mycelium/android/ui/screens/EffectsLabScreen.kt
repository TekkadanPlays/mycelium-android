package social.mycelium.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import social.mycelium.android.data.Author
import social.mycelium.android.data.Note

// ══════════════════════════════════════════════════════════════════════
//  Effects Lab — Animation testing ground
//
//  Mock notes with interactive effect triggers. Each note demonstrates
//  a specific card animation (publish shimmer, zap, like, boost, etc).
//  Tap action buttons to fire the corresponding effect.
// ══════════════════════════════════════════════════════════════════════

/** Effect types available in the lab. */
enum class EffectType(val label: String, val description: String) {
    PUBLISH_SHIMMER("Publish Shimmer", "Purple shimmer line traveling across the top — the sending state"),
    PUBLISH_CONFIRMED("Publish Confirmed", "Green line expanding from center then fading"),
    PUBLISH_FAILED("Publish Failed", "Red error line across the top"),
    ZAP("Zap", "Lightning bolt flash + golden pulse on the card"),
    LIKE("Like", "Heart pop + pink glow"),
    BOOST("Boost", "Repost ripple + green sweep"),
    REPLY("Reply", "Subtle slide-in from the left edge"),
    BOOKMARK("Bookmark", "Flag fill + brief highlight"),
}

// ── Mock data ────────────────────────────────────────────────────────

private val mockAuthors = listOf(
    Author(id = "mock_alice", username = "alice", displayName = "Alice", avatarUrl = null, nip05 = "alice@example.com"),
    Author(id = "mock_bob", username = "bob", displayName = "Bob", avatarUrl = null, nip05 = "bob@nostr.com"),
    Author(id = "mock_carol", username = "carol", displayName = "Carol", avatarUrl = null),
    Author(id = "mock_dave", username = "dave", displayName = "Dave", avatarUrl = null, nip05 = "dave@relay.damus.io"),
)

private fun buildMockNotes(): List<Pair<EffectType, Note>> {
    val now = System.currentTimeMillis() / 1000
    return listOf(
        EffectType.PUBLISH_SHIMMER to Note(
            id = "effects_lab_1", author = mockAuthors[0],
            content = "This note is being published... Watch the purple shimmer line travel across the top of the card. This is the Sending state animation.",
            timestamp = now - 60, likes = 0, shares = 0, comments = 0, hashtags = listOf("effects_lab")
        ),
        EffectType.PUBLISH_CONFIRMED to Note(
            id = "effects_lab_2", author = mockAuthors[1],
            content = "Published successfully! The MyceliumGreen confirmation line expands from center to edges, then gently fades away.",
            timestamp = now - 120, likes = 3, shares = 1, comments = 2, hashtags = listOf("effects_lab")
        ),
        EffectType.PUBLISH_FAILED to Note(
            id = "effects_lab_3", author = mockAuthors[2],
            content = "Oops — publish failed. All relays rejected this note. The red error line appears across the full width.",
            timestamp = now - 180, likes = 0, shares = 0, comments = 0, hashtags = listOf("effects_lab")
        ),
        EffectType.ZAP to Note(
            id = "effects_lab_4", author = mockAuthors[3],
            content = "Zap me! ⚡ Tap the lightning bolt to see the zap animation — a golden flash and pulse effect. This should feel electric and satisfying.",
            timestamp = now - 240, likes = 12, shares = 3, comments = 5, zapCount = 21, hashtags = listOf("effects_lab")
        ),
        EffectType.LIKE to Note(
            id = "effects_lab_5", author = mockAuthors[0],
            content = "Like this note! ❤️ Tap the heart to see the like animation — a pop + pink glow radiating from the button. Satisfying micro-interaction.",
            timestamp = now - 300, likes = 42, shares = 7, comments = 8, reactions = listOf("❤️", "🔥", "👀"), hashtags = listOf("effects_lab")
        ),
        EffectType.BOOST to Note(
            id = "effects_lab_6", author = mockAuthors[1],
            content = "Boost this! 🔁 Tap the repost button to see the boost animation — a green sweep across the card indicating the note has been shared.",
            timestamp = now - 360, likes = 18, shares = 9, comments = 3, hashtags = listOf("effects_lab")
        ),
        EffectType.REPLY to Note(
            id = "effects_lab_7", author = mockAuthors[2],
            content = "Reply to me! 💬 Tap reply to see a subtle slide-in effect from the left edge, hinting at the conversation chain being extended.",
            timestamp = now - 420, likes = 5, shares = 1, comments = 14, hashtags = listOf("effects_lab")
        ),
        EffectType.BOOKMARK to Note(
            id = "effects_lab_8", author = mockAuthors[3],
            content = "Bookmark this note! 🔖 Tap the bookmark button to see the flag fill animation with a brief highlight on the card.",
            timestamp = now - 480, likes = 8, shares = 2, comments = 1, hashtags = listOf("effects_lab")
        ),
    )
}

// ── Screen ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectsLabScreen(
    onBackClick: () -> Unit = {},
    onNoteClick: (Note) -> Unit = {},
) {
    val mockNotes = remember { buildMockNotes() }
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Effects Lab", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item {
                // Header description
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Animation Testing Ground",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap the action buttons on each card to trigger its effect. Each note demonstrates a different animation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            items(mockNotes, key = { it.second.id }) { (effectType, note) ->
                EffectsLabCard(
                    effectType = effectType,
                    note = note,
                    onNoteClick = { onNoteClick(note) }
                )
            }
        }
    }
}

// ── Effect Card ──────────────────────────────────────────────────────

@Composable
private fun EffectsLabCard(
    effectType: EffectType,
    note: Note,
    onNoteClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // ── Per-effect trigger state ─────────────────────────────────────
    var isEffectActive by remember { mutableStateOf(false) }
    // For publish shimmer, keep it looping while active
    var shimmerActive by remember { mutableStateOf(false) }

    // Auto-reset non-looping effects after a duration
    LaunchedEffect(isEffectActive) {
        if (isEffectActive && effectType != EffectType.PUBLISH_SHIMMER) {
            delay(if (effectType == EffectType.PUBLISH_CONFIRMED) 2500L else 1500L)
            isEffectActive = false
        }
    }

    // ── Card-level animation state ───────────────────────────────────
    val cardGlowAlpha by animateFloatAsState(
        targetValue = if (isEffectActive) when (effectType) {
            EffectType.ZAP -> 0.15f
            EffectType.LIKE -> 0.12f
            EffectType.BOOST -> 0.10f
            EffectType.BOOKMARK -> 0.08f
            else -> 0f
        } else 0f,
        animationSpec = tween(400),
        label = "card_glow"
    )

    val glowColor = when (effectType) {
        EffectType.ZAP -> Color(0xFFFFD700) // gold
        EffectType.LIKE -> Color(0xFFE57373) // pastel red
        EffectType.BOOST -> Color(0xFF8FBC8F) // mycelium green
        EffectType.BOOKMARK -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Top effect line ──────────────────────────────────────────
        Box(modifier = Modifier.fillMaxWidth().height(3.dp)) {
            when {
                effectType == EffectType.PUBLISH_SHIMMER && shimmerActive -> {
                    PurpleShimmerLine()
                }
                effectType == EffectType.PUBLISH_CONFIRMED && isEffectActive -> {
                    ConfirmedExpandLine()
                }
                effectType == EffectType.PUBLISH_FAILED && isEffectActive -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                    )
                }
                effectType == EffectType.ZAP && isEffectActive -> {
                    ZapFlashLine()
                }
                effectType == EffectType.BOOST && isEffectActive -> {
                    BoostSweepLine()
                }
                effectType == EffectType.LIKE && isEffectActive -> {
                    LikeGlowLine()
                }
                effectType == EffectType.REPLY && isEffectActive -> {
                    ReplySlideInLine()
                }
                effectType == EffectType.BOOKMARK && isEffectActive -> {
                    BookmarkHighlightLine()
                }
            }
        }

        // ── Card body with glow ──────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(glowColor.copy(alpha = cardGlowAlpha))
                .clickable { onNoteClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column {
                // Effect type badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EffectTypeBadge(effectType)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        effectType.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(8.dp))

                // Author row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            note.author.displayName.first().toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            note.author.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        note.author.nip05?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Content
                Text(
                    note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))

                // Description
                Text(
                    effectType.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontWeight = FontWeight.Light
                )
                Spacer(Modifier.height(12.dp))

                // ── Action buttons ───────────────────────────────────
                EffectActionRow(
                    effectType = effectType,
                    note = note,
                    isEffectActive = isEffectActive || shimmerActive,
                    onTriggerEffect = {
                        if (effectType == EffectType.PUBLISH_SHIMMER) {
                            shimmerActive = !shimmerActive
                        } else {
                            isEffectActive = true
                        }
                    }
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
}

// ── Effect type badge icon ───────────────────────────────────────────

@Composable
private fun EffectTypeBadge(effectType: EffectType) {
    val icon: ImageVector = when (effectType) {
        EffectType.PUBLISH_SHIMMER -> Icons.Filled.CloudUpload
        EffectType.PUBLISH_CONFIRMED -> Icons.Filled.CheckCircle
        EffectType.PUBLISH_FAILED -> Icons.Filled.Error
        EffectType.ZAP -> Icons.Filled.ElectricBolt
        EffectType.LIKE -> Icons.Filled.Favorite
        EffectType.BOOST -> Icons.Filled.Repeat
        EffectType.REPLY -> Icons.AutoMirrored.Filled.Reply
        EffectType.BOOKMARK -> Icons.Filled.Bookmark
    }
    val tint: Color = when (effectType) {
        EffectType.PUBLISH_SHIMMER -> MaterialTheme.colorScheme.primary
        EffectType.PUBLISH_CONFIRMED -> Color(0xFF8FBC8F)
        EffectType.PUBLISH_FAILED -> MaterialTheme.colorScheme.error
        EffectType.ZAP -> Color(0xFFFFD700)
        EffectType.LIKE -> Color(0xFFE57373)
        EffectType.BOOST -> Color(0xFF8FBC8F)
        EffectType.REPLY -> MaterialTheme.colorScheme.primary
        EffectType.BOOKMARK -> MaterialTheme.colorScheme.tertiary
    }
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
}

// ── Action row with trigger button ───────────────────────────────────

@Composable
private fun EffectActionRow(
    effectType: EffectType,
    note: Note,
    isEffectActive: Boolean,
    onTriggerEffect: () -> Unit,
) {
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val countStyle = MaterialTheme.typography.bodySmall

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: stats
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (note.likes > 0) {
                Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(14.dp), tint = mutedColor)
                Spacer(Modifier.width(2.dp))
                Text("${note.likes}", style = countStyle, color = mutedColor)
                Spacer(Modifier.width(12.dp))
            }
            if (note.shares > 0) {
                Icon(Icons.Outlined.Repeat, null, Modifier.size(14.dp), tint = mutedColor)
                Spacer(Modifier.width(2.dp))
                Text("${note.shares}", style = countStyle, color = mutedColor)
                Spacer(Modifier.width(12.dp))
            }
            if (note.comments > 0) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(14.dp), tint = mutedColor)
                Spacer(Modifier.width(2.dp))
                Text("${note.comments}", style = countStyle, color = mutedColor)
                Spacer(Modifier.width(12.dp))
            }
            if (note.zapCount > 0) {
                Icon(Icons.Filled.ElectricBolt, null, Modifier.size(14.dp), tint = Color(0xFFFFD700))
                Spacer(Modifier.width(2.dp))
                Text("${note.zapCount}", style = countStyle, color = Color(0xFFFFD700))
            }
        }

        // Right: trigger button
        FilledTonalButton(
            onClick = onTriggerEffect,
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = if (isEffectActive)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            val buttonIcon: ImageVector = when (effectType) {
                EffectType.PUBLISH_SHIMMER -> Icons.Filled.PlayArrow
                EffectType.PUBLISH_CONFIRMED -> Icons.Filled.CheckCircle
                EffectType.PUBLISH_FAILED -> Icons.Filled.Error
                EffectType.ZAP -> Icons.Filled.ElectricBolt
                EffectType.LIKE -> Icons.Filled.Favorite
                EffectType.BOOST -> Icons.Filled.Repeat
                EffectType.REPLY -> Icons.AutoMirrored.Filled.Reply
                EffectType.BOOKMARK -> Icons.Filled.Bookmark
            }
            Icon(buttonIcon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                if (isEffectActive) {
                    if (effectType == EffectType.PUBLISH_SHIMMER) "Stop" else "Playing..."
                } else "Trigger",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
//  ANIMATION COMPOSABLES
// ══════════════════════════════════════════════════════════════════════

/** Purple shimmer line — the original publish sending effect. */
@Composable
private fun PurpleShimmerLine() {
    val infiniteTransition = rememberInfiniteTransition(label = "purple_shimmer")
    val offset by infiniteTransition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )
    val purple = Color(0xFFB49AFF) // Violet dark primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        purple.copy(alpha = 0f),
                        purple.copy(alpha = 0.9f),
                        purple.copy(alpha = 0f)
                    ),
                    startX = offset * 1000f,
                    endX = (offset + 0.3f) * 1000f
                )
            )
    )
}

/** Green confirmation line — expands from center, then fades. */
@Composable
private fun ConfirmedExpandLine() {
    val expandFraction by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "confirmed_expand"
    )
    val fadeAlpha by animateFloatAsState(
        targetValue = 0f,
        animationSpec = tween(1500, delayMillis = 600),
        label = "confirmed_fade"
    )
    val green = Color(0xFF8FBC8F) // MyceliumGreen
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = expandFraction)
                .height(3.dp)
                .background(green.copy(alpha = 0.7f * (1f - fadeAlpha * 0.7f)))
        )
    }
}

/** Zap flash — golden lightning pulse across the card top. */
@Composable
private fun ZapFlashLine() {
    val infiniteTransition = rememberInfiniteTransition(label = "zap_flash")
    val flash by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 800
                0f at 0
                1f at 100
                0.3f at 200
                0.8f at 350
                0f at 800
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "zap_flash_val"
    )
    val gold = Color(0xFFFFD700)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        gold.copy(alpha = flash * 0.3f),
                        gold.copy(alpha = flash),
                        Color.White.copy(alpha = flash * 0.6f),
                        gold.copy(alpha = flash),
                        gold.copy(alpha = flash * 0.3f),
                    )
                )
            )
    )
}

/** Boost sweep — green line sweeping left to right. */
@Composable
private fun BoostSweepLine() {
    val infiniteTransition = rememberInfiniteTransition(label = "boost_sweep")
    val offset by infiniteTransition.animateFloat(
        initialValue = -0.2f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "boost_offset"
    )
    val green = Color(0xFF8FBC8F)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        green.copy(alpha = 0f),
                        green.copy(alpha = 0.8f),
                        green.copy(alpha = 0f)
                    ),
                    startX = offset * 1000f,
                    endX = (offset + 0.25f) * 1000f
                )
            )
    )
}

/** Like glow — pink/red pulse line. */
@Composable
private fun LikeGlowLine() {
    val infiniteTransition = rememberInfiniteTransition(label = "like_glow")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "like_alpha"
    )
    val pink = Color(0xFFE57373)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(pink.copy(alpha = alpha * 0.8f))
    )
}

/** Reply slide-in — line grows from left edge. */
@Composable
private fun ReplySlideInLine() {
    val fraction by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "reply_slide"
    )
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction = fraction)
            .height(3.dp)
            .background(primary.copy(alpha = 0.7f))
    )
}

/** Bookmark highlight — brief warm glow across top. */
@Composable
private fun BookmarkHighlightLine() {
    val alpha by animateFloatAsState(
        targetValue = 0f,
        animationSpec = tween(1200, easing = LinearEasing),
        label = "bookmark_fade"
    )
    val tertiary = MaterialTheme.colorScheme.tertiary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(tertiary.copy(alpha = 0.8f * (1f - alpha * 0.8f)))
    )
}
