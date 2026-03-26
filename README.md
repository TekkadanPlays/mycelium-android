# Mycelium

A native Android Nostr client built with Jetpack Compose and Material Design 3. Mycelium connects to relays via WebSocket and delivers decentralized social content through user-controlled relay configurations.

## Table of Contents

- [Install](#install)
- [Features](#features)
- [NIP Support](#nip-support)
- [Architecture](#architecture)
- [Build from Source](#build-from-source)
- [Dependencies](#dependencies)
- [Contributing](#contributing)
- [Credits](#credits)
- [License](#license)

## Install

### GitHub Releases

Download the latest APK from [GitHub Releases](https://github.com/TekkadanPlays/mycelium-android/releases).

### Obtainium

Auto-update via [Obtainium](https://obtainium.imranr.dev/) using the repo URL:

```
https://github.com/TekkadanPlays/mycelium-android
```

Or the update manifest:

```
https://raw.githubusercontent.com/TekkadanPlays/mycelium-android/main/obtanium.json
```

## Features

### Feed and Content
- Home feed with global and following filters, infinite scroll, and repost deduplication
- Forum-style topics with threaded reply chains
- Thread overlay stacks for chained exploration without losing scroll position
- Long-form article rendering with markdown support
- Live activity discovery and HLS playback with picture-in-picture

### Relay Management
- Profile-based relay organization with per-relay health tracking and auto-blocking
- Slot utilization dashboard and relay discovery
- Three background connection modes: Always On, Adaptive, and When Active

### Messaging and Payments
- Encrypted direct messages with conversation list
- Lightning zaps via Wallet Connect or external wallet
- Embedded self-custodial Lightning wallet with encrypted seed storage and NWC service

### Publishing
- Relay selection for all compose flows with inbox/outbox awareness
- Auto-save drafts, note scheduling, and offline retry queue
- Blossom media upload with auth events
- Automatic URL tracking parameter sanitization and EXIF metadata stripping
- Live markdown syntax highlighting and decorative Unicode text styles

### Profiles and Accounts
- Tabbed Notes/Replies/Media views with collapsible profile header
- Multi-account support with Amber (external signer) and nsec login

### Notifications
- 10-tab filtered view with Android push notifications across 8 channels
- Background DM relay checks alongside standard inbox polling

## NIP Support

Mycelium implements the following [Nostr Implementation Possibilities](https://github.com/nostr-protocol/nips):

| NIP | Name | Usage in Mycelium |
|-----|------|-------------------|
| [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md) | Basic Protocol | Event model, relay communication, subscription filters |
| [NIP-02](https://github.com/nostr-protocol/nips/blob/master/02.md) | Follow List | Kind-3 contact lists for Following feed filter |
| [NIP-04](https://github.com/nostr-protocol/nips/blob/master/04.md) | Encrypted DM (v1) | Legacy DM decryption (superseded by NIP-17) |
| [NIP-05](https://github.com/nostr-protocol/nips/blob/master/05.md) | DNS Identifiers | NIP-05 verification badge on profiles |
| [NIP-10](https://github.com/nostr-protocol/nips/blob/master/10.md) | Reply Threading | `e`/`E` tag parsing for root/reply chain resolution |
| [NIP-11](https://github.com/nostr-protocol/nips/blob/master/11.md) | Relay Info | Relay metadata display, icon, payment/auth detection |
| [NIP-17](https://github.com/nostr-protocol/nips/blob/master/17.md) | Private DMs | Gift-wrapped encrypted DMs with dedicated DM relay routing |
| [NIP-19](https://github.com/nostr-protocol/nips/blob/master/19.md) | Bech32 Entities | `npub`/`nsec`/`note`/`nevent`/`nprofile` encoding and inline rendering |
| [NIP-22](https://github.com/nostr-protocol/nips/blob/master/22.md) | Comments | Kind-11 topics and kind-1111 threaded comments |
| [NIP-23](https://github.com/nostr-protocol/nips/blob/master/23.md) | Long-Form Content | Kind-30023 article rendering with markdown |
| [NIP-25](https://github.com/nostr-protocol/nips/blob/master/25.md) | Reactions | Kind-7 reactions with custom emoji support |
| [NIP-30](https://github.com/nostr-protocol/nips/blob/master/30.md) | Custom Emoji | Emoji pack rendering in reactions and note content |
| [NIP-33](https://github.com/nostr-protocol/nips/blob/master/33.md) | Parameterized Replaceable | Addressable events for relay sets, settings, articles |
| [NIP-42](https://github.com/nostr-protocol/nips/blob/master/42.md) | Relay Authentication | AUTH challenge-response for restricted relays |
| [NIP-44](https://github.com/nostr-protocol/nips/blob/master/44.md) | Encrypted Payloads (v2) | Modern encryption for NIP-17 gift wraps |
| [NIP-47](https://github.com/nostr-protocol/nips/blob/master/47.md) | Wallet Connect | NWC for zap payments and embedded wallet NWC service |
| [NIP-53](https://github.com/nostr-protocol/nips/blob/master/53.md) | Live Activities | Live stream discovery and HLS playback |
| [NIP-55](https://github.com/nostr-protocol/nips/blob/master/55.md) | Android Signer | Amber external signer integration for key management |
| [NIP-57](https://github.com/nostr-protocol/nips/blob/master/57.md) | Zaps | Lightning zap requests and receipt display |
| [NIP-58](https://github.com/nostr-protocol/nips/blob/master/58.md) | Badges | Badge display on profiles |
| [NIP-65](https://github.com/nostr-protocol/nips/blob/master/65.md) | Relay Lists | Kind-10002 inbox/outbox relay discovery and outbox feed routing |
| [NIP-66](https://github.com/nostr-protocol/nips/blob/master/66.md) | Relay Discovery | Relay monitor data for onboarding and relay discovery |
| [NIP-78](https://github.com/nostr-protocol/nips/blob/master/78.md) | App-Specific Data | Kind-30078 synced settings across clients |
| [NIP-86](https://github.com/nostr-protocol/nips/blob/master/86.md) | Relay Management | Relay management API support |
| [NIP-88](https://github.com/nostr-protocol/nips/blob/master/88.md) | Polls | Poll creation, voting, and result display |
| [NIP-89](https://github.com/nostr-protocol/nips/blob/master/89.md) | Recommended Apps | Client tag for app discovery |
| [NIP-92](https://github.com/nostr-protocol/nips/blob/master/92.md) | Media Attachments | `imeta` tag parsing for inline media metadata |
| [NIP-96](https://github.com/nostr-protocol/nips/blob/master/96.md) | File Storage | Blossom media upload with BUD-01/02/04 |

## Architecture

Single-activity MVVM with Jetpack Navigation Compose.

| Module | Package | Role |
|--------|---------|------|
| `app/` | `social.mycelium.android` | Android application — UI, ViewModels, repositories, services, Room DB |
| `cybin/` | `com.example.cybin` | Nostr protocol library — events, crypto, NIP implementations, relay transport |

Cybin is included via Gradle composite build (`includeBuild`) — changes reflect immediately.

See [`docs/`](docs/README.md) for detailed architecture, relay system, navigation, and data model documentation.

## Build from Source

**Requirements:** JDK 11+, Android SDK (compileSdk 36, minSdk 35)

```bash
git clone https://github.com/TekkadanPlays/mycelium-android.git
cd mycelium-android
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Kotlin | 2.2.0 | Language |
| Ktor | 3.4.1 | HTTP + WebSocket (CIO engine) |
| Compose BOM | 2024.12.01 | UI framework |
| Coil | 2.5.0 | Image loading |
| Media3 | 1.3.1 | Video/livestream playback |
| secp256k1-kmp | 0.22.0 | Nostr cryptography |
| lightning-kmp | 1.11.5-SNAPSHOT | Embedded Lightning node |
| Kotlinx Serialization | 1.7.3 | JSON parsing |
| Room | 2.7.1 | Local database |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines. For AI agents, see [AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md).

## Credits

- [Amethyst / Quartz](https://github.com/vitorpamplona/amethyst) — Reference Nostr client architecture and outbox model
- [NextPlayer](https://github.com/anilbeesetti/nextplayer) — Video gesture system
- [TinderStateMachine](https://github.com/nicklashansen/tinder-state-machine) — Relay connection state machine
- [nostr.watch](https://nostr.watch) — NIP-66 relay discovery data
- [lightning-kmp](https://github.com/ACINQ/lightning-kmp) / [Phoenix](https://github.com/ACINQ/phoenix) — Embedded Lightning wallet (Apache 2.0)
- [PureLink-Android](https://github.com/ahmedthebest31/PureLink-Android) — URL sanitization (MIT)
- [Prism](https://github.com/hardran3/Prism) — Scheduling, drafts, image stripping, Blossom upload patterns

## License

MIT — see [LICENSE.md](LICENSE.md). Lightning wallet components from ACINQ are Apache 2.0 (included in LICENSE.md).
