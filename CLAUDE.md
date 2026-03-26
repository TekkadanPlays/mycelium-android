# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **See also:** [`AGENTS.md`](AGENTS.md) for comprehensive agent workflow rules, coding standards, versioning/release process, and project goals. [`CONTRIBUTING.md`](CONTRIBUTING.md) for contributor guidelines. [`docs/`](docs/README.md) for full architecture documentation.

## Project Overview

Mycelium is a Nostr protocol client for Android built with Jetpack Compose and Material Design 3. It handles relay WebSocket connections, event parsing (kinds 0, 1, 3, 6, 7, 11, 1011, 1068, 1111, 6969, 9734, 9735, 10000, 10002, 10003, 14/13/1059, 22242, 24242, 30000, 30023, 30073, 30078, 30166, 30311), thread navigation, profile metadata, push notifications, polls, DMs, live streams, and an embedded Lightning wallet.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires keystore.properties or falls back to debug signing)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk

# Run unit tests
./gradlew test

# Run a single test class
./gradlew testDebugUnitTest --tests "social.mycelium.android.ExampleUnitTest"

# Install on device
adb install app/build/outputs/apk/release/app-release.apk

# Compose compiler metrics (already configured, output at build/compose_metrics/)
./gradlew assembleRelease  # metrics generated alongside release build
```

**Requirements:** JDK 11+, Android SDK API 35+ (compileSdk/targetSdk 36, minSdk 35)

## Architecture

**Single-Activity MVVM** with Jetpack Compose Navigation. `MainActivity` hosts a `NavHost` defined in `MyceliumNavigation.kt`.

### Key Layers

- **ViewModels** (`viewmodel/`, 12 files): Activity-scoped globals (`AppViewModel`, `AccountStateViewModel` [2,427 lines], `FeedStateViewModel`) and NavBackStackEntry-scoped per-screen VMs (`DashboardViewModel`, `Kind1RepliesViewModel`, `ThreadRepliesViewModel`, `TopicsViewModel`, `RelayManagementViewModel`, `SearchViewModel`, `AnnouncementsViewModel`, `AuthViewModel`, `ThreadStateHolder`)
- **Repositories** (`repository/`, 49 files): Mix of singletons and instance-based repos. Includes repositories, caches, managers, API providers, and orchestrators. `NotesRepository` (2,912 lines) is the main feed owner. `NotificationsRepository` (1,976 lines). `Nip65RelayListRepository` (1,020 lines).
- **Relay Layer** (`relay/`, 7 files): `RelayConnectionStateMachine` (1,054 lines) orchestrates connections, `SubscriptionMultiplexer` (774 lines) deduplicates subscriptions with filter merging and ref-counting, `RelayHealthTracker` (666 lines) tracks per-relay metrics and auto-blocks after 5 consecutive failures, `Nip42AuthHandler` (311 lines), `RelayDeliveryTracker` (310 lines), `RelayLogBuffer` (139 lines), `NetworkConnectivityMonitor` (117 lines)
- **Screens** (`ui/screens/`, 52 files): Largest: `ModernThreadViewScreen` (3,257 lines), `RelayManagementScreen` (2,325 lines), `OnboardingScreen` (2,003 lines), `DashboardScreen` (1,954 lines)
- **Components** (`ui/components/`, 59 files): `NoteCard.kt` (3,807 lines) is the core note display component. `ModernNoteCard.kt` (1,158 lines).
- **Services** (`services/`, 20 files): `EventPublisher`, `RelayForegroundService`, `BlossomClient`, `NoteScheduler`, `NotificationChannelManager`, URL preview pipeline, zap/payment services.
- **Data Models** (`data/`, 17 files): `Note`, `Author`, `ThreadReply`, `DirectMessage`, `LiveActivity`, `NotificationData`, relay types, `Draft`, polls, `SyncedSettings`.
- **Database** (`db/`, 13 files): Room with 6 entities (profiles, NIP-65, NIP-11, events, follow lists, emoji packs) and 6 DAOs.
- **Lightning** (`lightning/`, 5 files): Embedded Phoenix wallet — `PhoenixWalletManager`, `NwcServiceProvider`, `SeedManager`.

### Navigation Patterns

Three navigation patterns coexist:
1. **Overlay:** Feed → Thread uses `AnimatedVisibility` overlay (shares dashboard's NavBackStackEntry/ViewModel). 4 independent stacks: home, topics, profile, notifications.
2. **Nav-based:** Thread → Thread, Notifications → Thread uses `navController.navigate("thread/{noteId}")` (each gets its own ViewModel)
3. **Bottom Nav:** Home, DMs, Wallet, Announcements (News), Alerts via `BottomNavDestinations` enum

`MyceliumNavigation.kt` is 5,230 lines and defines all routes, overlay thread infrastructure, bottom nav, snackbar host, and sidebar drawer.

Thread navigation uses `AppViewModel.notesById: Map<String, Note>` to support stacked threads. Legacy `selectedNote` single-slot is fallback only.

### Data Flow Patterns

- **Feed:** WebSocket → `NotesRepository` → `DashboardViewModel` → `DashboardScreen`. Two-phase count loading (kind-1 replies immediately, kind-7/kind-9735 after 600ms delay).
- **Profiles:** `ProfileMetadataCache` emits pubkey on `profileUpdated: SharedFlow`. NoteCard observes this directly via `LaunchedEffect` and increments `profileRevision` to trigger recomposition. Profiles cached in both SharedPreferences and Room.
- **Reply Threading:** `Kind1RepliesRepository` uses direct OkHttp WebSocket connections (bypasses relay pool) + ThreadReplyCache for instant display. `ThreadRepliesRepository` handles kind-1111 via relay pool with CRITICAL priority.
- **Outbox Relays (NIP-65):** `Nip65RelayListRepository` preloads kind-10002 write relays for quoted note authors when thread opens. `OutboxFeedManager` uses Thompson Sampling for Bayesian relay ranking.
- **Startup:** `StartupOrchestrator` coordinates 6 phases: Settings (CRITICAL, concurrent) + User State (HIGH, concurrent) → Feed (HIGH) → Enrichment (NORMAL) → Background (LOW) → Deep History. Phase 0 (settings) runs concurrently with Phase 1 (follow+mute) and does NOT block the feed. The feed waits only on `userStateReady` (Phase 1).

### Important Timing Pitfall

`fallbackRelayUrls` depends on `currentAccount` StateFlow which may be null on first composition. `LaunchedEffect` skips when `relayUrls.isEmpty()` and re-fires when URLs resolve.

## Module Structure

Two modules via Gradle composite build:

1. **`app/`** — Android application (`social.mycelium.android`). All source under `app/src/main/java/social/mycelium/android/`.
2. **`cybin/`** — In-repo Nostr protocol library (`com.example.cybin`, 28 files). Included via `includeBuild("cybin")` with dependency substitution in `settings.gradle.kts`. Changes are picked up immediately — no publish step needed.

### Cybin Library Structure

- **core/** (6 files): `Event`, `EventTemplate`, `Filter`, `TagArrayBuilder`, `Types`, `Utils`
- **crypto/** (4 files): `KeyPair`, `EventHasher`, `Nip04`, `Nip44`
- **nip19/** (4 files): `Bech32`, `Entities`, `Nip19Parser`, `Tlv`
- **nip25/** (1): `ReactionEvent`
- **nip47/** (1): `WalletConnect`
- **nip55/** (6): Amber external signer integration
- **nip57/** (1): `ZapRequest`
- **relay/** (3): `CybinRelayPool` (1,230 lines), `NostrProtocol`, `RelayUrl`
- **signer/** (2): `NostrSigner` (abstract), `NostrSignerInternal`

## Dependencies

- **Kotlin 2.2.0**, **AGP 8.13.0**
- **Ktor 3.4.1:** HTTP client + WebSocket (CIO engine — no OkHttp)
- **Compose BOM 2024.12.01** with strong skipping mode enabled
- **Coil 2.5.0:** Image loading (JPEG, PNG, GIF, SVG, video frames)
- **Media3 1.3.1:** Video/livestream playback (ExoPlayer, HLS)
- **secp256k1-kmp 0.22.0:** Nostr cryptography
- **lightning-kmp 1.11.5-SNAPSHOT + bitcoin-kmp 0.29.0:** Embedded Lightning node (ACINQ)
- **Kotlinx Serialization 1.7.3:** JSON parsing
- **Room 2.7.1 + KSP 2.2.0-2.0.2:** Local database (profiles, NIP-65, NIP-11, events, follow lists, emoji packs)
- **WorkManager 2.10.0:** Periodic background relay checks (Adaptive connection mode)
- **Google ML Kit:** Language detection + translation
- **Jsoup 1.17.2:** HTML parsing for URL previews
- **Compose RichText 1.0.0-alpha03:** Markdown rendering for long-form articles
- **AndroidX Security Crypto 1.1.0-alpha06:** Encrypted seed storage

### OkHttp Status

OkHttp is **not** a direct dependency. All HTTP and WebSocket traffic uses Ktor CIO engine. Coil 2.x brings OkHttp transitively for image loading only.

## Build Configuration Notes

- Debug builds use `.debug` application ID suffix and `WALLET_DEV_MODE = true` (testnet)
- Release builds use ProGuard/R8 with extensive keep rules for Compose, Ktor, serialization, Material Icons, and secp256k1. `WALLET_DEV_MODE = false` (mainnet).
- Parallel builds, build caching, configure-on-demand, and incremental Kotlin compilation enabled in `gradle.properties`
- Jetifier is disabled (all deps are AndroidX)
- Benchmark module exists but is commented out in `settings.gradle.kts` due to AGP version conflict

## Relay System

### CybinRelayPool (transport layer)
- `MAX_SUBS_PER_RELAY = 40` (modern relays handle 50-100+)
- 5-tier priority: CRITICAL(4) > HIGH(3) > NORMAL(2) > LOW(1) > BACKGROUND(0)
- Priority preemption: higher-priority sub evicts lowest-priority EOSE'd sub
- Adaptive limit reduction on NOTICE/CLOSED rate-limit (min 3)
- EOSE reaping: priority-aware thresholds — HIGH 30s, NORMAL 10s, LOW/BG 3s
- Reserved slots for HIGH+ priority
- Reconnect: exponential backoff 2s→30s, 6 attempts, 8/wave with 600ms stagger
- Session circuit breaker: 5min blacklist after exhausting reconnect attempts

### SubscriptionMultiplexer (774 lines)
- Filter merging: identical filters share one relay sub via FilterKey
- Ref-counting: CLOSE only when last consumer unsubscribes
- 50ms debounced REQ flush
- Bounded LRU dedup: 10K event IDs
- Per-relay filter map support (outbox model first-class)
- Account switch cleanup: `mux.clear()` resets all state

### RelayConnectionStateMachine (1,054 lines)
- Tinder StateMachine: Disconnected → Connecting → Connected → Subscribed → ConnectFailed
- Kind routing to registered handlers (kind-1→NotesRepository, kind-6→reposts, kind-11→Topics, etc.)
- 4 subscription types: requestFeedChange (main), requestTemporarySubscription, requestOneShotSubscription (EOSE auto-close), requestTemporarySubscriptionPerRelay (outbox)
- Keepalive: 2min check, 5min stale threshold
- Per-relay state batched with 100ms debounce flush

## Background Service

Three connection modes via `NotificationPreferences.ConnectionMode`:
- **Always On:** `RelayForegroundService` with persistent notification, persistent WebSockets
- **Adaptive** (default): `RelayCheckWorker` via WorkManager periodic task (15min+ intervals)
- **When Active:** Connections only while app is in foreground, zero background activity

## Persistence

- **Room:** Profile cache, NIP-65 relay lists, NIP-11 info, events, follow lists, emoji packs
- **SharedPreferences:** Relay profiles/categories, feed cache (debounced JSON), relay health, blocked relays, accounts, zap amounts, notification prefs, theme settings
- **Encrypted SharedPreferences:** Wallet seed only (`SeedManager`)

## NIP Support

NIP-01, NIP-02, NIP-04, NIP-05, NIP-10, NIP-11, NIP-17, NIP-19, NIP-22, NIP-23, NIP-25, NIP-30, NIP-33, NIP-42, NIP-44, NIP-47, NIP-53, NIP-55, NIP-57, NIP-58, NIP-65, NIP-66, NIP-78, NIP-86, NIP-88, NIP-89, NIP-92, NIP-96