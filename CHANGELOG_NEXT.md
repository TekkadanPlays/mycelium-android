# Next Release – Change Log

## Fixes

### PiP (Picture-in-Picture)
- **PiP restores on back gesture**: Gesturing back from a fullscreen video opened via PiP now automatically returns the video to PiP mode instead of closing the viewer.
- **PiP playback continuity**: PiP video no longer pauses when transitioning between PiP, fullscreen, and back. Playback is seamless through the entire cycle.
- **PiP has exclusive playback priority**: Feed video players yield to PiP — no more audio competition or duplicate playback when PiP is active for a video URL.
- **PiP mute state preserved**: Tapping PiP to go fullscreen now correctly inherits the unmuted state instead of reverting to a stale mute value.
- **Live activity auto-PiP**: Gesturing back from a live stream correctly hands off the player to PiP when auto-PiP is enabled.

### Feed
- **No feed reconnection on navigation**: Returning from live events, live streams, video viewer, or any other screen no longer triggers feed clearing, resubscription, or visible refresh.
- **Feed retained after long background**: Cached notes remain visible when the app returns from background. Loading indicator only appears on truly cold starts with an empty feed.
- **No duplicate video playback**: Opening a thread overlay from the feed pauses feed video players, preventing the same video from playing in both the feed and the thread.
- **Scroll stability for quoted notes**: Quoted notes with media no longer cause the feed to jump when scrolling up, thanks to a height reservation cache.

### Thread View
- **Quoted notes match feed styling**: Quoted notes in thread replies now use the same rich rendering as the home feed — full content blocks, media (images/videos), recursive nested quotes, expand/collapse, reaction/zap/reply counters, and profile avatars.
- **Full media in replies**: Reply media now uses the same carousel as the home feed — swipeable image pager, inline video players, blurhash placeholders, fullscreen magnifier, page indicators, and proper aspect ratio handling. Replaces the previous 56dp thumbnail grid.

### Media & Navigation
- **Image loading**: Added proper request headers so stricter CDNs no longer reject image requests with HTTP 403.
- **Thread swipe-back with galleries**: Swiping right on the first image in a thread gallery now dismisses the thread instead of being consumed by the gallery pager.
