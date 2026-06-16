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

    private suspend fun download(url: String, versionName: String): File? = withContext(Dispatchers.IO) {
        val dir = File(appContext.filesDir, "updates").apply { mkdirs() }
        // Clean up old APK files so we don't accumulate them.
        dir.listFiles { f -> f.name.endsWith(".apk") }?.forEach { it.delete() }
        val target = File(dir, "screens-v$versionName.apk")
        val partial = File(dir, target.name + ".part")
        runCatching {
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogBuffer.w(TAG, "APK download HTTP ${resp.code} for $url")
                    return@use null
                }
                val body = resp.body ?: return@use null
                val total = body.contentLength().takeIf { it > 0 }
                _state.value = State.Downloading(versionName, 0L, total)
                FileOutputStream(partial).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var got = 0L
                    body.byteStream().use { input ->
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            got += read
                            _state.value = State.Downloading(versionName, got, total)
                        }
                    }
                }
                if (!partial.renameTo(target)) {
                    partial.delete()
                    LogBuffer.w(TAG, "Could not rename downloaded APK into place")
                    return@use null
                }
                LogBuffer.i(TAG, "Downloaded ${target.absolutePath} (${target.length()} bytes)")
                target
            }
        }.getOrElse {
            LogBuffer.w(TAG, "APK download failed: ${it.message}", it)
            partial.delete()
            null
        }
    }

    private fun launchInstaller(apk: File) {
        // FileProvider authority must match the entry in AndroidManifest.xml.
        val authority = "${appContext.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(appContext, authority, apk)

        // ACTION_INSTALL_PACKAGE was deprecated in API 29 in favour of
        // ACTION_VIEW with the apk mime type. Both still work; the
        // platform routes to the package installer either way.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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
        try {
            appContext.startActivity(intent)
            LogBuffer.i(TAG, "Installer intent dispatched for ${apk.name}")
        } catch (e: Exception) {
            LogBuffer.w(TAG, "Installer launch failed: ${e.message}", e)
            _state.value = State.Failed("Couldn't launch installer: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Updater"
    }
}
