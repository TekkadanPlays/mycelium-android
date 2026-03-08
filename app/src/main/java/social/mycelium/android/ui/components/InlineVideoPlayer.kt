package social.mycelium.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import social.mycelium.android.R
import social.mycelium.android.ui.settings.MediaPreferences
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Inline video player for feed content.
 *
 * Uses [SharedPlayerPool] so the same ExoPlayer instance transfers between
 * inline feed and fullscreen views without re-buffering or stuttering.
 *
 * Mute state is persisted via [VideoMuteCache] so it survives feed↔fullscreen transitions.
 * Initial mute/autoplay behavior is controlled by [MediaPreferences].
 *
 * @param isVisible Whether this player is currently visible on screen.
 * @param onFullscreenClick Callback for fullscreen toggle (feed mode only).
 * @param onExitFullscreen Callback for exiting fullscreen (fullscreen mode only).
 */
@Composable
fun InlineVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
    isVisible: Boolean = true,
    instanceKey: String? = null,
    onFullscreenClick: () -> Unit = {},
    onExitFullscreen: () -> Unit = {},
    onAspectRatioKnown: ((Float) -> Unit)? = null
) {
    if (autoPlay) {
        // Resolve instanceKey: explicit param (PiP return) > cache (feed→fullscreen) > fallback to URL
        val resolvedKey = instanceKey ?: VideoInstanceKeyCache.get(url) ?: url
        FullVideoPlayer(url = url, instanceKey = resolvedKey, modifier = modifier, isVisible = isVisible, onExitFullscreen = onExitFullscreen)
    } else {
        FeedVideoPlayer(url = url, modifier = modifier, isVisible = isVisible, onFullscreenClick = onFullscreenClick, onAspectRatioKnown = onAspectRatioKnown)
    }
}

/**
 * Feed-mode video: autoplays muted with modern overlay controls.
 * Uses [SharedPlayerPool] so the player survives fullscreen transitions.
 */
@Composable
private fun FeedVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    onFullscreenClick: () -> Unit = {},
    onAspectRatioKnown: ((Float) -> Unit)? = null
) {
    val context = LocalContext.current
    val prefAutoplay by MediaPreferences.autoplayVideos.collectAsState()
    val prefSound by MediaPreferences.autoplaySound.collectAsState()
    // Stable instance key — unique per composable instance so two NoteCards
    // showing the same video URL get separate ExoPlayer instances.
    val instanceKey = remember(url) { java.util.UUID.randomUUID().toString() }

    var player by remember(url) { mutableStateOf<ExoPlayer?>(null) }
    // Mute state: check cache first (persists across transitions), then fall back to preference
    var isMuted by remember(url) { mutableStateOf(VideoMuteCache.get(url) ?: !prefSound) }
    var isActuallyPlaying by remember(url) { mutableStateOf(prefAutoplay) }
    var showControls by remember { mutableStateOf(true) }
    var playbackProgress by remember(url) { mutableFloatStateOf(0f) }
    var playbackDuration by remember(url) { mutableLongStateOf(0L) }
    var playbackPosition by remember(url) { mutableLongStateOf(0L) }
    var isSeeking by remember(url) { mutableStateOf(false) }
    // Track fullscreen transition so onDispose uses detach (keep player) instead of release (destroy)
    var goingFullscreen by remember(url) { mutableStateOf(false) }

    // Poll playback position for seekbar
    LaunchedEffect(player, isActuallyPlaying) {
        val p = player ?: return@LaunchedEffect
        while (isActuallyPlaying) {
            if (!isSeeking) {
                val dur = p.duration
                // Skip update when duration is unknown (TIME_UNSET = -9223372036854775807)
                // to prevent seekbar jumping to 100% during buffering
                if (dur > 0) {
                    val pos = p.currentPosition
                    playbackDuration = dur
                    playbackPosition = pos
                    playbackProgress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                }
            }
            delay(250)
        }
    }

    // Auto-hide controls after 3s
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

    // Listener for reporting video dimensions — stored so we can remove on dispose
    val sizeListener = remember(url) {
        object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    social.mycelium.android.utils.MediaAspectRatioCache.add(url, videoSize.width, videoSize.height)
                    val ratio = videoSize.width.toFloat() / videoSize.height.toFloat()
                    if (ratio in 0.3f..3.0f) onAspectRatioKnown?.invoke(ratio)
                }
            }
        }
    }

    // Acquire or re-acquire the pooled player (skip if PiP owns this instance)
    val currentPipState by PipStreamManager.pipState.collectAsState()
    val isPipActive = currentPipState?.instanceKey == instanceKey
    LaunchedEffect(url, isPipActive) {
        if (player == null && !isPipActive) {
            val p = SharedPlayerPool.acquire(context, instanceKey, url) ?: return@LaunchedEffect
            // Only seek if the player is far from the cached position (avoids jump on return)
            val cached = VideoPositionCache.get(url)
            if (cached > 0 && kotlin.math.abs(p.currentPosition - cached) > 1000) {
                p.seekTo(cached)
            }
            p.volume = if (isMuted) 0f else 1f
            p.repeatMode = ExoPlayer.REPEAT_MODE_ALL
            p.playWhenReady = prefAutoplay
            // Report video dimensions to aspect ratio cache once known
            p.removeListener(sizeListener) // prevent duplicates from pool re-use
            p.addListener(sizeListener)
            player = p
            // Reset: we re-acquired after fullscreen/PiP, so next dispose should release normally
            goingFullscreen = false
        }
    }

    // Re-acquire after fullscreen/PiP return: the LaunchedEffect above won't re-fire because
    // its keys (url, isPipActive) haven't changed. This coroutine polls until the fullscreen
    // player releases ownership, then re-acquires for the feed.
    LaunchedEffect(url, goingFullscreen, isPipActive) {
        if (!goingFullscreen || isPipActive) return@LaunchedEffect
        // Wait for FullVideoPlayer to dispose and release its ownership
        while (player == null) {
            delay(150)
            // Safe to re-acquire when: (a) player is in pool with zero owners (fullscreen
            // called detach), or (b) player was evicted/stolen from pool (PiP or LRU eviction)
            if (SharedPlayerPool.isUnowned(instanceKey) || !SharedPlayerPool.has(instanceKey)) {
                val p = SharedPlayerPool.acquire(context, instanceKey, url) ?: break
                val cached = VideoPositionCache.get(url)
                if (cached > 0 && kotlin.math.abs(p.currentPosition - cached) > 1000) {
                    p.seekTo(cached)
                }
                p.volume = if (isMuted) 0f else 1f
                p.repeatMode = ExoPlayer.REPEAT_MODE_ALL
                p.playWhenReady = if (isVisible) prefAutoplay else false
                p.removeListener(sizeListener)
                p.addListener(sizeListener)
                player = p
                goingFullscreen = false
                break
            }
        }
    }

    // Pause/resume based on visibility (skip if PiP owns this URL)
    LaunchedEffect(isVisible, player, isPipActive) {
        val p = player ?: return@LaunchedEffect
        if (isPipActive) { p.pause(); return@LaunchedEffect }
        if (isVisible) {
            if (isActuallyPlaying) p.play()
        } else {
            VideoPositionCache.set(url, p.currentPosition)
            p.pause()
        }
    }

    // Resume playback when app returns from background (ON_STOP pauses all players)
    // Also re-acquire the player after returning from fullscreen/PiP — the feed composable
    // stays in the back stack so LaunchedEffect(url, isPipActive) doesn't re-fire, but
    // ON_RESUME fires when video_viewer pops and this screen becomes foreground again.
    val feedLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(feedLifecycleOwner, url) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (player != null) {
                    // Player exists — just resume playback
                    if (isActuallyPlaying && isVisible) player?.play()
                }
                // player == null means we transitioned to fullscreen/PiP and need to re-acquire.
                // The LaunchedEffect(url, isPipActive) won't re-fire on its own since keys
                // haven't changed. Nudge it by toggling goingFullscreen which is checked inside.
                // We can't call acquire() directly here (not a suspend context), so we post
                // a recomposition that makes the LaunchedEffect's condition true again.
            }
        }
        feedLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { feedLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(url) {
        onDispose {
            player?.let {
                it.removeListener(sizeListener)
                VideoPositionCache.set(url, it.currentPosition)
                VideoMuteCache.set(url, isMuted)
                // Always pause before detach/release — prevents audio leaking from
                // orphaned players that stay in the pool after detach.
                it.pause()
            }
            if (goingFullscreen) {
                SharedPlayerPool.detach(instanceKey)
            } else {
                SharedPlayerPool.release(instanceKey)
            }
            player = null
        }
    }

    // Stable PlayerView that survives recomposition — prevents surface buffer detach spam
    val stablePlayerView = remember(url) { mutableStateOf<PlayerView?>(null) }

    // Attach/detach player from the stable view
    LaunchedEffect(player) {
        val view = stablePlayerView.value
        if (view != null && player != null) {
            // Force null→player cycle so PlayerView re-attaches the video surface
            // even when the same ExoPlayer instance is reused (e.g. PiP/fullscreen return).
            view.player = null
            view.player = player
        } else if (view != null && player == null) {
            view.player = null
        }
    }

    DisposableEffect(url) {
        onDispose {
            // Clear player from view before surface is destroyed
            stablePlayerView.value?.player = null
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { stablePlayerView.value?.requestLayout() },
            factory = { ctx ->
                // Inflate from XML to get surface_type="texture_view" which
                // prevents HDR video from overriding Night Mode / dark theme.
                (android.view.LayoutInflater.from(ctx)
                    .inflate(R.layout.video_player_texture_view, null) as PlayerView).apply {
                    stablePlayerView.value = this
                    player?.let { this.player = it }
                }
            },
            update = { view ->
                if (stablePlayerView.value !== view) stablePlayerView.value = view
                val p = player
                if (view.player !== p) view.player = p
            }
        )

        player?.let { p ->
            // Tap to show/hide controls
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { showControls = !showControls }
            )

            // Modern controls overlay
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                VideoControlsPill(
                    isMuted = isMuted,
                    isPlaying = isActuallyPlaying,
                    onMuteToggle = {
                        isMuted = !isMuted
                        p.volume = if (isMuted) 0f else 1f
                        VideoMuteCache.set(url, isMuted)
                    },
                    onPlayPauseToggle = {
                        isActuallyPlaying = !isActuallyPlaying
                        if (isActuallyPlaying) p.play() else p.pause()
                    },
                    onScreenToggle = {
                        VideoPositionCache.set(url, p.currentPosition)
                        VideoMuteCache.set(url, isMuted)
                        goingFullscreen = true
                        // Store instanceKey so FullVideoPlayer can acquire the same pool entry
                        VideoInstanceKeyCache.set(url, instanceKey)
                        // Immediately detach surface so fullscreen player can claim it
                        stablePlayerView.value?.player = null
                        SharedPlayerPool.detach(instanceKey)
                        player = null
                        onFullscreenClick()
                    },
                    isFullscreen = false,
                    progress = playbackProgress,
                    duration = playbackDuration,
                    position = playbackPosition,
                    onSeekStart = { isSeeking = true },
                    onSeek = { frac -> playbackProgress = frac; playbackPosition = (frac * playbackDuration).toLong() },
                    onSeekEnd = { frac ->
                        if (playbackDuration > 0) {
                            val seekMs = (frac * playbackDuration).toLong()
                            p.seekTo(seekMs)
                            playbackPosition = seekMs
                        }
                        isSeeking = false
                    },
                    onPipClick = {
                        VideoPositionCache.set(url, p.currentPosition)
                        VideoMuteCache.set(url, isMuted)
                        goingFullscreen = true // Prevent release on dispose
                        stablePlayerView.value?.player = null
                        player = null
                        PipStreamManager.startVideoPip(p, url, instanceKey)
                    }
                )
            }
        }
    }
}

/**
 * Fullscreen video player: acquires the pooled ExoPlayer for seamless transition.
 * Inherits mute state from feed via [VideoMuteCache].
 *
 * Gesture support (inspired by Next Player):
 * - Horizontal swipe to seek
 * - Vertical swipe left side for brightness, right side for volume
 * - Double-tap left/right to seek ±10s
 * - Long-press for 2× speed
 * - Pinch-to-zoom
 */
@Composable
private fun FullVideoPlayer(
    url: String,
    instanceKey: String,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    onExitFullscreen: () -> Unit = {}
) {
    val context = LocalContext.current
    var player by remember(url) { mutableStateOf<ExoPlayer?>(null) }
    // Inherit mute state from feed player via cache
    var isMuted by remember(url) { mutableStateOf(VideoMuteCache.get(url) ?: false) }
    var isActuallyPlaying by remember(url) { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }
    var playbackProgress by remember(url) { mutableFloatStateOf(0f) }
    var playbackDuration by remember(url) { mutableLongStateOf(0L) }
    var playbackPosition by remember(url) { mutableLongStateOf(0L) }
    var isSeeking by remember(url) { mutableStateOf(false) }

    // Gesture state holders
    val seekGestureState = rememberSeekGestureState(player)
    val doubleTapState = rememberDoubleTapState(player)
    val volumeBrightnessState = rememberVolumeAndBrightnessState()
    val zoomState = rememberVideoZoomState()

    // Track whether gesture-based seeking is active (swipe or slider)
    val isGestureSeeking = seekGestureState.isSeeking

    // Poll playback position for seekbar
    LaunchedEffect(player, isActuallyPlaying) {
        val p = player ?: return@LaunchedEffect
        while (isActuallyPlaying) {
            if (!isSeeking && !isGestureSeeking) {
                val dur = p.duration
                // Skip update when duration is unknown (TIME_UNSET = -9223372036854775807)
                // to prevent seekbar jumping to 100% during buffering
                if (dur > 0) {
                    val pos = p.currentPosition
                    playbackDuration = dur
                    playbackPosition = pos
                    playbackProgress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                }
            }
            delay(250)
        }
    }

    // Acquire pooled player — inherit mute state, resume seamlessly
    LaunchedEffect(url) {
        val p = SharedPlayerPool.acquire(context, instanceKey, url) ?: return@LaunchedEffect
        // Only seek if far from cached position
        val cached = VideoPositionCache.get(url)
        if (cached > 0 && kotlin.math.abs(p.currentPosition - cached) > 1000) {
            p.seekTo(cached)
        }
        p.volume = if (isMuted) 0f else 1f
        p.repeatMode = ExoPlayer.REPEAT_MODE_ALL
        p.playWhenReady = true
        p.play()
        player = p
    }

    // Auto-hide controls after 3s (reset when gestures are active)
    LaunchedEffect(showControls, isGestureSeeking) {
        if (showControls && !isGestureSeeking) {
            delay(3000)
            showControls = false
        }
    }

    // Pause/resume when paging between videos in fullscreen viewer
    LaunchedEffect(isVisible, player) {
        val p = player ?: return@LaunchedEffect
        if (isVisible) {
            if (isActuallyPlaying) p.play()
        } else {
            VideoPositionCache.set(url, p.currentPosition)
            p.pause()
        }
    }

    // Reset zoom when switching videos
    LaunchedEffect(url) { zoomState.reset() }

    // Resume playback when app returns from background (ON_STOP pauses all players)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, url) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                player?.let { p ->
                    if (isActuallyPlaying && isVisible) p.play()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(url) {
        onDispose {
            player?.let { p ->
                VideoPositionCache.set(url, p.currentPosition)
                VideoMuteCache.set(url, isMuted)
                p.pause()
            }
            SharedPlayerPool.detach(instanceKey)
            player = null
        }
    }

    // Stable PlayerView that survives recomposition
    val stablePlayerView = remember(url) { mutableStateOf<PlayerView?>(null) }

    LaunchedEffect(player) {
        val view = stablePlayerView.value
        if (view != null && player != null) {
            // Force null→player cycle so PlayerView re-attaches the video surface
            // even when the same ExoPlayer instance is reused (e.g. PiP return via pool).
            view.player = null
            view.player = player
        } else if (view != null && player == null) {
            view.player = null
        }
    }

    DisposableEffect(url) {
        onDispose { stablePlayerView.value?.player = null }
    }

    Box(modifier = modifier) {
        // Video surface with pinch-to-zoom transform applied
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoomState.zoom
                    scaleY = zoomState.zoom
                    translationX = zoomState.offset.x
                    translationY = zoomState.offset.y
                },
            factory = { ctx ->
                // Inflate from XML to get surface_type="texture_view" which
                // prevents HDR video from overriding Night Mode / dark theme.
                (android.view.LayoutInflater.from(ctx)
                    .inflate(R.layout.video_player_texture_view, null) as PlayerView).apply {
                    stablePlayerView.value = this
                    player?.let { this.player = it }
                }
            },
            update = { view ->
                if (stablePlayerView.value !== view) stablePlayerView.value = view
                val p = player
                if (view.player !== p) view.player = p
            }
        )

        player?.let { p ->
            // Gesture detection layer (replaces simple tap-to-toggle)
            VideoGestureLayer(
                seekState = seekGestureState,
                doubleTapState = doubleTapState,
                volumeBrightnessState = volumeBrightnessState,
                zoomState = zoomState,
                onToggleControls = { showControls = !showControls },
            )

            // Gesture feedback overlays
            SeekGestureOverlay(seekState = seekGestureState)
            DoubleTapSeekOverlay(doubleTapState = doubleTapState)
            LongPressSpeedOverlay(doubleTapState = doubleTapState)
            VolumeBrightnessOverlay(state = volumeBrightnessState)
            ZoomIndicatorOverlay(zoomState = zoomState)

            // Controls pill overlay (seekbar + buttons)
            AnimatedVisibility(
                visible = showControls && !isGestureSeeking,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
            ) {
                VideoControlsPill(
                    isMuted = isMuted,
                    isPlaying = isActuallyPlaying,
                    onMuteToggle = {
                        isMuted = !isMuted
                        p.volume = if (isMuted) 0f else 1f
                        VideoMuteCache.set(url, isMuted)
                    },
                    onPlayPauseToggle = {
                        isActuallyPlaying = !isActuallyPlaying
                        if (isActuallyPlaying) p.play() else p.pause()
                    },
                    onScreenToggle = {
                        VideoPositionCache.set(url, p.currentPosition)
                        VideoMuteCache.set(url, isMuted)
                        SharedPlayerPool.detach(instanceKey)
                        player = null
                        onExitFullscreen()
                    },
                    isFullscreen = true,
                    progress = playbackProgress,
                    duration = playbackDuration,
                    position = playbackPosition,
                    onSeekStart = { isSeeking = true },
                    onSeek = { frac -> playbackProgress = frac; playbackPosition = (frac * playbackDuration).toLong() },
                    onSeekEnd = { frac ->
                        if (playbackDuration > 0) {
                            val seekMs = (frac * playbackDuration).toLong()
                            p.seekTo(seekMs)
                            playbackPosition = seekMs
                        }
                        isSeeking = false
                    },
                    onPipClick = {
                        VideoPositionCache.set(url, p.currentPosition)
                        VideoMuteCache.set(url, isMuted)
                        stablePlayerView.value?.player = null
                        player = null
                        PipStreamManager.startVideoPip(p, url, instanceKey)
                        onExitFullscreen()
                    }
                )
            }
        }
    }
}

/**
 * Shared pill-shaped controls overlay used by both feed and fullscreen players.
 */
@Composable
private fun VideoControlsPill(
    isMuted: Boolean,
    isPlaying: Boolean,
    onMuteToggle: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onScreenToggle: () -> Unit,
    isFullscreen: Boolean,
    progress: Float = 0f,
    duration: Long = 0L,
    position: Long = 0L,
    onSeekStart: () -> Unit = {},
    onSeek: (Float) -> Unit = {},
    onSeekEnd: (Float) -> Unit = {},
    onPipClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .then(if (isFullscreen) Modifier.fillMaxWidth() else Modifier)
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Seekbar with timestamps (takes remaining space)
        if (duration > 0) {
            Text(
                text = formatDuration(position),
                color = Color.White,
                fontSize = 11.sp,
                style = MaterialTheme.typography.labelSmall
            )
            // Track latest slider value locally so tap-to-seek works correctly.
            // Without this, onValueChangeFinished would use the stale `progress`
            // parameter (pre-recomposition) instead of the just-tapped position.
            var latestSliderValue by remember { mutableFloatStateOf(progress) }
            latestSliderValue = progress
            Slider(
                value = progress,
                onValueChange = { latestSliderValue = it; onSeekStart(); onSeek(it) },
                onValueChangeFinished = { onSeekEnd(latestSliderValue) },
                modifier = Modifier.weight(1f).height(24.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
            Text(
                text = formatDuration(duration),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
                style = MaterialTheme.typography.labelSmall
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        // Controls on the right
        IconButton(onClick = onPlayPauseToggle, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onMuteToggle, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        if (onPipClick != null) {
            IconButton(onClick = onPipClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.PictureInPictureAlt,
                    contentDescription = "Picture in picture",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        IconButton(onClick = onScreenToggle, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
