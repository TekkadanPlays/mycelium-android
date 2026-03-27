# Mycelium Android — Relay System Reference

> Last verified against source: v0.5.03 (versionCode 41)
> Line counts verified via `wc -l` on actual source files.

## Overview

The relay system is the backbone of Mycelium. It manages persistent WebSocket connections to Nostr relays, routes events by kind, schedules subscriptions with priority preemption, deduplicates events, tracks relay health, and handles NIP-42 authentication — all while keeping slot usage low enough that relays don't throttle the client.

The stack is layered bottom-to-top:

```
┌──────────────────────────────────────────────────────────────────┐
│  Repositories / ViewModels                                       │
│  (NotesRepository, NotificationsRepository, Kind1RepliesRepo,    │
│   OutboxFeedManager, NoteCountsRepository, ProfileMetadataCache) │
├──────────────────────────────────────────────────────────────────┤
│  RelayConnectionStateMachine (1054 lines)                        │
│  Tinder FSM — owns CybinRelayPool, kind routing, feed lifecycle  │
├──────────────────────────────────────────────────────────────────┤
│  SubscriptionMultiplexer (774 lines)                             │
│  Filter merging, ref-counting, dedup, 50ms debounced REQ flush   │
├──────────────────────────────────────────────────────────────────┤
│  CybinRelayPool (1230 lines)                                     │
│  Per-relay scheduling, 5-tier priority, adaptive limits, reconnect│
├──────────────────────────────────────────────────────────────────┤
│  RelayConnection (Ktor CIO WebSocket per relay URL)              │
│  Separate receive → inbound Channel → parse coroutines           │
├──────────────────────────────────────────────────────────────────┤
│  NostrProtocol (NIP-01 wire format)                              │
│  JSON parsing, EVENT/REQ/CLOSE/OK/EOSE/NOTICE/AUTH framing       │
└──────────────────────────────────────────────────────────────────┘
```

**Supporting components** (same `relay/` package):

| File | Lines | Purpose |
|------|-------|---------|
| `RelayHealthTracker.kt` | 666 | Per-relay health metrics, flagging, auto-blocking, publish tracking |
| `Nip42AuthHandler.kt` | 311 | NIP-42 AUTH challenge interception, kind-22242 signing via Amber |
| `RelayDeliveryTracker.kt` | 310 | Per-event relay delivery confirmation tracking |
| `RelayLogBuffer.kt` | 139 | Circular buffer of recent relay events for the relay log screen |
| `NetworkConnectivityMonitor.kt` | 117 | Android ConnectivityManager callback, triggers reconnect on network regain |

---

## Table of Contents

- [Layer 1: Cybin Transport](#layer-1-cybin-transport)
  - [CybinRelayPool](#cybinrelaypool)
  - [RelayConnection (per-relay)](#relayconnection-per-relay)
  - [NostrProtocol](#nostrprotocol)
  - [SubscriptionPriority](#subscriptionpriority)
- [Layer 2: SubscriptionMultiplexer](#layer-2-subscriptionmultiplexer)
  - [Filter Merging](#filter-merging)
  - [Ref-Counting](#ref-counting)
  - [Deduplication](#deduplication)
  - [Debounced REQ Flush](#debounced-req-flush)
  - [EOSE Tracking](#eose-tracking)
  - [Per-Relay Filter Maps](#per-relay-filter-maps)
- [Layer 3: RelayConnectionStateMachine](#layer-3-relayconnectionstatemachine)
  - [State Machine](#state-machine)
  - [Main Feed Subscription](#main-feed-subscription)
  - [Kind Routing](#kind-routing)
  - [Subscription Types](#subscription-types)
  - [Keepalive](#keepalive)
  - [Resume on Wake](#resume-on-wake)
- [Layer 4: Supporting Components](#layer-4-supporting-components)
  - [RelayHealthTracker](#relayhealthtracker)
  - [Nip42AuthHandler](#nip42authhandler)
  - [RelayDeliveryTracker](#relaydeliverytracker)
  - [NetworkConnectivityMonitor](#networkconnectivitymonitor)
  - [RelayLogBuffer](#relaylogbuffer)
- [Subscription Lifecycle Examples](#subscription-lifecycle-examples)
- [Slot Budget (Typical Home Feed)](#slot-budget-typical-home-feed)
- [Event Flow: WebSocket to Screen](#event-flow-websocket-to-screen)
- [Outbox Model (NIP-65)](#outbox-model-nip-65)
- [Background Connectivity](#background-connectivity)

---

## Layer 1: Cybin Transport

All transport code lives in `cybin/cybin/src/main/java/com/example/cybin/relay/`.

### CybinRelayPool

**File:** `CybinRelayClient.kt` (1230 lines)

Multi-relay WebSocket pool with priority-based subscription scheduling. This is the lowest layer that the application code interacts with.

#### Key Constants (verified from source)

| Constant | Value | Description |
|----------|-------|-------------|
| `MAX_SUBS_PER_RELAY` | **40** | Per-relay concurrent subscription cap. Modern relays (strfry, nostream, khatru) handle 50-100+; 40 gives headroom without queuing churn. |
| `STALE_EOSE_HIGH_MS` | 30,000ms | Stale threshold for HIGH priority subs (feed needs live events) |
| `STALE_EOSE_NORMAL_MS` | 10,000ms | Stale threshold for NORMAL priority subs |
| `STALE_EOSE_LOW_MS` | 3,000ms | Stale threshold for LOW/BACKGROUND priority subs |
| `SESSION_BLACKLIST_MS` | 300,000ms (5 min) | Circuit breaker — blacklist relay after exhausting reconnect attempts |

#### Per-Relay State

Each relay maintains a `RelaySchedulerState`:
- `activeSubs` — Currently active subscription handles
- `queuedReqs` — Pending subscriptions waiting for a slot
- `effectiveLimit` — Current slot cap (starts at `MAX_SUBS_PER_RELAY`, reduced adaptively)
- `eoseReceived` — Set of subscription IDs that have received EOSE

#### Priority Preemption

When a relay is at capacity and a higher-priority subscription arrives:

1. The scheduler looks for the lowest-priority subscription that has already received EOSE
2. If found and the new sub's priority is higher, the old sub is evicted (CLOSE sent)
3. The new sub takes its slot
4. The evicted sub goes back to the queue (and may reactivate later if slots free up)

This ensures CRITICAL subscriptions (settings sync, auth) always get through, even when the relay is saturated with LOW/BACKGROUND work.

#### Adaptive Limit Reduction

When a relay sends `NOTICE "too many concurrent REQs"` or `CLOSED` with a rate-limit reason:
- `effectiveLimit` is reduced (minimum floor: 3)
- This reduction persists for the session
- Prevents wasted round-trips to relays that can't handle 40 concurrent subs

#### EOSE Reaping

Stale EOSE'd subs are periodically reaped to free slots for queued work. A sub is "stale" if it received EOSE more than `STALE_EOSE_*_MS` ago (priority-aware thresholds) and there are higher-priority subs waiting in the queue. One-shot subs bypass this entirely — they auto-close on EOSE.

#### Reconnection Strategy

- **Exponential backoff:** 2s → 30s, 6 attempts per relay
- **Wave-based reconnection:** 8 relays per wave, 600ms stagger between waves
- **Session circuit breaker:** 5-minute blacklist after exhausting all reconnect attempts
- **Jittered backoff:** Random jitter prevents thundering herd when multiple relays fail simultaneously
- **Scoped disconnect:** `disconnectIdleRelays(candidateUrls)` lets the pool drop relays that are no longer needed (e.g., outbox relays for unfollowed users) without killing connections that serve other subscriptions

#### Reserved Slots

A portion of slots are reserved for HIGH+ priority subscriptions. Non-HIGH subs cannot fill the last N reserved slots, ensuring the main feed and critical operations always have room.

### RelayConnection (per-relay)

Each relay URL gets one WebSocket connection managed inside `CybinRelayPool`. The connection architecture:

```
Ktor CIO WebSocket session (native wss://, ping/pong)
    │
    ├── Receive coroutine → Channel.UNLIMITED inbound
    │       │
    │       └── Parse coroutine → NostrProtocol.parse()
    │               │
    │               └── Dispatch to registered callbacks
    │
    └── Send pump → Channel.UNLIMITED outbound
            │
            └── REQ/CLOSE/EVENT frames written to WebSocket
```

Receive and parse run in separate coroutines so WebSocket frame reading never blocks on event processing. The inbound channel is `UNLIMITED` to prevent backpressure from stalling the WebSocket.

### NostrProtocol

**File:** `NostrProtocol.kt`

NIP-01 wire protocol implementation. Handles:
- **Outgoing:** `REQ`, `CLOSE`, `EVENT`, `AUTH` frame construction
- **Incoming:** `EVENT`, `EOSE`, `OK`, `NOTICE`, `CLOSED`, `AUTH` frame parsing

Parsing uses `org.json` (`JSONArray`/`JSONObject`). Each incoming WebSocket text frame is parsed into the appropriate message type and dispatched to the registered `RelayConnectionListener`.

### SubscriptionPriority

Defined in `CybinRelayClient.kt`:

```
CRITICAL(4) — Settings sync, NIP-42 auth
HIGH(3)     — Main feed, follow list, mute list
NORMAL(2)   — Outbox feed, notifications, thread replies
LOW(1)      — Counts, bookmarks, profile fetches
BACKGROUND(0) — DMs, NIP-66 discovery, preloading
```

Priority determines:
1. **Preemption eligibility** — Higher priority can evict lower EOSE'd subs
2. **EOSE stale threshold** — Higher priority subs get more time before being reaped
3. **Reserved slot access** — HIGH+ can use reserved slots

### RelayConnectionListener

Interface implemented by `RelayConnectionStateMachine` to receive relay events:

```
onConnecting(url)
onConnected(url)
onDisconnected(url)
onError(url, message)
onAuth(url, challenge)
onOk(url, eventId, accepted, message)
onEose(url, subscriptionId)
```

Plus per-event callbacks registered when creating subscriptions.

---

## Layer 2: SubscriptionMultiplexer

**File:** `relay/SubscriptionMultiplexer.kt` (774 lines)

Sits between the `CybinRelayPool` and `RelayConnectionStateMachine`. All 48+ subscription callsites in the app route through the multiplexer (either directly or via `RelayConnectionStateMachine`'s temporary subscription methods which delegate internally).

```
UI / Feature
    ↓
SubscriptionMultiplexer (filter merge, ref-count, debounce)
    ↓
CybinRelayPool (per-relay scheduling, priority preemption, reconnect)
    ↓
RelayConnection (Ktor WebSocket)
```

### Filter Merging

When two consumers subscribe with identical filters, the multiplexer shares one relay subscription between them:

```
Consumer A subscribes: Filter(kinds=[1], limit=50, since=T)
Consumer B subscribes: Filter(kinds=[1], limit=50, since=T)

Result: ONE relay REQ with that filter. Events dispatched to both A and B.
```

Filters are compared by a `FilterKey` derived from their normalized content. Identical filters share a single relay-side subscription.

### Ref-Counting

Each merged subscription maintains a consumer reference count:

```
Consumer A subscribes → refCount = 1, REQ sent to relay
Consumer B subscribes (same filter) → refCount = 2, no new REQ
Consumer A unsubscribes → refCount = 1, relay sub stays open
Consumer B unsubscribes → refCount = 0, CLOSE sent to relay
```

The relay subscription is only CLOSEd when the **last** consumer unsubscribes. This prevents wasteful open/close cycles when multiple screens need the same data.

### Deduplication

A bounded LRU set of 10,000 event IDs prevents duplicate event processing across all subscriptions and relays. When the same note arrives from 5 different relays, the callback fires once.

### Debounced REQ Flush

Rapid subscribe/unsubscribe calls are batched with a **50ms debounce**. This prevents relay churn when, for example, a screen initializes and creates 5 subscriptions in quick succession — they're all sent in one batch.

### EOSE Tracking

The multiplexer tracks EOSE (End of Stored Events) per merged subscription, per relay. Consumers can observe when stored events are exhausted and transition to live-only mode.

### Per-Relay Filter Maps

Outbox model subscriptions need different filters per relay (e.g., relay A gets `authors=[alice, bob]` while relay B gets `authors=[carol, dave]`). The multiplexer supports this as a first-class citizen — these subscriptions bypass filter merging since they're inherently per-relay.

### Priority Upgrade

When a new consumer subscribes to an already-merged subscription with a higher priority than the existing one, the merged subscription's priority is upgraded. This ensures that if a BACKGROUND consumer opened a sub and a HIGH consumer later needs the same data, the relay-side sub gets the higher priority for scheduling.

### Account Switch Cleanup

`mux.clear()` is called on account switch. This resets all dedup state, ref-counts, and merged subscriptions to prevent stale data from the previous account leaking.

---

## Layer 3: RelayConnectionStateMachine

**File:** `relay/RelayConnectionStateMachine.kt` (1054 lines)

The central orchestrator. Owns one `CybinRelayPool` instance, drives it with a Tinder StateMachine pattern, and exposes subscription methods to all repositories and ViewModels.

### State Machine

**States:**

```
Disconnected → Connecting → Connected → Subscribed
                                ↓
                          ConnectFailed (message)
```

**Events:**

| Event | Trigger |
|-------|---------|
| `ConnectRequested(relayUrls)` | Initial connect or relay set change |
| `Connected` | At least one relay WebSocket handshake succeeds |
| `ConnectFailed(message)` | All relays failed to connect |
| `RetryRequested` | Retry after backoff timer |
| `FeedChangeRequested(relayUrls, filter, kind1Filter)` | Feed subscription update (filter or relay change) |
| `DisconnectRequested` | User-initiated disconnect or account switch |

**Side Effects:**

| Side Effect | Action |
|-------------|--------|
| `ConnectRelays(urls)` | Open WebSocket connections |
| `OnConnected` | Transition complete, ready for subscriptions |
| `ScheduleRetry` | Start backoff timer (3 attempts, 2s/5s) |
| `UpdateSubscription(urls, filter, kind1Filter)` | Apply or update the main feed subscription |
| `DisconnectClient` | Close all connections and clear state |

### Main Feed Subscription

The primary subscription is owned by `NotesRepository`. It's a single combined REQ sent across all user relays covering:

- **kind-1** — Text notes (with optional `authors` filter for Following mode)
- **kind-6** — Reposts
- **kind-11** — Topics (NIP-22)
- **kind-1011** — Scoped moderation (NIP-22)
- **kind-30311** — Live activities (NIP-53)

Optional count filters (kind-7 reactions + kind-9735 zap receipts) are added for visible notes in Phase 2 of the counts pipeline.

**Idempotent feed change:** `requestFeedChange()` compares the new relay URLs + kind-1 filter + counts note IDs against the current subscription. If nothing changed, the transition is skipped entirely.

**Resume subscription provider:** A lambda from `NotesRepository` ensures that `requestReconnectOnResume()` re-applies the correct Following filter and relay set without reconnecting from scratch.

### Kind Routing

Events from the main feed subscription are routed by kind to registered handlers:

| Kind | Handler | Destination |
|------|---------|-------------|
| 1 | `onKind1WithRelay` | `NotesRepository` (with relay URL attribution) |
| 6 | `onKind6WithRelay` | `NotesRepository` (repost pipeline) |
| 11 | `onKind11` | `TopicsRepository` |
| 1011 | `onKind1011` | `ScopedModerationRepository` |
| 30311 | `onKind30311` | `LiveActivityRepository` |
| 7, 9735 | `onCountsEvent` | `NoteCountsRepository` |

Each handler is registered as a callback. Events are dispatched with O(1) routing by kind.

### Subscription Types

The state machine exposes four subscription methods to the rest of the app:

#### 1. `requestFeedChange()` — Main Feed

Replaces the existing main feed subscription. Only `NotesRepository` should call this for the primary feed. `TopicsRepository` may call it when opening Topics to preserve the kind-1 filter.

#### 2. `requestTemporarySubscription()` — Auxiliary

For long-lived auxiliary subscriptions: thread replies, notifications, profile/contact fetches, bookmark lookups. Multiple overloads:
- Single filter
- Multi-filter
- With relay URL attribution (callback receives relay URL alongside event)
- Per-relay filter map (outbox model)

Returns a `TemporarySubscriptionHandle` with `cancel()`, `pause()`, `resume()`.

#### 3. `requestOneShotSubscription()` — EOSE Auto-Close

For transient queries where you want all stored events and then auto-close. Collects events until all relays send EOSE + a settle window, then auto-CLOSEs. Hard timeout fallback prevents hanging on unresponsive relays.

Used for: bookmarks (kind-10003), mute list (kind-10000), anchor subscriptions (kind-30073), settings sync (kind-30078), emoji packs.

#### 4. `requestTemporarySubscriptionPerRelay()` — Outbox Model

Each relay gets its own unique filter set. Used by:
- `OutboxFeedManager` — Different author sets per relay based on NIP-65 write relay lists
- `NoteCountsRepository` — Per-relay fan-out: note count filters sent only to relays where each note was seen

### Keepalive

The state machine runs a keepalive check:
- **Interval:** Every 2 minutes
- **Stale threshold:** 5 minutes since last event from any relay
- If stale → forced reconnect
- Runs only while the app is in the foreground (or Always On connection mode)

### Resume on Wake

When the app resumes from background:
1. `requestReconnectOnResume()` is called (3s debounce via `NetworkConnectivityMonitor`)
2. The `resumeSubscriptionProvider` lambda re-applies the subscription from `NotesRepository`
3. Following filter and relay set are preserved without a full disconnect/reconnect cycle
4. `perRelayState` is updated for the UI ("3/5 relays connected" display)

### Per-Relay State

`perRelayState: StateFlow<Map<String, RelayEndpointStatus>>` exposes per-relay connection status for the UI. Updates are **batched** with a 100ms debounce flush to prevent StateFlow emissions on every individual relay connection event.

`RelayEndpointStatus`: `Connecting`, `Connected`, `Failed`

---

## Layer 4: Supporting Components

### RelayHealthTracker

**File:** `relay/RelayHealthTracker.kt` (666 lines) · **Pattern:** Singleton

Tracks per-relay health metrics across all connection paths (main feed, direct WebSocket, profile fetches, etc.).

**Recording:** `RelayConnectionStateMachine` registers a `RelayConnectionListener` on `CybinRelayPool` and updates health on `onConnecting` / `onConnected` / `onError`. Do not double-record from repository code that already uses the pool — one WebSocket per normalized relay URL; multiple REQs multiplex on that socket.

#### Metrics Per Relay (`RelayHealthInfo`)

| Field | Type | Description |
|-------|------|-------------|
| `connectionAttempts` | `Int` | Lifetime TCP/WebSocket handshake starts (not parallel socket count) |
| `connectionFailures` | `Int` | Total failures |
| `consecutiveFailures` | `Int` | Failures without a success in between |
| `eventsReceived` | `Long` | Total events from this relay |
| `connectTimeMs` | `Long` | Average WebSocket handshake time (rolling window of 10) |
| `lastConnectedAt` | `Long` | Last successful connection (epoch ms) |
| `lastFailedAt` | `Long` | Last failure (epoch ms) |
| `lastEventAt` | `Long` | Last event received (epoch ms) |
| `firstSeenAt` | `Long` | First time relay was seen |
| `lastError` | `String?` | Last error message |
| `isFlagged` | `Boolean` | Unreliable — consecutive failures exceeded threshold |
| `isBlocked` | `Boolean` | User explicitly blocked this relay |

Computed: `failureRate: Float`, `uptimeRatio: Float`

#### Flagging & Blocking

- **Auto-flagging:** 5 consecutive failures → `isFlagged = true` (UI warning)
- **Auto-blocking:** 5 consecutive failures → 6-hour cooldown (relay excluded from subscriptions)
- **Manual blocking:** User can block relays from the UI. Persisted in SharedPreferences.
- **`filterBlocked(urls)`:** Called before every subscription to remove blocked/cooldown relays

#### Publish Tracking

Tracks per-event publish delivery across relays:

1. `registerPendingPublish(eventId, relayUrls)` — Records expected delivery targets
2. `recordPublishOk(eventId, relayUrl)` — Relay sent OK for this event
3. `finalizePendingPublish(eventId)` — Called after 10s timeout; marks unconfirmed relays as failed
4. `publishReports: StateFlow<List<PublishReport>>` — Last 50 publish results for the UI
5. `publishFailure: SharedFlow<String>` — Emits snackbar messages for failed publishes

### Nip42AuthHandler

**File:** `relay/Nip42AuthHandler.kt` (311 lines)

Intercepts NIP-42 AUTH challenges from relays and handles authentication transparently.

**Flow:**

```
Relay sends AUTH challenge
    → Nip42AuthHandler intercepts (registered as onAuth listener)
    → Signs kind-22242 auth event via Amber (background, no UI for pre-approved kinds)
    → Sends signed auth event back to relay
    → Tracks per-relay auth status
    → On success: renews/re-sends subscription filters
```

**Per-relay auth status:**

```
NONE → CHALLENGED → AUTHENTICATING → AUTHENTICATED
                                   → FAILED
```

Authentication happens in the background without user interaction (Amber pre-approves kind-22242 signing). If auth fails, the relay's subscriptions continue to work but may receive limited data depending on the relay's policy.

### RelayDeliveryTracker

**File:** `relay/RelayDeliveryTracker.kt` (310 lines)

Tracks which relays have confirmed receipt of published events. Used by the publish pipeline to show per-relay delivery status in the UI.

### NetworkConnectivityMonitor

**File:** `relay/NetworkConnectivityMonitor.kt` (117 lines)

Wraps Android's `ConnectivityManager.NetworkCallback`. When the network is regained after a loss:
- Triggers `requestReconnectOnResume()` on the state machine
- **3-second debounce** prevents rapid reconnect attempts during network flapping
- Handles both Wi-Fi and cellular transitions

### RelayLogBuffer

**File:** `relay/RelayLogBuffer.kt` (139 lines)

Circular buffer that stores recent relay events (connections, disconnections, errors, EOSE, events received) for the `RelayLogScreen` debug UI. Bounded to prevent memory growth.

---

## Subscription Lifecycle Examples

### Example 1: Main Feed (kind-1 text notes)

```
1. User opens app, account restored
2. StartupOrchestrator reaches Phase 2 (Feed)
3. NotesRepository calls RelayConnectionStateMachine.requestFeedChange(
       relayUrls = [user's relay list],
       kind1Filter = Filter(kinds=[1], authors=[followList], limit=50)
   )
4. StateMachine transitions: Disconnected → Connecting → Connected → Subscribed
5. CybinRelayPool opens WebSocket to each relay URL
6. One REQ sent per relay: kinds=[1,6,11,1011,30311], authors=[followList]
7. Events arrive → kind routing → NotesRepository.pendingKind1Events
8. 120ms debounce → flushKind1Events() → _notes StateFlow → UI
```

### Example 2: Thread Replies (kind-1111)

```
1. User taps a note → overlay thread opens
2. ThreadRepliesRepository calls requestTemporarySubscription(
       Filter(kinds=[1111], #E=[rootNoteId]),
       priority = CRITICAL,
       relayUrls = [note's relay URLs + user's relays]
   )
3. Multiplexer checks for existing merged sub → none found → new REQ
4. CybinRelayPool sends REQ at CRITICAL priority (preempts if needed)
5. Replies arrive → ThreadRepliesRepository processes them
6. User closes thread → handle.cancel() → multiplexer decrements ref count
7. Ref count = 0 → CLOSE sent to relay → slot freed
```

### Example 3: One-Shot Bookmark Fetch

```
1. StartupOrchestrator reaches Phase 3 (Enrichment)
2. BookmarkRepository calls requestOneShotSubscription(
       Filter(kinds=[10003], authors=[userPubkey], limit=1),
       priority = LOW,
       timeoutMs = 5000
   )
3. Relay sends stored kind-10003 event → callback fires
4. Relay sends EOSE → settle window starts (200ms)
5. All relays EOSE'd + settle elapsed → auto-CLOSE
6. If no EOSE within 5000ms → hard timeout → auto-CLOSE
7. Slot freed immediately for other work
```

### Example 4: Outbox Feed (NIP-65 per-relay)

```
1. DashboardViewModel.startOutboxFeed() → OutboxFeedManager.start()
2. Phase 1: Batch-fetch kind-10002 for all followed users via indexer relays
3. Phase 2: Build relay→authors map:
     wss://relay.damus.io → [alice, bob, carol]
     wss://nos.lol → [bob, dave, eve]
     wss://relay.nostr.band → [carol, frank]
4. Cap at 30 outbox relays, ranked by author count
5. requestTemporarySubscriptionPerRelay(perRelayFilterMap, NORMAL)
6. Each relay gets only its relevant authors filter
7. Events → NotesRepository.pendingKind1Events (same pipeline, dedup by note ID)
```

---

## Slot Budget (Typical Home Feed)

A typical home feed session uses the following relay subscription slots:

| Subscription | Priority | Slots | Lifecycle |
|-------------|----------|-------|-----------|
| Main feed (kind-1,6,11,1011,30311) | HIGH | 1 | Permanent while feed is active |
| Notifications (merged from multiple filters) | LOW | 1 | Permanent while logged in |
| NoteCountsRepository (phase 1 + 2) | LOW | 1 | Replaces on phase change |
| OutboxFeedManager (NIP-65 per-relay) | NORMAL | 1 | Permanent while outbox active |
| Profile batch fetch (kind-0) | LOW | 0-1 | One-shot, auto-closes on EOSE |
| Quoted note resolution | LOW | 0-1 | One-shot per batch |
| Settings sync (kind-30078) | CRITICAL | 0 | One-shot at startup, closes immediately |
| Follow list (kind-3) | HIGH | 0 | One-shot at startup, closes immediately |
| Mute list (kind-10000) | HIGH | 0 | One-shot at startup, closes immediately |

**Steady state:** ~3-4 active slots per relay. One-shot subscriptions contribute briefly then free their slots.

Compare with the `MAX_SUBS_PER_RELAY = 40` cap — there's ample headroom for thread replies, quoted note resolution, and outbox per-relay subs without hitting limits.

When the user opens a thread, that adds 1-2 temporary subscriptions (kind-1111 replies + kind-1 replies). When the thread closes, those slots are freed.

---

## Event Flow: WebSocket to Screen

### Full Path for a Kind-1 Text Note

```
1. WebSocket frame arrives at RelayConnection
   └── Raw text: ["EVENT","sub_abc",{"id":"...","kind":1,...}]

2. Receive coroutine pushes to inbound Channel

3. Parse coroutine: NostrProtocol.parse(text)
   └── JSONArray → extract message type "EVENT" → parse Event object

4. CybinRelayPool dispatches to registered subscription callback
   └── Includes relay URL for attribution

5. RelayConnectionStateMachine kind routing
   └── kind == 1 → onKind1WithRelay callback

6. NotesRepository.onKind1WithRelay(event, relayUrl)
   └── Push to pendingKind1Events (ConcurrentLinkedQueue)
   └── scheduleKind1Flush() — 120ms debounce

7. flushKind1Events() runs (under processEventMutex)
   └── Convert Event → Note (resolve author, parse tags, extract media)
   └── Dedup by note ID
   └── Merge into _notes StateFlow (sorted by timestamp)
   └── Update feed cache (debounced 2s write to SharedPreferences)

8. DashboardViewModel observes notesRepository.notes
   └── Flow collection, no transformation needed

9. DashboardScreen LazyColumn
   └── items(notes, key = { it.id }, contentType = { "note" })
   └── NoteCard(note, ...) renders each item
```

### Profile Resolution (Parallel)

```
Meanwhile, for each note with an unresolved author:

1. NotesRepository.convertEventToNote() creates Author with whatever
   ProfileMetadataCache has (may be just truncated pubkey)

2. ProfileMetadataCache.fetchProfileBatch() runs on a debounce
   └── Batches all pending pubkeys into one kind-0 REQ
   └── Sends via requestOneShotSubscription(priority = LOW)

3. Kind-0 events arrive → cache updated → SharedPreferences persisted

4. profileUpdated: SharedFlow<String> emits the pubkey

5. NoteCard has a LaunchedEffect observing profileUpdated
   └── Filters by relevantPubkeys (author + mentioned) + 200ms debounce
   └── Increments profileRevision counter
   └── Compose sees the state change → recomposes with new author data
```

---

## Outbox Model (NIP-65)

The outbox model ensures notes are fetched from each author's preferred write relays, not just the user's relays. This dramatically improves feed completeness.

### Implementation

**Manager:** `repository/OutboxFeedManager.kt`

**Phase 1 — NIP-65 Fetch:**
1. Follow list loads (kind-3)
2. `OutboxFeedManager.start()` batch-fetches kind-10002 (relay list) for all followed users
3. Uses indexer relays (e.g., relay.nostr.band) for fast resolution
4. Falls back to outbox relays for missing users

**Phase 2 — Per-Relay Subscription:**
1. Build a `Map<String, Set<String>>` of relay URL → author pubkeys
2. Cap at 30 outbox relays, ranked by author count (most-covered relays first)
3. Call `requestTemporarySubscriptionPerRelay()` — each relay gets a filter with only its relevant authors
4. Events flow into the same `NotesRepository` pipeline and are deduped by note ID

### Thompson Sampling

`OutboxFeedManager` uses Thompson Sampling (Bayesian relay ranking) to select which outbox relays to subscribe to. Relays that consistently deliver events get higher scores; relays that time out or deliver nothing get lower scores. This naturally adapts over time without manual configuration.

### Self-Healing

If the outbox feed misses events from certain authors (detected by comparing known follows against received events), the manager falls back to indexer relays for those specific authors. This "self-healing" mechanism ensures no followed user is permanently lost due to a misconfigured or offline outbox relay.

---

## Background Connectivity

Three connection modes, configured in `NotificationPreferences`:

### Always On (`ConnectionMode.ALWAYS_ON`)

- `RelayForegroundService` runs with persistent notification
- WebSockets stay open
- Keepalive checks detect stale connections
- Highest battery usage
- Real-time notifications

### Adaptive (`ConnectionMode.ADAPTIVE`) — Default

- No foreground service
- WebSockets close when app is backgrounded
- `RelayCheckWorker` (WorkManager periodic task) checks inbox relays at configurable intervals (minimum 15 minutes per Android WorkManager constraints)
- Fetches new notifications (DMs, mentions, zaps, replies) since last check
- Moderate battery usage

### When Active (`ConnectionMode.WHEN_ACTIVE`)

- Connections only exist while app is in foreground
- `ON_STOP` → all WebSockets closed, subscriptions paused
- `ON_RESUME` → full reconnect
- Zero background activity
- Lowest battery usage

### Lifecycle Wiring

In `MainActivity`:

```
ON_RESUME:
  → If onboarding complete: requestReconnectOnResume()
  → If ALWAYS_ON: start foreground service + keepalive
  → If ADAPTIVE: start keepalive (foreground only)
  → If WHEN_ACTIVE: start keepalive (foreground only)

ON_STOP:
  → If WHEN_ACTIVE: disconnectAll() + stopKeepalive()
  → Video pause, PiP handling

ON_DESTROY (isFinishing):
  → stopKeepalive()
  → stop foreground service
  → If WHEN_ACTIVE: cancel WorkManager periodic work
```

---

## Appendix: File Inventory

All relay system files with verified line counts:

### Cybin Transport (`cybin/cybin/src/main/java/com/example/cybin/relay/`)

| File | Lines | Description |
|------|-------|-------------|
| `CybinRelayClient.kt` | 1,230 | RelayPool + per-relay scheduling + RelayConnection |
| `NostrProtocol.kt` | — | NIP-01 wire format parser |
| `RelayUrl.kt` | — | URL normalization (`RelayUrlNormalizer`) |

### App Relay Layer (`app/src/main/java/social/mycelium/android/relay/`)

| File | Lines | Description |
|------|-------|-------------|
| `RelayConnectionStateMachine.kt` | 1,054 | Central orchestrator, Tinder FSM, kind routing |
| `SubscriptionMultiplexer.kt` | 774 | Filter merging, ref-counting, dedup, debounce |
| `RelayHealthTracker.kt` | 666 | Per-relay health metrics, flagging, publish tracking |
| `Nip42AuthHandler.kt` | 311 | NIP-42 AUTH challenge handling |
| `RelayDeliveryTracker.kt` | 310 | Per-event relay delivery confirmation |
| `RelayLogBuffer.kt` | 139 | Circular event buffer for debug UI |
| `NetworkConnectivityMonitor.kt` | 117 | Android connectivity callback |

### Related Repositories

| File | Lines | Role in Relay System |
|------|-------|---------------------|
| `NotesRepository.kt` | 2,912 | Main feed owner, kind-1/6 processing, feed cache |
| `NotificationsRepository.kt` | 1,976 | Notification subscription and processing |
| `Nip65RelayListRepository.kt` | 1,020 | NIP-65 relay list management for any user |
| `OutboxFeedManager.kt` | ~500 | NIP-65 outbox-aware feed discovery |
| `NoteCountsRepository.kt` | ~700 | Two-phase reaction/zap count loading |
| `Kind1RepliesRepository.kt` | ~1,000 | Kind-1 thread replies (direct WebSocket path) |
| `ThreadRepliesRepository.kt` | ~600 | Kind-1111 thread replies via relay pool |
| `ProfileMetadataCache.kt` | ~800 | Batched kind-0 profile fetching |
| `StartupOrchestrator.kt` | ~200 | Phased startup coordination |