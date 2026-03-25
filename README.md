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
- **Embedded Lightning Wallet** — Self-custodial Phoenix-based Lightning node using [lightning-kmp](https://github.com/ACINQ/lightning-kmp) with encrypted seed storage, NIP-47 NWC service provider, bolt11 invoice creation/payment, and real-time channel balance monitoring (dev/testnet)
- **Publishing** — Full relay selection screen for all compose flows (notes, topics, replies, comments) with NIP-65 inbox/outbox awareness and optimistic local rendering
- **Link Sanitization** — Automatic stripping of tracking parameters (utm, fbclid, gclid, si, igshid, etc.) from all URLs in both displayed and published content, powered by [PureLink](https://github.com/ahmedthebest31/PureLink-Android)
- **Image Privacy** — Surgical EXIF/metadata stripping from JPEG and PNG images before upload without re-encoding, with optional compression modes
- **Note Scheduling** — Schedule kind-1 notes and kind-11 topics for future publication using AlarmManager with WorkManager reliability, surviving device reboots
- **Blossom Media Upload** — Native Blossom (BUD-01/02/04) HTTP blob storage client with kind-24242 auth events, multi-strategy upload (PUT/POST), and Amethyst-compatible tag ordering
- **Drafts** — Auto-save and resume drafts for all compose flows (notes, topics, replies) with scheduling support and offline retry queue
- **Markdown Compose** — Live syntax highlighting in compose text fields with headings, bold/italic, code blocks, blockquotes, lists, and URL/nostr entity coloring
- **Unicode Text Styles** — 15 decorative Unicode text transformations (serif bold, script, fraktur, monospace, double-struck, circled, small caps, etc.) with strikethrough and underline
- **Profiles** — Tabbed Notes/Replies/Media views, following/follower counts, banner and avatar display, follow/zap/DM actions
- **Multi-Account** — Switch between Amber (NIP-55 external signer) and nsec accounts with per-account onboarding state

## Architecture

Single-activity MVVM with Jetpack Navigation Compose. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full reference.

**Key layers:**
- **Cybin** (`cybin/`, 28 files) — In-repo Nostr protocol library: event model, filters, secp256k1 signing, NIP implementations (NIP-04, NIP-19, NIP-25, NIP-44, NIP-47, NIP-55, NIP-57), Ktor CIO WebSocket relay client with priority-based subscription scheduler
- **Relay Layer** (`relay/`, 7 files) — `RelayConnectionStateMachine` (1,054 lines), `SubscriptionMultiplexer` (774 lines — filter merging, ref-counting, dedup), `RelayHealthTracker` (666 lines), `Nip42AuthHandler`, `RelayDeliveryTracker`, `NetworkConnectivityMonitor`, `RelayLogBuffer`
- **Repository Layer** (`repository/`, 49 files) — Repositories, caches, managers, and services handling feed ingestion, thread resolution, notifications, profile metadata, relay lists, bookmarks, moderation, polls, payments, settings sync, and startup orchestration
- **ViewModel Layer** (`viewmodel/`, 12 files) — 3 activity-scoped global (`AppViewModel`, `AccountStateViewModel`, `FeedStateViewModel`) + 9 screen-scoped
- **UI Layer** (`ui/`) — 52 screen composables, 59 reusable components, Material Design 3 theming

## Prerequisites

- **JDK 11** or later
- **Android SDK** — compileSdk 36, targetSdk 36, minSdk 35 (Android 15+)
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

### GitHub Releases (recommended)

Download the latest APK directly from [GitHub Releases](https://github.com/TekkadanPlays/mycelium-android/releases).

### Obtainium

You can install and stay up to date via [Obtainium](https://obtainium.imranr.dev/) using either:

- **GitHub repo URL (recommended):** `https://github.com/TekkadanPlays/mycelium-android` — add the repo directly and Obtainium will track releases automatically.
- **Update manifest:** `https://raw.githubusercontent.com/TekkadanPlays/mycelium-android/main/obtanium.json`

### Manual APK install

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Ktor | 3.4.1 | HTTP client + WebSocket (CIO engine — no OkHttp) |
| Compose BOM | 2024.12.01 | UI framework (strong skipping mode) |
| Coil | 2.5.0 | Image loading with GIF support |
| Media3 | 1.3.1 | Video/livestream playback (ExoPlayer) |
| secp256k1-kmp | 0.22.0 | Nostr cryptography |
| lightning-kmp | 1.11.5-SNAPSHOT | Embedded Lightning node (ACINQ) |
| bitcoin-kmp | 0.29.0 | Bitcoin primitives (ACINQ) |
| Kotlinx Serialization | 1.7.3 | JSON parsing |
| Room | 2.7.1 | Local database (profiles, NIP-65, NIP-11, events, follow lists, emoji packs) |
| WorkManager | 2.10.0 | Periodic background relay checks (Adaptive connection mode) |
| Jsoup | 1.17.2 | HTML parsing for URL previews |
| Compose RichText | 1.0.0-alpha03 | Markdown rendering for long-form articles (kind 30023) |
| Google ML Kit | — | Language detection + translation |

## NIP Support

NIP-01 (protocol), NIP-02 (contacts), NIP-04 (encryption v1), NIP-05 (DNS identifiers), NIP-10 (reply threading), NIP-11 (relay info), NIP-17 (gift-wrapped DMs), NIP-19 (bech32 entities), NIP-22 (topics/comments), NIP-23 (long-form content), NIP-25 (reactions), NIP-30 (custom emoji), NIP-33 (parameterized replaceable), NIP-42 (relay auth), NIP-44 (encryption v2), NIP-47 (wallet connect), NIP-53 (live activities), NIP-55 (external signer), NIP-57 (zaps), NIP-58 (badges), NIP-65 (relay lists), NIP-66 (relay discovery), NIP-78 (settings sync), NIP-86 (relay management), NIP-88 (polls), NIP-89 (client tags), NIP-92 (imeta), NIP-96 (file storage)

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for release history and [`docs/`](docs/README.md) for comprehensive documentation.

## Credits & Acknowledgments

Mycelium builds on the work of several open-source projects:

- **[Amethyst / Quartz](https://github.com/vitorpamplona/amethyst)** — Reference Nostr client architecture, outbox relay model, relay hint extraction, and expanded relay fetch strategies
- **[NextPlayer](https://github.com/anilbeesetti/nextplayer)** — Video gesture system (seek, volume/brightness, pinch-to-zoom) adapted for Compose
- **[TinderStateMachine](https://github.com/nicklashansen/tinder-state-machine)** — Finite state machine used in relay connection management
- **[nostr.watch](https://nostr.watch)** — NIP-66 relay discovery and monitoring data
- **[relay.tools](https://relay.tools)** — Relay metadata and information service
- **[secp256k1-kmp](https://github.com/nicklashansen/secp256k1-kmp)** — Kotlin Multiplatform secp256k1 cryptography for Nostr event signing
- **[lightning-kmp](https://github.com/ACINQ/lightning-kmp)** — Embedded Lightning node implementation for self-custodial wallet (Apache 2.0)
- **[Phoenix](https://github.com/ACINQ/phoenix)** — Reference architecture for wallet manager, node params, and peer connection patterns (Apache 2.0)
- **[Ktor](https://ktor.io)** — Kotlin async HTTP client and WebSocket engine
- **[Coil](https://coil-kt.github.io/coil/)** — Image loading with GIF and video frame support
- **[Media3 / ExoPlayer](https://developer.android.com/media/media3)** — Video and livestream playback
- **[PureLink-Android](https://github.com/ahmedthebest31/PureLink-Android)** — URL sanitization logic for stripping tracking parameters from links (MIT)
- **[Prism](https://github.com/hardran3/Prism)** — Reference for note scheduling, draft persistence, image metadata stripping, and Blossom media upload patterns (unlicensed — FOSS license requested)

## License

MIT — see [LICENSE](LICENSE).

### Third-Party Licenses

- **lightning-kmp** and **phoenix** by [ACINQ](https://acinq.co/) are licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). See [NOTICE-ACINQ](NOTICE-ACINQ) for the required notice.
