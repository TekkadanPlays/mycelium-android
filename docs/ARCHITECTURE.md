# Mycelium Android — Architecture Reference

> Canonical architecture reference for the Mycelium Android Nostr client.
> Designed for AI consumption (Claude Opus 4.6 + Excalidraw) and developer onboarding.

## Overview

Mycelium is a native Android Nostr protocol client. Connects to relays via persistent WebSocket, renders feeds of text notes (kind-1), forum topics (kind-11/1111), live activities (NIP-53), and encrypted DMs (NIP-17). Handles 20+ event kinds with NIP-65 outbox relay discovery, NIP-42 auth, NIP-57 zaps, NIP-55 Amber signing.

**Stack:** Kotlin · Jetpack Compose · MD3 · Ktor 3.0 WebSocket · Coil 2.5 · Media3 1.3 · secp256k1-kmp · Tinder StateMachine
**Pattern:** Single-activity MVVM, Jetpack Navigation Compose. One `MainActivity` → `NavHost` in `MyceliumNavigation.kt` (3500 lines).

---

## 1. Layer Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│  UI LAYER (ui/)                                                 │
│  44 Screens · 42 Components · MD3 Theme · MyceliumNavigation    │
├─────────────────────────────────────────────────────────────────┤
│  VIEWMODEL LAYER (viewmodel/)                                   │
│  3 Activity-scoped (App, Account, FeedState)                    │
│  8 Screen-scoped (Dashboard, Kind1Replies, ThreadReplies, etc.) │
├─────────────────────────────────────────────────────────────────┤
│  REPOSITORY LAYER (repository/)                                 │
│  33 repositories (Notes, Notifications, Topics, Profiles, etc.) │
├─────────────────────────────────────────────────────────────────┤
│  RELAY LAYER (relay/)                                           │
│  RelayConnectionStateMachine · SubscriptionMultiplexer          │
│  RelayHealthTracker · Nip42AuthHandler · NetworkConnectivityMon │
├─────────────────────────────────────────────────────────────────┤
│  CYBIN LIBRARY (cybin/)                                         │
│  CybinRelayPool · CybinRelayClient (Ktor WS)                   │
│  Event · Filter · NostrSigner · NIP implementations             │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Cybin Library (cybin/)

In-repo Nostr protocol library (`com.example.cybin`). Included via `includeBuild("cybin")` with Gradle dependency substitution.

### Modules
- **core/**: `Event` (NIP-01 signed event with JSON ser/de), `EventTemplate` (unsigned), `Filter` (NIP-01 REQ), `TagArrayBuilder` DSL, `Types` (HexKey, Kind, TagArray)
- **crypto/**: `KeyPair` (secp256k1 via secp256k1-kmp), NIP-01 event hashing, NIP-04 encryption
- **nip19/**: Bech32 encoding/decoding, TLV parsing, npub/nsec/note/nevent/nprofile entities
- **nip25/**: Reaction event builder (kind-7)
- **nip47/**: Wallet Connect (NWC) payment request/response
- **nip55/**: External signer (Amber) — `NostrSignerExternal`, `AmberDetector`, `ExternalSignerLogin`, `Permission`, `CommandType`
- **nip57/**: Zap request events (kind-9734)
- **signer/**: `NostrSigner` (abstract), `NostrSignerInternal` (local nsec Schnorr signing)

### CybinRelayPool (relay/CybinRelayClient.kt, 881 lines)
Multi-relay WebSocket pool with priority-based subscription scheduling.

```
Per-relay: RelayConnection (Ktor WebSocket session)
           RelaySchedulerState (activeSubs, queuedReqs, effectiveLimit, eoseReceived)

Priority levels: CRITICAL(4) > HIGH(3) > NORMAL(2) > LOW(1) > BACKGROUND(0)
Per-relay cap: MAX_SUBS_PER_RELAY = 12
Preemption: Higher-priority sub evicts lowest-priority EOSE'd sub
Adaptive limits: NOTICE/CLOSED rate-limit → effectiveLimit reduced (min 3)
EOSE reaping: STALE_EOSE_MS = 2s — evicts EOSE'd subs when queue waiting
Reconnect: exponential backoff 2s→30s, 6 attempts
Scoped disconnect: disconnectIdleRelays(candidateUrls) prevents killing outbox connections
```

### RelayConnectionListener interface
`onConnecting`, `onConnected`, `onDisconnected`, `onError`, `onAuth`, `onOk`, `onEose`

---

## 3. Relay Layer (relay/)

### Relay Stack (bottom → top)
```
CybinRelayClient (Ktor WebSocket per relay URL)
    ↓
CybinRelayPool (priority scheduler, per-relay slots, reconnect)
    ↓
SubscriptionMultiplexer (filter merge, ref-count, dedup)
    ↓
RelayConnectionStateMachine (Tinder FSM, main feed owner)
    ↓
Repositories / ViewModels
```

### RelayConnectionStateMachine (singleton, 983 lines)
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

### SubscriptionMultiplexer (423 lines)
Flow-based multiplexer on top of CybinRelayPool.
- **Filter merging:** Identical filters share one relay sub via FilterKey
- **Ref-counting:** Relay sub CLOSEd only when last consumer unsubscribes
- **50ms debounced REQ flush**
- **EOSE tracking:** Per-merged-sub, per-relay
- **Bounded LRU dedup:** 10K event IDs
- **Priority upgrade:** New consumer with higher priority upgrades merged sub

### RelayHealthTracker (singleton, 567 lines)
Per-relay health metrics: connectionAttempts, failures, consecutiveFailures, eventsReceived, avgLatencyMs, lastError.
- **Flagging:** 5 consecutive failures → flagged (UI warning)
- **Auto-blocking:** 5 consecutive failures → 6-hour cooldown
- **Manual blocking:** Persisted in SharedPreferences
- **filterBlocked():** Called before every subscription
- **Publish tracking:** registerPendingPublish → recordPublishOk (per-relay OK) → finalizePendingPublish (10s timeout). `publishReports` StateFlow (last 50), `publishFailure` SharedFlow for snackbar.

### Nip42AuthHandler (236 lines)
Intercepts AUTH challenges → signs kind-22242 via Amber (background, no UI) → tracks per-relay status (NONE → CHALLENGED → AUTHENTICATING → AUTHENTICATED/FAILED) → on success renews filters.

### NetworkConnectivityMonitor (98 lines)
Android ConnectivityManager.NetworkCallback. On network regained → `requestReconnectOnResume()` with 3s debounce.

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

## 5. Repository Layer (33 repositories)

### Core Feed
| Repository | Size | Pattern | Purpose |
|-----------|------|---------|---------|
| NotesRepository | 105KB | Singleton | Main feed owner, pending/displayed, feed cache, pagination, optimistic rendering |
| TopicsRepository | 31KB | Singleton | Kind-11 topics, pending/displayed split, topic injection |
| OutboxFeedManager | 13KB | Singleton | NIP-65 outbox-aware feed discovery |

### Thread/Reply
| Repository | Size | Purpose |
|-----------|------|---------|
| Kind1RepliesRepository | 36KB | Kind-1 replies via direct WebSocket, ThreadReplyCache, batched parent resolution |
| ThreadRepliesRepository | 21KB | Kind-1111 replies via relay pool, live reply awareness |
| TopicRepliesRepository | 7KB | Topic reply counts |
| ReplyCountCache | 2KB | In-memory reply count cache |

### Social Graph
| Repository | Size | Purpose |
|-----------|------|---------|
| ContactListRepository | 12KB | Kind-3 follow list (5min TTL), follow/unfollow publish |
| ProfileMetadataCache | 28KB | Kind-0 profiles, batch fetching, SharedPreferences persistence |
| ProfileCountsRepository | 12KB | Following/follower counts via indexer relays |
| ProfileFeedRepository | 15KB | Author-specific note feed with relay-side pagination |
| ReactionsRepository | 3KB | Optimistic reaction state |

### Relay Management
| Repository | Size | Purpose |
|-----------|------|---------|
| RelayStorageManager | 14KB | Profiles/categories persistence, CRUD |
| RelayRepository | 17KB | NIP-65 kind-10002 for current user |
| Nip65RelayListRepository | 42KB | NIP-65 for ANY user, batch fetch, outbox resolution |
| Nip66RelayDiscoveryRepository | 42KB | NIP-66 relay discovery, disk-cached |

### Notifications & Social
| Repository | Size | Purpose |
|-----------|------|---------|
| NotificationsRepository | 50KB | All notification types, consolidation, target enrichment, Android push |
| NoteCountsRepository | 25KB | Reaction/zap counts, two-phase loading, per-relay fan-out |
| BookmarkRepository | 6KB | Kind-10003, EOSE-based one-shot |
| MuteListRepository | 7KB | Kind-10000, EOSE-based one-shot |
| ScopedModerationRepository | 10KB | Kind-1011 NIP-22 |

### Messaging & Media
| Repository | Size | Purpose |
|-----------|------|---------|
| DirectMessageRepository | 15KB | NIP-17 gift-wrapped DMs, NIP-04/NIP-44 decryption |
| LiveActivityRepository | 12KB | NIP-53 kind-30311 |
| LiveChatRepository | 4KB | Kind-1311 live chat |
| TranslationService | 7KB | ML Kit language detection + translation |

### Payments & Other
| Repository | Purpose |
|-----------|---------|
| CoinosRepository (14KB) | CoinOS wallet integration |
| NwcConfigRepository (2KB) | NIP-47 config |
| ZapStatePersistence (2KB) | Persisted zap button state |
| AnchorSubscriptionRepository (5KB) | Kind-30073 anchor events |
| DraftsRepository (3KB) | Local draft persistence |
| QuotedNoteCache (5KB) | Quoted note metadata |
| Nip86RelayManagementClient (11KB) | NIP-86 relay management API |

---

## 6. ViewModel Layer

### Activity-Scoped (global, survive navigation)

**AppViewModel** — Cross-screen state: `notesById` map for thread navigation, `selectedNote` (legacy fallback), image/video viewer URLs, `mediaPageByNoteId` (album swipe), `hiddenNoteIds` (Clear Read), `pendingReactionsData`.

**AccountStateViewModel** (64KB) — Multi-account management, login/logout, all publish methods (`publishKind1`, `publishRepost`, `publishTopic`, `publishThreadReply`, `publishKind1Reply`, `publishTopicReply`, `sendZap`, `followUser`, `unfollowUser`, `reactToNote`, `deleteEvent`). Amber signer management. Onboarding state gates relay connections.

**FeedStateViewModel** — Feed filter: All vs Following toggle, sort order, home scroll position save/restore.

### Screen-Scoped (per NavBackStackEntry)

**DashboardViewModel** — Feed observation, relay state, follow list loading, URL preview enrichment (viewport-aware prefetch: PREFETCH_AHEAD=15, 400ms debounce).

**Kind1RepliesViewModel** — Kind-1 reply loading/sorting. Overlay threads share dashboard's instance; nav-based threads get fresh instance.

**ThreadRepliesViewModel** — Kind-1111 replies, live reply awareness (`newReplyCount`, `newRepliesByParent`). Observes `EventPublisher.publishedEvents` for local injection.

**TopicsViewModel** — Topic list, note counts, pending/displayed management.

**RelayManagementViewModel** (25KB) — Relay profile/category CRUD, relay testing, NIP-65 publishing.

---

## 7. Navigation Architecture

### Entry Point
`MainActivity.kt` → single Activity, edge-to-edge. Initializes Coil, RelayHealthTracker, ProfileMetadataCache, feed cache, NetworkConnectivityMonitor, notification channels. Lifecycle: ON_RESUME → relay reconnect; ON_STOP → video pause.

`MyceliumNavigation.kt` (3500 lines) — NavHost, all routes, overlay thread infrastructure, bottom nav, snackbar host, sidebar drawer.

### Three Navigation Patterns

| Pattern | Mechanism | Used For |
|---------|-----------|----------|
| **Overlay stack** | `SnapshotStateList<Note>` + `AnimatedVisibility` | Feed → Thread (chained). 4 stacks: home, topics, profile, notifications |
| **NavController** | `navController.navigate("route/{id}")` | Settings, compose, relay screens, media viewers |
| **Bottom nav** | `BottomNavDestinations` enum | Home, Topics, Live, DMs, Notifications |

### Overlay Stack Architecture
Each main screen has its own overlay thread stack:
- `overlayThreadStack` — home feed
- `overlayTopicThreadStack` — topics
- `overlayProfileThreadStack` — profile
- `overlayNotifThreadStack` + `overlayNotifReplyKinds` — notifications

Tapping a note inside overlay pushes onto stack (not navigate). BackHandler pops. "Preserve routes" (`note_relays/`, `relay_log/`, `reactions/`, `zap_settings`, `profile/`) don't clear overlay.

### Key Routes
`dashboard`, `topics`, `live_explorer`, `conversations`, `notifications`, `thread/{noteId}`, `profile/{pubkey}`, `compose`, `compose_topic`, `compose_topic_reply/{noteId}`, `reply/{noteId}`, `relay_management`, `relay_discovery`, `relay_health`, `relay_log/{relayUrl}`, `settings/*` (8 sub-screens), `onboarding`, `wallet`, `image_viewer`, `video_viewer`, `live_stream/{id}`, `chat/{id}`

---

## 8. UI Layer

### Screen Composables (44 screens)
**Feed:** DashboardScreen (79KB), TopicsScreen (61KB), NotificationsScreen (62KB, 10 tabs)
**Thread:** ModernThreadViewScreen (145KB, unified kind-1/1111), TopicThreadScreen (17KB)
**Profile:** ProfileScreen (53KB, Notes/Replies/Media tabs)
**Compose:** ComposeNoteScreen, ComposeTopicScreen, ComposeTopicReplyScreen, ReplyComposeScreen — all use RelaySelectionScreen
**Relay:** RelayManagementScreen (104KB), RelayDiscoveryScreen (50KB), RelayHealthScreen (61KB), RelayLogScreen (54KB)
**Media:** LiveStreamScreen (41KB, HLS + PiP), ImageContentViewerScreen, VideoContentViewerScreen
**DMs:** ConversationsScreen (21KB), ChatScreen (16KB), NewDmScreen
**Settings:** 10 screens (hub + 9 sub-screens)
**Other:** OnboardingScreen (90KB), WalletScreen, QrCodeScreen, ReactionsScreen, DraftsScreen

### Core Components (42 composables)
- **NoteCard.kt** (134KB) — Primary note display. Action schemas per kind. PublishProgressLine (shimmer/green/red). Relay orbs. Quoted notes.
- **ModernNoteCard.kt** (50KB) — Thread view variant
- **GlobalSidebar.kt** (27KB) — Drawer with relay health badge, profile, account switcher
- **InlineVideoPlayer.kt** (21KB) — Feed video with SharedPlayerPool (LRU, max 3)
- **SemiCircleZapMenu.kt** (32KB) — Arc zap amount picker
- **RelayOrbs.kt** (15KB) — Stacked relay indicators (3 max + count)
- **ThreadSlideBackBox.kt** (5KB) — Swipe-back gesture for overlay threads
- **ThreadFab.kt** (10KB) — FAB with sort toggle

### NoteCard Action Row Schemas
- **KIND1_FEED:** Lightning | Boost | Likes | Caret (3-dot menu)
- **KIND11_FEED:** Up | Down | Boost | Lightning | Likes | Caret
- **KIND1111_REPLY:** Up | Down | Lightning | Likes | Reply | Caret

---

## 9. Services Layer

### EventPublisher (singleton)
Central publisher: build EventTemplate → inject client tag → sign → send → track.
`publishedEvents: SharedFlow<Event>` — event bus for live awareness.

### RelayForegroundService
Android foreground service (specialUse). Keeps WebSocket alive when backgrounded. Respects `NotificationPreferences.backgroundServiceEnabled`.

### NotificationChannelManager (8 channels)
Service: RELAY_SERVICE (low). Social: REPLIES (high), COMMENTS (high), MENTIONS (default), REACTIONS (low), ZAPS (high), REPOSTS (low), DMS (high).

### URL Preview Pipeline
`DashboardViewModel.updateVisibleRange()` → viewport prefetch (first+15, 400ms debounce) → UrlPreviewManager → HTTP parse → LRU cache.

---

## 10. Authentication

### Two Signing Paths
1. **Amber (NIP-55):** External signer via Android ContentProvider IPC. `AmberSignerManager` detects, requests permissions per event kind, signs background (no UI for pre-approved kinds). NIP-04/NIP-44 encryption.
2. **nsec (internal):** `NostrSignerInternal` uses secp256k1 KeyPair. Private key in-memory only.

### Multi-Account
`AccountStateViewModel` manages multiple `AccountInfo` records (SharedPreferences JSON). Account switching: `disconnectAndClearForAccountSwitch()` clears relay connections, NIP-65 cache, feed notes, notification subs.

---

## 11. Nostr Event Kinds

| Kind | Name | Handler |
|------|------|---------|
| 0 | Profile metadata | ProfileMetadataCache |
| 1 | Text note | NotesRepository |
| 3 | Contact list | ContactListRepository |
| 6 | Repost | NotesRepository.handleKind6Repost |
| 7 | Reaction | NoteCountsRepository + ReactionsRepository |
| 11 | Topic (NIP-22) | TopicsRepository |
| 1011 | Scoped moderation | ScopedModerationRepository |
| 1111 | Thread reply (NIP-22) | ThreadRepliesRepository |
| 1311 | Live chat (NIP-53) | LiveChatRepository |
| 9734 | Zap request (NIP-57) | ZapRequestBuilder |
| 9735 | Zap receipt | NoteCountsRepository |
| 10000 | Mute list | MuteListRepository |
| 10002 | Relay list (NIP-65) | Nip65RelayListRepository |
| 10003 | Bookmarks | BookmarkRepository |
| 14/13/1059 | Gift-wrapped DM (NIP-17) | DirectMessageRepository |
| 22242 | Relay auth (NIP-42) | Nip42AuthHandler |
| 30073 | Anchor subscription | AnchorSubscriptionRepository |
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

NIP-01, NIP-02, NIP-04, NIP-10, NIP-11, NIP-17, NIP-19, NIP-22, NIP-25, NIP-42, NIP-44, NIP-47, NIP-53, NIP-55, NIP-57, NIP-65, NIP-66, NIP-86, NIP-89
