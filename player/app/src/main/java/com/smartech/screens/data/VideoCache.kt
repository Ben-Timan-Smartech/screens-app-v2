package com.smartech.screens.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk-backed LRU video cache. Lives under the app's internal files dir —
 * survives app restarts, cleared on uninstall. Honours a soft cap (default 8GB)
 * with oldest-access-first eviction.
 *
 * Layout: `<filesDir>/videos/<videoId>.mp4`.
 *
 * Download strategy: stream from [OkHttpClient] straight to disk, atomically
 * rename from `.part` on completion. Partial files are orphaned on crash and
 * reaped on next startup.
 */
class VideoCache(
    context: Context,
    private val http: OkHttpClient,
) {
    private val root: File = File(context.filesDir, "videos").apply { mkdirs() }
    private val inflight = ConcurrentHashMap<String, Boolean>()

    init {
        // Reap stray .part files from a previous crash.
        root.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    fun file(videoId: String): File = File(root, "$videoId.mp4")
    fun has(videoId: String): Boolean = file(videoId).exists() && file(videoId).length() > 0

    /**
     * Ensure a video is present locally. If already cached, returns immediately.
     * If [onProgress] is supplied, emits `(bytesDownloaded, totalBytesOrNull)`
     * as bytes arrive. `total` may be null if the server didn't send a
     * Content-Length, e.g. a chunked response.
     */
    suspend fun ensure(
        item: VideoItem,
        onProgress: ((bytes: Long, total: Long?) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val target = file(item.id)
        if (target.exists() && target.length() > 0) return@withContext target
        if (inflight.putIfAbsent(item.id, true) != null) {
            while (inflight.containsKey(item.id)) Thread.sleep(100)
            return@withContext target
        }
        try {
            downloadTo(item.url, target, onProgress)
            target
        } finally {
            inflight.remove(item.id)
        }
    }

    private fun downloadTo(
        url: String,
        target: File,
        onProgress: ((bytes: Long, total: Long?) -> Unit)?,
    ) {
        val partial = File(target.parentFile, target.name + ".part")
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Download failed: HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw IllegalStateException("Empty body for $url")
            val total: Long? = body.contentLength().takeIf { it > 0 }
            // Initial 0-byte tick so UI can show "starting…" without waiting
            // for the first chunk on slow connections.
            onProgress?.invoke(0L, total)
            FileOutputStream(partial).use { out ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                var accumulated = 0L
                body.byteStream().use { input ->
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        accumulated += read
                        onProgress?.invoke(accumulated, total)
                    }
                }
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                throw IllegalStateException("Could not rename part file into place")
            }
        }
    }

    /**
     * Drop files not referenced by [keep], then evict least-recently-used until
     * under [capBytes]. Returns the list of evicted ids (useful for telemetry).
     */
    suspend fun reconcile(keep: Set<String>, capBytes: Long): List<String> =
        withContext(Dispatchers.IO) {
            val evicted = mutableListOf<String>()

            // Remove anything no longer in the playlist.
            root.listFiles { f -> f.isFile && f.extension == "mp4" }?.forEach { f ->
                val id = f.nameWithoutExtension
                if (id !in keep) {
                    if (f.delete()) evicted += id
                }
            }

            // If still over cap, evict oldest first.
            var total = totalBytes()
            if (total <= capBytes) return@withContext evicted

            val remaining = root.listFiles { f -> f.isFile && f.extension == "mp4" }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()
            for (f in remaining) {
                if (total <= capBytes) break
                val size = f.length()
                if (f.delete()) {
                    evicted += f.nameWithoutExtension
                    total -= size
                    Log.w(TAG, "Evicted ${f.name} (size $size)")
                }
            }
            evicted
        }

    fun cachedIds(): List<String> =
        root.listFiles { f -> f.isFile && f.extension == "mp4" }
            ?.map { it.nameWithoutExtension } ?: emptyList()

    fun totalBytes(): Long =
        root.listFiles { f -> f.isFile }?.sumOf { it.length() } ?: 0L

    companion object {
        private const val TAG = "VideoCache"
    }
}
