# Mycelium Android — Navigation Reference

> Last verified against source: v0.5.03 (versionCode 41)
> Primary source: `MyceliumNavigation.kt` (5230 lines)

## Overview

Mycelium uses a single-activity architecture with Jetpack Navigation Compose. `MainActivity` hosts the entire UI; `MyceliumNavigation.kt` defines the `NavHost`, all routes, overlay thread infrastructure, bottom navigation, snackbar host, and the sidebar drawer.

There is also a `SplashActivity` that serves as the launch entry point before handing off to `MainActivity`.

---

## Table of Contents

- [Three Navigation Patterns](#three-navigation-patterns)
- [Bottom Navigation](#bottom-navigation)
- [Overlay Stack Architecture](#overlay-stack-architecture)
- [Route Reference](#route-reference)
- [ViewModel Scoping Rules](#viewmodel-scoping-rules)
- [Thread Navigation Decision Tree](#thread-navigation-decision-tree)
- [Key Implementation Details](#key-implementation-details)

---

## Three Navigation Patterns

Three distinct navigation mechanisms coexist in the app. Understanding which one is used where is critical for working on navigation code.

### 1. Overlay Stack (AnimatedVisibility)

Used for **feed → thread** transitions. The thread view is rendered as an overlay on top of the feed, so the feed stays in memory and scroll position is preserved. Multiple threads can be stacked (tapping a quoted note inside a thread pushes another overlay).

**Mechanism:** `SnapshotStateList<Note>` + `AnimatedVisibility` with slide-in/slide-out transitions.

**Where it's used:**
- Home feed → Thread
- Topics feed → Thread
- Profile notes → Thread
- Notifications → Thread

**Back behavior:** `BackHandler` pops the top overlay off the stack. When the stack is empty, the BackHandler is removed and normal nav back takes over.

**Swipe-back:** `ThreadSlideBackBox` wraps each overlay and allows horizontal swipe-right to dismiss.

### 2. NavController Navigation

Used for **screen-to-screen** transitions that should create a new back stack entry with their own `NavBackStackEntry` and potentially their own ViewModel scope.

**Mechanism:** `navController.navigate("route/{arg}")`

**Where it's used:**
- Thread → Thread (deep thread navigation creates a new NavBackStackEntry)
- Notification → Thread (navigates to `thread/{noteId}` route)
- Any screen → Profile
- Any screen → Compose screens (note, topic, reply)
- Any screen → Settings and sub-screens
- Any screen → Relay management screens
- Any screen → Media viewers (image, video)

### 3. Bottom Navigation Tabs

Used to switch between top-level destinations. Bottom nav does not push onto the back stack in the traditional sense — it uses `NavController` with `launchSingleTop = true` and `restoreState = true` semantics.

**Mechanism:** `BottomNavDestinations` enum + `ScrollAwareBottomNavigationBar` composable.

---

## Bottom Navigation

Defined in `ui/components/BottomNavigation.kt`:

```
enum class BottomNavDestinations(route, label, icon):
    HOME       ("home",          "Home",   Icons.Default.Home)
    MESSAGES   ("messages",      "DMs",    Icons.Default.Email)
    WALLET     ("wallet",        "Wallet", Icons.Filled.AccountBalanceWallet)
    ANNOUNCEMENTS ("announcements", "News", Icons.Outlined.Campaign)
    ALERTS     ("notifications", "Alerts", Icons.Default.Notifications)
```

**Five tabs:** Home, DMs, Wallet, Announcements (News), Alerts (Notifications)

> **Note:** Topics and Live are **not** bottom nav tabs. Topics is accessible from the home feed (likely via the sidebar or a tab within the home screen). Live streams are accessed via navigation to the live explorer.

The bottom nav bar auto-hides on scroll down and reappears on scroll up (`ScrollAwareBottomNavigationBar`).

---

## Overlay Stack Architecture

This is the most complex part of the navigation system. Each major feed screen maintains its own independent overlay thread stack:

| Stack Variable | Owner Screen | Purpose |
|----------------|-------------|---------|
| `overlayThreadStack` | Home (Dashboard) | Threads opened from home feed |
| `overlayTopicThreadStack` | Topics | Threads opened from topics feed |
| `overlayProfileThreadStack` | Profile | Threads opened from profile notes |
| `overlayNotifThreadStack` | Notifications | Threads opened from notification cards |

### How It Works

1. **Tap a note** in the feed → the note is pushed onto the relevant `overlayThreadStack`
2. `MyceliumNavigation` renders the stack as a series of `AnimatedVisibility` composables layered with `zIndex`
3. Each overlay shows `ModernThreadViewScreen` for the top-of-stack note
4. **Tap a quoted note** inside the thread → pushes another note onto the stack (chained thread exploration)
5. **Back press or swipe-right** → pops the top overlay
6. When the stack is empty → the underlying feed is fully visible again

### Overlay-Preserving Routes

Certain navigation routes do **not** clear the overlay stack when navigated to. These are routes that are considered "child" views of a thread:

- `note_relays/` — "Note seen on relays" screen
- `relay_log/` — Per-relay event log
- `reactions/` — Reactions list for a note
- `zap_settings` — Zap configuration
- `profile/` — User profile (opened from within a thread)

Navigating to any of these routes keeps the overlay stack intact so the user can return to the exact thread position.

### Overlay vs Nav-Based Thread Navigation

The key distinction:

| Scenario | Mechanism | ViewModel |
|----------|-----------|-----------|
| Feed → Thread | Overlay | Shares feed screen's `NavBackStackEntry` and ViewModel |
| Thread → Thread (chained) | Overlay push | Same shared ViewModel |
| Notification → Thread | `navController.navigate("thread/{id}")` | Fresh `NavBackStackEntry`, own ViewModel |
| Thread → Thread (via nav) | `navController.navigate("thread/{id}")` | Fresh `NavBackStackEntry`, own ViewModel |

This means overlay threads share state (the `DashboardViewModel` or `TopicsViewModel`) while nav-based threads are isolated.

---

## Route Reference

All routes are defined as `composable("route")` blocks in `MyceliumNavigation.kt`.

### Top-Level / Bottom Nav

| Route | Screen | Description |
|-------|--------|-------------|
| `dashboard` | `DashboardScreen` | Home feed. Waits for `accountsRestored` before rendering. |
| `home` | — | Bottom nav route, resolves to dashboard |
| `messages` | `ConversationsScreen` | DM conversation list (NIP-17) |
| `wallet` | `WalletScreen` | Embedded Phoenix Lightning wallet |
| `announcements` | `AnnouncementsFeedScreen` | News/announcements feed |
| `notifications` | `NotificationsScreen` | 10-tab filtered notification view |
| `topics` | `TopicsScreen` | Kind-11 topics feed with kind-1111 replies |

### Thread & Content

| Route | Screen | Description |
|-------|--------|-------------|
| `thread/{noteId}` | — | Nav-based thread view (own ViewModel) |
| `profile/{pubkey}` | `ProfileScreen` | User profile with Notes/Replies/Media tabs |
| `user_profile` | `ProfileScreen` | Current user's own profile |

### Compose / Publish

| Route | Screen | Description |
|-------|--------|-------------|
| `compose` | `ComposeNoteScreen` | Compose new kind-1 note |
| `compose_topic` | `ComposeTopicScreen` | Compose new kind-11 topic |
| `compose_topic_reply/{noteId}` | `ComposeTopicReplyScreen` | Reply to a topic |
| `reply/{noteId}` | `ReplyComposeScreen` | Reply to a note (kind-1) |
| `compose_article` | `ComposeArticleScreen` | Compose long-form article (kind-30023) |

### Relay Management

| Route | Screen | Description |
|-------|--------|-------------|
| `relay_management` | `RelayManagementScreen` | Relay profiles and categories |
| `relay_discovery` | `RelayDiscoveryScreen` | NIP-66 relay discovery |
| `relay_connection_status` | `RelayConnectionStatusScreen` | Per-relay connection state |
| `relay_needs_attention` | `RelayNeedsAttentionScreen` | Relays with health issues |
| `settings/relay_health` | `RelayHealthScreen` | Detailed relay health metrics |
| `relay_log/{relayUrl}` | `RelayLogScreen` | Per-relay event log |
| `relay_users/{relayUrl}` | `RelayUsersScreen` | Users on a relay |
| `relay_user_management/{relayUrl}` | `RelayUserManagementScreen` | NIP-86 relay user management |
| `note_relays/{relayUrlsEncoded}` | `NoteRelaysScreen` | Which relays a note was seen on |

### Settings

| Route | Screen | Description |
|-------|--------|-------------|
| `settings` | `SettingsScreen` | Settings hub |
| `settings/power` | `PowerSettingsScreen` | Power/performance settings |
| `settings/appearance` | `AppearanceSettingsScreen` | Theme, accent color, compact media |
| `settings/media` | `MediaSettingsScreen` | Autoplay, media servers, EXIF stripping |
| `settings/account_preferences` | `AccountPreferencesScreen` | Account-level preferences |
| `settings/notifications` | `NotificationSettingsScreen` | Notification toggles, connection mode |
| `settings/filters_blocks` | `FiltersBlocksSettingsScreen` | Mute list, content filters |
| `settings/data_storage` | `DataStorageSettingsScreen` | Cache management, data usage |
| `settings/direct_messages` | `DmSettingsScreen` | DM-specific settings |
| `settings/about` | `AboutScreen` | App info, credits, licenses |
| `settings/debug` | `DebugSettingsScreen` | Debug tools and diagnostics |
| `settings/publish_results` | `PublishResultsScreen` | Per-relay publish success/failure history |

### Media & Viewers

| Route | Screen | Description |
|-------|--------|-------------|
| `image_viewer` | `ImageContentViewerScreen` | Fullscreen image gallery (per-note) |
| `video_viewer` | `VideoContentViewerScreen` | Fullscreen video player |
| `live_stream/{id}` | `LiveStreamScreen` | HLS live stream with PiP support |
| `live_explorer` | `LiveExplorerScreen` | NIP-53 live activity discovery |

### Direct Messages

| Route | Screen | Description |
|-------|--------|-------------|
| `messages` | `ConversationsScreen` | Conversation list |
| `chat/{peerPubkey}` | `ChatScreen` | Chat with a specific user |
| `new_dm` | `NewDmScreen` | Start a new DM conversation |

### Other

| Route | Screen | Description |
|-------|--------|-------------|
| `onboarding` | `OnboardingScreen` | Post-login relay initialization flow |
| `user_qr` | `QrCodeScreen` | QR code for sharing npub |
| `zap_settings` | `ZapSettingsScreen` | Zap amount configuration |
| `reactions/{noteId}` | `ReactionsScreen` | View all reactions on a note |
| `drafts` | `DraftsScreen` | Saved drafts and scheduled notes |
| `lists` | `ListsScreen` | People lists (kind-30000) and hashtag subscriptions |
| `list_detail/{listId}` | `ListDetailScreen` | Detail view for a specific list |
| `effects_lab` | `EffectsLabScreen` | Visual effects playground (debug) |
| `debug_follow_list` | `DebugFollowListScreen` | Debug view of follow list state |
| `article_view/{eventId}` | `ArticleViewScreen` | Long-form article reader (kind-30023) |

---

## ViewModel Scoping Rules

Understanding ViewModel scoping is essential for avoiding state bugs.

### Activity-Scoped ViewModels (Global)

These survive all navigation and are shared across every screen:

| ViewModel | Purpose |
|-----------|---------|
| `AppViewModel` | Cross-screen state: `notesById` map, selected note, media viewer URLs, hidden note IDs, pending reactions |
| `AccountStateViewModel` | Multi-account management, all publish methods, Amber signer, onboarding state |
| `FeedStateViewModel` | Feed filter (All/Following), sort order, scroll position save/restore |

These are obtained at the `MainActivity` level and passed down through `MyceliumNavigation`.

### Screen-Scoped ViewModels (Per NavBackStackEntry)

These are created per `composable()` route and destroyed when the route is popped from the back stack:

| ViewModel | Scope | Purpose |
|-----------|-------|---------|
| `DashboardViewModel` | `dashboard` route | Feed observation, relay state, follow list, URL preview prefetch |
| `Kind1RepliesViewModel` | Overlay: shares dashboard's entry. Nav: own entry. | Kind-1 reply loading and sorting |
| `ThreadRepliesViewModel` | Per thread view | Kind-1111 replies, live reply awareness |
| `TopicsViewModel` | `topics` route | Topic list, counts, pending/displayed |
| `RelayManagementViewModel` | Relay management routes | Relay CRUD, NIP-65 publishing |
| `SearchViewModel` | Search contexts | Search queries and results |
| `AnnouncementsViewModel` | `announcements` route | Announcements feed state |
| `AuthViewModel` | Auth/login flows | Authentication state |
| `ThreadStateHolder` | Thread views | Thread-specific state management |

### The Overlay Scoping Trap

Because overlay threads share the underlying feed's `NavBackStackEntry`, they also share its ViewModels. This means:

- An overlay thread opened from the dashboard uses `DashboardViewModel`
- `Kind1RepliesViewModel` obtained via `viewModel()` inside an overlay returns the **same instance** as the feed's
- A nav-based thread (`navController.navigate("thread/{id}")`) gets a **fresh** `Kind1RepliesViewModel`

If you add state to a screen-scoped ViewModel, be aware that overlay threads will share that state with the feed.

---

## Thread Navigation Decision Tree

When a note is tapped, the system decides which navigation pattern to use:

```
User taps a note
    │
    ├─ Is the user currently on a feed screen (dashboard, topics, profile, notifications)?
    │   │
    │   YES → Push onto that screen's overlay stack (AnimatedVisibility)
    │         └─ Thread shares the feed's NavBackStackEntry and ViewModel
    │
    │   NO → Is the user inside an overlay thread?
    │       │
    │       ├─ Tapping a quoted note → Push another overlay onto the stack (chained)
    │       │
    │       └─ Navigating to a completely different thread → navController.navigate("thread/{id}")
    │           └─ Gets its own NavBackStackEntry and fresh ViewModel
    │
    └─ Is the user on a screen that doesn't have an overlay stack?
        │
        └─ navController.navigate("thread/{id}")
            └─ Gets its own NavBackStackEntry and fresh ViewModel
```

---

## Key Implementation Details

### Route Guards

Several routes have guard logic that redirects based on authentication state:

- `dashboard` — Waits for `accountsRestored` before rendering (prevents guest/login UI flash on cold start)
- `notifications` — Redirects to `onboarding` if account exists but onboarding is incomplete; redirects to `dashboard` if no account (to show sign-in)
- `topics` — Same guard logic as notifications

### Navigation Arguments

Routes with parameters use `navArgument`:

```kotlin
composable(
    "thread/{noteId}",
    arguments = listOf(navArgument("noteId") { type = NavType.StringType })
)

composable(
    "profile/{pubkey}",
    arguments = listOf(navArgument("pubkey") { type = NavType.StringType })
)

composable(
    "note_relays/{relayUrlsEncoded}",
    // relayUrlsEncoded is comma-separated, URI-encoded relay URLs
)
```

### Transitions

The app uses Material Motion transitions defined in `MaterialMotion.kt` alongside custom `AnimatedContentTransitionScope` configuration. Overlay threads use `slideInHorizontally` / `slideOutHorizontally` with `FastOutSlowInEasing`.

### Deep Links

The app does not currently define `deepLinks` in the nav graph. External `nostr:` URI handling is done via intent filters in the manifest and resolved in `MainActivity` before navigation.

### Sidebar (Drawer)

`GlobalSidebar` is a navigation drawer accessible from the home screen. It provides:
- Profile quick-view
- Account switcher
- Relay health badge (red dot when relays need attention)
- Navigation shortcuts to: Profile, Relay Management, Relay Health, Lists, Drafts, Wallet, Settings
- Debug tools (follow list debug, effects lab)

### PiP (Picture-in-Picture) Navigation

When a live stream is playing in PiP mode:
- `PipStreamOverlay` renders a floating mini-player
- `PipStreamManager` tracks the active stream state
- Tapping the PiP overlay navigates back to the live stream screen
- PiP survives navigation between screens

### Scroll Position Preservation

Feed scroll position is preserved in `FeedStateViewModel` using `LazyListState` save/restore. When navigating away from the dashboard and back, the scroll position is restored. The overlay system inherently preserves scroll position since the feed stays in composition behind the overlay.

When dismissing fullscreen media (image viewer, video viewer), the feed position is preserved because these are separate nav routes that don't disturb the underlying LazyColumn state.