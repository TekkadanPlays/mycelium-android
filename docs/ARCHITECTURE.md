# Mycelium Android — Architecture Reference

> Canonical architecture reference for the Mycelium Android Nostr client.
> Last verified against source: v0.5.03 (versionCode 41). Line counts via `wc -l`.
> See also: [DEVELOPMENT.md](DEVELOPMENT.md) · [DATA_MODELS.md](DATA_MODELS.md) · [NAVIGATION.md](NAVIGATION.md) · [RELAY_SYSTEM.md](RELAY_SYSTEM.md)

## Overview

Mycelium is a native Android Nostr protocol client. Connects to relays via persistent WebSocket, renders feeds of text notes (kind-1), forum topics (kind-11/1111), live activities (NIP-53), and encrypted DMs (NIP-17). Handles 20+ event kinds with NIP-65 outbox relay discovery, NIP-42 auth, NIP-57 zaps, NIP-55 Amber signing.

**Stack:** Kotlin 2.2.0 · Jetpack Compose (BOM 2024.12.01) · MD3 · Ktor 3.4.1 WebSocket (CIO engine) · Coil 2.5 · Media3 1.3.1 · secp256k1-kmp 0.22.0 · Tinder StateMachine · Room 2.7.1
**Pattern:** Single-activity MVVM, Jetpack Navigation Compose. One `MainActivity` → `NavHost` in `MyceliumNavigation.kt` (5,230 lines).

---

## 1. Layer Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│  UI LAYER (ui/)                                                 │
│  52 Screens · 59 Components · MD3 Theme · MyceliumNavigation    │
├─────────────────────────────────────────────────────────────────┤
│  VIEWMODEL LAYER (viewmodel/)                                   │
│  3 Activity-scoped (App, Account, FeedState)                    │
│  9 Screen-scoped (Dashboard, Kind1Replies, ThreadReplies, etc.) │
├─────────────────────────────────────────────────────────────────┤
│  REPOSITORY LAYER (repository/)                                 │
│  49 files (repos, caches, managers, services)                   │
├─────────────────────────────────────────────────────────────────┤
│  RELAY LAYER (relay/)                                           │
│  RelayConnectionStateMachine · SubscriptionMultiplexer          │
│  RelayHealthTracker · Nip42AuthHandler · RelayDeliveryTracker   │
│  NetworkConnectivityMonitor · RelayLogBuffer                    │
├─────────────────────────────────────────────────────────────────┤
│  CYBIN LIBRARY (cybin/)                                         │
│  CybinRelayPool · RelayConnection (Ktor CIO WS)                │
│  Event · Filter · NostrSigner · NIP implementations (28 files)  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Cybin Library (cybin/)

In-repo Nostr protocol library (`com.example.cybin`). Included via `includeBuild("cybin")` with Gradle dependency substitution.

### Modules
- **core/** (6 files): `Event` (NIP-01 signed event with JSON ser/de), `EventTemplate` (unsigned), `Filter` (NIP-01 REQ), `TagArrayBuilder` DSL, `Types` (HexKey, Kind, TagArray), `Utils` (CybinUtils — random hex/alphanumeric)
- **crypto/** (4 files): `KeyPair` (secp256k1 via secp256k1-kmp), `EventHasher` (NIP-01 event hashing), `Nip04` (NIP-04 encryption v1), `Nip44` (NIP-44 encryption v2)
- **nip19/** (4 files): `Bech32` encoding/decoding, `Tlv` parsing, `Entities` (NPub, NSec, NEvent, NProfile, Note), `Nip19Parser`
- **nip25/** (1 file): `ReactionEvent` builder (kind-7)
- **nip47/** (1 file): `WalletConnect` — NWC payment request/response
- **nip55/** (6 files): External signer (Amber) — `NostrSignerExternal`, `AmberDetector`, `ExternalSignerLogin`, `CommandType`, `IActivityLauncher`, `IntentRequestManager`
- **nip57/** (1 file): `ZapRequest` events (kind-9734)
- **relay/** (3 files): `CybinRelayPool` + `RelayConnection` (1,230 lines), `NostrProtocol` (NIP-01 wire format), `RelayUrl` normalizer
- **signer/** (2 files): `NostrSigner` (abstract), `NostrSignerInternal` (local nsec Schnorr signing)

### CybinRelayPool (relay/CybinRelayClient.kt, 1,230 lines)
Multi-relay WebSocket pool with priority-based subscription scheduling.

```
Per-relay: RelayConnection (Ktor CIO WebSocket session)
           RelaySchedulerState (activeSubs, queuedReqs, effectiveLimit, eoseReceived)

Priority levels: CRITICAL(4) > HIGH(3) > NORMAL(2) > LOW(1) > BACKGROUND(0)
Per-relay cap: MAX_SUBS_PER_RELAY = 40
Preemption: Higher-priority sub evicts lowest-priority EOSE'd sub
Adaptive limits: NOTICE/CLOSED rate-limit → effectiveLimit reduced (min 3)
EOSE reaping: Priority-aware — HIGH 30s, NORMAL 10s, LOW/BG 3s
Reserved slots: Last N slots reserved for HIGH+ priority
Reconnect: exponential backoff 2s→30s, 6 attempts, 8/wave with 600ms stagger
Circuit breaker: 5min blacklist after exhausting reconnect attempts
Scoped disconnect: disconnectIdleRelays(candidateUrls) prevents killing outbox connections
```

### RelayConnectionListener interface
`onConnecting`, `onConnected`, `onDisconnected`, `onError`, `onAuth`, `onOk`, `onEose`

---

## 3. Relay Layer (relay/)

> For a comprehensive deep-dive, see [RELAY_SYSTEM.md](RELAY_SYSTEM.md).

### Relay Stack (bottom → top)
```
RelayConnection (Ktor CIO WebSocket per relay URL)
    ↓
CybinRelayPool (priority scheduler, per-relay slots, reconnect)
    ↓
SubscriptionMultiplexer (filter merge, ref-count, dedup)
    ↓
RelayConnectionStateMachine (Tinder FSM, main feed owner)
    ↓
Repositories / ViewModels
```

### RelayConnectionStateMachine (singleton, 1,054 lines)
Owns one `CybinRelayPool`. Tinder StateMachine pattern.

**States:** Disconnected → Connecting → Connected → Subscribed → ConnectFailed
**Events:** ConnectRequested, Connected, ConnectFailed, RetryRequested, FeedChangeRequested, DisconnectRequested
**Side Effects:** ConnectRelays, OnConnected, ScheduleRetry, UpdateSubscription, DisconnectClient

**Main feed subscription:** One combined REQ across all user relays:
- kind-1 (notes), kind-6 (reposts), kind-11 (topics), kind-1011 (moderation), kind-30311 (live activities)
- Optional kind-7 + kind-9735 count filters for visible notes

**Kind routing via registered handlers:**
- `onKind1WithRelay` → NotesRepository
- `onKind6WithRelay` → NotesRepository (reposts)
- `onKind11` → TopicsRepository
- `onKind1011` → ScopedModerationRepository
- `onKind30311` → LiveActivityRepository
- kind-7/9735 → NoteCountsRepository.onCountsEvent

**Idempotent feed change:** Skips transition if relayUrls + kind1Filter + countsNoteIds unchanged.
**resumeSubscriptionProvider:** Lambda from NotesRepository ensures app-resume re-applies Following filter.
**Keepalive:** 2-min check, 5-min stale threshold → forced reconnect.
**Retry:** 3 attempts, 2s/5s backoff.

### Subscription Types
1. **requestFeedChange()** — Main feed (owned by NotesRepository). Replaces existing subscription.
2. **requestTemporarySubscription()** — Auxiliary subs (thread replies, notifications). Overloads: single/multi-filter, with-relay-URL, per-relay filter maps.
3. **requestOneShotSubscription()** — EOSE-based auto-close. Collects events → auto-CLOSEs after all relays EOSE + settle. Hard timeout fallback. Used for bookmarks, mute list, anchor subs.
4. **requestTemporarySubscriptionPerRelay()** — Outbox model: each relay gets its own filter set.

### SubscriptionMultiplexer (774 lines)
Flow-based multiplexer on top of CybinRelayPool.
- **Filter merging:** Identical filters share one relay sub via FilterKey
- **Ref-counting:** Relay sub CLOSEd only when last consumer unsubscribes
- **50ms debounced REQ flush**
- **EOSE tracking:** Per-merged-sub, per-relay
- **Bounded LRU dedup:** 10K event IDs
- **Priority upgrade:** New consumer with higher priority upgrades merged sub
- **Per-relay filter map support:** Outbox model subscriptions are first-class citizens
- **Account switch cleanup:** `mux.clear()` resets all state on account change

### RelayHealthTracker (singleton, 666 lines)
Per-relay health metrics: connectionAttempts, failures, consecutiveFailures, eventsReceived, avgLatencyMs, lastError.
- **Flagging:** 5 consecutive failures → flagged (UI warning)
- **Auto-blocking:** 5 consecutive failures → 6-hour cooldown
- **Manual blocking:** Persisted in SharedPreferences
- **filterBlocked():** Called before every subscription
- **Publish tracking:** registerPendingPublish → recordPublishOk (per-relay OK) → finalizePendingPublish (10s timeout). `publishReports` StateFlow (last 50), `publishFailure` SharedFlow for snackbar.

### Nip42AuthHandler (311 lines)
Intercepts AUTH challenges → signs kind-22242 via Amber (background, no UI) → tracks per-relay status (NONE → CHALLENGED → AUTHENTICATING → AUTHENTICATED/FAILED) → on success renews filters.

### RelayDeliveryTracker (310 lines)
Tracks per-event relay delivery confirmation. Used by the publish pipeline to show per-relay delivery status in the UI.

### NetworkConnectivityMonitor (117 lines)
Android ConnectivityManager.NetworkCallback. On network regained → `requestReconnectOnResume()` with 3s debounce.

### RelayLogBuffer (139 lines)
Circular buffer of recent relay events (connections, errors, EOSE, events received) for the `RelayLogScreen` debug UI.

### Slot Optimization (typical home feed: ~3-4 slots)
1. Main feed (HIGH) — 1 permanent
2. Notifications (LOW) — 1 permanent (merged from 3)
3. NoteCountsRepository (LOW) — 1 (replaces on phase change)
4. OutboxFeedManager (LOW) — 1
5. Transient one-shots auto-close via EOSE

---

## 4. Data Flow Pipelines

### Home Feed Pipeline
```
WebSocket Event → CybinRelayPool → RelayConnectionStateMachine (kind routing)
  → onKind1WithRelay → NotesRepository.pendingKind1Events (ConcurrentLinkedQueue)
  → scheduleKind1Flush() (120ms debounce)
  → flushKind1Events() (batch merge into _notes StateFlow under processEventMutex)
  → DashboardViewModel observes notesRepository.notes
  → DashboardScreen LazyColumn
```

### Outbox Feed (NIP-65)
```
Follow list loads → DashboardViewModel.loadFollowList() → startOutboxFeed()
  → OutboxFeedManager.start()
  → Phase 1: batch-fetch NIP-65 (kind-10002) for all followed users via indexer relays
  → Phase 2: build relay→authors map, cap at 30 outbox relays ranked by author count
  → requestTemporarySubscriptionPerRelay() (each relay gets only its relevant authors)
  → Events → NotesRepository.pendingKind1Events (same pipeline, dedup by note ID)
```

### Repost Pipeline (Kind-6)
```
Kind-6 event → NotesRepository.handleKind6Repost()
  → Content-embedded: parse inner event JSON
  → Tag-only: pendingRepostBuffer → 500ms debounce → flushRepostBatch() (ONE batched sub)
  → Both: parse NIP-10 e-tags for rootNoteId/replyToId/isReply
  → Merge into feed as composite "repost:eventId" note
```

### Thread Reply Pipeline (two parallel systems)
**Kind-1 replies** (Kind1RepliesRepository): Direct OkHttp WebSocket (bypasses relay pool) + ThreadReplyCache for instant display. Batched parent resolution (300ms debounce, recursive).
**Kind-1111 replies** (ThreadRepliesRepository): Relay pool requestTemporarySubscription (CRITICAL priority). Pending/displayed split with 1.5s cutoff for live reply awareness. New replies → "(y new)" badges.

### Notification Pipeline
```
Kind-1/7/6/9735/1111 → NotificationsRepository.handleEvent()
  → Dedup via seenEventIds → route by kind
  → Consolidation: multiple likes/zaps on same note → single row
  → Target enrichment: pendingTargetFetches → 500ms debounce → flushTargetFetchBatch()
  → Android push: fireAndroidNotification() → 8 channels
```

### Counts Pipeline (Two-Phase)
```
Phase 1: kind-1 reply counts loaded with main feed (NoteCountsRepository.onLiveEvent)
Phase 2: kind-7 + kind-9735 after 600ms delay (requestFeedChangeWithCounts)
  → Per-relay fan-out: filters sent to relays where each note was seen
```

### Profile Metadata Pipeline
```
ProfileMetadataCache (singleton, persisted to SharedPreferences)
  → fetchProfileBatch(): batched kind-0 requests
  → profileUpdated: SharedFlow<String> — NoteCard observes per relevantPubkeys set
  → resolveAuthor(pubkey): returns Author from cache or fetches
```

### Publishing Pipeline
```
AccountStateViewModel.publishKind1(content, relayUrls)
  → EventPublisher.publish(context, signer, relayUrls, kind, content, tags)
  → Sign with NostrSigner → send via RelayConnectionStateMachine.send()
  → Track via RelayHealthTracker (10s OK timeout per relay)
  → publishedEvents SharedFlow → live awareness (thread replies, topics)
  → NotesRepository.injectOwnNote() → immediate display (optimistic)
  → updatePublishState(Confirmed) → green progress line → auto-clear 3s
  → Relay echoes: locallyPublishedIds check → skip pending, merge relay URLs
```

### Zap Pipeline
```
SemiCircleZapMenu (amount) → ZapRequestBuilder (kind-9734, NIP-57)
  → LightningAddressResolver (LUD-16) → LnurlResolver (bolt11 invoice)
  → NwcPaymentManager (NIP-47) OR external wallet intent
  → ZapStatePersistence.markZapped()
```

---

## 5. Repository Layer (49 files)

The repository directory contains repositories, caches, managers, and service classes. Not all are "repositories" in the strict pattern sense — some are caches, API providers, or orchestrators.

### Core Feed
| File | Lines | Pattern | Purpose |
|------|-------|---------|---------|
| NotesRepository | 2,912 | Singleton | Main feed owner, pending/displayed, feed cache, pagination, optimistic rendering |
| TopicsRepository | — | Singleton | Kind-11 topics, pending/displayed split, topic injection |
| TopicsPublishService | — | Singleton | Topic publishing helper |
| OutboxFeedManager | — | Singleton | NIP-65 outbox-aware feed discovery, Thompson Sampling relay ranking |

### Thread/Reply
| File | Lines | Purpose |
|------|-------|---------|
| Kind1RepliesRepository | ~1,000 | Kind-1 replies via direct WebSocket, ThreadReplyCache, batched parent resolution |
| ThreadRepliesRepository | ~600 | Kind-1111 replies via relay pool, live reply awareness |
| TopicRepliesRepository | — | Topic reply counts |
| ReplyCountCache | — | In-memory reply count cache |
| DeepHistoryFetcher | — | Deep thread history fetching for older replies |

### Social Graph
| File | Purpose |
|------|---------|
| ContactListRepository | Kind-3 follow list (5min TTL), follow/unfollow publish |
| ProfileMetadataCache | Kind-0 profiles, batch fetching, SharedPreferences + Room persistence |
| ProfileCountsRepository | Following/follower counts via indexer relays |
| ProfileFeedRepository | Author-specific note feed with relay-side pagination |
| ReactionsRepository | Optimistic reaction state |
| PeopleListRepository | Kind-30000 people lists |

### Relay Management
| File | Lines | Purpose |
|------|-------|---------|
| RelayStorageManager | — | Profiles/categories persistence, CRUD |
| RelayRepository | — | NIP-65 kind-10002 for current user |
| Nip65RelayListRepository | 1,020 | NIP-65 for ANY user, batch fetch, outbox resolution |
| Nip66RelayDiscoveryRepository | — | NIP-66 relay discovery, disk-cached |

### Notifications & Social
| File | Lines | Purpose |
|------|-------|---------|
| NotificationsRepository | 1,976 | All notification types, consolidation, target enrichment, Android push |
| NoteCountsRepository | — | Reaction/zap counts, two-phase loading, per-relay fan-out |
| BookmarkRepository | — | Kind-10003, EOSE-based one-shot |
| MuteListRepository | — | Kind-10000, EOSE-based one-shot |
| ScopedModerationRepository | — | Kind-1011 NIP-22 moderation |
| BadgeRepository | — | NIP-58 badge definitions and awards |

### Messaging & Media
| File | Purpose |
|------|---------|
| DirectMessageRepository | NIP-17 gift-wrapped DMs, NIP-04/NIP-44 decryption |
| LiveActivityRepository | NIP-53 kind-30311 |
| LiveChatRepository | Kind-1311 live chat |
| TranslationService | ML Kit language detection + translation |
| ArticleEmbedCache | Kind-30023 article embed metadata |

### Payments & Wallet
| File | Purpose |
|------|---------|
| CoinosRepository | CoinOS wallet integration |
| CoinosApiProvider / RealCoinosApiProvider / MockCoinosApiProvider | CoinOS API abstraction |
| NwcConfigRepository | NIP-47 Wallet Connect config |
| ZapStatePersistence | Persisted zap button state |

### Polls & Voting
| File | Purpose |
|------|---------|
| PollResponseRepository | NIP-88 poll response handling (kind-1018) |
| VoteRepository | Kind-30011 vote tracking |
| ZapPollResponseRepository | Kind-6969 zap poll responses |

### Content & Caching
| File | Purpose |
|------|---------|
| QuotedNoteCache | Quoted note metadata (nevent/note1 resolution) |
| Nip05Verifier | NIP-05 verification via HTTP |
| EmojiPackRepository | Custom emoji pack definitions (NIP-30) |
| EmojiPackSelectionRepository | User's selected emoji packs |

### Infrastructure
| File | Purpose |
|------|---------|
| AccountScopedRegistry | Per-account repository lifecycle management |
| StartupOrchestrator | Phased startup coordination (5 phases) |
| SettingsSyncManager | NIP-78 kind-30078 settings sync across devices |
| AnchorSubscriptionRepository | Kind-30073 anchor events |
| DraftsRepository | Local draft persistence |
| Nip86RelayManagementClient | NIP-86 relay management API |

---

## 6. ViewModel Layer (12 files)

### Activity-Scoped (global, survive navigation)

**AppViewModel** — Cross-screen state: `notesById` map for thread navigation, `selectedNote` (legacy fallback), image/video viewer URLs, `mediaPageByNoteId` (album swipe), `hiddenNoteIds` (Clear Read), `pendingReactionsData`.

**AccountStateViewModel** (2,427 lines) — Multi-account management, login/logout, all publish methods (`publishKind1`, `publishRepost`, `publishTopic`, `publishThreadReply`, `publishKind1Reply`, `publishTopicReply`, `sendZap`, `followUser`, `unfollowUser`, `reactToNote`, `deleteEvent`). Amber signer management. Onboarding state gates relay connections.

**FeedStateViewModel** — Feed filter: All vs Following toggle, sort order, home scroll position save/restore.

### Screen-Scoped (per NavBackStackEntry)

**DashboardViewModel** — Feed observation, relay state, follow list loading, URL preview enrichment (viewport-aware prefetch: PREFETCH_AHEAD=15, 400ms debounce).

**Kind1RepliesViewModel** — Kind-1 reply loading/sorting. Overlay threads share dashboard's instance; nav-based threads get fresh instance.

**ThreadRepliesViewModel** — Kind-1111 replies, live reply awareness (`newReplyCount`, `newRepliesByParent`). Observes `EventPublisher.publishedEvents` for local injection.

**TopicsViewModel** — Topic list, note counts, pending/displayed management.

**RelayManagementViewModel** — Relay profile/category CRUD, relay testing, NIP-65 publishing.

**SearchViewModel** — Search queries and results.

**AnnouncementsViewModel** — Announcements/news feed state.

**AuthViewModel** — Authentication flow state.

**ThreadStateHolder** — Thread-specific state management.

---

## 7. Navigation Architecture

> For a comprehensive reference including all routes and decision trees, see [NAVIGATION.md](NAVIGATION.md).

### Entry Point
`MainActivity.kt` → single Activity, edge-to-edge. Initializes Coil, RelayHealthTracker, ProfileMetadataCache, feed cache, NetworkConnectivityMonitor, notification channels. Lifecycle: ON_RESUME → relay reconnect; ON_STOP → video pause.

`MyceliumNavigation.kt` (5,230 lines) — NavHost, all routes, overlay thread infrastructure, bottom nav, snackbar host, sidebar drawer.

### Three Navigation Patterns

| Pattern | Mechanism | Used For |
|---------|-----------|----------|
| **Overlay stack** | `SnapshotStateList<Note>` + `AnimatedVisibility` | Feed → Thread (chained). 4 stacks: home, topics, profile, notifications |
| **NavController** | `navController.navigate("route/{id}")` | Settings, compose, relay screens, media viewers, thread-to-thread |
| **Bottom nav** | `BottomNavDestinations` enum | Home, DMs, Wallet, Announcements (News), Alerts (Notifications) |

### Overlay Stack Architecture
Each main screen has its own overlay thread stack:
- `overlayThreadStack` — home feed
- `overlayTopicThreadStack` — topics
- `overlayProfileThreadStack` — profile
- `overlayNotifThreadStack` + `overlayNotifReplyKinds` — notifications

Tapping a note inside overlay pushes onto stack (not navigate). BackHandler pops. "Preserve routes" (`note_relays/`, `relay_log/`, `reactions/`, `zap_settings`, `profile/`) don't clear overlay.

### Key Routes
`dashboard`, `topics`, `announcements`, `messages`, `wallet`, `notifications`, `thread/{noteId}`, `profile/{pubkey}`, `user_profile`, `compose`, `compose_topic`, `compose_topic_reply/{noteId}`, `reply/{noteId}`, `compose_article`, `relay_management`, `relay_connection_status`, `relay_needs_attention`, `settings/relay_health`, `relay_log/{relayUrl}`, `settings/*` (10 sub-screens), `onboarding`, `image_viewer`, `video_viewer`, `live_stream/{id}`, `live_explorer`, `chat/{peerPubkey}`, `new_dm`, `user_qr`, `zap_settings`, `lists`, `drafts`, `effects_lab`, `debug_follow_list`, `article_view/{eventId}`, `settings/publish_results`

---

## 8. UI Layer

### Screen Composables (52 screens)
**Feed:** DashboardScreen (1,954 lines), TopicsScreen (1,610 lines), NotificationsScreen (1,608 lines, 10 tabs), AnnouncementsFeedScreen
**Thread:** ModernThreadViewScreen (3,257 lines, unified kind-1/1111), TopicThreadScreen
**Profile:** ProfileScreen (1,679 lines, Notes/Replies/Media tabs)
**Compose:** ComposeNoteScreen, ComposeTopicScreen, ComposeTopicReplyScreen, ReplyComposeScreen, ComposeArticleScreen — all use RelaySelectionScreen
**Relay:** RelayManagementScreen (2,325 lines), RelayDiscoveryScreen, RelayHealthScreen, RelayLogScreen, RelayConnectionStatusScreen, RelayNeedsAttentionScreen, RelaySelectionScreen, RelayUsersScreen, RelayUserManagementScreen, NoteRelaysScreen
**Media:** LiveStreamScreen (HLS + PiP), LiveExplorerScreen, ImageContentViewerScreen, VideoContentViewerScreen
**DMs:** ConversationsScreen, ChatScreen, NewDmScreen, DmSettingsScreen
**Settings:** 12 screens (hub + about + debug + 9 category sub-screens)
**Articles:** ArticleViewScreen
**Lists:** ListsScreen, ListDetailScreen
**Other:** OnboardingScreen (2,003 lines), WalletScreen, QrCodeScreen, ReactionsScreen, DraftsScreen, EffectsLabScreen, DebugFollowListScreen, PublishResultsScreen, AccountPreferencesScreen

### Core Components (59 composables)
- **NoteCard.kt** (3,807 lines) — Primary note display. Action schemas per kind. PublishProgressLine (shimmer/green/red). Relay orbs. Quoted notes.
- **ModernNoteCard.kt** (1,158 lines) — Thread view variant
- **GlobalSidebar.kt** (694 lines) — Drawer with relay health badge, profile, account switcher
- **InlineVideoPlayer.kt** (735 lines) — Feed video with SharedPlayerPool (LRU, max 3)
- **SemiCircleZapMenu.kt** (700 lines) — Arc zap amount picker
- **ExpandingZapMenu.kt** — Alternative zap menu variant
- **RelayOrbs.kt** — Stacked relay indicators (3 max + count)
- **ThreadSlideBackBox.kt** — Swipe-back gesture for overlay threads
- **ThreadFab.kt** — FAB with sort toggle
- **ClickableNoteContent.kt** — Tappable text with nostr: URI, URL, hashtag, and profile annotation handling
- **MarkdownNoteContent.kt** — Markdown-rendered note content
- **ArticleCard.kt** — Long-form article preview card
- **PollCard.kt** — NIP-88 poll rendering with vote buttons
- **SharedPlayerPool.kt** — LRU video player pool (max 3 concurrent ExoPlayer instances)
- **VideoGestureDetector.kt** / **VideoGestureOverlays.kt** / **VideoGestureState.kt** — Seek, volume, brightness, pinch-to-zoom gestures
- **EmojiPicker.kt** / **EmojiDrawer.kt** / **EmojiPackGrid.kt** / **EmojiData.kt** — Custom emoji selection (NIP-30)
- **ReactionEmoji.kt** / **ReactionFavoritesBar.kt** / **ReactionsBottomSheet.kt** — Emoji reaction display and selection
- **MentionSuggestion.kt** — @mention autocomplete in compose fields
- **PipStreamManager.kt** / **PipStreamOverlay.kt** — Picture-in-picture live stream overlay
- **AccountSwitchBottomSheet.kt** — Multi-account switcher
- **WalletConnectDialog.kt** — NIP-47 wallet connect pairing
- **ZapButton.kt** / **ZapConfigurationDialog.kt** / **ZapMenuRow.kt** — Zap UI elements
- **SupportMyceliumZapDialog.kt** — Developer support zap dialog

### NoteCard Action Row Schemas
- **KIND1_FEED:** Lightning | Boost | Likes | Caret (3-dot menu)
- **KIND11_FEED:** Up | Down | Boost | Lightning | Likes | Caret
- **KIND1111_REPLY:** Up | Down | Lightning | Likes | Reply | Caret

---

## 9. Services Layer (20 files)

### EventPublisher (singleton)
Central publisher: build EventTemplate → inject NIP-89 client tag → sign → send → track.
`publishedEvents: SharedFlow<Event>` — event bus for live awareness (thread replies, topics).

### RelayForegroundService
Android foreground service (specialUse). Keeps WebSocket alive when backgrounded. Three connection modes: Always On, Adaptive (WorkManager), When Active. See `NotificationPreferences.ConnectionMode`.

### RelayCheckWorker
WorkManager periodic task for Adaptive connection mode. Checks inbox relays for new events at configurable intervals (15min minimum).

### NotificationChannelManager (8 channels)
Service: RELAY_SERVICE (low). Social: REPLIES (high), COMMENTS (high), MENTIONS (default), REACTIONS (low), ZAPS (high), REPOSTS (low), DMS (high).

### BlossomClient
Native Blossom (BUD-01/02/04) HTTP blob storage client with kind-24242 auth events, multi-strategy upload (PUT/POST). Uses Ktor `HttpClient` with `ChannelProvider` streaming body.

### NoteScheduler / ScheduleReceiver / ScheduledNoteWorker
Note scheduling pipeline: `AlarmManager` triggers `ScheduleReceiver` → `ScheduledNoteWorker` publishes pre-signed event. Survives device reboots via `BootReceiver`.

### URL Preview Pipeline
`DashboardViewModel.updateVisibleRange()` → viewport prefetch (first+15, 400ms debounce) → `UrlPreviewManager` → `UrlPreviewService` (Jsoup HTML parse) → `UrlPreviewCache` (LRU).

### Payment Services
- **LightningAddressResolver** — LUD-16 address → LNURL-pay endpoint resolution
- **LnurlResolver** — LNURL-pay → bolt11 invoice
- **NwcPaymentManager** — NIP-47 Wallet Connect payment execution
- **ZapRequestBuilder** — NIP-57 kind-9734 zap request event construction
- **ZapPaymentHandler** — Orchestrates the full zap flow (resolve → invoice → pay)

### Other Services
- **HtmlParser** — HTML parsing utilities
- **Nip86Client** — NIP-86 relay management HTTP API client
- **KillAppReceiver** — Broadcast receiver for clean app shutdown
- **BootReceiver** — Re-schedules pending notes after device reboot

---

## 10. Authentication

### Two Signing Paths
1. **Amber (NIP-55):** External signer via Android ContentProvider IPC. `AmberSignerManager` (in `auth/`) detects Amber, requests permissions per event kind, signs background (no UI for pre-approved kinds). Supports NIP-04 and NIP-44 encryption delegation.
2. **nsec (internal):** `NostrSignerInternal` (in Cybin) uses secp256k1 `KeyPair`. Private key in-memory only.

### Multi-Account
`AccountStateViewModel` manages multiple `AccountInfo` records (SharedPreferences JSON). `AccountScopedRegistry` provides per-account repository instances with dedicated `CoroutineScope`s. Account switching: `disconnectAndClearForAccountSwitch()` clears relay connections, NIP-65 cache, feed notes, notification subs. `SubscriptionMultiplexer.clear()` resets dedup state.

---

## 11. Nostr Event Kinds

| Kind | Name | Handler |
|------|------|---------|
| 0 | Profile metadata | ProfileMetadataCache |
| 1 | Text note | NotesRepository |
| 3 | Contact list | ContactListRepository |
| 6 | Repost | NotesRepository.handleKind6Repost |
| 7 | Reaction (NIP-25) | NoteCountsRepository + ReactionsRepository |
| 11 | Topic (NIP-22) | TopicsRepository |
| 1011 | Scoped moderation (NIP-22) | ScopedModerationRepository |
| 1068 | Poll (NIP-88) | PollResponseRepository |
| 1111 | Thread reply (NIP-22) | ThreadRepliesRepository |
| 1311 | Live chat (NIP-53) | LiveChatRepository |
| 6969 | Zap poll | ZapPollResponseRepository |
| 9734 | Zap request (NIP-57) | ZapRequestBuilder |
| 9735 | Zap receipt (NIP-57) | NoteCountsRepository |
| 10000 | Mute list | MuteListRepository |
| 10002 | Relay list (NIP-65) | Nip65RelayListRepository |
| 10003 | Bookmarks | BookmarkRepository |
| 14/13/1059 | Gift-wrapped DM (NIP-17) | DirectMessageRepository |
| 22242 | Relay auth (NIP-42) | Nip42AuthHandler |
| 24242 | Blossom auth (BUD-01) | BlossomClient |
| 30000 | People list | PeopleListRepository |
| 30009 | Badge definition (NIP-58) | BadgeRepository |
| 30023 | Long-form article (NIP-23) | ArticleEmbedCache |
| 30073 | Anchor subscription | AnchorSubscriptionRepository |
| 30078 | Settings sync (NIP-78) | SettingsSyncManager |
| 30166 | Relay discovery (NIP-66) | Nip66RelayDiscoveryRepository |
| 30311 | Live activity (NIP-53) | LiveActivityRepository |

---

## 12. Key Patterns & Pitfalls

### Relay URL Resolution Timing
`fallbackRelayUrls` depends on `currentAccount` StateFlow. On first composition, may be null → empty relay list. Fix: `LaunchedEffect` skips when `relayUrls.isEmpty()`, re-fires when URLs resolve.

### ViewModel Scoping
Overlay threads share dashboard's NavBackStackEntry → same ViewModel. Nav-based threads get fresh ViewModel. Implication: overlay thread ViewModel is shared, nav-based is isolated.

### Profile Rendering
NoteCard observes `profileUpdated` directly (not via repo). `profileRevision` counter forces recomposition without changing note object. Filtered by `relevantPubkeys` set + 200ms debounce.

### Subscription Batching
Multiple individual subs (notifications, reposts) batched into single REQ via debounced buffers (500ms). Prevents 200+ concurrent LOW subs.

### Feed Session State
`FeedSessionState` enum (Idle → Loading → Live → Refreshing) prevents UI/re-subscribe conflicts. Loading overlay suppressed when feed is Live or Refreshing.

### Infinite Scroll Fix
`isLoadingOlder` used as `LaunchedEffect` key so the effect restarts when loading completes, triggering next page load from stale-closure bug.

---

## 13. NIP Support

NIP-01 (protocol), NIP-02 (contacts), NIP-04 (encryption v1), NIP-05 (DNS identifiers), NIP-10 (reply threading), NIP-11 (relay info), NIP-17 (gift-wrapped DMs), NIP-19 (bech32 entities), NIP-22 (topics/comments), NIP-23 (long-form content), NIP-25 (reactions), NIP-30 (custom emoji), NIP-33 (parameterized replaceable), NIP-42 (relay auth), NIP-44 (encryption v2), NIP-47 (wallet connect), NIP-53 (live activities), NIP-55 (external signer), NIP-57 (zaps), NIP-58 (badges), NIP-65 (relay lists), NIP-66 (relay discovery), NIP-78 (settings sync), NIP-86 (relay management), NIP-88 (polls), NIP-89 (client tags), NIP-92 (imeta), NIP-96 (file storage)

## 14. Persistence Layer

### Room Database (`db/`)
6 entities, 6 DAOs, 1 `AppDatabase`:

| Entity | DAO | Purpose |
|--------|-----|---------|
| `CachedProfileEntity` | `ProfileDao` | Kind-0 profile metadata cache |
| `CachedNip65Entity` | `Nip65Dao` | Kind-10002 relay list cache |
| `CachedNip11Entity` | `Nip11Dao` | NIP-11 relay information documents |
| `CachedEventEntity` | `EventDao` | Generic event cache |
| `CachedFollowListEntity` | `FollowListDao` | Kind-3 follow list cache |
| `CachedEmojiPackEntity` | `EmojiPackDao` | Custom emoji pack definitions |

Room is a **cache layer** — primary data flows from WebSockets through in-memory StateFlows. Room provides persistence across process death and faster cold starts.

### SharedPreferences
Used for: relay profiles/categories, feed cache (debounced JSON blob), relay health metrics, blocked relay list, account info, zap amounts, notification preferences, theme settings, publish state. Incrementally migrating high-churn data to Room.

### Encrypted SharedPreferences
`androidx.security:security-crypto` — Used exclusively for wallet seed storage (`SeedManager` in `lightning/`).

## 15. Lightning Wallet (`lightning/`)

Embedded self-custodial Lightning node using ACINQ's `lightning-kmp`:

| File | Purpose |
|------|---------|
| `PhoenixWalletManager` | Node lifecycle, channel management, invoice creation/payment, balance monitoring |
| `NwcServiceProvider` | NIP-47 NWC service — handles `make_invoice`, `pay_invoice`, `get_balance` requests |
| `SeedManager` | Encrypted seed storage and recovery |
| `LightningDatabase` | Wallet persistence |
| `AndroidLoggerFactory` | Logging bridge for lightning-kmp |

Debug builds connect to **testnet**; release builds connect to **mainnet** (controlled by `WALLET_DEV_MODE` build config).
