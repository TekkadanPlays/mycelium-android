# Mycelium Android — Documentation

> Last verified against source: v0.5.03 (versionCode 41)

## Index

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Full architecture reference — layer diagram, data flow pipelines, component inventory |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Developer setup, building, testing, project structure walkthrough |
| [DATA_MODELS.md](DATA_MODELS.md) | Core data types — `Note`, `Author`, `ThreadReply`, `DirectMessage`, relay types, etc. |
| [NAVIGATION.md](NAVIGATION.md) | Navigation patterns — overlay stack system, NavController routes, bottom nav |
| [RELAY_SYSTEM.md](RELAY_SYSTEM.md) | Full relay stack — Cybin transport → RelayPool → Multiplexer → StateMachine → Repositories |
| [background-connectivity-architecture.md](background-connectivity-architecture.md) | Design doc: Adaptive / Always On / When Active connection modes |
| [lnurl-proxy-architecture.md](lnurl-proxy-architecture.md) | Design doc: LNURL proxy server for embedded Lightning wallet |

## Root-Level Files

| File | Description |
|------|-------------|
| [README.md](../README.md) | Project overview, features, build instructions, dependency table |
| [CLAUDE.md](../CLAUDE.md) | AI assistant context file — architecture summary and build commands |
| [CHANGELOG.md](../CHANGELOG.md) | Release history |
| [CHANGELOG_NEXT.md](../CHANGELOG_NEXT.md) | Unreleased changes staged for next version |
| [RELEASE_NOTES.md](../RELEASE_NOTES.md) | Detailed release notes for major versions |
| [LICENSE](../LICENSE) | MIT license |
| [NOTICE-ACINQ](../NOTICE-ACINQ) | Apache 2.0 notice for lightning-kmp and phoenix |

## Quick Orientation

Mycelium is a native Android Nostr client. The codebase has two modules:

1. **`app/`** — The Android application (`social.mycelium.android`)
2. **`cybin/`** — In-repo Nostr protocol library (`com.example.cybin`), included via Gradle composite build

All application code lives under `app/src/main/java/social/mycelium/android/`:

```
├── auth/           # Amber signer management (1 file)
├── cache/          # NIP-11 cache, thread reply cache (2 files + subdirectory)
├── data/           # Data models — Note, Author, Relay, Draft, etc. (17 files)
├── db/             # Room database — entities, DAOs, AppDatabase (13 files)
├── lightning/      # Embedded Phoenix wallet, NWC service, seed management (5 files)
├── network/        # HTTP client, WebSocket client (2 files)
├── relay/          # Relay orchestration layer (7 files)
├── repository/     # Data repositories and managers (49 files)
├── services/       # Event publisher, schedulers, notification channels, etc. (20 files)
├── ui/
│   ├── components/ # Reusable composables — NoteCard, ZapMenu, VideoPlayer, etc. (59 files)
│   ├── icons/      # Custom icon definitions (1 file)
│   ├── navigation/ # NavHost, route definitions, transitions (2 files)
│   ├── performance/# Compose performance utilities (2 files)
│   ├── screens/    # Screen composables — Dashboard, Thread, Profile, etc. (52 files)
│   ├── settings/   # Preference singletons (2 files)
│   └── theme/      # MD3 theme, colors, typography (4 files)
├── utils/          # Utility classes — link sanitizer, markdown, image processing (22 files)
├── viewmodel/      # ViewModels — activity-scoped and screen-scoped (12 files)
├── MainActivity.kt
└── SplashActivity.kt
```

The Cybin library lives under `cybin/cybin/src/main/java/com/example/cybin/`:

```
├── core/           # Event, Filter, TagArrayBuilder, Types, Utils (6 files)
├── crypto/         # KeyPair, EventHasher, NIP-04, NIP-44 encryption (4 files)
├── nip19/          # Bech32 encoding, TLV parsing, entity types (4 files)
├── nip25/          # Reaction events — kind 7 (1 file)
├── nip47/          # Wallet Connect — NWC request/response (1 file)
├── nip55/          # External signer (Amber) integration (6 files)
├── nip57/          # Zap request events — kind 9734 (1 file)
├── relay/          # CybinRelayPool, NostrProtocol, RelayUrl (3 files)
└── signer/         # NostrSigner abstract + internal implementation (2 files)
```

## Where to Start

- **New contributor?** → [DEVELOPMENT.md](DEVELOPMENT.md)
- **Understanding the architecture?** → [ARCHITECTURE.md](ARCHITECTURE.md)
- **Working on relay code?** → [RELAY_SYSTEM.md](RELAY_SYSTEM.md)
- **Working on navigation / new screens?** → [NAVIGATION.md](NAVIGATION.md)
- **Understanding data types?** → [DATA_MODELS.md](DATA_MODELS.md)