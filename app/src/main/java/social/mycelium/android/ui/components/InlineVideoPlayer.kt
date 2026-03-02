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
    onFullscreenClick: () -> Unit = {},
    onExitFullscreen: () -> Unit = {},
    onAspectRatioKnown: ((Float) -> Unit)? = null
) {
    if (autoPlay) {
        FullVideoPlayer(url = url, modifier = modifier, isVisible = isVisible, onExitFullscreen = onExitFullscreen)
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
                val dur = p.duration.coerceAtLeast(1L)
                val pos = p.currentPosition
                playbackDuration = dur
                playbackPosition = pos
                playbackProgress = (pos.toFloat() / dur).coerceIn(0f, 1f)
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

    // Acquire or re-acquire the pooled player
    LaunchedEffect(url) {
        if (player == null) {
            val p = SharedPlayerPool.acquire(context, url)
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
        }
    }

    // Pause/resume based on visibility
    LaunchedEffect(isVisible, player) {
        val p = player ?: return@LaunchedEffect
        if (isVisible) {
            if (isActuallyPlaying) p.play()
        } else {
            VideoPositionCache.set(url, p.currentPosition)
            p.pause()
        }
    }

    DisposableEffect(url) {
        onDispose {
            player?.let {
                it.removeListener(sizeListener)
                VideoPositionCache.set(url, it.currentPosition)
                VideoMuteCache.set(url, isMuted)
            }
            if (goingFullscreen) {
                SharedPlayerPool.detach(url)
            } else {
                SharedPlayerPool.release(url)
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
                        SharedPlayerPool.detach(url)
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
                        val seekMs = (frac * p.duration).toLong()
                        p.seekTo(seekMs)
                        isSeeking = false
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
                val dur = p.duration.coerceAtLeast(1L)
                val pos = p.currentPosition
                playbackDuration = dur
                playbackPosition = pos
                playbackProgress = (pos.toFloat() / dur).coerceIn(0f, 1f)
            }
            delay(250)
        }
    }

    // Acquire pooled player — inherit mute state, resume seamlessly
    LaunchedEffect(url) {
        val p = SharedPlayerPool.acquire(context, url)
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

    DisposableEffect(url) {
        onDispose {
            player?.let { p ->
                VideoPositionCache.set(url, p.currentPosition)
                VideoMuteCache.set(url, isMuted)
            }
            SharedPlayerPool.detach(url)
            player = null
        }
    }

    // Stable PlayerView that survives recomposition
    val stablePlayerView = remember(url) { mutableStateOf<PlayerView?>(null) }

    LaunchedEffect(player) {
        val view = stablePlayerView.value
        if (view != null && player != null) {
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
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
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
                        SharedPlayerPool.detach(url)
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
                        val seekMs = (frac * p.duration).toLong()
                        p.seekTo(seekMs)
                        isSeeking = false
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
    onSeekEnd: (Float) -> Unit = {}
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
            Slider(
                value = progress,
                onValueChange = { onSeekStart(); onSeek(it) },
                onValueChangeFinished = { onSeekEnd(progress) },
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
