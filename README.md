# Mycelium (Android)

A native Nostr protocol client for Android built with Jetpack Compose and Material Design 3. Mycelium connects to Nostr relays via WebSocket, renders feeds of text notes (kind-1), forum-style topics (kind-11/1111), live activities (NIP-53), and encrypted direct messages (NIP-17) — all with full relay management, push notifications, zap payments, and multi-account support.

## Features

- **Home Feed** — Global and Following filters with infinite scroll, repost deduplication, and outbox-aware relay discovery (NIP-65)
- **Topics** — Forum-style threads using kind-11 root posts and kind-1111 replies (NIP-22)
- **Thread View** — Threaded reply chains with live reply awareness, pull-to-refresh, sort toggle, and chained thread exploration via overlay stacks
- **Notifications** — 10-tab filtered view (All, Unseen, Replies, Threads, Comments, Likes, Zaps, Reposts, Mentions, Highlights) with Android push notifications across 8 channels
- **Direct Messages** — NIP-17 gift-wrapped encrypted DMs with conversation list and modern chat bubbles
- **Live Streams** — NIP-53 live activity discovery and HLS playback with picture-in-picture
- **Relay Management** — Profile-based relay organization, NIP-66 relay discovery, per-relay health tracking with auto-blocking, slot utilization dashboard, and NIP-42 authentication
- **Zaps** — NIP-57 lightning zaps via NIP-47 Wallet Connect or external wallet, with arc amount picker and per-note zap state persistence
- **Publishing** — Full relay selection screen for all compose flows (notes, topics, replies, comments) with NIP-65 inbox/outbox awareness and optimistic local rendering
- **Link Sanitization** — Automatic stripping of tracking parameters (utm, fbclid, gclid, si, igshid, etc.) from all URLs in both displayed and published content, powered by [PureLink](https://github.com/ahmedthebest31/PureLink-Android)
- **Profiles** — Tabbed Notes/Replies/Media views, following/follower counts, banner and avatar display, follow/zap/DM actions
- **Multi-Account** — Switch between Amber (NIP-55 external signer) and nsec accounts with per-account onboarding state

## Architecture

Single-activity MVVM with Jetpack Navigation Compose. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full reference.

**Key layers:**
- **Cybin** (`cybin/`) — In-repo Nostr protocol library: event model, filters, secp256k1 signing, NIP implementations, Ktor WebSocket relay client with priority-based subscription scheduler
- **Relay Layer** (`relay/`) — `RelayConnectionStateMachine`, `SubscriptionMultiplexer` (filter merging, ref-counting, dedup), `RelayHealthTracker`, `Nip42AuthHandler`, `NetworkConnectivityMonitor`
- **Repository Layer** (`repository/`) — 33 specialized data repositories handling feed ingestion, thread resolution, notifications, profile metadata, relay lists, bookmarks, moderation, and payments
- **ViewModel Layer** (`viewmodel/`) — 11 ViewModels (3 activity-scoped global, 8 screen-scoped)
- **UI Layer** (`ui/`) — 44 screen composables, 42 reusable components, Material Design 3 theming

## Prerequisites

- **JDK 11** or later
- **Android SDK** (API 35+, compileSdk/targetSdk 36, minSdk 35)
- Android Studio or command-line build tools

## Build

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires keystore.properties or falls back to debug signing)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk

# Run unit tests
./gradlew test
```

## Install

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

## Obtainium

You can install or update Mycelium via [Obtainium](https://obtainium.imranr.dev/) using the app's update manifest:

- **Raw URL:** `https://raw.githubusercontent.com/TekkadanPlays/mycelium-android/main/obtanium.json`

Add this URL in Obtainium to get release updates and download the APK from GitHub Releases.

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Ktor | 3.0.0 | HTTP client + WebSocket (OkHttp engine) |
| Compose BOM | 2024.12.01 | UI framework (strong skipping mode) |
| Coil | 2.5.0 | Image loading with GIF support |
| Media3 | 1.3.1 | Video/livestream playback (ExoPlayer) |
| secp256k1-kmp | 0.20.0 | Nostr cryptography |
| Kotlinx Serialization | 1.7.3 | JSON parsing |
| Google ML Kit | — | Language detection + translation |

## NIP Support

NIP-01 (protocol), NIP-02 (contacts), NIP-04 (encryption), NIP-10 (reply threading), NIP-11 (relay info), NIP-17 (gift-wrapped DMs), NIP-19 (bech32 entities), NIP-22 (topics), NIP-25 (reactions), NIP-42 (relay auth), NIP-44 (encryption v2), NIP-47 (wallet connect), NIP-53 (live activities), NIP-55 (external signer), NIP-57 (zaps), NIP-65 (relay lists), NIP-66 (relay discovery), NIP-86 (relay management), NIP-89 (client tags)

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for release history.

## Credits & Acknowledgments

Mycelium builds on the work of several open-source projects:

- **[Amethyst / Quartz](https://github.com/vitorpamplona/amethyst)** — Reference Nostr client architecture, outbox relay model, relay hint extraction, and expanded relay fetch strategies
- **[NextPlayer](https://github.com/anilbeesetti/nextplayer)** — Video gesture system (seek, volume/brightness, pinch-to-zoom) adapted for Compose
- **[TinderStateMachine](https://github.com/nicklashansen/tinder-state-machine)** — Finite state machine used in relay connection management
- **[nostr.watch](https://nostr.watch)** — NIP-66 relay discovery and monitoring data
- **[relay.tools](https://relay.tools)** — Relay metadata and information service
- **[secp256k1-kmp](https://github.com/nicklashansen/secp256k1-kmp)** — Kotlin Multiplatform secp256k1 cryptography for Nostr event signing
- **[Ktor](https://ktor.io)** — Kotlin async HTTP client and WebSocket engine
- **[Coil](https://coil-kt.github.io/coil/)** — Image loading with GIF and video frame support
- **[Media3 / ExoPlayer](https://developer.android.com/media/media3)** — Video and livestream playback
- **[PureLink-Android](https://github.com/ahmedthebest31/PureLink-Android)** — URL sanitization logic for stripping tracking parameters from links (MIT)

## License

MIT — see [LICENSE](LICENSE).
