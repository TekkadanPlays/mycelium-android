# Mycelium v0.3.0-beta Release Notes

## Feed & Performance
- **Infinite scroll fix**: Home feed now properly loads older notes when scrolling to the bottom — fixed stale closure in pagination detection
- **Re-subscription loop eliminated**: Feed no longer redundantly re-subscribes when follow list hasn't changed (idempotency guard)
- **Removed hard timeout on sign-in relay queries**: Relay data loads directly from persisted storage instead of polling

## Note Menu Overhaul
- **Multi-tiered 3-dot menu** with organized sub-menus:
  - **Follow / Unfollow** author directly from any note
  - **Message** author (placeholder for upcoming DM support)
  - **Copy →** sub-menu: Copy text, Copy author npub, Copy nevent
  - **Bookmarks →** sub-menu: Add to public, Add to private
  - **Share**: Generates proper `njump.me/nevent1...` links with relay hints
  - **Translate** / Show original
  - **Filters & Blocks →** sub-menu: Block, Mute
  - **Report**

## Sensitive Content (NIP-36)
- Notes with `content-warning` tags or `#nsfw` hashtags are hidden behind a blur overlay
- Tap to reveal individual notes
- Global toggle in Settings → Filters & Blocks → "Show sensitive content"

## Action Row Refactor
- Bookmark and Share buttons moved from action row into the 3-dot menu
- All action buttons right-aligned with consistent 40dp/20dp sizing
- Thread reply controls reordered: Lightning → Likes → Reply → Caret

## NIP-19 Encoding
- Added `toNote()`, `toNpub()`, and `encodeNevent()` helpers to cybin library
- Share links now use proper `nevent1` bech32 encoding with relay hints and author pubkey

## UI & Branding
- New purple mushroom vector asset on splash screen and feed loading overlay
- CoinOS wallet tab shows "Coming soon.." placeholder with lightning icon
- Notifications tab: Unseen tab removed (was experimental)
- Settings restructured: Your Experience, Account, App, Support sections
- New settings screens: Zaps, Notifications, Filters & Blocks, Data & Storage
- Confirmation dialogs before clearing cached data

## Known Limitations
- Gift-wrapped DMs (NIP-17) not yet implemented
- Zapraisers not yet supported
- Block/Mute publish to relays not yet wired (placeholder toasts)
- Bookmarks publish not yet wired (placeholder)
