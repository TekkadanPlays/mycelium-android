package social.mycelium.android.lightning

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.LoggerConfig
import co.touchlab.kermit.Severity
import fr.acinq.lightning.logging.LoggerFactory

/**
 * Routes lightning-kmp's Kermit logs to Android Logcat.
 */
private object AndroidLogWriter : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        when (severity) {
            Severity.Verbose -> android.util.Log.v(tag, message, throwable)
            Severity.Debug -> android.util.Log.d(tag, message, throwable)
            Severity.Info -> android.util.Log.i(tag, message, throwable)
            Severity.Warn -> android.util.Log.w(tag, message, throwable)
            Severity.Error -> android.util.Log.e(tag, message, throwable)
            Severity.Assert -> android.util.Log.wtf(tag, message, throwable)
        }
    }
}

val AndroidLoggerFactory = LoggerFactory(
    object : LoggerConfig {
        override val logWriterList: List<LogWriter> = listOf(AndroidLogWriter)
        override val minSeverity: Severity = Severity.Debug
    }
)
