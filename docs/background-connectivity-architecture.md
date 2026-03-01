# Background Relay Connectivity Architecture

## Problem Statement

Currently the app has a binary toggle: **foreground service ON** (persistent WebSockets + notification) or **OFF** (connections die when backgrounded). This creates two bad experiences:

1. **Service ON**: Battery drain, persistent notification clutter, bandwidth usage even when idle
2. **Service OFF**: Every app resume triggers "reconnecting" overlay, missed notifications, slow feed restore

Users need granular control over how relay connections behave when the app isn't in the foreground.

---

## Proposed Connection Modes

### 1. Always On (current "background service enabled")
- **Behavior**: `RelayForegroundService` runs with persistent notification. WebSockets stay open. `startKeepalive()` detects stale connections and reconnects.
- **Battery**: Highest — continuous network activity, wakelock via foreground service
- **Use case**: Power users who want real-time notifications and instant feed on resume
- **Implementation**: Already exists. No changes needed.

### 2. Adaptive (recommended default)
- **Behavior**: No foreground service. When app is backgrounded, WebSockets close naturally. A `WorkManager` periodic task checks inbox relays at a configurable interval (default: 15 min) for new notifications (DMs, mentions, zaps, replies). On resume, reconnect is fast because the process may still be alive with cached feed.
- **Battery**: Moderate — periodic wakeups (batched by Android), no persistent connections
- **Use case**: Most users who want notifications without constant battery drain
- **Implementation**: New `RelayCheckWorker` using WorkManager.

### 3. When Active (battery saver)
- **Behavior**: Connections only exist while the app is in the foreground. On `ON_STOP`, all WebSockets are closed and subscriptions paused. On `ON_RESUME`, full reconnect. No background checks at all.
- **Battery**: Lowest — zero background activity
- **Use case**: Users who want maximum battery life and only check nostr manually
- **Implementation**: Add explicit disconnect on `ON_STOP` in `MainActivity`.

---

## Implementation Plan

### Phase 1: ConnectionMode enum + preferences

**File**: `ui/settings/NotificationPreferences.kt`

```kotlin
enum class ConnectionMode {
    ALWAYS_ON,   // Foreground service, persistent WebSockets
    ADAPTIVE,    // WorkManager periodic inbox check, no persistent connections
    WHEN_ACTIVE  // Connections only while app is visible
}
```

- Replace `backgroundServiceEnabled: Boolean` with `connectionMode: ConnectionMode`
- Default: `ADAPTIVE`
- Stored as string in SharedPreferences (`KEY_CONNECTION_MODE`)
- Keep `backgroundServiceEnabled` as a computed property for backward compat:
  ```kotlin
  val backgroundServiceEnabled: StateFlow<Boolean> = connectionMode.map { it == ConnectionMode.ALWAYS_ON }
  ```

### Phase 2: Settings UI

**File**: `ui/screens/NotificationSettingsScreen.kt`

Replace the "Keep relay connections alive" toggle with a 3-option selector:

```
┌─────────────────────────────────────────┐
│  Background Connectivity                │
│                                         │
│  ○ Always On                            │
│    Persistent connections. Real-time     │
│    notifications. Higher battery usage.  │
│    ⓘ Uses a foreground service with     │
│      persistent notification.            │
│                                         │
│  ● Adaptive (Recommended)               │
│    Periodic inbox checks for new         │
│    notifications. Good battery life.     │
│    ⓘ Checks every 15 minutes by default │
│                                         │
│  ○ When Active                           │
│    Connections only while app is open.   │
│    Best battery life. No background      │
│    notifications.                        │
└─────────────────────────────────────────┘
```

When Adaptive is selected, show an interval picker:
- 15 min (default), 30 min, 1 hour, 2 hours, 6 hours

### Phase 3: WorkManager periodic check (Adaptive mode)

**New file**: `services/RelayCheckWorker.kt`

```kotlin
class RelayCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // 1. Get user's inbox relay URLs from RelayStorageManager
        // 2. Open temporary WebSocket connections to inbox relays
        // 3. Fetch kind-1 (replies), kind-7 (reactions), kind-9735 (zaps),
        //    kind-4 (DMs) since last check timestamp
        // 4. For each new event, dispatch Android notification via NotificationChannelManager
        // 5. Close connections
        // 6. Save last-check timestamp
        return Result.success()
    }
}
```

**Scheduling** (in `MainActivity` or `Application.onCreate`):
```kotlin
fun scheduleAdaptiveCheck(intervalMinutes: Long) {
    val request = PeriodicWorkRequestBuilder<RelayCheckWorker>(
        intervalMinutes, TimeUnit.MINUTES
    )
        .setConstraints(Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build())
        .build()
    
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "relay_inbox_check",
        ExistingPeriodicWorkPolicy.UPDATE,
        request
    )
}
```

### Phase 4: MainActivity lifecycle wiring

**File**: `MainActivity.kt`

```kotlin
lifecycle.addObserver(LifecycleEventObserver { _, event ->
    val mode = NotificationPreferences.connectionMode.value
    when (event) {
        Lifecycle.Event.ON_RESUME -> {
            if (accountStateViewModel.onboardingComplete.value) {
                RelayConnectionStateMachine.getInstance().requestReconnectOnResume()
            }
            // ...existing PiP/NIP-66 logic...
        }
        Lifecycle.Event.ON_STOP -> {
            // ...existing PiP/video pause...
            if (mode == ConnectionMode.WHEN_ACTIVE) {
                // Explicitly disconnect all relays
                RelayConnectionStateMachine.getInstance().disconnectAll()
                RelayConnectionStateMachine.getInstance().stopKeepalive()
            }
        }
    }
})

// Service management
override fun onResume() {
    super.onResume()
    when (NotificationPreferences.connectionMode.value) {
        ConnectionMode.ALWAYS_ON -> {
            maybeStartRelayForegroundService()
            RelayConnectionStateMachine.getInstance().startKeepalive()
        }
        ConnectionMode.ADAPTIVE -> {
            // No foreground service, but start keepalive while in foreground
            RelayConnectionStateMachine.getInstance().startKeepalive()
        }
        ConnectionMode.WHEN_ACTIVE -> {
            // Keepalive only while active
            RelayConnectionStateMachine.getInstance().startKeepalive()
        }
    }
}

override fun onDestroy() {
    if (isFinishing) {
        RelayConnectionStateMachine.getInstance().stopKeepalive()
        stopRelayForegroundService()
        // Cancel WorkManager if WHEN_ACTIVE
        if (NotificationPreferences.connectionMode.value == ConnectionMode.WHEN_ACTIVE) {
            WorkManager.getInstance(this).cancelUniqueWork("relay_inbox_check")
        }
    }
}
```

### Phase 5: RelayConnectionStateMachine changes

**File**: `relay/RelayConnectionStateMachine.kt`

Add `disconnectAll()` method for WHEN_ACTIVE mode:
```kotlin
fun disconnectAll() {
    Log.d(TAG, "Disconnecting all relays (When Active mode)")
    currentSubscriptionHandle?.pause()
    relayPool.disconnectAll()
    _state.value = RelayState.Disconnected
}
```

---

## Migration

- On first launch after update, read old `background_service_enabled` boolean:
  - `true` → `ALWAYS_ON`
  - `false` → `ADAPTIVE` (upgrade them to the new default rather than WHEN_ACTIVE)
- Remove old `KEY_BACKGROUND_SERVICE` after migration

---

## Battery Impact Analysis

| Mode | Wakeups/hr | Network | CPU | Estimated drain/hr |
|------|-----------|---------|-----|-------------------|
| Always On | Continuous | ~50KB/min (following) | Keepalive every 30s | ~3-5% |
| Adaptive (15min) | 4 | ~10KB/check | ~2s/check | ~0.2% |
| Adaptive (1hr) | 1 | ~10KB/check | ~2s/check | ~0.05% |
| When Active | 0 | 0 | 0 | 0% |

---

## Dependencies

- `androidx.work:work-runtime-ktx` — already in most Android projects, check `build.gradle.kts`
- No new permissions needed (WorkManager uses existing INTERNET permission)

---

## Files to Create/Modify

| File | Action | Description |
|------|--------|-------------|
| `ui/settings/NotificationPreferences.kt` | Modify | Add `ConnectionMode` enum, replace boolean toggle |
| `ui/screens/NotificationSettingsScreen.kt` | Modify | Replace toggle with 3-mode selector + interval picker |
| `services/RelayCheckWorker.kt` | Create | WorkManager periodic inbox check |
| `services/RelayForegroundService.kt` | Modify | Guard start with `ALWAYS_ON` mode check |
| `relay/RelayConnectionStateMachine.kt` | Modify | Add `disconnectAll()` for WHEN_ACTIVE |
| `MainActivity.kt` | Modify | Wire mode to lifecycle, service, WorkManager scheduling |
| `app/build.gradle.kts` | Modify | Add WorkManager dependency if missing |

---

## Open Questions

1. **Adaptive mode notification content**: Should the periodic check show a summary notification ("3 new replies, 1 zap") or individual notifications per event?
2. **Adaptive check scope**: Check only inbox relays, or also outbox for new followers/reposts?
3. **WorkManager minimum interval**: Android enforces a 15-minute minimum for periodic work. Is this acceptable for the lowest interval option?
4. **Process death + Adaptive**: When WorkManager runs, the app process may not exist. The worker needs its own lightweight relay connection logic (not the full `RelayConnectionStateMachine`). Consider a slim `InboxCheckClient` that opens connections, fetches, and closes.
