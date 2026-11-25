package com.smartech.screens.data

import android.content.Context
import android.os.Environment
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
 * Disk-backed LRU video cache. Lives under the app's internal files dir, or
 * since v0.2.20 its folder on shared storage when that's much roomier (see
 * [pickRoot]) — survives app restarts, cleared on uninstall. Honours a soft
 * cap (default 8GB) with oldest-access-first eviction.
 *
 * Layout: `<filesDir>/videos/<key>.mp4` for completed downloads,
 *         `<filesDir>/videos/<key>.mp4.part` for in-progress.
 *
 * v0.2.26: the key is the file's version (`h-<hash>`) when the server sends
 * one, otherwise the item's id as before. A replaced file gets a new key, so
 * it's noticed; the same file under two ids is stored and downloaded once.
 * Files also stay after they leave the playlist, until the space is needed,
 * so content that comes back isn't downloaded again.
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
    private val root: File = pickRoot(context)
    /** File names being downloaded right now. */
    private val inflight = ConcurrentHashMap<String, Boolean>()

    /** v0.2.26: version file name → a file still saved under its old
     *  `<id>.mp4` name because it's on screen and can't be renamed yet.
     *  See [adopt]. */
    private val aliases = ConcurrentHashMap<String, File>()

    /** v0.2.26: how often the server has offered a file whose size isn't what
     *  it told us, per file name. See [SIZE_MISMATCH_LIMIT]. */
    private val sizeMismatches = ConcurrentHashMap<String, Int>()

    /** v0.2.26: held while files are deleted to make room, and by
     *  PlayerRepository while it picks files to play, so a file can't be
     *  deleted between "it's on disk" and "it's on screen". */
    val lock = Any()

    /** v0.2.26: names of the files that must never be evicted: what's playing,
     *  the playlist being synced and upcoming-event content. Supplied by
     *  PlayerRepository. */
    @Volatile
    var pinned: () -> Set<String> = { emptySet() }

    /** v0.2.9: the current cache cap in bytes. PlayerRepository keeps this in
     *  sync with the operator's setting each refresh. [ensure] refuses new
     *  downloads once the completed cache reaches it, so a low cap actually
     *  limits how much a screen stores rather than only trimming after the
     *  fact. Defaults to 8 GB to match the historical soft cap. */
    @Volatile
    var maxCacheBytes: Long = 8L * 1024 * 1024 * 1024

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
    // Stale .part files for videos no longer wanted get cleaned up by [evict].
    init { /* intentionally empty — keep .part across restarts */ }

    /** v0.2.26: name (without extension) of the file holding [item]'s current
     *  version. The prefix keeps a version from colliding with an item id. */
    fun key(item: VideoItem): String {
        val hash = item.hash
        if (hash.isNullOrBlank()) return item.id
        return HASH_PREFIX + hash.replace(UNSAFE_KEY_CHARS, "_").take(MAX_HASH_CHARS)
    }

    private fun ownFile(item: VideoItem): File = File(root, "${key(item)}.mp4")
    private fun partOf(target: File): File = File(root, "${target.name}.part")

    fun file(item: VideoItem): File {
        val own = ownFile(item)
        if (own.length() > 0L) return own
        return olderCopy(item, own) ?: own
    }

    fun has(item: VideoItem): Boolean = file(item).length() > 0L

    /** Bytes of [item] already downloaded but not finished. */
    fun partialBytes(item: VideoItem): Long = partOf(ownFile(item)).length()

    /** v0.2.26: a copy saved by a player older than this one, under the item's
     *  id. Only counts as this version when its size is exactly right. */
    private fun olderCopy(item: VideoItem, own: File): File? {
        if (item.hash.isNullOrBlank()) return null
        val bytes = item.bytes ?: return null
        if (bytes <= 0L) return null
        aliases[own.name]?.let { if (it.length() == bytes) return it }
        return File(root, "${item.id}.mp4").takeIf { it.length() == bytes }
    }

    /**
     * v0.2.26: take over files saved under their item id by an older player,
     * so updating doesn't download everything again. They're renamed to their
     * version key, except files in [inUse]: the player is holding those paths,
     * so they're used in place and renamed on a later start. Call with [lock]
     * held.
     */
    fun adopt(items: List<VideoItem>, inUse: Set<File>) {
        for (item in items) {
            val own = ownFile(item)
            if (own.length() > 0L) continue
            val old = olderCopy(item, own) ?: continue
            if (old in inUse) {
                aliases[own.name] = old
            } else if (old.renameTo(own)) {
                LogBuffer.i(TAG, "Kept ${item.id} from an earlier download")
            }
        }
    }

    /**
     * v0.1.73: purge a cached video (completed + any partial) so the next
     * [ensure] re-downloads a clean copy. Used by [PlaybackWatchdog] when
     * ExoPlayer reports a corrupt/truncated source file
     * (ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE / container-malformed) —
     * the bytes on disk are bad, and prepare() would just re-read them.
     * v0.2.26: takes the file the player was actually given, which isn't
     * always the version being synced.
     */
    fun invalidate(file: File) {
        val deleted = file.delete()
        partOf(file).delete()
        LogBuffer.i(TAG, "Invalidated cached video ${file.name} (removed=$deleted)")
    }

    /**
     * Ensure a video is present locally. If already cached, returns immediately.
     * If [onProgress] is supplied, emits `(bytesDownloaded, totalBytesOrNull)`
     * as bytes arrive. `total` may be null if the server didn't send a
     * Content-Length, e.g. a chunked response.
     *
     * v0.2.26: [stillWanted] is checked as the download runs; when it turns
     * false the download stops with [DownloadCancelledException], keeping the
     * bytes so far. Throws [DownloadRefusedException] when trying again soon
     * won't help (no such file, wrong size, no room).
     */
    suspend fun ensure(
        item: VideoItem,
        stillWanted: () -> Boolean = { true },
        onProgress: ((bytes: Long, total: Long?) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val ready = file(item)
        if (ready.length() > 0L) return@withContext ready
        val target = ownFile(item)
        if (inflight.putIfAbsent(target.name, true) != null) {
            while (inflight.containsKey(target.name)) Thread.sleep(100)
            // The first caller finished — but "finished" includes "failed".
            // Don't return `target` unconditionally: if that download threw,
            // the file isn't on disk and the caller would wrongly log "Cached"
            // and clear the failure badge. Treat a missing file as a failure so
            // the caller's catch path runs.
            if (!has(item)) {
                throw IOException("Download of ${item.id} failed on the concurrent caller")
            }
            return@withContext file(item)
        }
        try {
            preparePartial(item, target)
            if (!has(item)) {
                checkRoom(item, target)
                downloadTo(item, target, stillWanted, onProgress)
            }
            file(item)
        } finally {
            inflight.remove(target.name)
        }
    }

    /** Size [item] must be, or null when unknown or no longer trusted. */
    private fun expectedBytes(item: VideoItem, target: File): Long? {
        val bytes = item.bytes ?: return null
        if (bytes <= 0L) return null
        return bytes.takeIf { (sizeMismatches[target.name] ?: 0) < SIZE_MISMATCH_LIMIT }
    }

    /** v0.2.26: sort out what an earlier attempt left behind. */
    private fun preparePartial(item: VideoItem, target: File) {
        val bytes = expectedBytes(item, target) ?: return
        val partial = partOf(target)
        if (!item.hash.isNullOrBlank() && !partial.exists()) {
            // A download started by an older player, under the item's id.
            val old = File(root, "${item.id}.mp4")
            val oldPart = partOf(old)
            val oldHave = oldPart.length()
            if (oldHave > 0L && oldHave < bytes && !inflight.containsKey(old.name)) {
                oldPart.renameTo(partial)
            }
        }
        val have = partial.length()
        if (have == bytes) {
            // Finished, but the box lost power before the rename.
            if (partial.renameTo(target)) LogBuffer.i(TAG, "${target.name} was already complete")
        } else if (have > bytes) {
            partial.delete()
        }
    }

    /** v0.2.9: free-space + cap guard. Never let caching fill the device or
     *  blow past the configured cap. v0.2.26: makes room first by dropping
     *  files that aren't wanted any more, and knows the size it needs. Only
     *  when that isn't enough is the download refused: the item just isn't
     *  cached (it drops out of playback) instead of the tablet running out of
     *  storage. */
    private fun checkRoom(item: VideoItem, target: File) {
        val need = ((item.bytes ?: 0L) - partOf(target).length()).coerceAtLeast(0L)
        makeRoom(need)
        val free = usableSpace()
        if (free - need <= MIN_FREE_BYTES) {
            LogBuffer.w(TAG, "Skipping ${item.id}: low storage (free ${free / MB}MB, needs ${need / MB}MB, headroom ${MIN_FREE_BYTES / MB}MB)")
            throw DownloadRefusedException("Not enough free storage on this screen")
        }
        val used = completedBytes()
        if (used >= maxCacheBytes) {
            LogBuffer.w(TAG, "Skipping ${item.id}: at cache cap (${used / MB}MB ≥ ${maxCacheBytes / MB}MB)")
            throw DownloadRefusedException("This screen's video storage limit is full")
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
     * wifi throws every few minutes. Anything longer (no DNS) surfaces after
     * the retries are exhausted; v0.2.26: a missing file or a wrong size
     * surfaces straight away.
     */
    private fun downloadTo(
        item: VideoItem,
        target: File,
        stillWanted: () -> Boolean,
        onProgress: ((bytes: Long, total: Long?) -> Unit)?,
    ) {
        val partial = partOf(target)
        val expected = expectedBytes(item, target)
        val maxAttempts = 6
        var attempt = 0
        var backoffMs = 1_500L
        var lastError: Throwable? = null
        while (attempt < maxAttempts) {
            attempt++
            val existing = partial.length()
            try {
                if (!stillWanted()) throw DownloadCancelledException()
                val builder = Request.Builder().url(item.url)
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
                            val total = rangeTotal(response.header("Content-Range"))
                                ?: if (remaining > 0) existing + remaining else null
                            checkSize(expected, total, target)
                            streamBodyToPart(response, partial, existing, total, stillWanted, onProgress, append = true)
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
                            checkSize(expected, total, target)
                            streamBodyToPart(response, partial, 0L, total, stillWanted, onProgress, append = false)
                        }
                        416 -> {
                            // Range not satisfiable — the .part is as long as
                            // the resource, or longer. v0.2.26: exactly as long
                            // means it's the finished file (the box lost power
                            // before the rename), so don't download it again.
                            val real = rangeTotal(response.header("Content-Range"))
                            val complete = real != null && real == existing &&
                                (expected == null || real == expected)
                            if (!complete) {
                                // The server replaced the video, or we
                                // miscounted. Wipe the partial and retry from
                                // scratch.
                                LogBuffer.w(TAG, "HTTP 416 for ${target.name}; discarding partial and retrying")
                                partial.delete()
                                if (expected != null && existing < expected) wrongSize(target, existing, expected)
                                throw IOException("Range not satisfiable; reset")
                            }
                        }
                        404, 410 -> throw DownloadRefusedException("HTTP ${response.code}")
                        else -> {
                            // Non-retriable for other client errors, retriable
                            // for 5xx. We treat both as IOException so the
                            // loop backs off.
                            throw IOException("HTTP ${response.code}")
                        }
                    }
                }
                // v0.2.26: a long file can arrive without a length, so a cut
                // connection looks like a clean end. The size says which.
                val have = partial.length()
                if (have <= 0L) throw IOException("Empty download")
                if (expected != null && have < expected) {
                    throw IOException("Premature EOF for ${partial.name}: got $have of $expected bytes")
                }
                if (expected != null && have > expected) wrongSize(target, have, expected)
                // Success — promote .part to .mp4 atomically.
                if (!partial.renameTo(target)) {
                    partial.delete()
                    throw IOException("Could not rename .part into place")
                }
                sizeMismatches.remove(target.name)
                return
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: DownloadRefusedException) {
                throw e
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

    /** Total size from a `Content-Range: bytes a-b/total` header. */
    private fun rangeTotal(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

    /** Refuse before any bytes are spent when the server is about to send a
     *  different size from the one it told us. */
    private fun checkSize(expected: Long?, total: Long?, target: File) {
        if (expected != null && total != null && total != expected) wrongSize(target, total, expected)
    }

    /** v0.2.26: what's on offer isn't the version we were told about, usually
     *  because the server's list is about to catch up. Throw the bytes away
     *  and try later. After [SIZE_MISMATCH_LIMIT] goes the size is ignored: a
     *  video that plays beats one that never arrives. */
    private fun wrongSize(target: File, got: Long, expected: Long): Nothing {
        partOf(target).delete()
        val n = (sizeMismatches[target.name] ?: 0) + 1
        sizeMismatches[target.name] = n
        LogBuffer.w(TAG, "${target.name} is $got bytes, not $expected ($n/$SIZE_MISMATCH_LIMIT)")
        throw DownloadRefusedException("Wrong file size")
    }

    private fun streamBodyToPart(
        response: okhttp3.Response,
        partial: File,
        startingBytes: Long,
        total: Long?,
        stillWanted: () -> Boolean,
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
        var lastCheckNs = System.nanoTime()
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
                    val now = System.nanoTime()
                    if (now - lastCheckNs >= WANTED_CHECK_NS) {
                        lastCheckNs = now
                        if (!stillWanted()) throw DownloadCancelledException()
                    }
                }
            }
            // v0.2.26: onto the disk before the rename, so a power cut can't
            // leave a finished name on a half-written file.
            runCatching { out.fd.sync() }
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
     * Evict least-recently-used files not named in [keep] until the cache fits
     * under [capBytes]. Returns the names evicted (useful for telemetry).
     *
     * v0.2.26: a file that has left the playlist is kept until its space is
     * needed, so content that comes back isn't downloaded twice. Nothing kept
     * and a cap of 0 is Clear cache: everything goes.
     */
    suspend fun reconcile(keep: Set<String>, capBytes: Long): List<String> =
        withContext(Dispatchers.IO) { evict(keep, capBytes, 0L) }

    /** v0.2.26: [reconcile] with the current pins and cap. */
    suspend fun trim(): List<String> = withContext(Dispatchers.IO) { makeRoom(0L) }

    /** v0.2.26: drop files that aren't wanted any more until [needBytes] fit. */
    fun makeRoom(needBytes: Long): List<String> =
        synchronized(lock) { evict(pinned(), maxCacheBytes, needBytes) }

    private fun evict(keep: Set<String>, capBytes: Long, needBytes: Long): List<String> {
        val evicted = mutableListOf<String>()
        synchronized(lock) {
            // Size the cap check on completed .mp4s only. A huge stale .part
            // (e.g. a resource that grew across a re-encode) must not count
            // toward the total and force eviction of good, fully-cached files.
            val mp4s = root.listFiles { f -> f.isFile && f.extension == "mp4" } ?: emptyArray()
            var total = mp4s.sumOf { it.length() }
            val clearing = capBytes <= 0L
            // Files kept for later leave more free than the 300 MB a download
            // needs, so holding on to them never puts the box near Android's
            // own low-storage limit.
            fun tight() = total + needBytes > capBytes || usableSpace() - needBytes < RETAIN_FREE_BYTES

            // v0.1.39: reap orphan .part files for videos that are no longer
            // wanted. Skip parts for videos currently inflight — those FDs are
            // still being written. v0.2.26: only when they're a week old or the
            // space is needed, so a download that comes back can resume.
            val needSpace = clearing || tight()
            val now = System.currentTimeMillis()
            root.listFiles { f -> f.isFile && f.name.endsWith(".mp4.part") }?.forEach { f ->
                val name = f.name.removeSuffix(".part")
                // v0.1.44: explicit containsKey — `in` on a ConcurrentHashMap is
                // ambiguous (key or value) and newer KGP makes that an error.
                if (name !in keep && !inflight.containsKey(name) &&
                    (needSpace || now - f.lastModified() > ORPHAN_PART_MS)
                ) {
                    f.delete()
                }
            }

            // NEVER evict a name in [keep]. Those are in rotation or about to
            // be; evicting one just makes the next sync download it again.
            val spare = mp4s
                .filter { it.name !in keep }
                .sortedBy { it.lastModified() }
            for (f in spare) {
                if (!clearing && !tight()) break
                val size = f.length()
                if (f.delete()) {
                    evicted += f.nameWithoutExtension
                    total -= size
                    Log.w(TAG, "Evicted ${f.name} (size $size)")
                }
            }
            // Pinned set alone exceeds the cap — refuse to evict in-rotation
            // items (that would just churn re-downloads). Log so the fleet
            // telemetry surfaces an over-capacity playlist instead.
            if (!clearing && total > capBytes) {
                LogBuffer.throttledW(
                    TAG, "cache-over-cap",
                    "Pinned playlist (${keep.size} item(s), $total B) exceeds cache cap $capBytes B; not evicting in-rotation videos",
                )
            }
        }
        return evicted
    }

    /** v0.2.26: mark [file] as used just now, so the least recently used order
     *  means something. [nowMs] is server time: box clocks can be years out. */
    fun touch(file: File, nowMs: Long) {
        runCatching { file.setLastModified(nowMs) }
    }

    /** Ids of the videos saved under their item id (no version known). Files
     *  named by version aren't ids; see PlayerRepository.cachedItemIds. */
    fun legacyIds(): List<String> =
        root.listFiles { f -> f.isFile && f.extension == "mp4" && !f.name.startsWith(HASH_PREFIX) }
            ?.map { it.nameWithoutExtension } ?: emptyList()

    /** How many finished videos are on disk. */
    fun videoCount(): Int =
        root.listFiles { f -> f.isFile && f.extension == "mp4" }?.size ?: 0

    fun totalBytes(): Long =
        root.listFiles { f -> f.isFile }?.sumOf { it.length() } ?: 0L

    /** Free space on the volume the cache lives on. */
    fun usableSpace(): Long = root.usableSpace

    /** Bytes used by completed (.mp4) videos only — excludes in-progress .part
     *  files, so a large partial download doesn't spuriously trip the cap. */
    fun completedBytes(): Long =
        root.listFiles { f -> f.isFile && f.extension == "mp4" }?.sumOf { it.length() } ?: 0L

    companion object {
        private const val TAG = "VideoCache"
        private const val MB = 1_048_576L
        /** v0.2.9: minimum free space the cache will preserve on the device.
         *  ensure() skips downloads once free space would drop below this, so a
         *  screen never fills its own storage — protecting small-storage boxes
         *  where the cache cap (default 8 GB) exceeds the free space available.
         *  v0.2.20: 1 GB → 300 MB. 1 GB blocked a 5 MB clip on a box with 958 MB
         *  free; 300 MB still leaves Android room to run. */
        const val MIN_FREE_BYTES = 300L * 1024 * 1024

        /** v0.2.26: free space to leave when holding on to videos that aren't
         *  in use. Above Android's own low-storage limit (up to 500 MB), with
         *  room for an app update. */
        private const val RETAIN_FREE_BYTES = 800L * 1024 * 1024

        /** v0.2.26: see [key]. */
        private const val HASH_PREFIX = "h-"
        private const val MAX_HASH_CHARS = 100
        private val UNSAFE_KEY_CHARS = Regex("[^A-Za-z0-9._-]")

        /** v0.2.26: after this many wrong-size answers, take the file anyway. */
        private const val SIZE_MISMATCH_LIMIT = 3

        private const val ORPHAN_PART_MS = 7L * 24 * 60 * 60 * 1000
        private const val WANTED_CHECK_NS = 2_000_000_000L

        /** v0.2.20: external storage has to beat internal by this much before
         *  the cache moves there. On a normal tablet the two are the same
         *  volume (equal free space), so the cache stays where it always was;
         *  only split-storage boxes move. The margin also stops it flipping. */
        private const val EXTERNAL_PREFERRED_MARGIN = 1L * 1024 * 1024 * 1024

        /**
         * v0.2.20: where the videos live. Some boxes (MEZ.A.4: 32 GB tablet)
         * split storage — app-private internal data sits on a small partition
         * (under 1 GB free) while the shared storage Android's Settings shows
         * has ~19 GB free. Use the app's own folder on shared storage when it's
         * built in (not a removable card), mounted, and clearly roomier. No
         * permission is needed for getExternalFilesDir. Whichever root is not
         * in use has its leftover videos cleared in the background, so moving
         * frees the small partition (the playlist re-downloads once).
         */
        private fun pickRoot(context: Context): File {
            val internal = File(context.filesDir, "videos").apply { mkdirs() }
            val external: File? = runCatching {
                val dir = context.getExternalFilesDir("videos")
                if (dir != null && (dir.isDirectory || dir.mkdirs()) &&
                    Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED &&
                    !Environment.isExternalStorageRemovable(dir)
                ) dir else null
            }.getOrNull()
            // v0.2.26: count our own videos as free space. They now stay after
            // they leave the playlist, and without this a filling cache could
            // tip the choice over at the next start, which would delete and
            // re-download the lot.
            val internalFree = internal.usableSpace + videoBytes(internal)
            val externalFree = if (external != null) external.usableSpace + videoBytes(external) else -1L
            val useExternal = external != null && externalFree > internalFree + EXTERNAL_PREFERRED_MARGIN
            val root: File = if (useExternal) external!! else internal
            val other: File? = if (useExternal) internal else external
            if (useExternal) {
                // Warning level so it reaches /api/logs: it only happens on
                // split-storage boxes and is the first thing to check there.
                LogBuffer.w(TAG, "Videos stored on shared storage (free ${externalFree / MB}MB); internal has only ${internalFree / MB}MB free")
            } else {
                LogBuffer.i(TAG, "Videos stored internally (free ${internalFree / MB}MB)")
            }
            if (other != null) {
                Thread {
                    val stale = other.listFiles { f ->
                        f.isFile && (f.name.endsWith(".mp4") || f.name.endsWith(".mp4.part"))
                    } ?: emptyArray()
                    var freed = 0L
                    var count = 0
                    for (f in stale) {
                        val len = f.length()
                        if (f.delete()) { freed += len; count++ }
                    }
                    if (count > 0) {
                        LogBuffer.w(TAG, "Cleared $count old video file(s), ${freed / MB}MB, from the unused storage location")
                    }
                }.apply { isDaemon = true; name = "cache-move-cleanup" }.start()
            }
            return root
        }

        private fun videoBytes(dir: File): Long =
            dir.listFiles { f -> f.isFile && (f.name.endsWith(".mp4") || f.name.endsWith(".mp4.part")) }
                ?.sumOf { it.length() } ?: 0L
    }
}

/** v0.2.26: the download was stopped because nobody needs it any more. */
class DownloadCancelledException : IOException("No longer needed")

/** v0.2.26: trying again soon won't help: no such file, wrong size, no room. */
class DownloadRefusedException(message: String) : IOException(message)
