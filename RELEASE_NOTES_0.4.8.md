# v0.4.80-beta

## What's New

### Bug Fixes
- **Mention display names now resolve properly** — @mentions that showed hex pubkeys instead of display names will now update reactively when profiles load
- **Quoted notes use relay hints** — nevent1 relay hints are now extracted and prioritized for fetching, replacing the old indexer-based approach that caused quoted notes to spin indefinitely
- **Links are now underlined** — URLs in note body text are visually distinct to prevent accidental taps
- **Quoted note media no longer gets cut off** — images in quoted notes now render with proper minimum height instead of a thin slice
- **Thread view shows media from quoted notes** — tapping a quoted note to open its thread now properly passes through media URLs

### Settings & Power
- Reorganized settings menu (removed redundant Relays/Zaps/General entries)
- New **Power** settings screen with battery optimization and background connectivity modes
- Three connectivity modes: Always On, Adaptive (WorkManager), When Active
- Cleaned up DM and Notification settings screens
