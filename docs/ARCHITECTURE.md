# Mycelium Android — Architecture & State Flow Documentation

## Overview

Mycelium is a Nostr client for Android built with Jetpack Compose, Kotlin Coroutines/Flows, and direct WebSocket connections to Nostr relays. The app follows an MVVM architecture with repository-backed data layers and a single-Activity Compose Navigation host.

---

## 1. Navigation Architecture

### Entry Point
- **`MainActivity`** → single Activity, `configChanges` handles rotation
- **`MyceliumNavigation.kt`** → `NavHost` with all route composables

### Navigation Patterns

| Pattern | Used For | Mechanism |
|---------|----------|-----------|
| **Overlay** | Feed → Thread | `overlayThreadNoteId` state var; `AnimatedVisibility` over dashboard |
| **Nav-based** | Thread → Thread, Notifications → Thread, Profile → Thread | `navController.navigate("thread/{noteId}")` |
| **Bottom Nav** | Home, DMs, Live, Relays, Alerts | `BottomNavDestinations` enum → route mapping |

### Thread Navigation Flow

```
Feed (dashboard composable)
  └─ Tap note → overlayThreadNoteId = note.id (overlay thread)
       └─ Tap quoted note → store both notes in notesById map
            → navigate("thread/{originalId}") [on backstack]
            → navigate("thread/{quotedId}") [visible]
                 └─ Back → pops to original thread (nav-based)
                      └─ Back → pops to dashboard
```

**Key state:** `AppViewModel.notesById: Map<String, Note>` — stores notes by ID for thread navigation. Each `thread/{noteId}` composable resolves its note from this map, so stacked threads don't interfere with each other.

**Legacy fallback:** `AppViewModel.selectedNote` — single slot used by older callers (notifications, profile). Thread composable checks `notesById[noteId]` first, then `selectedNote?.takeIf { it.id == noteId }`.

### Route Definitions

| Route | Screen | Key Args |
|-------|--------|----------|
| `dashboard` | DashboardScreen (home feed) | — |
| `topics` | TopicsScreen | — |
| `thread/{noteId}` | ModernThreadViewScreen | `replyKind` (1=kind-1, 1111=kind-1111), `highlightReplyId` |
| `profile/{authorId}` | ProfileScreen | `authorId` |
| `notifications` | NotificationsScreen | — |
| `relays` | RelayManagementScreen | — |
| `settings` | SettingsScreen | — |
| `compose` | ComposeNoteScreen | — |
| `reply_compose` | ReplyComposeScreen | `rootId`, `rootPubkey`, `parentId`, `parentPubkey` |
| `image_viewer` | ImageContentViewerScreen | URLs/index via AppViewModel |
| `video_viewer` | VideoContentViewerScreen | URLs/index via AppViewModel |
| `live_stream/{id}` | LiveStreamScreen | addressable event ID |

---

## 2. ViewModel Layer

### AppViewModel (global, Activity-scoped)
- **Purpose:** Cross-screen shared state (selected note, image viewer, media pages)
- **Key state:**
  - `selectedNote: Note?` — legacy single-note slot
  - `notesById: Map<String, Note>` — thread navigation map (supports stacked threads)
  - `imageViewerUrls/videoViewerUrls` — media viewer state
  - `mediaPageByNoteId` — persists album swipe position
  - `replyToNote` — note shown at top of reply compose
  - `threadRelayUrls` — relay URLs for thread opened from topics feed

### AccountStateViewModel (global, Activity-scoped)
- **Purpose:** Authentication, account management, signing operations
- **Key state:**
  - `authState` — login status, user profile, guest mode
  - `currentAccount` — active Nostr keypair (npub/nsec via Amber)
  - `zapInProgressNoteIds`, `zappedNoteIds`, `zappedAmountByNoteId`
- **Key operations:** `loginWithAmber()`, `sendReaction()`, `sendZap()`, `publishThreadReply()`

### DashboardViewModel (scoped to dashboard composable)
- **Purpose:** Feed note management, relay filtering, display state
- **Key state:**
  - `notes: List<Note>` — all fetched notes
  - `displayedNotes: List<Note>` — filtered/sorted for display
  - `urlPreviewsByNoteId` — enrichment data
  - `relayState` — connection status per relay
- **Delegates to:** `NotesRepository` for data, `ContactListRepository` for follow list

### FeedStateViewModel (global)
- **Purpose:** Feed filter/sort preferences
- **Key state:**
  - `isFollowing: Boolean` — All vs Following filter
  - `homeSortOrder: HomeSortOrder` — Latest vs Popular
  - Home scroll position save/restore

### Kind1RepliesViewModel (scoped to NavBackStackEntry)
- **Purpose:** Kind-1 reply threads for home feed notes
- **Key state:** `replies: List<Note>`, `threadedReplies: List<ThreadedReply>`, `totalReplyCount`
- **Delegates to:** `Kind1RepliesRepository`
- **Important:** Each `thread/{noteId}` composable gets its own instance via NavBackStackEntry scoping. The overlay thread shares the dashboard's instance.

### ThreadRepliesViewModel (scoped to NavBackStackEntry)
- **Purpose:** Kind-1111 reply threads for topic notes
- **Same pattern as Kind1RepliesViewModel** but for topic threads

### TopicsViewModel (scoped to topics composable)
- **Purpose:** Topic feed management (kind-11 anchor events)

---

## 3. Repository Layer

### NotesRepository (singleton via DashboardViewModel)
- **Purpose:** Fetch and manage kind-1 notes from relays
- **Data flow:**
  1. WebSocket subscription to relays for kind-1 events
  2. Parse events → `Note` objects with author, content, media, hashtags
  3. Emit to `_notes: MutableStateFlow<List<Note>>`
  4. Profile updates batched via `updateAuthorsInNotesBatch()`
- **Key optimization:** Two-phase approach — notes arrive first, then profiles fill in via `ProfileMetadataCache.profileUpdated` flow

### ContactListRepository
- **Purpose:** Fetch kind-3 (contact list) events for follow filtering
- **Key optimization:**
  - Priority relays: `wss://purplepag.es`, `wss://user.kindpag.es`
  - In-flight deduplication prevents duplicate kind-3 fetches
  - 2-minute cache TTL

### NoteCountsRepository (singleton object)
- **Purpose:** Reply counts, reaction counts, zap totals for notes
- **Data flow:**
  1. **Phase 1** (immediate): Subscribe for kind-1 reply counts (`#e` tag filter)
  2. **Phase 2** (600ms delay): Subscribe for kind-7 reactions + kind-9735 zaps
  3. Newest-first ordering so top-of-feed notes get counts first
- **Key state:** `countsByNoteId: StateFlow<Map<String, NoteCounts>>`
- **Thread awareness:** `setThreadNoteIdsOfInterest()` subscribes counts for root + reply IDs when thread view opens

### Kind1RepliesRepository
- **Purpose:** Fetch kind-1 replies to a specific note
- **Mechanism:** Direct OkHttp WebSocket connections (bypasses Quartz relay pool)
- **Data flow:**
  1. Check `ThreadReplyCache` for cached replies (instant emit)
  2. Open WebSocket to each relay + fallback relays (`relay.damus.io`, `nos.lol`)
  3. Send REQ filter: `kinds=[1], #e=[noteId]`
  4. Parse replies, resolve NIP-10 threading (root/reply markers)
  5. Emit to `_replies: MutableStateFlow<Map<String, List<Note>>>`
  6. Schedule missing parent fetches for incomplete threads

### ProfileMetadataCache (singleton)
- **Purpose:** Cache kind-0 profile metadata (display name, avatar, etc.)
- **Key API:**
  - `getAuthor(pubkey)` — resolve from cache
  - `resolveAuthor(pubkey)` — resolve or return placeholder
  - `requestProfiles(pubkeys, relays)` — fetch uncached profiles
  - `profileUpdated: SharedFlow<String>` — emits pubkey when profile loads/updates
- **Consumers:** NoteCard observes `profileUpdated` directly for live profile rendering

### QuotedNoteCache (singleton)
- **Purpose:** Cache quoted note metadata (content snippet, author) for inline rendering
- **Key state:** `quotedNoteMetas: StateFlow<Map<String, QuotedNoteMeta>>`

### ReplyCountCache (singleton)
- **Purpose:** Fast in-memory cache of reply counts per note ID
- **Used by:** Kind1RepliesViewModel to persist counts across thread opens

---

## 4. UI Component State

### NoteCard
- **Profile observation:** Each NoteCard observes `ProfileMetadataCache.profileUpdated` directly via `LaunchedEffect`. When a profile loads, `profileRevision` increments, triggering `remember(note.author.id, profileRevision)` to re-resolve the author.
- **Quoted note profiles:** Same pattern for quoted note authors (`quotedProfileRevision`)
- **Reaction/zap dropdowns:** When expanded, fetch uncached profiles via `requestProfiles()` and observe `profileUpdated` for live rendering

### AdaptiveHeader
- **Mycelium logo menu:** Dropdown from title with All/Following, Latest/Popular, Topics navigation
- **Topics filter row:** Shown only on Topics screen (All/Following + Latest/Popular chips + favorites star)
- **Engagement filter:** Separate filter icon menu (Most Replies/Likes/Zaps)

### ModernThreadViewScreen
- **Reply loading:** `LaunchedEffect(note.id, relayUrlsKey, replyKind)` — skips when relayUrls empty (waits for resolution), loads when URLs arrive
- **ViewModel selection:** `replyKind == 1` → Kind1RepliesViewModel, else → ThreadRepliesViewModel
- **Sub-thread drill-down:** `rootReplyIdStack` for navigating into nested reply chains
- **Quoted note navigation:** `onNoteClick` callback navigates to `thread/{clickedNote.id}`

---

## 5. Data Flow Diagrams

### Feed Loading Priority Chain

```
1. WebSocket connect to subscribed relays
2. REQ kind-1 notes (main feed)
3. Parse notes → emit to _notes StateFlow
4. Phase 1 counts: REQ kind-1 replies (#e tags) → reply counts
5. Kind-3 follow list fetch (priority relays, 2min cache)
6. Apply Following filter → displayedNotes
7. Phase 2 counts (600ms): REQ kind-7 + kind-9735 → reactions/zaps
8. Profile fetches (kind-0) for visible note authors
9. URL preview enrichment (async)
```

### Profile Update Propagation

```
ProfileMetadataCache.requestProfiles(pubkeys, relays)
  → WebSocket REQ kind-0
  → Parse → store in cache
  → emit pubkey on profileUpdated SharedFlow
  → NoteCard LaunchedEffect filters for matching pubkey
  → profileRevision++ → recomposition with fresh author data
  
NotesRepository also observes profileUpdated:
  → updateAuthorsInNotesBatch() → updates _notes StateFlow
  → updateDisplayedNotes() → feed recomposes
```

### Thread Reply Loading

```
ModernThreadViewScreen opens
  → LaunchedEffect(note.id, relayUrlsKey, replyKind)
  → [if relayUrls empty: skip, wait for re-fire]
  → [if relayUrls present:]
    → kind1RepliesViewModel.loadRepliesForNote(note, relayUrls)
      → Kind1RepliesRepository.fetchRepliesForNote()
        → Check ThreadReplyCache (instant emit if cached)
        → Open WebSocket to relays + fallback relays
        → REQ kind-1, #e=[noteId]
        → Parse replies → emit to _replies StateFlow
        → Kind1RepliesViewModel.observeRepliesFromRepository()
          → organizeRepliesIntoThreads() → threadedReplies
          → UI renders via itemsIndexed(displayList)
```

---

## 6. Relay Health Tracking & Blocking

### RelayHealthTracker (`relay/RelayHealthTracker.kt`)
Singleton that tracks per-relay health metrics across **all** connection paths.

**Metrics tracked per relay:**
- `connectionAttempts` / `connectionFailures` — total counts
- `consecutiveFailures` — resets on success, triggers flagging at threshold (5)
- `eventsReceived` — total events from this relay
- `avgLatencyMs` — rolling average of last 10 connection latencies
- `lastConnectedAt` / `lastFailedAt` — timestamps
- `lastError` — most recent error message

**Flagging:** A relay is automatically flagged when it accumulates 5+ consecutive failures without a successful connection. Flagged relays surface a warning banner in the Relay Management screen.

**Blocking:** Users can block relays from the warning banner. Blocked relays are:
- Persisted via `SharedPreferences` (`relay_health` prefs)
- Excluded from all connection paths via `filterBlocked()`
- Excluded from `RelayConnectionStateMachine.executeUpdateSubscription()`
- Excluded from `Kind1RepliesRepository` direct WebSocket connections

### Integration Points

```
RelayConnectionStateMachine (Quartz NostrClient)
  └─ IRelayClientListener
       ├─ onConnecting → recordConnectionAttempt()
       ├─ onConnected  → recordConnectionSuccess()
       └─ onCannotConnect → recordConnectionFailure()

Kind1RepliesRepository (direct OkHttp WebSocket)
  └─ WebSocketListener
       ├─ onOpen    → recordConnectionSuccess()
       └─ onFailure → recordConnectionFailure()

All connection paths
  └─ filterBlocked(relayUrls) before opening connections
```

### UI: Relay Management Warning Banner
- Appears at top of `RelayManagementScreen` when flagged or blocked relays exist
- Collapsible card showing each troubled relay with:
  - Relay URL, consecutive failure count, last error
  - **Block** button (flagged relays) — adds to persistent blocklist
  - **Dismiss** button (flagged relays) — resets consecutive failures, removes flag
  - **Unblock** button (blocked relays) — removes from blocklist

### Initialization
- `RelayHealthTracker.init(context)` called from `MainActivity.onCreate()` before any relay connections
- Loads persisted blocklist from SharedPreferences

---

## 6b. Outbox Relay Preloading (NIP-65)

### Concept
When a user opens a thread containing a quoted note, we preload the quoted note author's
**write (outbox) relays** from their kind-10002 event. When the user then taps the quoted
note, replies are fetched from the author's outbox relays in addition to the user's own
relays and fallbacks — significantly improving reply discovery.

### Flow

```
ModernThreadViewScreen opens
  → LaunchedEffect(note.id)
    → For each note.quotedEventIds:
      → QuotedNoteCache.getCached(quotedId) → get authorId
      → Nip65RelayListRepository.fetchOutboxRelaysForAuthor(authorId, discoveryRelays)
        → REQ kind-10002 from purplepag.es + user's cache relays
        → Parse "r" tags → extract write relays
        → Cache in authorOutboxCache (LRU, max 100)

User taps quoted note → Kind1RepliesViewModel.loadRepliesForNote()
  → Kind1RepliesRepository.fetchRepliesForNote(noteId, relayUrls, authorPubkey)
    → Nip65RelayListRepository.getCachedOutboxRelays(authorPubkey)
    → Merge: targetRelays + authorOutbox + fallbackRelays
    → filterBlocked() → open WebSocket to each
```

### Key Files
- **`Nip65RelayListRepository.kt`** — `fetchOutboxRelaysForAuthor()`, `getCachedOutboxRelays()`
- **`Kind1RepliesRepository.kt`** — `fetchRepliesForNote(authorPubkey)` enriches relay list
- **`ModernThreadViewScreen.kt`** — `LaunchedEffect` triggers preload on thread open

---

## 7. Known Patterns & Pitfalls

### Relay URL Resolution Timing
- `fallbackRelayUrls` depends on `currentAccount` which is a `StateFlow`
- On first composition, `currentAccount` may be null → empty relay list
- **Fix:** `LaunchedEffect` skips when `relayUrls.isEmpty()`, re-fires when URLs resolve

### ViewModel Scoping
- `viewModel()` in a `composable()` block scopes to that NavBackStackEntry
- Overlay threads share the dashboard's NavBackStackEntry → same ViewModel instance
- Nav-based threads each get their own NavBackStackEntry → fresh ViewModel
- **Implication:** Opening a second thread from the overlay clears the first thread's replies in the shared ViewModel

### Thread Navigation State
- `notesById` map in AppViewModel stores notes for all open threads
- `selectedNote` is legacy single-slot — only used as fallback
- Overlay → nav transition pushes original thread onto nav backstack before quoted thread

### Profile Rendering
- NoteCard observes `profileUpdated` directly (not via NotesRepository)
- This ensures profiles render immediately when they load, even if NotesRepository batch update hasn't run yet
- `profileRevision` counter forces recomposition without changing the note object

---

## 7. File Reference

### ViewModels (`viewmodel/`)
| File | Scope | Purpose |
|------|-------|---------|
| `AppViewModel.kt` | Activity | Cross-screen state, note storage, media viewer |
| `AccountStateViewModel.kt` | Activity | Auth, signing, zaps, reactions |
| `DashboardViewModel.kt` | Dashboard | Feed notes, relay filtering |
| `FeedStateViewModel.kt` | Activity | Feed filter/sort preferences |
| `Kind1RepliesViewModel.kt` | NavBackStackEntry | Kind-1 reply threads |
| `ThreadRepliesViewModel.kt` | NavBackStackEntry | Kind-1111 reply threads |
| `TopicsViewModel.kt` | Topics | Topic feed management |

### Repositories (`repository/`)
| File | Type | Purpose |
|------|------|---------|
| `NotesRepository.kt` | Instance | Kind-1 note fetching, author updates |
| `ContactListRepository.kt` | Instance | Kind-3 follow list |
| `NoteCountsRepository.kt` | Singleton object | Reply/reaction/zap counts |
| `Kind1RepliesRepository.kt` | Instance | Kind-1 reply fetching (direct WS) |
| `ThreadRepliesRepository.kt` | Instance | Kind-1111 reply fetching |
| `ProfileMetadataCache.kt` | Singleton | Kind-0 profile cache + live updates |
| `QuotedNoteCache.kt` | Singleton | Quoted note metadata cache |
| `ReplyCountCache.kt` | Singleton | Reply count cache |
| `RelayRepository.kt` | Instance | Relay connection management |
| `ReactionsRepository.kt` | Instance | Reaction publishing |

### Navigation (`ui/navigation/`)
| File | Purpose |
|------|---------|
| `MyceliumNavigation.kt` | NavHost, all route composables, thread overlay logic |

### Screens (`ui/screens/`)
| File | Purpose |
|------|---------|
| `DashboardScreen.kt` | Home feed with pull-to-refresh, search, filter |
| `ModernThreadViewScreen.kt` | Thread view with threaded replies, zaps, reactions |
| `TopicsScreen.kt` | Topic feed (kind-11 anchors) |
| `ProfileScreen.kt` | User profile with notes/replies tabs |
| `NotificationsScreen.kt` | Notification feed |

### Components (`ui/components/`)
| File | Purpose |
|------|---------|
| `NoteCard.kt` | Feed note card with live profile updates |
| `AdaptiveHeader.kt` | Top app bar with Mycelium logo menu |
| `BottomNavigation.kt` | Bottom nav destinations enum |
| `ScrollAwareBottomNavigation.kt` | Bottom nav bar with scroll-aware visibility |
| `ProfilePicture.kt` | Avatar component |
| `ZapButtonWithMenu.kt` | Zap amount selector |
