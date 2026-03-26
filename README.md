# Mycelium

A native Nostr protocol client for Android built with Jetpack Compose and Material Design 3.

Mycelium connects to Nostr relays via WebSocket and delivers decentralized social content — text notes, forum-style topics, threaded discussions, encrypted DMs, live streams, and zap payments — all through user-controlled relay configurations.

## Features

- **Home Feed** — Global and Following filters with infinite scroll, repost deduplication, and outbox-aware relay discovery (NIP-65)
- **Topics** — Forum-style threads using kind-11 root posts and kind-1111 replies (NIP-22)
- **Thread View** — Threaded reply chains with live reply awareness, pull-to-refresh, sort toggle, and chained thread exploration via overlay stacks
- **Notifications** — 10-tab filtered view (All, Unseen, Replies, Threads, Comments, Likes, Zaps, Reposts, Mentions, Highlights) with Android push notifications across 8 channels
- **Direct Messages** — NIP-17 gift-wrapped encrypted DMs with conversation list and modern chat bubbles
- **Live Streams** — NIP-53 live activity discovery and HLS playback with picture-in-picture
- **Relay Management** — Profile-based relay organization, NIP-66 relay discovery, per-relay health tracking with auto-blocking, slot utilization dashboard, and NIP-42 authentication
- **Zaps** — NIP-57 lightning zaps via NIP-47 Wallet Connect or external wallet, with arc amount picker and per-note zap state persistence
- **Embedded Lightning Wallet** — Self-custodial Phoenix-based Lightning node with encrypted seed storage, NIP-47 NWC service provider, bolt11 invoice creation/payment, and real-time channel balance monitoring
- **Publishing** — Full relay selection for all compose flows with NIP-65 inbox/outbox awareness and optimistic local rendering
- **Link Sanitization** — Automatic stripping of tracking parameters (utm, fbclid, gclid, si, igshid, etc.) from all URLs
- **Image Privacy** — EXIF/metadata stripping from JPEG and PNG images before upload without re-encoding
- **Note Scheduling** — Schedule notes and topics for future publication with AlarmManager + WorkManager reliability
- **Blossom Media Upload** — Native BUD-01/02/04 HTTP blob storage with kind-24242 auth events
- **Drafts** — Auto-save and resume drafts for all compose flows with scheduling support and offline retry queue
- **Markdown Compose** — Live syntax highlighting with headings, bold/italic, code blocks, blockquotes, lists, and URL/nostr entity coloring
- **Unicode Text Styles** — 15 decorative Unicode text transformations with strikethrough and underline
- **Profiles** — Tabbed Notes/Replies/Media views with banner, avatar, follow/zap/DM actions
- **Multi-Account** — Switch between Amber (NIP-55 external signer) and nsec accounts

## Install

### GitHub Releases (recommended)

Download the latest APK from [GitHub Releases](https://github.com/TekkadanPlays/mycelium-android/releases).

### Obtainium

Install and auto-update via [Obtainium](https://obtainium.imranr.dev/):

- **GitHub repo URL:** `https://github.com/TekkadanPlays/mycelium-android`
- **Update manifest:** `https://raw.githubusercontent.com/TekkadanPlays/mycelium-android/main/obtanium.json`

### Build from Source

**Requirements:** JDK 11+, Android SDK (compileSdk 36, minSdk 35)

```bash
git clone https://github.com/TekkadanPlays/mycelium-android.git
cd mycelium-android
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

## Architecture

Single-activity MVVM with Jetpack Navigation Compose across two modules:

| Module | Package | Role |
|--------|---------|------|
| `app/` | `social.mycelium.android` | Android application — UI, ViewModels, repositories, services, Room DB |
| `cybin/` | `com.example.cybin` | Nostr protocol library — events, crypto, NIP implementations, Ktor relay client |

Cybin is included via Gradle composite build (`includeBuild`) — changes are reflected immediately.

**Key layers:**

- **Cybin** (28 files) — Event model, filters, secp256k1 signing, NIP-04/19/25/44/47/55/57, Ktor CIO WebSocket relay client with priority scheduler
- **Relay Layer** (7 files) — `RelayConnectionStateMachine`, `SubscriptionMultiplexer` (filter merging + ref-counting + dedup), `RelayHealthTracker`, `Nip42AuthHandler`, `NetworkConnectivityMonitor`
- **Repository Layer** (49 files) — Feed ingestion, thread resolution, notifications, profile metadata, relay lists, bookmarks, moderation, polls, payments, startup orchestration
- **ViewModel Layer** (12 files) — 3 activity-scoped global + 9 screen-scoped ViewModels
- **UI Layer** — 52 screen composables, 59 reusable components, Material Design 3 theming
- **Services** (20 files) — Event publishing, background relay connections, media upload, scheduling, notifications

See [`docs/`](docs/README.md) for comprehensive documentation.

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Kotlin | 2.2.0 | Language |
| Ktor | 3.4.1 | HTTP client + WebSocket (CIO engine) |
| Compose BOM | 2024.12.01 | UI framework (strong skipping mode) |
| Coil | 2.5.0 | Image loading (JPEG, PNG, GIF, SVG, video frames) |
| Media3 | 1.3.1 | Video/livestream playback (ExoPlayer, HLS) |
| secp256k1-kmp | 0.22.0 | Nostr cryptography |
| lightning-kmp | 1.11.5-SNAPSHOT | Embedded Lightning node (ACINQ) |
| bitcoin-kmp | 0.29.0 | Bitcoin primitives (ACINQ) |
| Kotlinx Serialization | 1.7.3 | JSON parsing |
| Room | 2.7.1 | Local database |
| WorkManager | 2.10.0 | Periodic background tasks |
| Jsoup | 1.17.2 | HTML parsing for URL previews |
| Compose RichText | 1.0.0-alpha03 | Markdown rendering (kind-30023) |
| Google ML Kit | — | Language detection + translation |

## NIP Support

NIP-01, NIP-02, NIP-04, NIP-05, NIP-10, NIP-11, NIP-17, NIP-19, NIP-22, NIP-23, NIP-25, NIP-30, NIP-33, NIP-42, NIP-44, NIP-47, NIP-53, NIP-55, NIP-57, NIP-58, NIP-65, NIP-66, NIP-78, NIP-86, NIP-88, NIP-89, NIP-92, NIP-96

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on forking, building, and submitting changes.

For AI agents working in this codebase, see [AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md).

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for release history.

## Credits

Mycelium builds on the work of several open-source projects:

- **[Amethyst / Quartz](https://github.com/vitorpamplona/amethyst)** — Reference Nostr client architecture, outbox relay model, relay hint extraction
- **[NextPlayer](https://github.com/anilbeesetti/nextplayer)** — Video gesture system (seek, volume/brightness, pinch-to-zoom)
- **[TinderStateMachine](https://github.com/nicklashansen/tinder-state-machine)** — Finite state machine for relay connections
- **[nostr.watch](https://nostr.watch)** — NIP-66 relay discovery and monitoring data
- **[relay.tools](https://relay.tools)** — Relay metadata and information service
- **[secp256k1-kmp](https://github.com/nicklashansen/secp256k1-kmp)** — Kotlin Multiplatform secp256k1 cryptography
- **[lightning-kmp](https://github.com/ACINQ/lightning-kmp)** — Embedded Lightning node (Apache 2.0)
- **[Phoenix](https://github.com/ACINQ/phoenix)** — Reference wallet architecture (Apache 2.0)
- **[Ktor](https://ktor.io)** — Kotlin async HTTP and WebSocket engine
- **[Coil](https://coil-kt.github.io/coil/)** — Image loading
- **[Media3 / ExoPlayer](https://developer.android.com/media/media3)** — Video and livestream playback
- **[PureLink-Android](https://github.com/ahmedthebest31/PureLink-Android)** — URL tracking parameter sanitization (MIT)
- **[Prism](https://github.com/hardran3/Prism)** — Reference for scheduling, drafts, image metadata stripping, Blossom upload

## License

MIT — see [LICENSE](LICENSE).

Lightning wallet components from [ACINQ](https://acinq.co/) are licensed under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0). See [NOTICE-ACINQ](NOTICE-ACINQ).
