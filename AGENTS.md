# AGENTS.md — Mycelium Android

> **Purpose:** Universal AI agent guidance for any tool (Cursor, Claude Code, Copilot, Windsurf, Cline, etc.) working in this codebase. Read this file first before making any changes.

## Project Identity

**Mycelium** is a native Android Nostr protocol client. It delivers decentralized social content — text notes, forum-style topics, encrypted DMs, live streams, zaps, and more — to users through their chosen relay configurations. The app is built with Jetpack Compose, Material Design 3, and a custom Nostr protocol library (Cybin).

- **Package:** `social.mycelium.android`
- **Repository:** `TekkadanPlays/mycelium-android`
- **License:** MIT (with Apache 2.0 components from ACINQ — see `LICENSE.md`)
- **Current version:** Check `app/build.gradle.kts` → `versionName` / `versionCode`

## Before You Start

### 1. Read the Architecture

| File | When to read |
|------|-------------|
| `CLAUDE.md` | Always — concise architecture summary, build commands, layer inventory |
| `docs/ARCHITECTURE.md` | When working across layers or making structural changes |
| `docs/RELAY_SYSTEM.md` | When touching relay, subscription, or multiplexer code |
| `docs/NAVIGATION.md` | When adding screens, routes, or modifying thread overlay |
| `docs/DATA_MODELS.md` | When working with `Note`, `Author`, relay types, or Room entities |
| `docs/DEVELOPMENT.md` | For build setup, project structure walkthrough |

### 2. Understand the Module Boundary

```
mycelium-android/
├── app/          ← Android application (social.mycelium.android)
├── cybin/        ← Nostr protocol library (com.example.cybin) — composite build
└── docs/         ← Architecture and design documentation
```

- **Cybin** is included via `includeBuild("cybin")` in `settings.gradle.kts` with dependency substitution. Changes are picked up immediately — no publish step needed.
- Cybin handles: events, filters, cryptography, NIP implementations, relay transport.
- App handles: UI, ViewModels, repositories, services, database, navigation.
- **Never move app-layer concerns (Android context, Compose, Room) into Cybin.** Cybin must remain a pure Kotlin/Android library with no UI dependencies.

### 3. Know What NOT to Touch

- `nostr-watch/`, `lightning-kmp/`, `phoenix/`, `better-antigravity/` — external reference repos, gitignored, not part of the build
- `.gradle/`, `build/`, `.kotlin/` — generated directories
- `keystore.properties`, `*.jks`, `*.keystore` — signing credentials (never committed)
- `local.properties` — local SDK paths

## Architecture Quick Reference

### Layer Responsibilities

```
┌─────────────────────────────────────────────┐
│  UI Layer (ui/screens/, ui/components/)      │  Compose screens and reusable components
├─────────────────────────────────────────────┤
│  ViewModel Layer (viewmodel/)                │  State management, UI logic
├─────────────────────────────────────────────┤
│  Repository Layer (repository/)              │  Data orchestration, caching, business logic
├─────────────────────────────────────────────┤
│  Relay Layer (relay/)                        │  Connection management, subscriptions, health
├─────────────────────────────────────────────┤
│  Cybin Library (cybin/)                      │  Nostr protocol: events, crypto, NIPs, transport
├─────────────────────────────────────────────┤
│  Database (db/) + Services (services/)       │  Room persistence, background workers, publishing
└─────────────────────────────────────────────┘
```

### Critical Files (Handle with Care)

These files are large, complex, and interconnected. Changes here have wide blast radius:

| File | Lines | Risk |
|------|-------|------|
| `MyceliumNavigation.kt` | ~5,230 | All routes, overlay system, bottom nav, drawer |
| `NoteCard.kt` | ~3,800 | Core note display — affects every feed |
| `ModernThreadViewScreen.kt` | ~3,250 | Thread rendering, reply chains |
| `NotesRepository.kt` | ~2,900 | Main feed data pipeline |
| `AccountStateViewModel.kt` | ~2,400 | Account state, follows, mutes, settings |
| `RelayManagementScreen.kt` | ~2,300 | Relay configuration UI |
| `NotificationsRepository.kt` | ~1,950 | Notification ingestion and categorization |
| `DashboardScreen.kt` | ~1,950 | Home feed screen |
| `CybinRelayPool.kt` | ~1,230 | Transport-level relay pool with priority scheduler |
| `RelayConnectionStateMachine.kt` | ~1,050 | Relay connection lifecycle |
| `Nip65RelayListRepository.kt` | ~1,020 | Outbox relay resolution |

**Before editing any file over 500 lines:** Read the file (or at least the relevant section) first. Understand the existing patterns before making changes.

### Data Flow Patterns

- **Feed pipeline:** WebSocket → `CybinRelayPool` → `RelayConnectionStateMachine` → kind routing → `NotesRepository` → `DashboardViewModel` → `DashboardScreen`
- **Profile resolution:** `ProfileMetadataCache` emits on `profileUpdated: SharedFlow` → `NoteCard` observes via `LaunchedEffect` → `profileRevision` triggers recomposition
- **Outbox (NIP-65):** `Nip65RelayListRepository` resolves kind-10002 write relays → `OutboxFeedManager` ranks via Thompson Sampling
- **Startup:** `StartupOrchestrator` coordinates 6 phases — settings + user state (concurrent) → feed → enrichment → background → deep history

### Navigation Patterns

Three patterns coexist — do not mix them:

1. **Overlay:** Feed → Thread via `AnimatedVisibility` (shares NavBackStackEntry/ViewModel)
2. **Nav-based:** Thread → Thread, Notifications → Thread via `navController.navigate("thread/{noteId}")`
3. **Bottom Nav:** Home, DMs, Wallet, News, Alerts via `BottomNavDestinations` enum

### Important Timing Pitfall

`fallbackRelayUrls` depends on `currentAccount` StateFlow which may be null on first composition. `LaunchedEffect` skips when `relayUrls.isEmpty()` and re-fires when URLs resolve. Always guard against null/empty relay states.

## Development Workflow

### Building

```bash
# Debug build (local testing)
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Install on connected device
adb install app/build/outputs/apk/release/app-release.apk
```

**Requirements:** JDK 11+, Android SDK with compileSdk 36, minSdk 35

### Testing Protocol

1. **Build verification:** `./gradlew assembleDebug` must succeed with no errors
2. **Local device testing:** Install and manually verify the change on a physical Android device
3. **Regression check:** Verify feed loading, thread navigation, and relay connections still work
4. **No automated UI tests exist yet** — manual verification is the standard

### Versioning and Release Process

Releases are **owner-initiated only** after sufficient manual testing:

1. **Version bump:** Update `versionCode` (integer, always increment) and `versionName` in `app/build.gradle.kts`
2. **Version format:** `MAJOR.MINOR.PATCH` with `-beta` suffix (e.g., `v0.5.11-beta`)
   - `MAJOR` = fundamental architecture changes
   - `MINOR` = feature batches (01-11 within a minor, then bump minor)
   - `PATCH` = within the minor series, incremented per release
3. **Tag:** `git tag v{versionName}-beta` (e.g., `v0.5.11-beta`)
4. **Push tag:** `git push origin v{versionName}-beta` triggers GitHub Actions release workflow
5. **GitHub Actions** builds release APK and creates a GitHub Release automatically
6. **Obtanium manifest:** Update `obtanium.json` with new version entry

**Agents must NOT:**
- Bump versions or create tags without explicit owner request
- Push to remote without explicit owner request
- Modify the release workflow without explicit owner request

### Commit Style

Follow the existing commit message conventions visible in `git log`:

```
feat: description of new feature
fix: description of bug fix
chore: version bumps, manifest updates, dependency changes
refactor: code restructuring without behavior change
release: v{version} — summary of changes
```

Multi-line bodies are encouraged for complex changes. Reference NIPs when relevant (e.g., "feat: NIP-51 relay sync").

## Coding Standards

### Kotlin / Android

- **Kotlin 2.2.0** with JVM target 11
- **Compose** with strong skipping mode enabled — leverage `@Stable` / `@Immutable` where it matters
- **Coroutines** for all async work — no callbacks, no `runBlocking` on main thread
- **Kotlinx Serialization** for JSON — not Gson, not Moshi
- **Ktor CIO** for all HTTP/WebSocket — not OkHttp (OkHttp is only present transitively via Coil)

### Code Organization Principles

1. **Single responsibility:** Each repository handles one concern (notes, profiles, relays, notifications, etc.)
2. **Dependency direction:** UI → ViewModel → Repository → Relay/DB. Never reverse.
3. **State management:** `StateFlow` and `SharedFlow` for reactive state. `MutableState` only in Composables.
4. **Error handling:** Relay failures are expected — use `RelayHealthTracker` scoring, not crash-on-error.
5. **Resource cleanup:** Always clean up WebSocket subscriptions. Use `SubscriptionMultiplexer` ref-counting.

### Modularity Requirements

This project should remain forkable and modular:

- **Cybin** is independently publishable — maintain clean API surface
- **Feature-specific code** should stay in its own files/packages — don't merge unrelated features into mega-files
- **Avoid circular dependencies** between repositories
- **Prefer composition over inheritance** in data models and UI components
- **Keep NIP implementations isolated** — each NIP's logic should be locatable by searching for its kind numbers

### What to Avoid

- Adding new direct dependencies without explicit owner approval
- Introducing OkHttp usage (use Ktor CIO)
- Creating files over 1,000 lines without splitting — the project already has too many large files
- Adding comments that just narrate code (e.g., `// increment the counter`) — only explain non-obvious intent
- Hardcoding relay URLs — relay configuration is user-controlled
- Blocking the main thread — all relay/network operations must be suspending or on `Dispatchers.IO`

## Efficient Agent Practices

### Minimize Token Usage

1. **Read CLAUDE.md first** — it has a compressed architecture summary
2. **Use targeted reads** — don't read entire large files. Use search tools to find the specific section.
3. **Check docs/ before exploring code** — the documentation often answers structural questions
4. **Search before creating** — check if a utility, pattern, or component already exists

### Making Changes

1. **Read before editing** — always read the target file (or relevant section) before modifying
2. **Understand blast radius** — changes to shared components (NoteCard, NotesRepository, MyceliumNavigation) affect many screens
3. **Preserve existing patterns** — when adding features, follow the patterns already established in similar code
4. **Incremental changes** — prefer small, focused changes over large rewrites
5. **Lint check** — verify no linter errors after substantive edits

### When Working on Relay Code

- Read `docs/RELAY_SYSTEM.md` for the full relay stack documentation
- The relay system has 5 priority tiers: CRITICAL(4) > HIGH(3) > NORMAL(2) > LOW(1) > BACKGROUND(0)
- `SubscriptionMultiplexer` deduplicates subscriptions via filter merging and ref-counting
- Relay health tracking auto-blocks after 5 consecutive failures
- Reconnect uses exponential backoff (2s→30s, 6 attempts)

### When Working on UI

- All screens use Material Design 3 theming
- `NoteCard.kt` is the universal note display component — changes here affect all feeds
- Thread navigation has three distinct patterns (see Navigation Patterns above)
- Image loading uses Coil 2.x — follow existing patterns for `AsyncImage`
- Video playback uses Media3/ExoPlayer

### When Working on the Feed

- `NotesRepository` owns the feed state
- `DashboardViewModel` bridges repository to UI
- Two-phase count loading: kind-1 replies immediately, kind-7/kind-9735 after 600ms delay
- Feed uses `LazyColumn` with keys — always provide stable keys for list items

## Documentation Maintenance

When making significant changes, update the relevant documentation:

| Change type | Update |
|------------|--------|
| New screen or route | `docs/NAVIGATION.md` |
| New data model or entity | `docs/DATA_MODELS.md` |
| Relay system changes | `docs/RELAY_SYSTEM.md` |
| Architecture changes | `docs/ARCHITECTURE.md`, `CLAUDE.md` |
| New feature | `CHANGELOG.md` (append to unreleased section) |
| New NIP support | `CLAUDE.md` NIP list, `README.md` NIP list |
| New dependency | `CLAUDE.md` dependencies section, `README.md` table |
| Build/config changes | `docs/DEVELOPMENT.md`, `CLAUDE.md` build section |

**Keep `CLAUDE.md` in sync** — it's the primary entry point for AI agents. When architecture changes, update it.

## File Index for Quick Navigation

### Entry Points
- `MainActivity.kt` — single activity host
- `ui/navigation/MyceliumNavigation.kt` — all routes and nav infrastructure

### Core Data Pipeline
- `cybin/relay/CybinRelayPool.kt` — transport layer
- `relay/RelayConnectionStateMachine.kt` — connection lifecycle
- `relay/SubscriptionMultiplexer.kt` — filter merging and dedup
- `repository/NotesRepository.kt` — feed state owner
- `viewmodel/DashboardViewModel.kt` → `ui/screens/DashboardScreen.kt`

### Key Repositories
- `repository/ProfileMetadataCache.kt` — profile resolution
- `repository/Nip65RelayListRepository.kt` — outbox relay discovery
- `repository/NotificationsRepository.kt` — notification categorization
- `repository/DirectMessageRepository.kt` — NIP-17 DMs
- `repository/StartupOrchestrator.kt` — boot sequence coordination

### Services
- `services/EventPublisher.kt` — event signing and relay delivery
- `services/RelayForegroundService.kt` — persistent relay connections
- `services/RelayCheckWorker.kt` — periodic background checks
- `services/BlossomClient.kt` — media upload (BUD-01/02/04)

### Database
- `db/AppDatabase.kt` — Room database definition
- `db/` — entities and DAOs for profiles, NIP-65, NIP-11, events, follow lists, emoji packs

## Project Goals

The overarching goal is to deliver Nostr content to users in the most **optimal**, **efficient**, and **flexible** manner:

1. **Respect user relay choices** — relay configuration is sovereign. Never override or ignore user preferences.
2. **Efficient data fetching** — use outbox model, filter merging, subscription dedup, and priority scheduling to minimize redundant relay traffic.
3. **Graceful degradation** — relay failures are normal. The app must remain functional when individual relays go down.
4. **Battery and bandwidth awareness** — background connections have three modes (Always On, Adaptive, When Active). Respect the user's choice.
5. **Modularity** — keep the codebase forkable. Others should be able to take Mycelium and build their own Nostr experience.
