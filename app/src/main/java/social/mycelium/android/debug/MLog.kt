package social.mycelium.android.debug

import android.util.Log
import social.mycelium.android.BuildConfig

/**
 * Unified log facade for Mycelium.
 *
 * Drop-in replacement for `android.util.Log` that also routes entries through
 * [DiagnosticLog] for file persistence. In release builds, only WARN and ERROR
 * reach disk; in debug builds, all levels are persisted AND mirrored to logcat.
 *
 * ## Channel auto-detection
 *
 * The [channel] parameter defaults to [DiagnosticLog.Channel.GENERAL]. Callers
 * in specific subsystems should pass the appropriate channel:
 *
 * ```kotlin
 * MLog.d("NotesRepo", "flushed 42 events", DiagnosticLog.Channel.FEED)
 * MLog.w("Nip42Auth", "challenge expired", DiagnosticLog.Channel.AUTH)
 * MLog.e("NwcPayment", "timeout after 15s", DiagnosticLog.Channel.WALLET)
 * ```
 *
 * For the common case where the tag is enough context, just use:
 * ```kotlin
 * MLog.d(TAG, "something happened")
 * ```
 *
 * ## Tag-to-channel mapping
 *
 * When no explicit channel is provided, [resolveChannel] infers one from the tag
 * using prefix matching. This keeps migration easy: just replace `Log.d(TAG, ...)`
 * with `MLog.d(TAG, ...)` and the channel is auto-detected.
 */
object MLog {

    // ── Tag → Channel mapping (prefix-based, checked in order) ──────────

    private val TAG_CHANNEL_MAP = listOf(
        // Feed pipeline
        "NotesRepo" to DiagnosticLog.Channel.FEED,
        "DashboardVM" to DiagnosticLog.Channel.FEED,
        "OutboxFeed" to DiagnosticLog.Channel.FEED,
        "FeedWindow" to DiagnosticLog.Channel.FEED,
        "GlobalFeed" to DiagnosticLog.Channel.FEED,
        "DeepHistory" to DiagnosticLog.Channel.FEED,
        "Pipeline" to DiagnosticLog.Channel.FEED,
        "Enrichment" to DiagnosticLog.Channel.FEED,

        // Relay layer
        "CybinRelay" to DiagnosticLog.Channel.RELAY,
        "RelayConn" to DiagnosticLog.Channel.RELAY,
        "RelaySM" to DiagnosticLog.Channel.RELAY,
        "RelayHealth" to DiagnosticLog.Channel.RELAY,
        "RelayLog" to DiagnosticLog.Channel.RELAY,
        "SubMux" to DiagnosticLog.Channel.RELAY,
        "NetworkMon" to DiagnosticLog.Channel.RELAY,

        // Sync / relay management
        "RelayMgmt" to DiagnosticLog.Channel.SYNC,
        "RelayCat" to DiagnosticLog.Channel.SYNC,
        "Nip65" to DiagnosticLog.Channel.SYNC,
        "Nip66" to DiagnosticLog.Channel.SYNC,

        // Auth / signing
        "Nip42" to DiagnosticLog.Channel.AUTH,
        "Amber" to DiagnosticLog.Channel.AUTH,
        "Signer" to DiagnosticLog.Channel.AUTH,
        "AccountState" to DiagnosticLog.Channel.AUTH,

        // Notifications
        "Notif" to DiagnosticLog.Channel.NOTIFICATION,
        "NotifRepo" to DiagnosticLog.Channel.NOTIFICATION,
        "NotifChannel" to DiagnosticLog.Channel.NOTIFICATION,
        "RelayCheck" to DiagnosticLog.Channel.NOTIFICATION,

        // Wallet / payments
        "Coinos" to DiagnosticLog.Channel.WALLET,
        "Nwc" to DiagnosticLog.Channel.WALLET,
        "Zap" to DiagnosticLog.Channel.WALLET,
        "Wallet" to DiagnosticLog.Channel.WALLET,
        "Lightning" to DiagnosticLog.Channel.WALLET,

        // Startup
        "Startup" to DiagnosticLog.Channel.STARTUP,
        "Orchestrator" to DiagnosticLog.Channel.STARTUP,
        "Splash" to DiagnosticLog.Channel.STARTUP,

        // State / storage
        "Storage" to DiagnosticLog.Channel.STATE,
        "Prefs" to DiagnosticLog.Channel.STATE,
        "Room" to DiagnosticLog.Channel.STATE,
        "Dao" to DiagnosticLog.Channel.STATE,

        // Content repositories
        "Topic" to DiagnosticLog.Channel.FEED,
        "Thread" to DiagnosticLog.Channel.FEED,
        "Kind1Rep" to DiagnosticLog.Channel.FEED,
        "ProfileFeed" to DiagnosticLog.Channel.FEED,
        "ProfileMeta" to DiagnosticLog.Channel.FEED,
        "QuotedNote" to DiagnosticLog.Channel.FEED,
        "Bookmark" to DiagnosticLog.Channel.FEED,
        "Mute" to DiagnosticLog.Channel.FEED,
        "Emoji" to DiagnosticLog.Channel.FEED,
        "Badge" to DiagnosticLog.Channel.FEED,
        "LiveActivity" to DiagnosticLog.Channel.FEED,
        "DirectMsg" to DiagnosticLog.Channel.FEED,
        "DM" to DiagnosticLog.Channel.FEED,
    )

    /**
     * Resolve the [DiagnosticLog.Channel] for a given log tag.
     * Falls back to [DiagnosticLog.Channel.GENERAL] if no prefix matches.
     */
    fun resolveChannel(tag: String): DiagnosticLog.Channel {
        for ((prefix, channel) in TAG_CHANNEL_MAP) {
            if (tag.startsWith(prefix, ignoreCase = true)) return channel
        }
        return DiagnosticLog.Channel.GENERAL
    }

    // ── VERBOSE ─────────────────────────────────────────────────────────

    fun v(tag: String, msg: String, channel: DiagnosticLog.Channel = resolveChannel(tag), trace: String? = null) {
        if (BuildConfig.DEBUG) Log.v(tag, msg)
        DiagnosticLog.log(channel, DiagnosticLog.Level.VERBOSE, tag, msg, trace)
    }

    // ── DEBUG ───────────────────────────────────────────────────────────

    fun d(tag: String, msg: String, channel: DiagnosticLog.Channel = resolveChannel(tag), trace: String? = null) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
        DiagnosticLog.log(channel, DiagnosticLog.Level.DEBUG, tag, msg, trace)
    }

    // ── INFO ────────────────────────────────────────────────────────────

    fun i(tag: String, msg: String, channel: DiagnosticLog.Channel = resolveChannel(tag), trace: String? = null) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
        DiagnosticLog.log(channel, DiagnosticLog.Level.INFO, tag, msg, trace)
    }

    // ── WARN ────────────────────────────────────────────────────────────

    fun w(tag: String, msg: String, channel: DiagnosticLog.Channel = resolveChannel(tag), trace: String? = null) {
        Log.w(tag, msg)
        DiagnosticLog.log(channel, DiagnosticLog.Level.WARN, tag, msg, trace)
    }

    fun w(tag: String, msg: String, tr: Throwable, channel: DiagnosticLog.Channel = resolveChannel(tag), trace: String? = null) {
        Log.w(tag, msg, tr)
        DiagnosticLog.log(channel, DiagnosticLog.Level.WARN, tag, "$msg | ${tr.message}", trace)
    }

    // ── ERROR ───────────────────────────────────────────────────────────

    fun e(tag: String, msg: String, channel: DiagnosticLog.Channel = resolveChannel(tag), trace: String? = null) {
        Log.e(tag, msg)
        DiagnosticLog.log(channel, DiagnosticLog.Level.ERROR, tag, msg, trace)
    }

    fun e(tag: String, msg: String, tr: Throwable, channel: DiagnosticLog.Channel = resolveChannel(tag), trace: String? = null) {
        Log.e(tag, msg, tr)
        DiagnosticLog.log(channel, DiagnosticLog.Level.ERROR, tag, "$msg | ${tr::class.simpleName}: ${tr.message}", trace)
    }
}
