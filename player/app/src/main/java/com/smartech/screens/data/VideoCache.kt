package com.smartech.screens.data

import android.content.Context
import android.util.Log
import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Disk-backed LRU video cache. Lives under the app's internal files dir —
 * survives app restarts, cleared on uninstall. Honours a soft cap (default 8GB)
 * with oldest-access-first eviction.
 *
 * Layout: `<filesDir>/videos/<videoId>.mp4` for completed downloads,
 *         `<filesDir>/videos/<videoId>.mp4.part` for in-progress.
 *
 * v0.1.39: resumable downloads. The `.part` file is preserved across:
 *   - retries within a single [ensure] call (transient drops → backoff + Range)
 *   - process restarts (init no longer reaps .part files; ensure resumes
 *     from whatever bytes are already on disk)
 *
 * Spotty in-store wifi was burning through full re-downloads of 100+ MB
 * videos on every drop. With Range-resume + an HTTP client that doesn't
 * impose a callTimeout, the same drop costs us only the bytes after the
 * last write.
 */
class VideoCache(
    context: Context,
    sharedHttp: OkHttpClient,
) {
    private val root: File = File(context.filesDir, "videos").apply { mkdirs() }
    private val inflight = ConcurrentHashMap<String, Boolean>()

    // v0.1.39: dedicated downloader client. The shared OkHttp client has a
    // 60 s callTimeout (correct for API requests) — for a 300 MB video on
    // 1 Mbps wifi that's nowhere near enough. We inherit the connection
    // pool + interceptors via newBuilder() and lift the call timeout while
    // keeping connect/read sensible so a truly dead socket still bails.
    private val http: OkHttpClient = sharedHttp.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // unbounded — Range-resume handles drops
        .retryOnConnectionFailure(true)
        .build()

    // v0.1.39: was `root.listFiles { ... }?.forEach { it.delete() }` which
    // reaped .part files on every cold start, killing resumable state.
    // Now we leave them alone — they're the canonical resume marker.
    // Stale .part files for videos no longer in the playlist get cleaned
    // up by [reconcile] alongside their .mp4 siblings.
    init { /* intentionally empty — keep .part across restarts */ }

    fun file(videoId: String): File = File(root, "$videoId.mp4")
    fun has(videoId: String): Boolean = file(videoId).exists() && file(videoId).length() > 0

    /**
     * v0.1.73: purge a cached video (completed + any partial) so the next
     * [ensure] re-downloads a clean copy. Used by [PlaybackWatchdog] when
     * ExoPlayer reports a corrupt/truncated source file
     * (ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE / container-malformed) —
     * the bytes on disk are bad, and prepare() would just re-read them.
     */
    fun invalidate(videoId: String) {
        val mp4 = file(videoId)
        val part = File(root, "$videoId.mp4.part")
        val deleted = mp4.delete()
        part.delete()
        LogBuffer.i(TAG, "Invalidated cached video $videoId (mp4 removed=$deleted)")
    }

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
            // The first caller finished — but "finished" includes "failed".
            // Don't return `target` unconditionally: if that download threw,
            // the file isn't on disk and the caller would wrongly log "Cached"
            // and clear the failure badge. Treat a missing file as a failure so
            // the caller's catch path runs.
            if (!has(item.id)) {
                throw IOException("Download of ${item.id} failed on the concurrent caller")
            }
            return@withContext target
        }
        try {
            downloadTo(item.url, target, onProgress)
            target
        } finally {
            inflight.remove(item.id)
        }
    }

    /**
     * Stream the URL into `<target>.part`, atomically rename on completion.
     *
     * v0.1.39: on each attempt, if the .part file already has bytes on disk
     * (either from a prior failed attempt or a prior process), send
     * `Range: bytes=<existing>-` and append the response body. We retry
     * IOException with exponential backoff up to [maxAttempts]; each retry
     * picks up exactly where the previous one stopped because the .part
     * file is preserved across the inner loop.
     *
     * The retry loop handles transient connection drops — the kind in-store
     * wifi throws every few minutes. Permanent failures (HTTP 404, no DNS)
     * still surface after the retries are exhausted.
     */
    private fun downloadTo(
        url: String,
        target: File,
        onProgress: ((bytes: Long, total: Long?) -> Unit)?,
    ) {
        val partial = File(target.parentFile, target.name + ".part")
        val maxAttempts = 6
        var attempt = 0
        var backoffMs = 1_500L
        var lastError: Throwable? = null
        while (attempt < maxAttempts) {
            attempt++
            val existing = if (partial.exists()) partial.length() else 0L
            try {
                val builder = Request.Builder().url(url)
                if (existing > 0L) {
                    builder.header("Range", "bytes=$existing-")
                    LogBuffer.i(TAG, "Resuming download of ${target.name} from byte $existing (attempt $attempt)")
                }
                http.newCall(builder.build()).execute().use { response ->
                    when (response.code) {
                        206 -> {
                            // Server honored the range — body has bytes
                            // starting at `existing`. Append to file.
                            val remaining = response.body?.contentLength() ?: -1L
                            val total = if (remaining > 0) existing + remaining else null
                            streamBodyToPart(response, partial, existing, total, onProgress, append = true)
                        }
                        200 -> {
                            // Either we asked for the whole thing (existing
                            // == 0) or the server ignored our Range header.
                            // Either way we have the full payload — truncate
                            // any stale .part bytes and start fresh.
                            if (existing > 0L) {
                                LogBuffer.w(TAG, "Server ignored Range; restarting ${target.name} from 0")
                            }
                            val total = response.body?.contentLength()?.takeIf { it > 0 }
                            streamBodyToPart(response, partial, 0L, total, onProgress, append = false)
                        }
                        416 -> {
                            // Range not satisfiable — usually means the .part
                            // file is now larger than the resource (server
                            // replaced the video, or we miscounted). Wipe
                            // the partial and retry from scratch.
                            LogBuffer.w(TAG, "HTTP 416 for ${target.name}; discarding partial and retrying")
                            partial.delete()
                            throw IOException("Range not satisfiable; reset")
                        }
                        else -> {
                            // Non-retriable for client errors, retriable for
                            // 5xx. We treat both as IOException so the loop
                            // backs off; if it's a real 404 the retries will
                            // exhaust and we bubble up.
                            throw IOException("HTTP ${response.code} for $url")
                        }
                    }
                }
                // Success — promote .part to .mp4 atomically.
                if (!partial.renameTo(target)) {
                    partial.delete()
                    throw IOException("Could not rename .part into place")
                }
                return
            } catch (e: IOException) {
                lastError = e
                LogBuffer.w(TAG, "Download attempt $attempt/$maxAttempts failed for ${target.name}: ${e.message}")
                if (attempt >= maxAttempts) break
                try {
                    Thread.sleep(backoffMs)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                // Capped exponential — 1.5s, 3s, 6s, 12s, 24s, 30s.
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
        throw lastError ?: IOException("Download failed after $maxAttempts attempts")
    }

    private fun streamBodyToPart(
        response: okhttp3.Response,
        partial: File,
        startingBytes: Long,
        total: Long?,
        onProgress: ((bytes: Long, total: Long?) -> Unit)?,
        append: Boolean,
    ) {
        val body = response.body ?: throw IOException("Empty body")
        // Bytes the SERVER says are in *this body* — for a 206 that's
        // the remaining range, for a 200 the whole file. Used after
        // the read loop to detect premature EOF: a stalled/cut
        // connection often manifests as a clean `input.read() == -1`
        // before the body's actually been delivered, with no exception
        // thrown. Without this check we'd silently rename a truncated
        // .part into .mp4 and hand it to ExoPlayer, which then fails
        // with SOURCE_IO ("Read position out of range") when it walks
        // the MP4 atoms and they reference offsets past the real EOF.
        val expectedBodyBytes = body.contentLength()
        // Initial tick so the UI shows "starting…" before the first chunk
        // lands on slow connections.
        onProgress?.invoke(startingBytes, total)
        var bytesFromBody = 0L
        FileOutputStream(partial, append).use { out ->
            val buffer = ByteArray(64 * 1024)
            var accumulated = startingBytes
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    accumulated += read
                    bytesFromBody += read
                    onProgress?.invoke(accumulated, total)
                }
            }
        }
        // Premature EOF check. Only fires when the server actually
        // told us how big the body should be (chunked transfers have
        // contentLength() == -1 — no check possible there). Throwing
        // here pushes the caller's retry loop, which will issue a
        // Range request for the missing tail.
        if (expectedBodyBytes > 0 && bytesFromBody < expectedBodyBytes) {
            throw IOException(
                "Premature EOF for ${partial.name}: got $bytesFromBody of $expectedBodyBytes body bytes"
            )
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
            // v0.1.39: also reap orphan .part files for videos that
            // are no longer in the playlist. Skip parts for videos
            // currently inflight — those FDs are still being written.
            // (Pattern: "<videoId>.mp4.part" → strip ".mp4.part".)
            root.listFiles { f -> f.isFile && f.name.endsWith(".mp4.part") }?.forEach { f ->
                val id = f.name.removeSuffix(".mp4.part")
                // v0.1.44: use explicit containsKey instead of `id !in inflight`.
                // The `in` operator on a ConcurrentHashMap calls Map.contains(),
                // which the Kotlin compiler flags as ambiguous (could mean
                // containsKey OR containsValue) — newer KGP turns that warning
                // into a hard compile error.
                if (id !in keep && !inflight.containsKey(id)) {
                    f.delete()
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
