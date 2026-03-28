package social.mycelium.android.ui.components.note

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import social.mycelium.android.data.PollData
import social.mycelium.android.data.PollOption
import social.mycelium.android.repository.content.PollResponseRepository
import social.mycelium.android.repository.cache.ProfileMetadataCache
import social.mycelium.android.repository.cache.QuotedNoteCache
import social.mycelium.android.repository.content.ZapPollResponseRepository
import social.mycelium.android.utils.NoteContentBlock
import social.mycelium.android.utils.UrlDetector
import social.mycelium.android.utils.buildNoteContentWithInlinePreviews
import social.mycelium.android.ui.components.common.ProfilePicture
import social.mycelium.android.ui.components.preview.UrlPreviewCard

private val MyceliumGreen = Color(0xFF8FBC8F)

/**
 * NIP-88 Poll UI block rendered inside NoteCard when note.pollData != null.
 * Square edge-to-edge design matching the app's visual language.
 */
@Composable
fun PollBlock(
    pollData: PollData,
    noteId: String,
    noteAuthorPubkey: String,
    relayUrls: List<String>,
    myPubkey: String?,
    onVote: (noteId: String, authorPubkey: String, selectedOptions: Set<String>, relayHint: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val talliesMap by PollResponseRepository.tallies.collectAsState()
    val tally = talliesMap[noteId]

    LaunchedEffect(noteId) {
        PollResponseRepository.fetchTally(noteId, relayUrls, myPubkey)
    }

    // Live subscription: keep receiving new votes while the poll is in view
    DisposableEffect(noteId, relayUrls) {
        val handle = PollResponseRepository.subscribeLive(noteId, relayUrls, myPubkey)
        onDispose { PollResponseRepository.cancelLive(noteId) }
    }

    // Populate option labels on the tally from PollData (available here but not in the repository)
    LaunchedEffect(tally, pollData.options) {
        if (tally != null && tally.optionLabels.isEmpty() && pollData.options.isNotEmpty()) {
            val labels = pollData.options.associate { it.code to it.label }
            PollResponseRepository.setOptionLabels(noteId, labels)
        }
    }

    val hasVoted = tally?.myVotedOptions?.isNotEmpty() == true
    val totalVoters = tally?.totalVoters ?: 0
    val showResults = hasVoted || pollData.hasEnded || pollData.showResults
    var pendingSelections by remember(noteId) { mutableStateOf<Set<String>>(emptySet()) }

    // Find the leading option for highlight
    val maxVotes = remember(tally) {
        tally?.votesByOption?.values?.maxOfOrNull { it.size } ?: 0
    }

    // ── Voter profile resolution for inline avatars ──
    val profileCache = remember { ProfileMetadataCache.getInstance() }
    val allVoterPubkeys = remember(tally?.votesByOption) {
        tally?.votesByOption?.values?.flatten()?.toSet() ?: emptySet()
    }
    var voterProfileRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(allVoterPubkeys) {
        if (allVoterPubkeys.isEmpty()) return@LaunchedEffect
        val uncached = allVoterPubkeys.filter { profileCache.getAuthor(it) == null }
        if (uncached.isNotEmpty()) {
            profileCache.requestProfiles(uncached, profileCache.getConfiguredRelayUrls())
        }
    }
    LaunchedEffect(Unit) {
        profileCache.profileUpdated.collect { pk ->
            if (pk in allVoterPubkeys) voterProfileRevision++
        }
    }
    @Suppress("UNUSED_EXPRESSION") voterProfileRevision

    // Auto-collapse polls with many options to reduce UI clutter
    val manyOptions = pollData.options.size > 4
    var optionsExpanded by remember(noteId) { mutableStateOf(!manyOptions) }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Header bar ──
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.HowToVote,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (pollData.isMultipleChoice) "MULTIPLE CHOICE" else "POLL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                }
                // Status: voter count / deadline / ended
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pollData.hasEnded) {
                        Text(
                            text = "CLOSED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            letterSpacing = 0.5.sp
                        )
                    } else if (pollData.endsAt != null) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.width(3.dp))
                        val timeLeft = remember(pollData.endsAt) { formatTimeRemaining(pollData.endsAt) }
                        Text(
                            text = timeLeft,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    if (totalVoters > 0 || tally?.isFetching == true) {
                        if (pollData.endsAt != null || pollData.hasEnded) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "\u00B7",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = if (tally?.isFetching == true) "loading\u2026"
                            else "$totalVoters vote${if (totalVoters != 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // ── Options ── (collapse to first 4 when poll has many options)
        val visibleOptions = if (optionsExpanded) pollData.options else pollData.options.take(4)
        visibleOptions.forEachIndexed { index, option ->
            val isMyVote = tally?.myVotedOptions?.contains(option.code) == true
            val isPending = option.code in pendingSelections
            val voteCount = tally?.votesByOption?.get(option.code)?.size ?: 0
            val percentage = if (totalVoters > 0) (voteCount.toFloat() / totalVoters * 100) else 0f
            val isLeading = showResults && voteCount > 0 && voteCount == maxVotes

            PollOptionRow(
                option = option,
                showResults = showResults,
                isMyVote = isMyVote,
                isPending = isPending,
                isLeading = isLeading,
                voteCount = voteCount,
                percentage = percentage,
                enabled = !hasVoted && !pollData.hasEnded,
                onClick = {
                    if (hasVoted || pollData.hasEnded) return@PollOptionRow
                    if (pollData.isMultipleChoice) {
                        pendingSelections = if (option.code in pendingSelections)
                            pendingSelections - option.code
                        else
                            pendingSelections + option.code
                    } else {
                        onVote(noteId, noteAuthorPubkey, setOf(option.code), relayUrls.firstOrNull())
                    }
                },
                myPubkey = myPubkey,
                onPollVote = onVote,
            )

            // ── Inline voter avatars ──
            if (showResults && voteCount > 0) {
                val voterPubkeys = tally?.votesByOption?.get(option.code)?.toList() ?: emptyList()
                val displayVoters = voterPubkeys.take(5)
                val extraCount = voteCount - displayVoters.size
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 6.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Overlapping mini avatars
                    Box {
                        displayVoters.forEachIndexed { avatarIdx, pubkey ->
                            val author = profileCache.resolveAuthor(pubkey)
                            Box(
                                modifier = Modifier
                                    .padding(start = (avatarIdx * 14).dp)
                                    .zIndex((displayVoters.size - avatarIdx).toFloat())
                                    .size(20.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surface,
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.surface,
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                            ) {
                                ProfilePicture(
                                    author = author,
                                    size = 20.dp,
                                    onClick = {}
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width((displayVoters.size * 14 + 6).dp))
                    if (extraCount > 0) {
                        Text(
                            text = "+$extraCount more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            if (index < visibleOptions.lastIndex) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )
            }
        }

        // ── "Show all / Show fewer" toggle for collapsed polls ──
        if (manyOptions) {
            val hiddenCount = pollData.options.size - 4
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                shape = RectangleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { optionsExpanded = !optionsExpanded }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (optionsExpanded) "Show fewer" else "Show all ${pollData.options.size} options (+$hiddenCount more)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ── Submit bar for multiple choice ──
        if (pollData.isMultipleChoice && !hasVoted && !pollData.hasEnded && pendingSelections.isNotEmpty()) {
            Surface(
                color = MyceliumGreen,
                shape = RectangleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onVote(noteId, noteAuthorPubkey, pendingSelections, relayUrls.firstOrNull())
                        pendingSelections = emptySet()
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.HowToVote,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Submit ${pendingSelections.size} selection${if (pendingSelections.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun PollOptionRow(
    option: PollOption,
    showResults: Boolean,
    isMyVote: Boolean,
    isPending: Boolean,
    isLeading: Boolean,
    voteCount: Int,
    percentage: Float,
    enabled: Boolean,
    onClick: () -> Unit,
    onProfileClick: (String) -> Unit = {},
    onNoteClick: (social.mycelium.android.data.Note) -> Unit = {},
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    myPubkey: String? = null,
    onPollVote: ((String, String, Set<String>, String?) -> Unit)? = null,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = if (showResults) (percentage / 100f).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "poll_progress"
    )

    val progressColor = when {
        isMyVote -> MyceliumGreen.copy(alpha = 0.20f)
        isLeading -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
    }
    val barAccentColor = when {
        isMyVote -> MyceliumGreen
        isLeading -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val leftBorderColor = when {
        isPending -> MaterialTheme.colorScheme.primary
        isMyVote -> MyceliumGreen
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (leftBorderColor != Color.Transparent)
                    Modifier.border(width = 0.dp, color = Color.Transparent) // placeholder; actual border drawn below
                else Modifier
            )
            .drawBehind {
                // Fill progress bar from left
                if (showResults && animatedProgress > 0f) {
                    drawRect(
                        color = progressColor,
                        topLeft = Offset.Zero,
                        size = Size(size.width * animatedProgress, size.height)
                    )
                    // Bottom accent line under progress
                    drawRect(
                        color = barAccentColor,
                        topLeft = Offset(0f, size.height - 2.dp.toPx()),
                        size = Size(size.width * animatedProgress, 2.dp.toPx())
                    )
                }
                // Left accent bar for selected/voted
                if (leftBorderColor != Color.Transparent) {
                    drawRect(
                        color = leftBorderColor,
                        topLeft = Offset.Zero,
                        size = Size(3.dp.toPx(), size.height)
                    )
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (leftBorderColor != Color.Transparent) 16.dp else 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 12.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Check indicator
            if (isPending || (showResults && isMyVote)) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = if (isMyVote) "Your vote" else "Selected",
                    modifier = Modifier.size(16.dp),
                    tint = if (isMyVote) MyceliumGreen else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
            }

            // Option label — rich content: NIP-19 refs, media, quoted notes, URLs
            RichPollOptionContent(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = when {
                        isMyVote -> FontWeight.SemiBold
                        isLeading && showResults -> FontWeight.Medium
                        else -> FontWeight.Normal
                    },
                    color = when {
                        isMyVote -> MyceliumGreen
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                ),
                maxLines = 2,
                modifier = Modifier.weight(1f),
                onProfileClick = onProfileClick,
                onNoteClick = onNoteClick,
                onVideoClick = onVideoClick,
                onOpenImageViewer = onOpenImageViewer,
                myPubkey = myPubkey,
                onPollVote = onPollVote,
            )

            // Results
            if (showResults) {
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${percentage.toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isMyVote -> MyceliumGreen
                            isLeading -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (voteCount > 0) {
                        Text(
                            text = "$voteCount vote${if (voteCount != 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

private val ZapYellow = Color(0xFFF59E0B)

/**
 * Kind-6969 Zap Poll UI block rendered inside NoteCard when note.zapPollData != null.
 * Votes are cast by zapping with a poll_option tag.
 */
@Composable
fun ZapPollBlock(
    zapPollData: social.mycelium.android.data.ZapPollData,
    noteId: String,
    noteAuthorPubkey: String,
    relayUrls: List<String>,
    myPubkey: String?,
    onZapVote: ((noteId: String, authorPubkey: String, optionIndex: Int, relayHint: String?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val talliesMap by ZapPollResponseRepository.tallies.collectAsState()
    val tally = talliesMap[noteId]

    LaunchedEffect(noteId) {
        ZapPollResponseRepository.fetchTally(noteId, relayUrls, myPubkey)
    }

    // Live subscription: keep receiving new zap votes while the poll is in view
    DisposableEffect(noteId, relayUrls) {
        val handle = ZapPollResponseRepository.subscribeLive(noteId, relayUrls, myPubkey)
        onDispose { ZapPollResponseRepository.cancelLive(noteId) }
    }

    // Populate option labels
    LaunchedEffect(tally, zapPollData.options) {
        if (tally != null && tally.optionLabels.isEmpty() && zapPollData.options.isNotEmpty()) {
            val labels = zapPollData.options.associate { it.index to it.description }
            ZapPollResponseRepository.setOptionLabels(noteId, labels)
        }
    }

    val hasVoted = tally?.myVotedOptions?.isNotEmpty() == true
    val totalSats = tally?.totalSats ?: 0L
    val totalVoters = tally?.totalVoters ?: 0
    val showResults = hasVoted || zapPollData.hasEnded

    // Find leading option by sats
    val satsByOption = remember(tally?.votesByOption) {
        tally?.votesByOption?.mapValues { (_, votes) -> votes.sumOf { it.amountSats } } ?: emptyMap()
    }
    val maxSats = satsByOption.values.maxOrNull() ?: 0L

    // Voter profile resolution
    val profileCache = remember { ProfileMetadataCache.getInstance() }
    val allVoterPubkeys = remember(tally?.votesByOption) {
        tally?.votesByOption?.values?.flatten()?.map { it.voterPubkey }?.toSet() ?: emptySet()
    }
    var voterProfileRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(allVoterPubkeys) {
        if (allVoterPubkeys.isEmpty()) return@LaunchedEffect
        val uncached = allVoterPubkeys.filter { profileCache.getAuthor(it) == null }
        if (uncached.isNotEmpty()) profileCache.requestProfiles(uncached, profileCache.getConfiguredRelayUrls())
    }
    LaunchedEffect(Unit) {
        profileCache.profileUpdated.collect { pk -> if (pk in allVoterPubkeys) voterProfileRevision++ }
    }
    @Suppress("UNUSED_EXPRESSION") voterProfileRevision

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Header bar ──
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⚡",
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "ZAP POLL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ZapYellow,
                        letterSpacing = 1.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (zapPollData.hasEnded) {
                        Text(
                            text = "CLOSED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            letterSpacing = 0.5.sp
                        )
                    } else if (zapPollData.closedAt != null) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = formatTimeRemaining(zapPollData.closedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    if (totalVoters > 0 || tally?.isFetching == true) {
                        if (zapPollData.closedAt != null || zapPollData.hasEnded) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "·",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = if (tally?.isFetching == true) "loading\u2026"
                            else "${social.mycelium.android.utils.ZapUtils.formatZapAmount(totalSats)} sats · $totalVoters vote${if (totalVoters != 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // ── Zap range info ──
        if (zapPollData.valueMinimum != null || zapPollData.valueMaximum != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                val rangeText = when {
                    zapPollData.valueMinimum != null && zapPollData.valueMaximum != null ->
                        "${zapPollData.valueMinimum}–${zapPollData.valueMaximum} sats per vote"

                    zapPollData.valueMinimum != null -> "min ${zapPollData.valueMinimum} sats"
                    else -> "max ${zapPollData.valueMaximum} sats"
                }
                Text(
                    text = rangeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        // ── Options ── (collapse to first 4 when > 4 options)
        val zapManyOptions = zapPollData.options.size > 4
        var zapOptionsExpanded by remember(noteId) { mutableStateOf(!zapManyOptions) }
        val visibleOptions = if (zapOptionsExpanded) zapPollData.options else zapPollData.options.take(4)
        visibleOptions.forEachIndexed { index, option ->
            val isMyVote = tally?.myVotedOptions?.contains(option.index) == true
            val optionSats = satsByOption[option.index] ?: 0L
            val optionVoters = tally?.votesByOption?.get(option.index)?.size ?: 0
            val percentage = if (totalSats > 0) (optionSats.toFloat() / totalSats * 100) else 0f
            val isLeading = showResults && optionSats > 0 && optionSats == maxSats

            ZapPollOptionRow(
                description = option.description,
                showResults = showResults,
                isMyVote = isMyVote,
                isLeading = isLeading,
                optionSats = optionSats,
                voterCount = optionVoters,
                percentage = percentage,
                enabled = !hasVoted && !zapPollData.hasEnded && onZapVote != null,
                onClick = {
                    if (!hasVoted && !zapPollData.hasEnded) {
                        onZapVote?.invoke(noteId, noteAuthorPubkey, option.index, relayUrls.firstOrNull())
                    }
                },
                myPubkey = myPubkey,
            )

            // Inline voter avatars
            if (showResults && optionVoters > 0) {
                val voters = tally?.votesByOption?.get(option.index) ?: emptyList()
                val displayVoters = voters.take(5)
                val extraCount = optionVoters - displayVoters.size
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 6.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        displayVoters.forEachIndexed { avatarIdx, vote ->
                            val author = profileCache.resolveAuthor(vote.voterPubkey)
                            Box(
                                modifier = Modifier
                                    .padding(start = (avatarIdx * 14).dp)
                                    .zIndex((displayVoters.size - avatarIdx).toFloat())
                                    .size(20.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surface,
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.surface,
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                            ) {
                                ProfilePicture(author = author, size = 20.dp, onClick = {})
                            }
                        }
                    }
                    Spacer(Modifier.width((displayVoters.size * 14 + 6).dp))
                    if (extraCount > 0) {
                        Text(
                            text = "+$extraCount more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            if (index < visibleOptions.lastIndex) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )
            }
        }

        // ── "Show all / Show fewer" toggle for collapsed zap polls ──
        if (zapManyOptions) {
            val hiddenCount = zapPollData.options.size - 4
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                shape = RectangleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { zapOptionsExpanded = !zapOptionsExpanded }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (zapOptionsExpanded) "Show fewer" else "Show all ${zapPollData.options.size} options (+$hiddenCount more)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ZapPollOptionRow(
    description: String,
    showResults: Boolean,
    isMyVote: Boolean,
    isLeading: Boolean,
    optionSats: Long,
    voterCount: Int,
    percentage: Float,
    enabled: Boolean,
    onClick: () -> Unit,
    onProfileClick: (String) -> Unit = {},
    onNoteClick: (social.mycelium.android.data.Note) -> Unit = {},
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    myPubkey: String? = null,
    onPollVote: ((String, String, Set<String>, String?) -> Unit)? = null,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = if (showResults) (percentage / 100f).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "zap_poll_progress"
    )

    val progressColor = when {
        isMyVote -> ZapYellow.copy(alpha = 0.15f)
        isLeading -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
    }
    val barAccentColor = when {
        isMyVote -> ZapYellow
        isLeading -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val leftBorderColor = when {
        isMyVote -> ZapYellow
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind {
                if (showResults && animatedProgress > 0f) {
                    drawRect(
                        color = progressColor,
                        topLeft = Offset.Zero,
                        size = Size(size.width * animatedProgress, size.height)
                    )
                    drawRect(
                        color = barAccentColor,
                        topLeft = Offset(0f, size.height - 2.dp.toPx()),
                        size = Size(size.width * animatedProgress, 2.dp.toPx())
                    )
                }
                if (leftBorderColor != Color.Transparent) {
                    drawRect(color = leftBorderColor, topLeft = Offset.Zero, size = Size(3.dp.toPx(), size.height))
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showResults && isMyVote) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Your vote",
                    modifier = Modifier.size(16.dp),
                    tint = ZapYellow
                )
                Spacer(Modifier.width(8.dp))
            }

            // Option description — rich content: NIP-19 refs, media, quoted notes, URLs
            RichPollOptionContent(
                text = description,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = when {
                        isMyVote -> FontWeight.SemiBold
                        isLeading && showResults -> FontWeight.Medium
                        else -> FontWeight.Normal
                    },
                    color = when {
                        isMyVote -> ZapYellow
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                ),
                maxLines = 2,
                modifier = Modifier.weight(1f),
                onProfileClick = onProfileClick,
                onNoteClick = onNoteClick,
                onVideoClick = onVideoClick,
                onOpenImageViewer = onOpenImageViewer,
                myPubkey = myPubkey,
                onPollVote = onPollVote,
            )

            if (showResults) {
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${percentage.toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isMyVote -> ZapYellow
                            isLeading -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (optionSats > 0) {
                        Text(
                            text = "${social.mycelium.android.utils.ZapUtils.formatZapAmount(optionSats)} sats",
                            style = MaterialTheme.typography.labelSmall,
                            color = ZapYellow.copy(alpha = 0.7f),
                            fontSize = 10.sp
                        )
                    }
                    if (voterCount > 0) {
                        Text(
                            text = "$voterCount vote${if (voterCount != 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            if (!showResults && enabled) {
                Spacer(Modifier.width(8.dp))
                Text("⚡", fontSize = 14.sp)
            }
        }
    }
}

/**
 * Rich content renderer for poll option labels.
 * Runs the label through the same content pipeline as kind-1 notes:
 * NIP-19 refs (npub → @name, nevent/note → quoted note), media, URLs, hashtags, custom emoji.
 */
@Composable
private fun RichPollOptionContent(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    onProfileClick: (String) -> Unit = {},
    onNoteClick: (social.mycelium.android.data.Note) -> Unit = {},
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    myPubkey: String? = null,
    onPollVote: ((String, String, Set<String>, String?) -> Unit)? = null,
) {
    val profileCache = remember { ProfileMetadataCache.getInstance() }
    val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    val uriHandler = LocalUriHandler.current

    // Check if the option label is primarily a single URL (common for link polls)
    val detectedUrls = remember(text) { UrlDetector.findUrls(text) }
    val isSingleUrlOption = remember(text, detectedUrls) {
        detectedUrls.size == 1 && text.trim() == detectedUrls.first()
    }

    if (isSingleUrlOption) {
        // Render compact URL embed card instead of raw URL text
        PollOptionUrlEmbed(
            url = detectedUrls.first(),
            style = style,
            modifier = modifier,
        )
    } else {
        // Detect media URLs for the block builder
        val mediaUrls = remember(text) {
            detectedUrls.filter { UrlDetector.isImageUrl(it) || UrlDetector.isVideoUrl(it) }.toSet()
        }

        val contentBlocks = remember(text) {
            buildNoteContentWithInlinePreviews(
                content = text,
                mediaUrls = mediaUrls,
                urlPreviews = emptyList(),
                linkStyle = linkStyle,
                profileCache = profileCache,
            )
        }

        Column(modifier = modifier) {
            contentBlocks.forEach { block ->
                when (block) {
                    is NoteContentBlock.Content -> {
                        val annotated = block.annotated
                        if (annotated.isNotEmpty()) {
                            ClickableNoteContent(
                                text = annotated,
                                style = style,
                                maxLines = maxLines,
                                emojiUrls = block.emojiUrls,
                                onClick = { offset ->
                                    val profile =
                                        annotated.getStringAnnotations(tag = "PROFILE", start = offset, end = offset)
                                            .firstOrNull()
                                    val url = annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                        .firstOrNull()
                                    when {
                                        profile != null -> onProfileClick(profile.item)
                                        url != null -> uriHandler.openUri(url.item)
                                    }
                                }
                            )
                        }
                    }

                    is NoteContentBlock.MediaGroup -> {
                        NoteMediaCarousel(
                            mediaList = block.urls,
                            allMediaUrls = block.urls,
                            groupStartIndex = 0,
                            initialMediaPage = 0,
                            isVisible = true,
                            onMediaPageChanged = {},
                            onImageTap = onOpenImageViewer,
                            onOpenImageViewer = onOpenImageViewer,
                            onVideoClick = onVideoClick,
                        )
                    }

                    is NoteContentBlock.QuotedNote -> {
                        val eventId = block.eventId
                        var meta by remember(eventId) { mutableStateOf(QuotedNoteCache.getCached(eventId)) }
                        if (meta == null) {
                            LaunchedEffect(eventId) {
                                meta = QuotedNoteCache.get(eventId)
                            }
                        }
                        val m = meta
                        if (m != null) {
                            val quotedAuthor = remember(m.authorId) { profileCache.resolveAuthor(m.authorId) }
                            QuotedNoteContent(
                                parentNoteId = eventId,
                                meta = m,
                                quotedAuthor = quotedAuthor,
                                quotedCounts = null,
                                linkStyle = linkStyle,
                                profileCache = profileCache,
                                isVisible = true,
                                onProfileClick = onProfileClick,
                                onNoteClick = onNoteClick,
                                onVideoClick = onVideoClick,
                                onOpenImageViewer = onOpenImageViewer,
                                myPubkey = myPubkey,
                                onPollVote = onPollVote,
                            )
                        } else {
                            Text(
                                text = "Loading quoted note…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }

                    is NoteContentBlock.Preview -> {
                        // Compact URL embed instead of full UrlPreviewCard
                        PollOptionUrlEmbed(
                            url = block.previewInfo.url,
                            style = style,
                        )
                    }

                    else -> { /* LiveEventReference, EmojiPack, Article — rare in poll options */
                    }
                }
            }
        }
    }
}

/**
 * Compact URL embed for poll option labels. Shows title + domain on the left,
 * tappable thumbnail on the right. The image opens the browser; the surrounding
 * poll option row still functions as the vote button.
 */
@Composable
private fun PollOptionUrlEmbed(
    url: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val urlPreviewService = remember { social.mycelium.android.services.UrlPreviewService() }
    val cacheRevision by social.mycelium.android.services.UrlPreviewCache.revision.collectAsState()

    var previewState by remember(url) {
        val cached = social.mycelium.android.services.UrlPreviewCache.get(url)
        mutableStateOf(cached)
    }

    // Re-read cache when revision changes (another component may have fetched it)
    @Suppress("UNUSED_EXPRESSION") cacheRevision
    LaunchedEffect(url, cacheRevision) {
        val cached = social.mycelium.android.services.UrlPreviewCache.get(url)
        if (cached != null) {
            previewState = cached
            return@LaunchedEffect
        }
        if (!social.mycelium.android.services.UrlPreviewCache.isLoading(url)) {
            social.mycelium.android.services.UrlPreviewCache.setLoadingState(
                url,
                social.mycelium.android.data.UrlPreviewState.Loading
            )
            val result = urlPreviewService.fetchPreview(url)
            social.mycelium.android.services.UrlPreviewCache.setLoadingState(url, result)
            if (result is social.mycelium.android.data.UrlPreviewState.Loaded) {
                previewState = result.previewInfo
            }
        }
    }

    val preview = previewState
    if (preview != null && preview.hasBasicInfo()) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Title + domain on the left
            Column(modifier = Modifier.weight(1f)) {
                if (preview.title.isNotEmpty()) {
                    Text(
                        text = preview.title,
                        style = style,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = preview.rootDomain,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Tappable thumbnail on the right
            if (preview.imageUrl.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                        )
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .clickable { uriHandler.openUri(url) },
                    contentAlignment = Alignment.Center
                ) {
                    coil.compose.AsyncImage(
                        model = preview.imageUrlFullPath,
                        contentDescription = preview.title.ifEmpty { "Link preview" },
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                }
            }
        }
    } else {
        // Fallback: show URL as styled text while loading or if no preview
        Text(
            text = url,
            style = style.copy(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Format seconds remaining into human-readable string. */
private fun formatTimeRemaining(endsAtEpochSeconds: Long): String {
    val nowSeconds = System.currentTimeMillis() / 1000
    val remaining = endsAtEpochSeconds - nowSeconds
    if (remaining <= 0) return "Ended"
    val days = remaining / 86400
    val hours = (remaining % 86400) / 3600
    val minutes = (remaining % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h left"
        hours > 0 -> "${hours}h ${minutes}m left"
        minutes > 0 -> "${minutes}m left"
        else -> "< 1m left"
    }
}
