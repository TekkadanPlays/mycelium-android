# Next Release – Change Log

## Fixes

### Notifications
- **Fixed notification leakage**: Added client-side relevance gate so events from relays that bypass subscription filters are dropped. Users no longer receive notifications for polls they didn't post or replies to other people's notes.
- **Fixed @mentions tab**: Replies where the target note fetch fails are now reclassified as MENTION when the user is cited in content via `nostr:npub`, so they appear in the Mentions tab.
- **Fixed poll vote notification tap**: Clicking a poll vote notification now opens the poll note (was silently failing because targetNote was never fetched).
- **Fixed poll display from notifications**: Poll notes opened from notifications now render with full poll options instead of plain text (PollData parsing was missing in notification eventToNote).
- **Fixed kind-1111 reply jump**: Tapping a kind-1111 thread reply notification now scrolls to the correct reply (scroll retry was blocked by premature success flag).

### Thread View
- **Fixed hyperlinks in NoteCard**: URL clicks in thread root notes now open the browser instead of navigating to the note (URL annotation handler was missing, only PROFILE and RELAY were checked).

### Scroll Performance
- **Eliminated Card overhead**: Replaced Material3 `Card` (which creates Surface → Box with clip, shadow RenderNode, and ripple layers) with a plain `Column` + background color for every note card in the feed.
- **Deferred video player creation**: ExoPlayer initialization (codec enumeration, surface allocation) is now delayed 200ms after a video card enters the viewport, preventing it from blocking the scroll frame.
- **Off-thread content parsing**: The 7-regex content block builder (`buildNoteContentWithInlinePreviews`) now runs on `Dispatchers.Default` via `produceState` instead of blocking the main thread during composition — applies to feed cards, quoted notes, and thread replies.
- **Eliminated double measure pass on quoted notes**: Replaced `Row(IntrinsicSize.Min)` + `fillMaxHeight()` accent bar with a `drawBehind` modifier that draws the accent bar without requiring intrinsic measurement.
- **Capped per-card profile observer coroutines**: Profile update observers on NoteCard, QuotedNoteContent, and thread replies now cap at 1 update with 1500ms debounce. Previously, every visible card maintained a permanent coroutine filtering all profile updates.
- **Eliminated O(N) recompositions**: Hoisted `compactMedia`, `showSensitiveContent`, and `countsByNoteId` `collectAsState` calls out of per-card composables to the screen level.
- **Stabilized ~15 lambda allocations**: Hoisted note-independent lambdas (`onReact`, `onBoost`, `onQuote`, `onFork`, `onNoteClick`, `onCustomZapSend`, `onFollowAuthor`, `onLike`, `onShare`, etc.) above `items{}` blocks in DashboardScreen and TopicsScreen using `remember`.
- **Replaced SubcomposeAsyncImage**: Switched feed images from `SubcomposeAsyncImage` (double measure pass per frame) to `AsyncImage` + `rememberAsyncImagePainter` with separate loading/error overlays.
- **Removed redundant layout nodes**: Stripped unnecessary `Surface` wrappers from text content blocks and live event references in NoteCard.
- **Thread view optimizations**: Removed redundant per-reply `countsByNoteId` subscription (threaded screen-level map instead), snapshot-read `diskCacheRestored` instead of per-reply flow collector, moved reply content parsing off main thread.

### Media & Navigation
- **Image loading**: Added proper request headers so stricter CDNs no longer reject image requests with HTTP 403.
- **Thread swipe-back with galleries**: Swiping right on the first image in a thread gallery now dismisses the thread instead of being consumed by the gallery pager.

## Architecture — Performance & Networking

### OkHttp Removal
- **Replaced OkHttp with Ktor CIO engine**: `MyceliumHttpClient` now uses Ktor's native coroutine-based CIO engine instead of the OkHttp Ktor engine wrapper. Zero OkHttp code in our codebase.
- **BlossomClient migrated to Ktor**: File uploads and deletes now use Ktor `HttpClient` with `ChannelProvider` streaming body instead of raw OkHttpClient.
- **Nip05Verifier migrated to Ktor**: NIP-05 verification now uses the shared `MyceliumHttpClient` instance instead of a standalone OkHttpClient.
- **Coil uses built-in OkHttp**: Coil 2.x's transitive OkHttp dependency handles image loading; we no longer manage an explicit OkHttpClient for it.
- **Removed explicit OkHttp dependencies**: `ktor-client-okhttp` and `com.squareup.okhttp3:okhttp` removed from `build.gradle.kts` and `libs.versions.toml`.

### SubscriptionMultiplexer — Centralized Subscription Gateway
- **All 48+ subscription callsites now route through the multiplexer**: `RelayConnectionStateMachine` temporary subscription methods delegate to `SubscriptionMultiplexer` internally — no callsite changes needed.
- **Global event deduplication**: Bounded LRU set (10K event IDs) prevents duplicate event processing across all subscriptions.
- **Ref-counted subscription lifecycle**: Identical filters from multiple consumers share one relay subscription; CLOSE sent only when the last consumer unsubscribes.
- **Per-relay filter map support**: Outbox model subscriptions (e.g. NoteCountsRepository) are first-class citizens in the multiplexer.
- **EOSE-based one-shot support**: Auto-closing subscriptions with settle window and hard timeout, routed through the multiplexer.
- **Relay URL passthrough**: Callbacks that need relay attribution (`WithRelay` variants) are fully supported.
- **50ms debounced REQ flush**: Rapid subscribe/unsubscribe calls are batched to reduce relay churn.
- **Account switch cleanup**: `mux.clear()` called on account switch to reset all dedup state, ref-counts, and merged subscriptions.

### Subscription Efficiency
- **EOSE-based one-shot subscriptions**: `PollResponseRepository` and `ProfileMetadataCache` fallback fetch converted from blind timeouts to EOSE-based auto-close — relay slots freed as soon as stored events are delivered.
- **Removed dead CybinSubscriptionHandle**: Subscription handle class replaced by multiplexer-managed handles.
- **Outbox feed priority upgrade**: OutboxFeedManager priority upgraded from LOW to NORMAL for better delivery reliability.
