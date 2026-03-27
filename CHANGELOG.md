# Changelog

## v0.5.20-beta (2026-03-27)
- **Relay transport** — Removed legacy `WebSocketClient`; relay traffic consolidated on Ktor/Cybin pool path with updates across state machine, multiplexer, health, NIP-42 auth, and connectivity monitoring
- **Repositories & feed** — Coordinated changes in notes, outbox, NIP-65/66, relay storage, startup, DMs, and related caches for the new transport behavior
- **Debug & diagnostics** — Session dump, verbose logging, and pipeline diagnostics helpers; relay log and debug settings wiring
- **UI** — Event delivery screen; relay management and dashboard adjustments; relay health screen removed in favor of integrated flows

## v0.5.12-beta (2026-03-26)
- **Onboarding overhaul** — Redesigned phase flow with relay diff review, indexer confirmation, list prefetching, and interactive notification/battery setup walkthrough
- **ImmutableList feed optimization** — `NotesRepository` emits `ImmutableList<Note>` for improved Compose recomposition skip rates
- **Background DM relay checks** — `RelayCheckWorker` queries NIP-17 DM relays (kind 1059) alongside inbox relays
- **Indexer diff banner** — Non-blocking DashboardScreen banner when remote kind-10086 differs from confirmed local indexers
- **Notification settings redesign** — Permission checks, DM content preview toggle, streamlined UI
- **Documentation framework** — AGENTS.md, CONTRIBUTING.md, .cursor/rules, README refresh, .gitignore cleanup

## v0.5.11-beta (2026-03-25)
- Auto-save drafts system with 10s interval across all 5 compose screens
- New ARTICLE draft type for NIP-23 long-form content
- Media gallery flickering fix (content-based key for aspect ratio state)
- FAB menu reorder (Drafts → Article → Post)
- DM deduplication fix, NIP-42 auth improvements

## v0.5.10-beta (2026-03-25)
- NIP-51 kind-30002 relay set sync with auto-publish on mutation
- Kind-10086 indexer relay list cold-start fetch and merge
- Cross-category relay overlap detection with severity-based warnings
- DM relay isolation enforcement
- NIP-66 indexer verification badge
- Zap drawer escape-from-card fix

## v0.5.03-beta
- Persist poll responses to Room DB
- Prefetch quoted notes in thread view

## v0.5.02-beta
- Outbox feed freshness improvements
- Quoted event counts in thread view
- Media layout shift fix
- NIP-30 custom emoji reaction rendering

## v0.5.01-beta
- Notification enrichment and relay resilience
- Feed deduplication improvements
- Battery info panel

## v0.5.00-beta
- NIP-11 relay icon fix
- People list feed filtering
- Feed architecture overhaul
- Kind-30011 NIP documentation support

## v0.4.99-beta
- Replace OkHttp with Ktor CIO engine
- Route all subscriptions through SubscriptionMultiplexer

## v0.4.98-beta
- Fix notification leakage, poll display, @mentions, hyperlinks, kind-1111 jump

## v0.4.97-beta
- NIP-19 in articles, themed hyperlinks, outbox coverage improvements

## v0.4.96-beta
- Subscription priority rebalancing, feed layout shift fixes
- Emoji packs, polls, GIF search

## v0.4.95-beta
- Scroll performance overhaul
- Phoenix wallet + NWC service provider
- Feature gate system

See [GitHub Releases](https://github.com/TekkadanPlays/mycelium-android/releases) for earlier versions.
