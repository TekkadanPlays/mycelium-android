package social.mycelium.android.debug

/**
 * Typed event names for [EventLog].
 *
 * Each constant documents its payload fields so callers know exactly
 * what to include. All events automatically get `ts`, `ev`, `ch`, `tag`
 * from [EventLog.emit]; these are the *additional* fields.
 *
 * ## Naming convention
 * `<domain>.<verb>` — domain matches the subsystem, verb is past-tense or state.
 *
 * ## Usage
 * ```kotlin
 * EventLog.emit(
 *     event   = LogEvents.RELAY_CONNECTED,
 *     channel = "RELAY",
 *     tag     = TAG,
 *     spanId  = sid,
 *     data    = mapOf("url" to url, "latency_ms" to elapsed, "attempt" to attempt)
 * )
 * ```
 */
object LogEvents {

    // ── Session ────────────────────────────────────────────────────────────
    /** Payload: version, version_code, sdk, device */
    const val SESSION_START = "session.start"
    /** Payload: pubkey_prefix (first 8 chars) */
    const val SESSION_ACCOUNT_SWITCH = "session.account_switch"
    /** No extra payload */
    const val SESSION_FOREGROUND = "session.foreground"
    /** No extra payload */
    const val SESSION_BACKGROUND = "session.background"

    // ── Startup phases ─────────────────────────────────────────────────────
    /** Payload: phase (Int), name (String), relay_count (Int) */
    const val STARTUP_PHASE_START = "startup.phase_start"
    /** Payload: phase (Int), name (String), elapsed_ms (Long) */
    const val STARTUP_PHASE_END = "startup.phase_end"
    /** Payload: phase (Int), name (String), reason (String) */
    const val STARTUP_PHASE_SKIPPED = "startup.phase_skipped"
    /** Payload: reset_reason (String, e.g. "account_switch") */
    const val STARTUP_RESET = "startup.reset"

    // ── Relay lifecycle ────────────────────────────────────────────────────
    /** Payload: url (String), attempt (Int) */
    const val RELAY_CONNECTING = "relay.connecting"
    /** Payload: url (String), latency_ms (Long), attempt (Int) */
    const val RELAY_CONNECTED = "relay.connected"
    /** Payload: url (String), error (String?), consecutive_failures (Int), online (Boolean) */
    const val RELAY_FAILED = "relay.failed"
    /** Payload: url (String), consecutive_failures (Int) */
    const val RELAY_FLAGGED = "relay.flagged"
    /** Payload: url (String) (triggered by successful connect after flag) */
    const val RELAY_UNFLAGGED = "relay.unflagged"
    /** Payload: url (String), consecutive_failures (Int), duration_hours (Int) */
    const val RELAY_BLOCKED = "relay.blocked"
    /** Payload: url (String), reason (String, e.g. "expiry" or "manual") */
    const val RELAY_UNBLOCKED = "relay.unblocked"
    /** Payload: url (String), reason (String, e.g. "keepalive_stale") */
    const val RELAY_STALE_RECONNECT = "relay.stale_reconnect"
    /** Payload: url (String) */
    const val RELAY_FORCE_RECONNECT = "relay.force_reconnect"
    /** Payload: (none) — device went offline */
    const val NETWORK_LOST = "network.lost"
    /** Payload: flagged_count (Int) — device came back online, relays amnestied */
    const val NETWORK_REGAINED = "network.regained"

    // ── Subscriptions ──────────────────────────────────────────────────────
    /** Payload: sub_id (String), kinds (String, comma-joined), relay_count (Int), priority (String) */
    const val SUB_CREATED = "sub.created"
    /** Payload: sub_id (String), url (String), events (Int), elapsed_ms (Long) */
    const val SUB_EOSE = "sub.eose"
    /** Payload: sub_id (String), reason (String, e.g. "eose_reaped", "consumer_gone", "account_switch") */
    const val SUB_CLOSED = "sub.closed"
    /** Payload: sub_id (String), url (String), priority (String), evicting_priority (String) */
    const val SUB_EVICTED = "sub.evicted"

    // ── NIP-42 Auth ────────────────────────────────────────────────────────
    /** Payload: url (String), challenge_prefix (String, first 16 chars) */
    const val AUTH_CHALLENGE = "auth.challenge"
    /** Payload: url (String), attempt (Int) */
    const val AUTH_SIGNING = "auth.signing"
    /** Payload: url (String), elapsed_ms (Long), replay_count (Int) */
    const val AUTH_SUCCESS = "auth.success"
    /** Payload: url (String), message (String) */
    const val AUTH_REJECTED = "auth.rejected"
    /** Payload: url (String), attempts (Int) */
    const val AUTH_SIGN_FAILED = "auth.sign_failed"
    /** Payload: url (String), event_count (Int) */
    const val AUTH_REPLAY = "auth.replay"

    // ── Feed pipeline ──────────────────────────────────────────────────────
    /** Payload: events (Int), elapsed_ms (Long) */
    const val FEED_FLUSH = "feed.flush"
    /** Payload: rows (Int) */
    const val FEED_BATCH_COMMIT = "feed.batch_commit"
    /** Payload: total (Int), added (Int), filtered (Int) */
    const val FEED_WINDOW_CHANGE = "feed.window_change"

    // ── Wallet / payments ──────────────────────────────────────────────────
    /** Payload: note_id_prefix (String), amount_sats (Long) */
    const val ZAP_INITIATED = "zap.initiated"
    /** Payload: note_id_prefix (String), callback_prefix (String) */
    const val ZAP_LNURLP_RESOLVED = "zap.lnurlp_resolved"
    /** Payload: note_id_prefix (String) */
    const val ZAP_INVOICE_FETCHED = "zap.invoice_fetched"
    /** Payload: note_id_prefix (String), amount_sats (Long), elapsed_ms (Long), via (String, "phoenix"|"nwc") */
    const val ZAP_SETTLED = "zap.settled"
    /** Payload: note_id_prefix (String), reason (String) */
    const val ZAP_FAILED = "zap.failed"
}

// ── Convenience emit extensions ────────────────────────────────────────────

/** Emit a startup phase start event. Returns the span ID for use in the matching [endStartupPhase]. */
fun EventLog.startStartupPhase(phase: Int, name: String, relayCount: Int = 0): String {
    val sid = spanId()
    emit(
        event   = LogEvents.STARTUP_PHASE_START,
        channel = "STARTUP",
        tag     = "Orchestrator",
        spanId  = sid,
        data    = mapOf("phase" to phase, "name" to name, "relay_count" to relayCount),
    )
    return sid
}

/** Emit a startup phase end event using the span ID returned by [startStartupPhase]. */
fun EventLog.endStartupPhase(phase: Int, name: String, spanId: String, elapsedMs: Long) {
    emit(
        event   = LogEvents.STARTUP_PHASE_END,
        channel = "STARTUP",
        tag     = "Orchestrator",
        spanId  = spanId,
        data    = mapOf("phase" to phase, "name" to name, "elapsed_ms" to elapsedMs),
    )
}

/** Emit relay.connecting and return a span ID for the connection attempt. */
fun EventLog.startRelayConnect(url: String, attempt: Int = 1): String {
    val sid = spanId()
    emit(
        event   = LogEvents.RELAY_CONNECTING,
        channel = "RELAY",
        tag     = "RelayHealth",
        spanId  = sid,
        data    = mapOf("url" to url, "attempt" to attempt),
    )
    return sid
}

/** Emit relay.connected using the span ID from [startRelayConnect]. */
fun EventLog.endRelayConnect(url: String, spanId: String, latencyMs: Long) {
    emit(
        event   = LogEvents.RELAY_CONNECTED,
        channel = "RELAY",
        tag     = "RelayHealth",
        spanId  = spanId,
        data    = mapOf("url" to url, "latency_ms" to latencyMs),
    )
}
