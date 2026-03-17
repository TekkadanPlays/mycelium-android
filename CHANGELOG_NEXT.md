# Next Release – Change Log

## Fixes


### Scroll Performance
- **Eliminated Card overhead**: Replaced Material3 `Card` (which creates Surface → Box with clip, shadow RenderNode, and ripple layers) with a plain `Column` + background color for every note card in the feed.
- **Deferred video player creation**: ExoPlayer initialization (codec enumeration, surface allocation) is now delayed 200ms after a video card enters the viewport, preventing it from blocking the scroll frame.
- **Off-thread content parsing**: The 7-regex content block builder (`buildNoteContentWithInlinePreviews`) now runs on `Dispatchers.Default` via `produceState` instead of blocking the main thread during composition — applies to feed cards, quoted notes, and thread replies.
- **Eliminated double measure pass on quoted notes**: Replaced `Row(IntrinsicSize.Min)` + `fillMaxHeight()` accent bar with a `drawBehind` modifier that draws the accent bar without requiring intrinsic measurement.
- **Coil image optimization**: Added `size(ORIGINAL)` and `allowHardware(true)` to feed image requests, preventing Coil from waiting for layout to determine target size before decoding.
- **Capped per-card profile observer coroutines**: Profile update observers on NoteCard, QuotedNoteContent, and thread replies now cap at 1 update with 1500ms debounce. Previously, every visible card maintained a permanent coroutine filtering all profile updates.
- **Eliminated O(N) recompositions**: Hoisted `compactMedia`, `showSensitiveContent`, and `countsByNoteId` `collectAsState` calls out of per-card composables to the screen level.
- **Stabilized ~15 lambda allocations**: Hoisted note-independent lambdas (`onReact`, `onBoost`, `onQuote`, `onFork`, `onNoteClick`, `onCustomZapSend`, `onFollowAuthor`, `onLike`, `onShare`, etc.) above `items{}` blocks in DashboardScreen and TopicsScreen using `remember`.
- **Replaced SubcomposeAsyncImage**: Switched feed images from `SubcomposeAsyncImage` (double measure pass per frame) to `AsyncImage` + `rememberAsyncImagePainter` with separate loading/error overlays.
- **Removed redundant layout nodes**: Stripped unnecessary `Surface` wrappers from text content blocks and live event references in NoteCard.
- **Thread view optimizations**: Removed redundant per-reply `countsByNoteId` subscription (threaded screen-level map instead), snapshot-read `diskCacheRestored` instead of per-reply flow collector, moved reply content parsing off main thread.

### Media & Navigation
- **Image loading**: Added proper request headers so stricter CDNs no longer reject image requests with HTTP 403.
- **Thread swipe-back with galleries**: Swiping right on the first image in a thread gallery now dismisses the thread instead of being consumed by the gallery pager.
