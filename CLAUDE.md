# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Mycelium is a Nostr protocol client for Android built with Jetpack Compose and Material Design 3. It handles relay WebSocket connections, event parsing (kinds 0, 1, 3, 1111, 10002, etc.), thread navigation, profile metadata, and push notifications.

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

- **ViewModels** (`viewmodel/`): Activity-scoped globals (`AppViewModel`, `AccountStateViewModel`, `FeedStateViewModel`) and NavBackStackEntry-scoped per-screen VMs (`DashboardViewModel`, `Kind1RepliesViewModel`, `ThreadRepliesViewModel`, `TopicsViewModel`)
- **Repositories** (`repository/`): ~31 specialized data classes. Mix of singletons (`NoteCountsRepository`, `ProfileMetadataCache`, `QuotedNoteCache`) and instance-based repos. `NotesRepository` is the largest (~100KB).
- **Relay Layer** (`relay/`): `RelayConnectionStateMachine` orchestrates connections, `SubscriptionMultiplexer` deduplicates subscriptions, `RelayHealthTracker` tracks per-relay metrics and auto-flags after 5 consecutive failures
- **Screens** (`ui/screens/`): ~45 composable screens. Largest: `ModernThreadViewScreen` (thread view), `DashboardScreen` (home feed), `RelayManagementScreen`
- **Components** (`ui/components/`): ~30+ reusable composables. `NoteCard.kt` (133KB) is the core note display component.

### Navigation Patterns

Three navigation patterns coexist:
1. **Overlay:** Feed → Thread uses `AnimatedVisibility` overlay (shares dashboard's NavBackStackEntry/ViewModel)
2. **Nav-based:** Thread → Thread, Notifications → Thread uses `navController.navigate("thread/{noteId}")` (each gets its own ViewModel)
3. **Bottom Nav:** Home, DMs, Live, Relays, Alerts via `BottomNavDestinations` enum

Thread navigation uses `AppViewModel.notesById: Map<String, Note>` to support stacked threads. Legacy `selectedNote` single-slot is fallback only.

### Data Flow Patterns

- **Feed:** WebSocket → `NotesRepository` → `DashboardViewModel` → `DashboardScreen`. Two-phase count loading (kind-1 replies immediately, kind-7/kind-9735 after 600ms delay).
- **Profiles:** `ProfileMetadataCache` emits pubkey on `profileUpdated: SharedFlow`. NoteCard observes this directly via `LaunchedEffect` and increments `profileRevision` to trigger recomposition.
- **Reply Threading:** `Kind1RepliesRepository` uses direct OkHttp WebSocket connections (bypasses relay pool) with fallback relays. Checks `ThreadReplyCache` first for instant display.
- **Outbox Relays (NIP-65):** `Nip65RelayListRepository` preloads kind-10002 write relays for quoted note authors when thread opens. Merged with target relays when user taps quoted note.

### Important Timing Pitfall

`fallbackRelayUrls` depends on `currentAccount` StateFlow which may be null on first composition. `LaunchedEffect` skips when `relayUrls.isEmpty()` and re-fires when URLs resolve.

## Dependencies

- **Cybin** (`cybin/` submodule): Local Nostr protocol library, included via Gradle dependency substitution in `settings.gradle.kts`
- **Ktor 3.0.0:** HTTP client + WebSocket (OkHttp + CIO engines)
- **Compose BOM 2024.12.01** with strong skipping mode enabled
- **Coil 2.5.0:** Image loading
- **Media3 1.3.1:** Video playback
- **Google ML Kit:** Language detection + translation
- **Kotlinx Serialization 1.7.3:** JSON parsing

## Build Configuration Notes

- Debug builds use `.debug` application ID suffix
- Release builds use ProGuard with extensive keep rules for Compose, Ktor, serialization, and Material Icons
- Parallel builds, build caching, and incremental Kotlin compilation enabled in `gradle.properties`
- Jetifier is disabled (all deps are AndroidX)
- Benchmark module exists but is commented out due to AGP version conflict

## Background Service

`RelayForegroundService` maintains persistent WebSocket connections when the app is backgrounded. Declared as `specialUse` foreground service type.
