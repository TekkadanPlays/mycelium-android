# Mycelium Relay Architecture — Battle Plan

## Current State (What Works)
- WebSocket connections via Cybin/Ktor are functional (wss:// fix applied)
- Feed loads, profiles fetch (466+ cached), note counts update across 32 relays
- URL previews work, feed cache persists across process death
- Profile cache: LRU with 2000 entries, 7-day TTL, disk persistence (1500 max)
- Feed cache: 200 notes persisted to SharedPreferences, restored on cold start
- Debounced event batching: kind-1 events batch at 120ms, display updates at 150ms
- Profile updates coalesced at 80ms before applying to notes list

## CRASH: Onboarding → Feed Transition

### Root Cause
When onboarding completes, `saveRelayConfig()` calls `requestDisconnect()` which tears down
ALL relay connections. Then it navigates to dashboard. The `DashboardScreen` `LaunchedEffect`
at line 237 fires with `onboardingComplete=true` and triggers `loadNotesFromFavoriteCategory()`.
But `relayCategories` may still be empty because `relayViewModel?.loadUserRelays(pubkey)` 
(line 186) is async and hasn't completed yet. The 150ms debounce isn't enough.

**The race condition:**
1. Onboarding saves categories to disk → sets phase=READY → navigates to dashboard
2. Dashboard mounts → `LaunchedEffect(currentAccount)` triggers `loadUserRelays(pubkey)` (async disk read)
3. `LaunchedEffect(isDashboardVisible, ..., relayCategories, ...)` fires BEFORE relayCategories loads
4. `relayCategories.isEmpty()` → returns early → no subscription
5. relayCategories loads → LaunchedEffect re-fires → subscription starts
6. BUT: multiple rapid re-fires of the subscription cause CybinRelayPool to open dozens of
   connections simultaneously → OOM on constrained devices

### Fix
- Gate the subscription LaunchedEffect on `relayCategories.isNotEmpty()` (already done at line 246)
- BUT: add a `hasLoadedRelays` guard that waits for the FIRST successful relay load before subscribing
- The real issue is the `relayViewModel?.loadUserRelays()` completing AFTER the subscription
  LaunchedEffect has already evaluated `relayCategories` as empty

## PROBLEM 1: Ungated Relay Connections

### Current Behavior
`CybinRelayPool` opens connections to ANY relay URL passed to `subscribe()`. There is no
central gate that says "only connect to relays the user has configured." Temporary subscriptions
(NoteCountsRepository, Kind1RepliesRepository, ProfileMetadataCache) can open connections to
arbitrary relay URLs from note metadata.

### What Should Happen
Three relay tiers with strict boundaries:
1. **Outbox relays** (user's NIP-65 write relays) — for publishing events
2. **Inbox relays** (user's NIP-65 read relays) — for receiving DMs/notifications  
3. **Indexer relays** (NIP-66 discovered) — for profile lookups, NIP-65 searches

The main feed subscription should ONLY use relays from the user's configured categories
(which are populated from NIP-65 during onboarding). Temporary subscriptions for profile
fetches should prefer indexer relays. Note counts should use the relays where notes were
actually seen (already implemented correctly).

### Fix
- `RelayConnectionStateMachine` should maintain a whitelist of allowed relay URLs
- `requestTemporarySubscription` should filter relay URLs against the whitelist + indexer list
- Add `setAllowedRelays(outbox: Set<String>, inbox: Set<String>, indexers: Set<String>)` method

## PROBLEM 2: Profile Fetching Strategy

### Current Behavior
`ProfileMetadataCache` fetches kind-0 from whatever relays are passed to it. The caller
(NotesRepository) passes `cacheRelayUrls` which comes from `loadIndexerRelays()`. This is
correct for bulk fetches but:
- Individual profile misses during feed scroll trigger single-pubkey fetches
- These go to indexer relays which may not have the profile
- No outbox relay fallback for individual profiles

### Optimization
- **Tier 1**: Check in-memory LRU cache (instant)
- **Tier 2**: Check disk cache (fast, already implemented)
- **Tier 3**: Batch fetch from indexer relays (current behavior, good)
- **Tier 4**: For cache misses after indexer fetch, try the note's source relay (has the profile)
- Batch size of 80 with 500ms debounce is good — keep it

## PROBLEM 3: Connection Multiplexing

### Current Behavior
Every `subscribe()` call can create new `RelayConnection` objects. Multiple subscriptions to
the same relay reuse the connection (via `getOrCreateConnection`), which is correct. But:
- The polling loop in `subscribe()` (wait 5s for connection) spawns a coroutine per subscription
- If 10 subscriptions target the same relay, 10 coroutines poll `isConnected`
- The 10s cooldown helps but doesn't eliminate the polling waste

### Optimization
- Replace polling with a `CompletableDeferred<Boolean>` that completes when connection succeeds/fails
- Subscriptions await the deferred instead of polling
- This eliminates N coroutines polling the same relay

## PROBLEM 4: Feed Cache Strategy

### Current Behavior
- Feed: 200 notes in SharedPreferences (JSON serialized)
- Profiles: 1500 in SharedPreferences (JSON serialized)
- Both use `Json.encodeToString` which is CPU-intensive for large payloads

### Optimization
- SharedPreferences is fine for this scale (200 notes, 1500 profiles)
- The 2-second debounce on feed save is good
- Consider: move to Room/SQLite only if SharedPreferences becomes a bottleneck (it won't at this scale)
- Ktor connection pooling via OkHttp engine already reuses TCP connections — this is optimal

## PROBLEM 5: Subscription Sprawl

### Current Behavior
The main feed subscription sends 7 filters per relay:
- kind-1 (notes), kind-6 (reposts), kind-11 (topics), kind-1011 (moderation), kind-30311 (live)
- Plus optional kind-7 and kind-9735 for counts

NoteCountsRepository opens a SEPARATE subscription for counts across 32 relays.
Kind1RepliesRepository opens ANOTHER subscription per thread view.
ProfileMetadataCache opens ANOTHER subscription per batch fetch.

### Optimization
- The main feed subscription already includes kind-7/9735 counts — NoteCountsRepository
  should NOT open a separate subscription for the same note IDs on the same relays
- Consolidate: when the main feed subscription already covers counts, skip the separate counts sub
- Thread replies and profile fetches are correctly separate (different relay sets, different lifecycle)

## PRIORITY ACTION ITEMS

### P0 — Fix the Crash (Do Now)
1. In `OnboardingScreen.saveRelayConfig()`: after saving categories, trigger a synchronous
   relay load in the ViewModel before navigating, OR pass the relay URLs directly to the
   dashboard via nav args so it doesn't need to wait for async disk read
2. In `DashboardScreen`: add a `derivedStateOf` that combines `onboardingComplete` AND
   `relayCategories.isNotEmpty()` before allowing subscription

### P1 — Gate Connections (This Session)
1. Add relay whitelist to `RelayConnectionStateMachine`
2. Store outbox/inbox/indexer URLs in a central `RelayRegistry` singleton
3. All subscription calls filter through the registry

### P2 — Eliminate Polling in CybinRelayPool (This Session)
1. Replace `while (!conn.isConnected && attempts < 50)` with `CompletableDeferred`
2. Connection completes the deferred on success/failure
3. Subscriptions `await()` with timeout instead of polling

### P3 — Consolidate Counts Subscription (Next Session)
1. NoteCountsRepository should check if the main feed subscription already covers the relay
2. Only open separate counts subscriptions for relays NOT in the main feed set

### P4 — Profile Fetch Fallback (Next Session)  
1. After indexer relay fetch misses, try the note's source relay
2. Track "last seen relay" per pubkey for targeted fetches

## WHAT KTOR GIVES US
- **Connection pooling**: OkHttp engine reuses TCP connections across HTTP and WebSocket
- **Coroutine-native**: No callback hell, structured concurrency with SupervisorJob
- **Single client**: One `HttpClient` instance for all HTTP + WebSocket = shared connection pool
- **Timeout control**: Per-request timeouts, connect/read/write separately configured
- **No ContentNegotiation overhead**: Removed — all parsing is manual (bodyAsText + Json)

## WHAT MORE CAN WE DO
- **Relay scoring**: Track latency + event yield per relay, prefer fast relays for profile fetches
- **Adaptive subscription**: Start with small `limit`, increase if feed is sparse
- **Compression**: Enable WebSocket permessage-deflate if relays support it (Ktor supports this)
- **Background sync**: WorkManager job to refresh profiles/feed cache while app is backgrounded
- **Relay deduplication**: Normalize URLs (trailing slash, scheme) before connecting — partially done
  but `CybinRelayPool` can still have both `wss://relay.damus.io` and `wss://relay.damus.io/`
