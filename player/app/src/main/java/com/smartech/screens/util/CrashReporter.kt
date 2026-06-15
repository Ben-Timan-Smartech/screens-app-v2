package com.smartech.screens.util

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Persistent crash reporter for the tablet.
 *
 * Installs a `Thread.setDefaultUncaughtExceptionHandler` that, when
 * something throws past every catch in the app, writes a structured
 * JSON record to `<filesDir>/crashes/<timestamp>.json` and then
 * chains to whatever handler was already installed (so the process
 * still dies the way Android expects).
 *
 * On the next launch the app calls [drainTo] from a coroutine to
 * ship pending records to the server (`POST /api/crashes`). Files
 * are deleted only on a 2xx response — if the server is unreachable
 * the records stay on disk and get retried on the launch after that.
 *
 * Why we don't use Crashlytics/Sentry/Bugsnag:
 *  • Cloud Run already runs the CMS, so a single HTTP endpoint on
 *    the same host is one less third-party setup + one less auth
 *    boundary to manage.
 *  • Smartech runs in low-bandwidth retail networks where the only
 *    HTTPS host on the corporate allow-list is the CMS itself —
 *    pinging Sentry would be blocked.
 *  • The dataset is small (single-digit crashes per fleet per
 *    week, hopefully). No need for sampling / quotas.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    /** Cap the number of LogBuffer entries embedded in each crash
     *  record. Enough context to reconstruct the last few seconds
     *  without bloating the report to KB. */
    private const val MAX_LOG_ENTRIES = 40

    @Serializable
    data class LogEntry(
        val time: Long,
        val level: String,
        val tag: String,
        val message: String,
    )

    @Serializable
    data class CrashRecord(
        /** Wall-clock ms at crash time. */
        val timeMs: Long,
        val appVersion: String,
        val versionCode: Int,
        val deviceModel: String,
        val androidVersion: String,
        /** Persistent device ID assigned by DeviceStore. Null on
         *  rare first-run crashes before the ID is generated. */
        val deviceId: String?,
        /** Human-readable screen code from onboarding. Same nullable
         *  caveat as deviceId. */
        val screenCode: String?,
        val exceptionClass: String,
        val exceptionMessage: String?,
        /** Full stack trace as a printable string. Includes the
         *  causal chain — Throwable.printStackTrace walks it for us. */
        val stackTrace: String,
        /** Last [MAX_LOG_ENTRIES] log lines before the crash. Often
         *  the smoking gun. */
        val recentLog: List<LogEntry>,
        /** Thread name where the exception was thrown. */
        val threadName: String,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    @Volatile private var crashesDir: File? = null
    @Volatile private var deviceIdProvider: (() -> String?)? = null
    @Volatile private var screenCodeProvider: (() -> String?)? = null

    /**
     * Install the uncaught-exception handler and prepare the on-disk
     * spool directory. Call once from `ScreensApp.onCreate()` —
     * idempotent if called more than once.
     */
    fun install(
        context: Context,
        deviceIdProvider: () -> String?,
        screenCodeProvider: () -> String?,
    ) {
        if (crashesDir != null) return
        this.deviceIdProvider = deviceIdProvider
        this.screenCodeProvider = screenCodeProvider
        val dir = File(context.filesDir, "crashes").apply { mkdirs() }
        crashesDir = dir
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Never let the crash reporter itself crash the crash
            // reporter; we don't want a corrupted spool to short-
            // circuit the rest of the recovery chain.
            try {
                recordCrash(throwable, thread.name)
            } catch (_: Throwable) {
                // best-effort
            }
            // Chain to whatever was already installed (typically
            // Android's RuntimeInit handler) so the process actually
            // terminates and the OS cleans up properly.
            prev?.uncaughtException(thread, throwable)
        }
        LogBuffer.i(TAG, "Crash reporter installed; pending=${pendingCount()}")
    }

    /** Number of crash records currently spooled on disk. Cheap to
     *  call; used for logging + a UI badge if we surface one later. */
    fun pendingCount(): Int =
        crashesDir?.list()?.count { it.endsWith(".json") } ?: 0

    /**
     * Ship every spooled crash to [uploader]. Returns the number
     * that uploaded successfully — failures stay on disk for the
     * next attempt. Should be called once on launch from a
     * background coroutine (the caller owns the HTTP client and
     * the auth/URL details, so we don't have to depend on OkHttp
     * here).
     */
    suspend fun drainTo(uploader: suspend (CrashRecord) -> Boolean): Int = withContext(Dispatchers.IO) {
        val dir = crashesDir ?: return@withContext 0
        val files = dir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedBy { it.name }
            ?: return@withContext 0
        var shipped = 0
        for (f in files) {
            val record = runCatching {
                json.decodeFromString(CrashRecord.serializer(), f.readText())
            }.getOrNull()
            if (record == null) {
                // Corrupt or unreadable — drop so we don't loop forever.
                f.delete()
                continue
            }
            val ok = runCatching { uploader(record) }.getOrElse { false }
            if (ok) {
                f.delete()
                shipped++
            } else {
                // First failure — stop. Server's down or rate-limiting;
                // we'll retry on the next launch. Avoids a stampede.
                break
            }
        }
        if (shipped > 0) LogBuffer.i(TAG, "Shipped $shipped crash report(s)")
        shipped
    }

    private fun recordCrash(throwable: Throwable, threadName: String) {
        val dir = crashesDir ?: return
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val log = LogBuffer.entries.value.take(MAX_LOG_ENTRIES).map {
            LogEntry(
                time = it.time,
                level = it.level.name,
                tag = it.tag,
                message = it.message + (it.cause?.let { c -> " · $c" } ?: ""),
            )
        }
        val record = CrashRecord(
            timeMs = System.currentTimeMillis(),
            appVersion = com.smartech.screens.BuildConfig.VERSION_NAME,
            versionCode = com.smartech.screens.BuildConfig.VERSION_CODE,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceId = runCatching { deviceIdProvider?.invoke() }.getOrNull(),
            screenCode = runCatching { screenCodeProvider?.invoke() }.getOrNull(),
            exceptionClass = throwable.javaClass.name,
            exceptionMessage = throwable.message,
            stackTrace = sw.toString(),
            recentLog = log,
            threadName = threadName,
        )
        val file = File(dir, "${record.timeMs}.json")
        file.writeText(json.encodeToString(CrashRecord.serializer(), record))
    }
}
