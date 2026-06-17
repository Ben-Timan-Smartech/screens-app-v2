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
        data object Checking : State()
        data class UpToDate(val versionName: String) : State()
        data class Downloading(
            val versionName: String,
            val bytes: Long,
            val totalBytes: Long?,
        ) : State() {
            val fraction: Float? get() = totalBytes?.let { if (it > 0) (bytes.toFloat() / it).coerceIn(0f, 1f) else null }
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

    /** Launch + every 6h. Quiet — failures just log to LogBuffer and
     *  leave [state] at Idle so the UI doesn't flash an error overlay
     *  when the user isn't expecting one (e.g. network blip). */
    fun start(intervalMs: Long = 6L * 60L * 60L * 1000L) {
        scope.launch {
            // Stagger the first check so we don't compete with the
            // playlist refresh on boot.
            delay(10_000L)
            while (true) {
                runCatching { checkAndUpdate(surfaceFailures = false) }
                    .onFailure { LogBuffer.w(TAG, "Background updater tick failed: ${it.message}") }
                delay(intervalMs)
            }
        }
    }

    /**
     * Single-shot check + update. Used by the CMS "update" command —
     * we want failures to surface in [state] there so the user can see
     * "Update failed: …" in the staff overlay if something goes wrong.
     */
    suspend fun checkAndUpdate(surfaceFailures: Boolean = true) {
        try {
            _state.value = State.Checking
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
                _state.value = State.UpToDate(BuildConfig.VERSION_NAME)
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
        val dir = File(appContext.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, "screens-v$versionName.apk")
        val partial = File(dir, target.name + ".part")
        // v0.1.51: clean up any APKs / .part files that DON'T match
        // the version we're about to fetch. Survives the current
        // version's .part so we can resume across process restarts.
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".apk") && f.name != target.name) f.delete()
            else if (f.name.endsWith(".apk.part") && f.name != partial.name) f.delete()
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
        FileOutputStream(partial, append).use { out ->
            val buf = ByteArray(64 * 1024)
            var got = startingBytes
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    out.write(buf, 0, read)
                    got += read
                    _state.value = State.Downloading(versionName, got, total)
                }
            }
        }
    }

    private fun launchInstaller(apk: File) {
        // FileProvider authority must match the entry in AndroidManifest.xml.
        val authority = "${appContext.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(appContext, authority, apk)

        // On Android 8+ apps need REQUEST_INSTALL_PACKAGES and the user
        // (or a device admin) has to grant install-from-unknown-sources
        // for this app once. If it's not granted, send them to that
        // settings page so they can flip it on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            LogBuffer.w(TAG, "REQUEST_INSTALL_PACKAGES not granted — opening settings")
            val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${appContext.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                appContext.startActivity(settingsIntent)
            } catch (e: Exception) {
                LogBuffer.w(TAG, "Couldn't open install-sources settings: ${e.message}")
            }
            _state.value = State.Failed("Allow installs from this app, then trigger the update again.")
            return
        }

        // v0.1.48: fallback chain. Stock Android binds the system
        // PackageInstaller to ACTION_VIEW + content URI from API 24+,
        // but some Amlogic / cheap-tablet ROMs (Sumvision Cyclone,
        // TX3 Mini) ship a TV launcher that doesn't include the
        // installer's intent filter — `startActivity` throws
        // ActivityNotFoundException. Try each plausible intent shape
        // before giving up so the in-app updater can actually swap
        // builds on those boxes.
        //
        //   1. ACTION_VIEW + content://   — current Android default.
        //   2. ACTION_INSTALL_PACKAGE     — deprecated in API 29 but
        //                                   still wired on older ROMs.
        //   3. (last resort) explicit launch of the well-known
        //      PackageInstaller component.
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
        }

        // First pass: pick the first intent whose target activity
        // actually resolves. PackageManager.resolveActivity returns
        // null when no app declares an intent-filter match — we'd
        // rather discover that here than via the surprise of
        // startActivity() throwing.
        val pm = appContext.packageManager
        for ((idx, intent) in candidates.withIndex()) {
            val resolved = pm.resolveActivity(intent, 0)
            if (resolved == null) {
                LogBuffer.w(TAG, "Installer candidate $idx (${intent.action}) — no activity found, trying next")
                continue
            }
            LogBuffer.i(
                TAG,
                "Installer candidate $idx (${intent.action}) resolved to " +
                    "${resolved.activityInfo.packageName}/${resolved.activityInfo.name}",
            )
            try {
                appContext.startActivity(intent)
                LogBuffer.i(TAG, "Installer intent dispatched for ${apk.name}")
                return
            } catch (e: Exception) {
                LogBuffer.w(TAG, "Installer candidate $idx launch threw: ${e.message}", e)
            }
        }

        // Nothing on the device claims the APK-install intent. This is
        // a ROM-config issue, not something the user can fix from the
        // app — but at least tell them what to do.
        LogBuffer.w(TAG, "No installer activity found on this device — APK left at ${apk.absolutePath}")
        _state.value = State.Failed(
            "This device has no package installer registered. Install manually by " +
                "copying the APK from ${apk.absolutePath} and tapping it from a file " +
                "manager, or sideload via ADB.",
        )
    }

    companion object {
        private const val TAG = "Updater"
    }
}
