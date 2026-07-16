package com.smartech.screens.data

import android.content.Context
import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Disk cache for guided-experience HTML (v0.1.92).
 *
 * A guided experience is a single self-contained HTML file (no external
 * scripts/styles/images — see interactive/README.md on the server). We fetch
 * it once, keep it under `<filesDir>/experiences/`, and render from that local
 * copy — so once it's been pulled the experience runs with **no network**, the
 * same guarantee cached videos get.
 *
 * [ensure] refreshes from the network when reachable and always returns the
 * local file if we have one, so a wifi drop mid-shift is a non-event: the
 * cached copy keeps serving.
 */
class ExperienceCache(
    context: Context,
    sharedHttp: OkHttpClient,
) {
    private val root: File = File(context.filesDir, "experiences").apply { mkdirs() }

    // The files are tiny (tens of KB) but store wifi is flaky; inherit the
    // shared pool/interceptors and keep timeouts short — a stale copy is far
    // better than blocking the attract loop on a dead socket.
    private val http: OkHttpClient = sharedHttp.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun fileFor(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return File(root, "$hash.html")
    }

    /** True if we already hold a local copy for [url]. */
    fun cached(url: String): File? =
        fileFor(url).takeIf { it.exists() && it.length() > 0L }

    /**
     * Make sure the HTML for [url] is on disk, refreshing from the network when
     * we can reach it. Returns the local file, or null when we have no cached
     * copy and the fetch failed (offline first-run). A network failure with an
     * existing cache is NOT an error — we return the cached file.
     */
    suspend fun ensure(url: String): File? = withContext(Dispatchers.IO) {
        val target = fileFor(url)
        runCatching {
            val req = Request.Builder().url(url).header("Accept", "text/html").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body?.bytes() ?: throw IOException("empty body")
                if (body.isEmpty()) throw IOException("zero-length body")
                // Atomic-ish write so a mid-write drop can't leave a truncated
                // file that the WebView would render as a blank page.
                val part = File(target.absolutePath + ".part")
                part.writeBytes(body)
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true); part.delete()
                }
                LogBuffer.i(TAG, "Cached experience ${body.size} B ← $url")
            }
        }.onFailure {
            // Offline / server hiccup — fall back to whatever we already have.
            LogBuffer.w(TAG, "Experience fetch failed (${it.message}); using cached copy if present")
        }
        target.takeIf { it.exists() && it.length() > 0L }
    }

    companion object {
        private const val TAG = "ExperienceCache"
    }
}
