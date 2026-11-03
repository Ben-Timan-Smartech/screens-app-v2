package com.smartech.screens.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebView

/**
 * v0.2.16: which WebView implementation (package + version) this box runs.
 *
 * Reported in the heartbeat so the CMS can show it — web page mode lives or
 * dies by the system WebView, and before this there was no way to check a
 * box's WebView without walking up to it with adb. Cached for a few minutes:
 * the heartbeat asks every 10 s, but the WebView only changes on an update.
 */
object WebViewInfo {

    data class Snapshot(val packageName: String, val versionName: String?)

    private const val CACHE_MS = 10 * 60_000L
    @Volatile private var cached: Snapshot? = null
    @Volatile private var cachedAtMs = 0L

    fun current(context: Context): Snapshot? {
        val now = System.currentTimeMillis()
        if (cachedAtMs != 0L && now - cachedAtMs < CACHE_MS) return cached
        val snap = runCatching { lookup(context) }.getOrNull()
        cached = snap
        cachedAtMs = now
        return snap
    }

    private fun lookup(context: Context): Snapshot? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()?.let { return Snapshot(it.packageName, it.versionName) }
        }
        // Pre-O (legacy build) has no public API for the active provider, so
        // report the first of the usual providers that's installed.
        val pm = context.packageManager
        for (pkg in listOf("com.google.android.webview", "com.android.webview", "com.android.chrome")) {
            try {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, 0)
                return Snapshot(info.packageName, info.versionName)
            } catch (_: PackageManager.NameNotFoundException) {
                // try the next one
            }
        }
        return null
    }
}
