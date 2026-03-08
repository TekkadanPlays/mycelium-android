# Changelog

## v0.4.89-beta (2026-03-08)
- **Reaction emoji on feed** — NIP-25 reactions made in thread view now display the correct emoji on the feed card (was showing heart instead of submitted emoji)
- **Repost counts fix** — Reactions, zaps, and vote counts now display correctly on reposted notes (was using synthetic repost ID instead of real event ID)
- **Vote loading speed** — Kind-30011 votes moved from Phase 2 (2.5s delay) to Phase 1 (immediate) in counts subscription; votes now appear alongside reply counts
- **Own notes stay on feed** — User's own notes no longer vanish from the home feed when the follow filter re-runs
- **Relay orbs accuracy** — Relay orbs only show confirmed relay locations (no more speculative NIP-65 outbox population)

## v0.4.87-beta (2026-03-02)
- **Smart profile fetching** — Kind-0 profiles fetched from indexer relays first; missing profiles automatically retried on outbox relays
- **Relay filtering** — Payment-required and auth-required relays skipped in subscription routing (no wasted slots)
- **Relay badges** — Paid and Auth labels shown on relay discovery and health screens
- **Tappable relay URLs** — `wss://` URLs in notes are now clickable links to relay info
- **Quoted note improvements** — Longer snippets (500 chars), 6 visible lines, smooth expand/collapse animation
- **Quoted note media stability** — Images in quoted notes no longer reset on scroll (aspect ratio cached globally)
- **Layout fixes** — Relay discovery and health screens no longer skew vertically from long tags/names

## v0.4.24-beta (2026-02-28)
- **Optimistic UI updates** — Reactions, boosts, and relay orbs now reflect immediately in thread view after publishing, without waiting for relay echo
- **Video gesture controls** — Fullscreen player gains horizontal swipe seek, vertical volume/brightness, double-tap ±10s, long-press 2× speed, and pinch-to-zoom (ported from NextPlayer)
- **HDR Night Mode fix** — Video player now uses TextureView to prevent HDR content from overriding system dark theme / Night Mode
- **Video seekbar in feed** — Card-view video player now includes a draggable seekbar with timestamps (was fullscreen-only)
- **Fullscreen controls centered** — Video controls pill is now full-width and bottom-centered in fullscreen mode

## v0.4.23-beta (2026-02-28)
- **Feed scroll position preserved** — dismissing fullscreen media no longer resets feed position (dashboard and profile)
- **Profile header state persists** — collapsing header offset, selected tab, and bio expanded state survive navigation
- **Video thumbnails** — Media tab now renders video frame thumbnails via Coil VideoFrameDecoder
- **Contextual media fullscreen** — tapping media opens per-note gallery instead of all-profile media
- **Scrollable profile header** — profile top section now collapses on scroll with nested scroll connection

## v0.1.2 (2026-02-08)
- Kind-1111 merge/UI fix, edge-to-edge embeds and images in feed
- Conditional body highlight, relay orbs, OP styling

## v0.1.1 (2026-02-08)
- Amber lifecycle fix, zap error feedback, relay picker, NIP-25 on kind-1111

## v0.1.0 (2026-02-08)
- Thread UI polish, topic and thread reply notifications

## v0.0.3 (2025-01-22)
- Lightning Zaps and Wallet Connect
