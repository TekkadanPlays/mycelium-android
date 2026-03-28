package social.mycelium.android.ui.screens

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import social.mycelium.android.ui.components.common.cutoutPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.outlined.Router
import social.mycelium.android.data.LiveActivity
import social.mycelium.android.data.LiveActivityStatus
import social.mycelium.android.data.LiveChatMessage
import social.mycelium.android.repository.LiveActivityRepository
import social.mycelium.android.repository.LiveChatRepository
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.ui.components.LiveStatusDot
import social.mycelium.android.ui.components.PipStreamManager
import social.mycelium.android.ui.components.RelayOrbs
import social.mycelium.android.viewmodel.AccountStateViewModel

/**
 * NIP-53 Live Stream viewer screen.
 * Shows the video player (HLS), stream info (title, host, participants),
 * and a placeholder for live chat (kind:1311) in the future.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveStreamScreen(
    activityAddressableId: String,
    onBackClick: () -> Unit,
    onProfileClick: (String) -> Unit = {},
    onRelayNavigate: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    accountStateViewModel: AccountStateViewModel = viewModel()
) {
    val decodedId = remember(activityAddressableId) { Uri.decode(activityAddressableId) }
    val repository = remember { LiveActivityRepository.getInstance() }
    val allActivities by repository.allActivities.collectAsState()
    val activity = allActivities.firstOrNull { "${it.hostPubkey}:${it.dTag}" == decodedId }

    if (activity == null) {
        // Activity not found or expired — show placeholder and allow back
        Scaffold(
            topBar = {
                Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Live Stream") },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        },
                        windowInsets = WindowInsets(0)
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.VideocamOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Stream not available",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    // Kill existing PiP only if opening a *different* stream that will actually play
    val hasPlayableStream = activity.streamingUrl != null || (activity.status == LiveActivityStatus.LIVE && activity.recordingUrl != null)
    LaunchedEffect(decodedId, hasPlayableStream) {
        if (hasPlayableStream && PipStreamManager.isActive && !PipStreamManager.isActiveFor(decodedId)) {
            PipStreamManager.kill()
        }
    }

    // --- Hoisted ExoPlayer: created at screen level so we can hand it off to PiP ---
    val context = LocalContext.current
    val streamUrl = activity.streamingUrl ?: activity.recordingUrl
    val isHls = remember(streamUrl) { streamUrl?.contains(".m3u8", ignoreCase = true) == true }

    var playerError by remember { mutableStateOf<String?>(null) }
    var isBuffering by remember { mutableStateOf(true) }
    var hasReceivedVideo by remember { mutableStateOf(false) }
    var showUnavailable by remember { mutableStateOf(streamUrl == null) }

    // Track whether we handed the player to PiP (so DisposableEffect doesn't release it)
    var handedOffToPip by remember { mutableStateOf(false) }

    // Try to reclaim a PiP player for this stream, or create a new one
    val player: ExoPlayer? = remember(streamUrl) {
        if (streamUrl == null) return@remember null
        val reclaimed = if (PipStreamManager.isActiveFor(decodedId)) {
            PipStreamManager.reclaimPlayer()?.player
        } else null
        reclaimed ?: ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(streamUrl)
            if (isHls) {
                val dataSourceFactory = DefaultDataSource.Factory(context)
                val hlsSource = HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
                setMediaSource(hlsSource)
            } else {
                setMediaItem(mediaItem)
            }
            prepare()
            playWhenReady = true
        }
    }

    // Pause player when app goes to background (unless handed off to PiP)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    if (player != null && !handedOffToPip) {
        DisposableEffect(lifecycleOwner, player) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                        if (!handedOffToPip && player.isPlaying) player.pause()
                    }
                    androidx.lifecycle.Lifecycle.Event.ON_START -> {
                        if (!handedOffToPip) player.play()
                    }
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // Player state listener + lifecycle
    if (player != null) {
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            isBuffering = false
                            hasReceivedVideo = true
                            showUnavailable = false
                            playerError = null
                        }
                        Player.STATE_BUFFERING -> {
                            isBuffering = true
                        }
                        Player.STATE_ENDED -> {
                            isBuffering = false
                        }
                        Player.STATE_IDLE -> {
                            isBuffering = false
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    isBuffering = false
                    playerError = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                            "Unable to connect to stream"
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                            "Unsupported stream format"
                        else -> "Broadcast unavailable"
                    }
                    showUnavailable = true
                }
            }
            player.addListener(listener)
            // If player is already ready (e.g. reclaimed from PiP), sync state immediately
            if (player.playbackState == Player.STATE_READY) {
                isBuffering = false
                hasReceivedVideo = true
                showUnavailable = false
                playerError = null
            }
            onDispose {
                player.removeListener(listener)
                if (!handedOffToPip) {
                    player.release()
                }
            }
        }

        // If still buffering after 15s with no video, show fallback
        LaunchedEffect(isBuffering, hasReceivedVideo) {
            if (isBuffering && !hasReceivedVideo) {
                delay(15_000)
                if (isBuffering && !hasReceivedVideo) {
                    showUnavailable = true
                }
            }
        }
    }

    // ── Relay orb tap navigates to relay log page via onRelayNavigate callback ──

    // ── Chat relay selector: default all activity relays selected ──
    var chatSelectedRelayUrls by remember(activity.relayUrls) {
        mutableStateOf(activity.relayUrls.toSet())
    }
    var showChatRelayPicker by remember { mutableStateOf(false) }

    // Build relay sections for the selection screen
    val chatRelaySections = remember(activity.relayUrls) {
        if (activity.relayUrls.isEmpty()) emptyList()
        else listOf(
            RelaySection(
                id = "live_activity_relays",
                title = "Broadcast relays",
                subtitle = "Relays from the live activity event",
                icon = Icons.Outlined.Router,
                relays = activity.relayUrls.map { url ->
                    RelayEntry(
                        url = url,
                        displayName = url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                        description = "NIP-53 relay"
                    )
                },
                initiallyExpanded = true,
                initiallyAllSelected = true
            )
        )
    }

    // ── Live Chat (kind:1311) ──
    val chatRepository = remember { LiveChatRepository.getInstance() }
    val chatMessages by chatRepository.messages.collectAsState()

    // Subscribe to chat messages for this activity using selected relays
    val activityAddress = remember(activity) { "30311:${activity.hostPubkey}:${activity.dTag}" }
    LaunchedEffect(Unit) {
        chatRepository.setCacheRelayUrls(activity.relayUrls)
    }
    DisposableEffect(activityAddress, chatSelectedRelayUrls) {
        val relays = chatSelectedRelayUrls.toList().ifEmpty { activity.relayUrls }
        Log.d("LiveStreamScreen", "Chat subscribe: addr=$activityAddress, relays=${relays.size}: ${relays.joinToString()}")
        chatRepository.subscribe(activityAddress, relays)
        onDispose { chatRepository.unsubscribe() }
    }

    // Back handler: hand off player to PiP if stream is playing AND auto-PiP is enabled
    val autoPipEnabled by social.mycelium.android.ui.settings.MediaPreferences.autoPipLiveActivities.collectAsState()
    val handleBack = {
        Log.d("LiveStreamScreen", "handleBack: player=${player != null}, hasReceivedVideo=$hasReceivedVideo, showUnavailable=$showUnavailable, autoPipEnabled=$autoPipEnabled, handedOffToPip=$handedOffToPip")
        if (player != null && hasReceivedVideo && !showUnavailable && autoPipEnabled) {
            handedOffToPip = true
            PipStreamManager.startPip(
                player = player,
                addressableId = decodedId,
                title = activity.title,
                hostName = activity.hostAuthor?.username ?: activity.hostPubkey.take(8)
            )
        }
        onBackClick()
    }

    BackHandler { if (showChatRelayPicker) showChatRelayPicker = false else handleBack() }

    // ── Chat relay picker overlay ──
    if (showChatRelayPicker) {
        RelaySelectionScreen(
            title = "Chat relays",
            sections = chatRelaySections,
            onConfirm = { selected ->
                chatSelectedRelayUrls = selected
                showChatRelayPicker = false
            },
            onBack = { showChatRelayPicker = false }
        )
        return
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LiveStatusDot(status = activity.status)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = activity.title ?: "Live Stream",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { handleBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        // Show manual PiP button when auto-PiP on back is disabled
                        if (!autoPipEnabled && player != null && hasReceivedVideo && !showUnavailable) {
                            IconButton(onClick = {
                                handedOffToPip = true
                                PipStreamManager.startPip(
                                    player = player,
                                    addressableId = decodedId,
                                    title = activity.title,
                                    hostName = activity.hostAuthor?.username ?: activity.hostPubkey.take(8)
                                )
                                onBackClick()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPictureAlt,
                                    contentDescription = "Picture in picture"
                                )
                            }
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // ── Top section: video + stream info (not scrollable, fixed) ──
            LiveStreamPlayerView(
                player = player,
                showUnavailable = showUnavailable,
                playerError = playerError,
                activity = activity,
                onPipClick = { handleBack() }
            )

            // Compact stream info row: host avatar, name, status, viewer count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activity.hostAuthor?.avatarUrl != null) {
                    AsyncImage(
                        model = activity.hostAuthor.avatarUrl,
                        contentDescription = "Host",
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { onProfileClick(activity.hostPubkey) },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onProfileClick(activity.hostPubkey) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activity.hostAuthor?.displayName ?: activity.hostAuthor?.username ?: activity.hostPubkey.take(12) + "…",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = activity.status.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (activity.status) {
                                LiveActivityStatus.LIVE -> Color(0xFFEF4444)
                                LiveActivityStatus.PLANNED -> Color(0xFFF59E0B)
                                LiveActivityStatus.ENDED -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        activity.currentParticipants?.let { count ->
                            if (count > 0) {
                                Text(
                                    text = "$count watching",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (activity.relayUrls.isNotEmpty()) {
                    RelayOrbs(
                        relayUrls = activity.relayUrls,
                        onRelayClick = { url -> onRelayNavigate(url) }
                    )
                }
            }

            // Summary / bio dropdown
            if (!activity.summary.isNullOrBlank()) {
                var bioExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { bioExpanded = !bioExpanded }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (bioExpanded) "Hide details" else "Show details",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (bioExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = bioExpanded,
                    enter = androidx.compose.animation.expandVertically(
                        expandFrom = Alignment.Top
                    ) + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically(
                        shrinkTowards = Alignment.Top
                    ) + androidx.compose.animation.fadeOut()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp)) {
                        Text(
                            text = activity.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Hashtags
                        if (activity.hashtags.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = activity.hashtags.joinToString(" ") { "#$it" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            )
                        }
                        // Participants
                        if (activity.participants.size > 1) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${activity.participants.size} participants",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

            // ── Chat section: fills remaining space ──
            // Chat header
            Text(
                text = "Live Chat",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Chat messages — reversed so newest are at the bottom
            val chatListState = rememberLazyListState()

            // Auto-scroll to bottom when new messages arrive
            LaunchedEffect(chatMessages.size) {
                if (chatMessages.isNotEmpty()) {
                    chatListState.animateScrollToItem(chatMessages.lastIndex)
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (chatMessages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (activity.status == LiveActivityStatus.LIVE) "No messages yet — be the first!" else "Chat is not active",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    LazyColumn(
                        state = chatListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = chatMessages,
                            key = { it.id }
                        ) { message ->
                            LiveChatMessageRow(
                                message = message,
                                onProfileClick = onProfileClick
                            )
                        }
                    }
                }
            }

            // Chat input — pinned at bottom
            LiveChatInput(
                onSend = { text ->
                    val addr = "30311:${activity.hostPubkey}:${activity.dTag}"
                    accountStateViewModel.publishLiveChatMessage(text, addr, chatSelectedRelayUrls)
                },
                enabled = activity.status == LiveActivityStatus.LIVE,
                selectedRelayCount = chatSelectedRelayUrls.size,
                totalRelayCount = activity.relayUrls.size,
                onRelayPickerClick = { showChatRelayPicker = true },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * Video player view — receives the player from the parent screen.
 * Shows the PlayerView when stream is available, or the broadcast unavailable fallback.
 * Includes a PiP button overlay in the top-right corner.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun LiveStreamPlayerView(
    player: ExoPlayer?,
    showUnavailable: Boolean,
    playerError: String?,
    activity: LiveActivity,
    onPipClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (player != null && !showUnavailable) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        this.player = player
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    }
                }
            )

            // PiP auto-activates on back gesture; no manual PiP button needed
        }
    } else if (showUnavailable) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            BroadcastUnavailableContent(
                message = playerError ?: "No video input is being received from this stream"
            )
        }
    } else {
        BroadcastUnavailablePlaceholder(
            activity = activity,
            modifier = modifier
        )
    }
}

@Composable
private fun BroadcastUnavailablePlaceholder(
    activity: LiveActivity,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (activity.imageUrl != null) {
            AsyncImage(
                model = activity.imageUrl,
                contentDescription = "Stream preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Dim overlay on top of preview image
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                BroadcastUnavailableContent(
                    message = if (activity.status == LiveActivityStatus.PLANNED) "Stream starting soon" else "Broadcast unavailable"
                )
            }
        } else {
            BroadcastUnavailableContent(
                message = if (activity.status == LiveActivityStatus.PLANNED) "Stream starting soon" else "Broadcast unavailable"
            )
        }
    }
}

@Composable
private fun BroadcastUnavailableContent(
    message: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.VideocamOff,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = Color.White.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.85f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "No video input is being received",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}

/**
 * Stream info section: host, title, summary, participant count, hashtags.
 */
@Composable
private fun LiveStreamInfo(
    activity: LiveActivity,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Host row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (activity.hostAuthor?.avatarUrl != null) {
                AsyncImage(
                    model = activity.hostAuthor.avatarUrl,
                    contentDescription = "Host avatar",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = "Host",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activity.hostAuthor?.displayName
                        ?: activity.hostAuthor?.username
                        ?: activity.hostPubkey.take(12) + "...",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiveStatusDot(status = activity.status)
                    Text(
                        text = activity.status.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (activity.status) {
                            LiveActivityStatus.LIVE -> Color(0xFFEF4444)
                            LiveActivityStatus.PLANNED -> Color(0xFFF59E0B)
                            LiveActivityStatus.ENDED -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    activity.currentParticipants?.let { count ->
                        if (count > 0) {
                            Text(
                                text = "$count watching",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Summary
        if (!activity.summary.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = activity.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Hashtags
        if (activity.hashtags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = activity.hashtags.joinToString(" ") { "#$it" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            )
        }

        // Participants list
        if (activity.participants.size > 1) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${activity.participants.size} participants",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A single chat message row: avatar, display name, and message content.
 */
@Composable
private fun LiveChatMessageRow(
    message: LiveChatMessage,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val profileCache = remember { ProfileMetadataCache.getInstance() }
    val author = message.author ?: profileCache.getAuthor(message.pubkey)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Avatar
        if (author?.avatarUrl != null) {
            AsyncImage(
                model = author.avatarUrl,
                contentDescription = "Avatar",
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onProfileClick(message.pubkey) },
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onProfileClick(message.pubkey) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = author?.displayName ?: author?.username ?: message.pubkey.take(8) + "…",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Chat input field with relay selector button (left) and send button (right).
 * The relay button opens the dedicated RelaySelectionScreen overlay.
 */
@Composable
private fun LiveChatInput(
    onSend: (String) -> Unit,
    enabled: Boolean,
    selectedRelayCount: Int,
    totalRelayCount: Int,
    onRelayPickerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Relay selector button — opens dedicated RelaySelectionScreen
        IconButton(
            onClick = onRelayPickerClick,
            enabled = enabled,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Outlined.Router,
                contentDescription = "Select relays ($selectedRelayCount/$totalRelayCount)",
                tint = if (selectedRelayCount == totalRelayCount)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(4.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    if (enabled) "Say something…" else "Chat unavailable",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (text.isNotBlank() && selectedRelayCount > 0) {
                        onSend(text.trim())
                        text = ""
                    }
                }
            )
        )

        Spacer(Modifier.width(4.dp))

        IconButton(
            onClick = {
                if (text.isNotBlank() && selectedRelayCount > 0) {
                    onSend(text.trim())
                    text = ""
                }
            },
            enabled = enabled && text.isNotBlank() && selectedRelayCount > 0,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (enabled && text.isNotBlank() && selectedRelayCount > 0)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
