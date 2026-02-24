# Mycelium v0.4.1-beta Release Notes

## Hotfix: Notifications & Background Service

### Android Notification Channels
- **8 independent notification channels** — each configurable in Android system settings:
  - **Relay connection** (low, ongoing) — foreground service keepalive
  - **Replies** (high) — kind-1 replies to your notes
  - **Comments** (high) — kind-1111 thread comments
  - **Mentions** (default) — when someone mentions you
  - **Reactions** (low) — likes and emoji reactions
  - **Zaps** (high) — lightning zaps
  - **Reposts** (low) — when someone reposts your note
  - **Direct Messages** (high) — NIP-17 DMs
- Old monolithic "Mycelium relay connection" channel is automatically deleted on upgrade

### Real-time Push Notifications
- **Android notifications now fire** for replies, comments, mentions, reactions, zaps, and reposts
- 10-second suppression window after subscription start prevents old event replay from spamming
- Each notification type respects its own user toggle in Settings → Notifications

### Background Service Controls
- **New "Keep relay connections alive" toggle** — disable to stop the foreground service entirely
- Global filter mode warning when background service is enabled (bandwidth/battery advisory)
- Service stops itself immediately when user disables at runtime

### Feed Loading Overlay Fix
- Loading overlay no longer sticks after backgrounding/foregrounding the app
- Overlay suppressed when feed session is Live or Refreshing (notes in memory)

---

# Mycelium v0.4.0-beta Release Notes

## Relay Management
- **Outbox tab redesigned** with three clear sections: Both (read/write), Outbox (write-only), and Inbox (read-only)
- **Designation picker** when adding a relay — choose exactly how it should be used
- **Relay selection screen** for publishing — pick which relays to send notes, replies, and topics to, with per-section toggle-all
- **Publish failure reporting** — snackbar alerts when publishes fail, with detailed per-relay results on the Relay Health screen
- **Relay health overhaul** — live slot utilization bars, color-coded latency, network overview card with health score ring
- **Sidebar relay badge** — red dot on the health icon when relays need attention

## Subscription Engine
- **Subscription multiplexer** — filter merging, ref-counting, and bounded dedup for efficient relay communication
- **Relay slot optimization** — reduced from 10+ competing subscriptions to 3-4 active slots per relay
- **One-shot subscriptions** — auto-close after EOSE for transient queries (bookmarks, mute lists, relay lists)
- **Outbox feed manager** — fetches notes from followed users' preferred write relays (NIP-65)

## Thread View
- **Long-press to collapse** now works on the entire reply card, not just the header
- **Live reply awareness** — new replies appear as tappable badges instead of interrupting your reading
- **Pull-to-refresh** on thread views
- **Sort order toggle** — newest or oldest first
- **Expanded relay fetch** — walks relay hints, author outbox relays, and indexers to find notes

## Notifications
- **Threads tab** — groups replies to your topics into condensed per-thread summaries with overlapping avatars
- **Per-tab mark-as-read** — clear notifications for just one tab at a time
- **Overlay thread navigation** — open threads directly from notification cards without leaving the screen
- **Improved filtering** — mention-only replies reclassified correctly, false-positive boosts removed

## Direct Messages
- **Redesigned chat screen** — modern bubble shapes with adaptive corner radii, date separators, message grouping, NIP-17 encryption label
- Material3 theming consistent with the rest of the app

## Profile
- **Media tab** — square two-column grid view; tap opens the thread, not fullscreen
- **Replies tab** — now accessible and functional
- **Fullscreen icon** on images — magnifying glass overlay for opening the image viewer
- **Thread overlay** — open threads from profile notes without navigating away

## Publishing
- **Dedicated relay selection** for all compose screens (notes, topics, replies, comments)
- **Target author inbox relays** shown when replying (NIP-65 lookup)
- **"Note seen on" relays** as an additional section for maximum reach

## UI Polish
- **App icon** updated with properly sized adaptive foreground
- **Snackbar theming** — matches Material3 surface colors
- **Profile tab indicator** — clean dot instead of underline
- **Sidebar** — merged outbox/inbox relay sections with read/write tags

## Known Limitations
- Zapraisers not yet supported
- Block/Mute publish to relays not yet wired
- Bookmarks publish not yet wired
