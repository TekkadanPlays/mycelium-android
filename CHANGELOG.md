# Changelog

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
