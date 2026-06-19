package com.smartech.screens.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.smartech.screens.BuildConfig
import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * In-app self-updater.
 *
 * Polls `/api/release/latest` on the CMS, compares the reported
 * versionCode to our own BuildConfig.VERSION_CODE, and — when the
 * server's is higher — downloads the modern APK to internal storage
 * and hands it to Android's PackageInstaller via a FileProvider URI.
 * Android's standard installer UI takes over from there (briefly:
 * "Update Screens?" → user presses OK → install runs → app restarts).
 *
 * Two triggers feed in:
 *   • Launch + every 6h while the app is running (the background loop
 *     started by [start]).
 *   • The CMS "update" command — `PlayerRepository.executeCommand`
 *     calls [checkAndUpdate] directly for an immediate update.
 *
 * The current state is exposed as [state] so a Compose overlay can
 * render "Updating to v0.1.1…" while the download is in flight.
 *
 * Signing caveat: PackageInstaller will refuse to install an APK whose
 * signing certificate differs from the one already on the device —
 * that's Android's "update vs reinstall" rule. As long as every CI
 * build uses the same release keystore (configured via repo secrets;
 * see release.yml), updates are seamless. If signatures ever diverge
 * the user gets an explicit error and has to uninstall + reinstall
 * once. The bundled debug keystore that ships in CI today is
 * deterministic per-runner, NOT deterministic across runners — so
 * v0.1.0 → v0.1.1 may require one manual reinstall until proper
 * release signing is in place.
 */
class Updater(
    private val appContext: Context,
    private val http: OkHttpClient,
    private val backendBaseUrlProvider: suspend () -> String?,
) {
    // v0.1.51: dedicated client for the APK download. Same pattern as
    // VideoCache (v0.1.39): the shared API client's 60 s callTimeout
    // is correct for /api/release/latest (a tiny JSON) but kills a
    // 4 MB APK download on a slow event-wifi connection. We inherit
    // the connection pool + interceptors via newBuilder() and lift
    // the call timeout while keeping connect/read sensible.
    private val downloadHttp: OkHttpClient = http.newBuilder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS) // unbounded — Range-resume handles drops
        .retryOnConnectionFailure(true)
        .build()
    sealed class State {
        data object Idle : State()
        // v0.1.72: `manual` marks a user-triggered check (CMS command /
        // staff button) so the overlay can show "Checking…" / "You're on
        // the latest" feedback. Background ticks leave it false and paint
        // nothing, avoiding flicker over the player.
        data class Checking(val manual: Boolean = false) : State()
        data class UpToDate(val versionName: String, val manual: Boolean = false) : State()
        data class Downloading(
            val versionName: String,
            val bytes: Long,
            val totalBytes: Long?,
            // v0.1.72: rolling throughput (bytes/sec) for the progress
            // dialog's speed + ETA readout. Null until the first chunk.
            val bytesPerSec: Long? = null,
        ) : State() {
            val fraction: Float? get() = totalBytes?.let { if (it > 0) (bytes.toFloat() / it).coerceIn(0f, 1f) else null }
            /** Seconds remaining at the current rate, or null if unknown. */
            val etaSeconds: Long? get() {
                val total = totalBytes ?: return null
                val rate = bytesPerSec ?: return null
                if (rate <= 0L) return null
                return (total - bytes).coerceAtLeast(0L) / rate
            }
        }
        data class Installing(val versionName: String) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable
    private data class LatestReleaseDto(
        val tagName: String? = null,
        val versionName: String? = null,
        val versionCode: Int = 0,
        val modernUrl: String? = null,
        val legacyUrl: String? = null,
    )

    /** Launch + periodic check on a tighter cadence than the original
     *  6 h — we now gate each tick on the time-of-day window so most
     *  ticks just immediately return. Quiet — failures only log to
     *  LogBuffer; [state] stays at Idle so the UI doesn't flash an
     *  error overlay when the user isn't expecting one.
     *
     *  v0.1.52: skip ticks that fall outside the evening window
     *  ([AUTO_UPDATE_START_HOUR]..[AUTO_UPDATE_END_HOUR], local time).
     *  Auto-pulling an APK at 14:30 used to interrupt mid-shift
     *  playback for the install prompt; restricting to overnight
     *  means screens swap version while the store is closed.
     *  Manual triggers — CMS "update" command, staff overlay's
     *  Update button, the tablet command palette — call
     *  [checkAndUpdate] directly and aren't subject to this gate. */
    fun start(intervalMs: Long = 2L * 60L * 60L * 1000L) {
        scope.launch {
            // Stagger the first check so we don't compete with the
            // playlist refresh on boot.
            delay(10_000L)
            while (true) {
                if (inAutoUpdateWindow()) {
                    runCatching { checkAndUpdate(surfaceFailures = false) }
                        .onFailure { LogBuffer.w(TAG, "Background updater tick failed: ${it.message}") }
                } else {
                    // Verbose enough that we can grep logs for "why
                    // didn't this device update overnight?" but not
                    // so chatty it spams the JSONL log.
                    LogBuffer.i(
                        TAG,
                        "Skipping auto-update — outside ${AUTO_UPDATE_START_HOUR}:00–${AUTO_UPDATE_END_HOUR}:00 window",
                    )
                }
                delay(intervalMs)
            }
        }
    }

    /** True when the local clock falls inside the configured evening
     *  window. Wraps midnight: with start=22, end=6 the window covers
     *  22:00–23:59 + 00:00–05:59. Uses [Calendar] so it works on the
     *  legacy minSdk-23 boxes (java.time needs API 26 / desugaring). */
    private fun inAutoUpdateWindow(): Boolean {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val s = AUTO_UPDATE_START_HOUR
        val e = AUTO_UPDATE_END_HOUR
        return if (s == e) {
            // start == end means "always" by convention — useful for
            // disabling the gate via a future runtime override.
            true
        } else if (s < e) {
            hour in s until e
        } else {
            // Wraps midnight (e.g. 22..6 means 22..23 OR 0..5).
            hour >= s || hour < e
        }
    }

    /**
     * Single-shot check + update. Used by the CMS "update" command —
     * we want failures to surface in [state] there so the user can see
     * "Update failed: …" in the staff overlay if something goes wrong.
     */
    suspend fun checkAndUpdate(surfaceFailures: Boolean = true) {
        try {
            _state.value = State.Checking(manual = surfaceFailures)
            val base = backendBaseUrlProvider()
            if (base.isNullOrBlank()) {
                LogBuffer.w(TAG, "No backend URL — can't check for updates")
                _state.value = State.Idle
                return
            }
            val latest = fetchLatest(base.trimEnd('/'))
            if (latest == null) {
                if (surfaceFailures) _state.value = State.Failed("Couldn't reach the update server")
                else _state.value = State.Idle
                return
            }
            val currentCode = BuildConfig.VERSION_CODE
            if (latest.versionCode <= currentCode) {
                LogBuffer.i(TAG, "Up to date: server v${latest.versionName} (code ${latest.versionCode}) vs local ${BuildConfig.VERSION_NAME} (code $currentCode)")
                _state.value = State.UpToDate(BuildConfig.VERSION_NAME, manual = surfaceFailures)
                return
            }
            // v0.1.40: pick the APK that matches this build's flavor.
            // Pre-v0.1.40 the updater always grabbed `modernUrl`, so
            // legacy tablets that hit "Update" downloaded the modern
            // APK and either refused to install (signature mismatch
            // looks the same — Android cares about the package's
            // declared minSdk) or installed but broke under the boot
            // env. We now route by `BuildConfig.FLAVOR` and fall back
            // to modern only when no legacy URL is in the release
            // (e.g. a workflow_dispatch with include_legacy=false).
            val isLegacy = BuildConfig.FLAVOR == "legacy"
            val url = when {
                isLegacy && !latest.legacyUrl.isNullOrBlank() -> latest.legacyUrl
                !isLegacy -> latest.modernUrl
                // Legacy build but no legacy APK in this release — bail
                // rather than push a modern APK that won't install.
                else -> null
            }
            if (url.isNullOrBlank()) {
                val msg = if (isLegacy)
                    "Release ${latest.versionName} has no legacy APK — sideload required"
                else
                    "Release ${latest.versionName} has no APK URL"
                if (surfaceFailures) _state.value = State.Failed(msg)
                else _state.value = State.Idle
                return
            }
            // v0.1.72: gate on the install permission BEFORE downloading.
            // Pulling 4 MB only to dead-end at the "install unknown apps"
            // wall — then re-pulling on the retry — was the "downloads
            // twice" bug. Prompt now; the operator enables it and re-runs.
            if (!canInstallPackages()) {
                LogBuffer.w(TAG, "Install-unknown-apps not granted — prompting before download")
                openInstallSettings()
                _state.value = State.Failed(
                    "Allow \"Install unknown apps\" for Screens (Settings just opened), " +
                        "then tap Update again."
                )
                return
            }
            LogBuffer.i(TAG, "Update available: ${latest.versionName} (code ${latest.versionCode}) flavor=${BuildConfig.FLAVOR} — downloading")
            val apk = download(url, latest.versionName ?: "unknown")
            if (apk == null) {
                if (surfaceFailures) _state.value = State.Failed("Download failed")
                else _state.value = State.Idle
                return
            }
            _state.value = State.Installing(latest.versionName ?: "unknown")
            launchInstaller(apk)
            // Don't reset state — the installer UI is now in front of us.
            // If the user cancels we'll go back to Idle on the next check.
        } catch (t: Throwable) {
            LogBuffer.w(TAG, "Update flow crashed: ${t.message}", t)
            if (surfaceFailures) _state.value = State.Failed(t.message ?: "Unknown error")
            else _state.value = State.Idle
        }
    }

    /** Reset the state — used by the dismiss button on a Failed dialog. */
    fun dismiss() {
        _state.value = State.Idle
    }

    private suspend fun fetchLatest(base: String): LatestReleaseDto? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$base/api/release/latest").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    LogBuffer.w(TAG, "release/latest HTTP ${r.code}")
                    return@use null
                }
                val body = r.body?.string() ?: return@use null
                json.decodeFromString<LatestReleaseDto>(body)
            }
        }.getOrElse {
            LogBuffer.w(TAG, "release/latest fetch failed: ${it.message}")
            null
        }
    }

    /**
     * Stream the APK from `url` into `<filesDir>/updates/<name>.apk`,
     * resuming across attempts via Range requests.
     *
     * v0.1.51: mirrors the VideoCache.downloadTo path. Spotty wifi
     * at events made the in-app updater unusable on legacy boxes:
     * a 4 MB APK at 50 KB/s on a flapping connection had no chance
     * inside the old 60 s callTimeout, and the previous code wiped
     * the partial file on any IOException. Now:
     *
     *   • `.part` for the SAME versionName survives across attempts
     *     and across process restarts. `.part` for OTHER versions
     *     gets cleaned up so disk doesn't fill.
     *   • Each attempt sends `Range: bytes=<existing>-` if there are
     *     existing bytes on disk. 206 appends; 200 truncates (server
     *     ignored Range); 416 wipes + retries.
     *   • IOException → capped exponential backoff (1.5 s → 30 s)
     *     up to 6 attempts.
     */
    private suspend fun download(url: String, versionName: String): File? = withContext(Dispatchers.IO) {
        // v0.1.54: write to external app-scoped storage instead of
        // internal. Both are wiped on uninstall, but external is
        // reachable from a file manager + adb pull without root — the
        // path that lands in the "manual install" fallback message
        // is now actually actionable. Falls back to internal storage
        // on devices where external is unavailable (rare; legacy
        // Amlogic boxes typically have it).
        val dir = (appContext.getExternalFilesDir("updates") ?: File(appContext.filesDir, "updates"))
            .apply { mkdirs() }
        val target = File(dir, "screens-v$versionName.apk")
        val partial = File(dir, target.name + ".part")
        // v0.1.51: clean up any APKs / .part files that DON'T match
        // the version we're about to fetch. Survives the current
        // version's .part so we can resume across process restarts.
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".apk") && f.name != target.name) f.delete()
            else if (f.name.endsWith(".apk.part") && f.name != partial.name) f.delete()
        }

        // v0.1.72: if the completed APK for this exact version is already
        // on disk, reuse it instead of re-downloading. The premature-EOF
        // guard below only ever renames a *complete* .part into .apk, so a
        // present target is whole. This is what stops the "downloads twice"
        // behaviour when the first attempt downloaded fine but the install
        // didn't complete (permission wall / dismissed prompt).
        if (target.exists() && target.length() > 0L) {
            LogBuffer.i(TAG, "Reusing cached ${target.name} (${target.length()} bytes) — skipping download")
            return@withContext target
        }

        val maxAttempts = 6
        var attempt = 0
        var backoffMs = 1_500L
        var lastError: Throwable? = null
        while (attempt < maxAttempts) {
            attempt++
            val existing = if (partial.exists()) partial.length() else 0L
            try {
                val reqBuilder = Request.Builder().url(url)
                if (existing > 0L) {
                    reqBuilder.header("Range", "bytes=$existing-")
                    LogBuffer.i(TAG, "Resuming APK download from byte $existing (attempt $attempt)")
                }
                downloadHttp.newCall(reqBuilder.build()).execute().use { resp ->
                    when (resp.code) {
                        206 -> {
                            val body = resp.body ?: throw java.io.IOException("Empty body on 206")
                            val remaining = body.contentLength().takeIf { it > 0 } ?: -1L
                            val total = if (remaining > 0) existing + remaining else null
                            _state.value = State.Downloading(versionName, existing, total)
                            streamBodyToPart(body, partial, existing, total, versionName, append = true)
                        }
                        200 -> {
                            if (existing > 0L) {
                                LogBuffer.w(TAG, "Server ignored Range; restarting APK download from 0")
                            }
                            val body = resp.body ?: throw java.io.IOException("Empty body on 200")
                            val total = body.contentLength().takeIf { it > 0 }
                            _state.value = State.Downloading(versionName, 0L, total)
                            streamBodyToPart(body, partial, 0L, total, versionName, append = false)
                        }
                        416 -> {
                            LogBuffer.w(TAG, "HTTP 416 for APK; discarding partial and retrying")
                            partial.delete()
                            throw java.io.IOException("Range not satisfiable; reset")
                        }
                        else -> {
                            throw java.io.IOException("APK download HTTP ${resp.code} for $url")
                        }
                    }
                }
                // Success — promote .part → .apk atomically.
                if (!partial.renameTo(target)) {
                    partial.delete()
                    throw java.io.IOException("Could not rename .part into place")
                }
                LogBuffer.i(TAG, "Downloaded ${target.absolutePath} (${target.length()} bytes)")
                return@withContext target
            } catch (e: java.io.IOException) {
                lastError = e
                LogBuffer.w(TAG, "APK download attempt $attempt/$maxAttempts failed: ${e.message}")
                if (attempt >= maxAttempts) break
                try {
                    Thread.sleep(backoffMs)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@withContext null
                }
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
        LogBuffer.w(TAG, "APK download failed after $maxAttempts attempts: ${lastError?.message}", lastError)
        null
    }

    private fun streamBodyToPart(
        body: okhttp3.ResponseBody,
        partial: File,
        startingBytes: Long,
        total: Long?,
        versionName: String,
        append: Boolean,
    ) {
        // v0.1.53: same premature-EOF guard as VideoCache. OkHttp's
        // input stream returns -1 cleanly when a midstream socket
        // close happens, with no exception — so without this check
        // we'd rename a truncated .part into .apk and the installer
        // would fail with a confusing "package parse" error. Catching
        // it here pushes the caller's retry loop, which will resume
        // via Range for the missing tail.
        val expectedBodyBytes = body.contentLength()
        var bytesFromBody = 0L
        // v0.1.72: rolling throughput for the progress dialog. Averaged
        // since this attempt's first byte — smooth enough for a 4 MB file,
        // and resets cleanly when a resumed attempt starts a fresh stream.
        val startedAtMs = System.currentTimeMillis()
        FileOutputStream(partial, append).use { out ->
            val buf = ByteArray(64 * 1024)
            var got = startingBytes
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    out.write(buf, 0, read)
                    got += read
                    bytesFromBody += read
                    val elapsed = System.currentTimeMillis() - startedAtMs
                    val rate = if (elapsed > 250) bytesFromBody * 1000L / elapsed else null
                    _state.value = State.Downloading(versionName, got, total, rate)
                }
            }
        }
        if (expectedBodyBytes > 0 && bytesFromBody < expectedBodyBytes) {
            throw java.io.IOException(
                "Premature EOF on APK: got $bytesFromBody of $expectedBodyBytes body bytes"
            )
        }
    }

    /** v0.1.72: true when this app can launch a package install. On
     *  Android 8+ that needs the per-app "install unknown apps" grant;
     *  pre-8 it's a global setting we can't check, so we assume true and
     *  let the system installer prompt. */
    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            appContext.packageManager.canRequestPackageInstalls()

    /** Open the per-app "install unknown apps" settings page so the
     *  operator can grant it. No-op pre-Android 8. */
    private fun openInstallSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            appContext.startActivity(intent)
        } catch (e: Exception) {
            LogBuffer.w(TAG, "Couldn't open install-sources settings: ${e.message}")
        }
    }

    private fun launchInstaller(apk: File) {
        // FileProvider authority must match the entry in AndroidManifest.xml.
        val authority = "${appContext.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(appContext, authority, apk)

        // Safety net — checkAndUpdate already gates on this before the
        // download, but the background path or a revoked grant could still
        // reach here without permission. Send the operator to settings.
        if (!canInstallPackages()) {
            LogBuffer.w(TAG, "REQUEST_INSTALL_PACKAGES not granted — opening settings")
            openInstallSettings()
            _state.value = State.Failed("Allow \"Install unknown apps\" for Screens, then trigger the update again.")
            return
        }

        // v0.1.48/v0.1.54: fallback chain. Stock Android binds the
        // system PackageInstaller to ACTION_VIEW + content URI from
        // API 24+, but some Amlogic / cheap-tablet ROMs (Sumvision
        // Cyclone, TX3 Mini) ship custom installers that don't
        // declare the standard intent filter — `resolveActivity`
        // returns null even though there IS a working installer on
        // the device. Try every plausible intent shape, then as a
        // last resort fire them through `startActivity` blindly
        // (some installers register without the DEFAULT category,
        // which `resolveActivity(intent, 0)` filters out but
        // `startActivity` can still dispatch to).
        //
        //   1. ACTION_VIEW + content://      — current Android.
        //   2. ACTION_INSTALL_PACKAGE + content:// — older ROMs.
        //   3. ACTION_VIEW + file://         — Android 6/7 only
        //                                      (FileUriExposedException
        //                                       above API 24).
        val candidates = buildList {
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            @Suppress("DEPRECATION")
            add(
                Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    putExtra(Intent.EXTRA_RETURN_RESULT, false)
                    putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, appContext.packageName)
                }
            )
            // v0.1.54: file:// scheme for Android 6 (API 23) — some
            // Amlogic ROMs ship installers wired to the file scheme
            // only. Skipped on API 24+ where this would throw
            // FileUriExposedException.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                add(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            Uri.fromFile(apk),
                            "application/vnd.android.package-archive",
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }

        val pm = appContext.packageManager
        // First pass: prefer candidates whose target activity
        // resolves cleanly. resolveActivity(intent, 0) implies
        // MATCH_DEFAULT_ONLY — most installers declare DEFAULT
        // category and match here.
        for ((idx, intent) in candidates.withIndex()) {
            val resolved = pm.resolveActivity(intent, 0)
            if (resolved == null) {
                LogBuffer.w(TAG, "Installer candidate $idx (${intent.action} ${intent.data?.scheme}) — no activity, will retry blind")
                continue
            }
            LogBuffer.i(
                TAG,
                "Installer candidate $idx (${intent.action} ${intent.data?.scheme}) resolved to " +
                    "${resolved.activityInfo.packageName}/${resolved.activityInfo.name}",
            )
            try {
                appContext.startActivity(intent)
                LogBuffer.i(TAG, "Installer intent dispatched for ${apk.name}")
                return
            } catch (e: Exception) {
                LogBuffer.w(TAG, "Installer candidate $idx startActivity threw: ${e.message}", e)
            }
        }

        // v0.1.54: blind pass. Some custom ROMs ship the installer
        // without the DEFAULT category — `resolveActivity` returns
        // null for them, but `startActivity` can still find the
        // activity if it knows the package. We've already logged the
        // failure of every candidate above; now retry each by going
        // straight to startActivity and catching ActivityNotFound.
        for ((idx, intent) in candidates.withIndex()) {
            try {
                appContext.startActivity(intent)
                LogBuffer.i(
                    TAG,
                    "Installer candidate $idx (${intent.action} ${intent.data?.scheme}) " +
                        "dispatched via blind startActivity",
                )
                return
            } catch (e: android.content.ActivityNotFoundException) {
                LogBuffer.w(TAG, "Installer candidate $idx blind dispatch: ActivityNotFoundException")
            } catch (e: Exception) {
                LogBuffer.w(TAG, "Installer candidate $idx blind dispatch threw: ${e.message}", e)
            }
        }

        // Nothing on the device claims the APK-install intent. The
        // path we surface is external app-scoped storage (v0.1.54),
        // so the operator can actually navigate to it from a file
        // manager without needing root or adb.
        LogBuffer.w(TAG, "No installer activity found on this device — APK left at ${apk.absolutePath}")
        _state.value = State.Failed(
            "This device has no package installer registered. Install manually:\n\n" +
                "1. Open a file manager on the device.\n" +
                "2. Navigate to ${apk.absolutePath}\n" +
                "3. Tap the APK to install.\n\n" +
                "Or sideload via adb: `adb install ${apk.absolutePath}`",
        )
    }

    companion object {
        private const val TAG = "Updater"

        /** v0.1.52: hours-of-day (local) bracketing the auto-update
         *  window. Inclusive of start, exclusive of end — so 22..6 means
         *  the loop checks for updates between 22:00 and 05:59 local
         *  time. Hardcoded for now; if a store ever needs a different
         *  window we can lift it to a per-screen server-pushed setting. */
        private const val AUTO_UPDATE_START_HOUR = 22
        private const val AUTO_UPDATE_END_HOUR = 6
    }
}
