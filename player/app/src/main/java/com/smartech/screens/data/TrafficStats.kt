package com.smartech.screens.data

import com.smartech.screens.util.LogBuffer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.File
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap

/**
 * v0.2.26: how much network this box uses a day, and on what. Sent with the
 * heartbeat so a store on a weak connection can be checked from the CMS. Only
 * totals are kept, never addresses.
 */
enum class TrafficCategory(val wire: String) {
    MEDIA("media"),
    SPLASH("splash"),
    LIBRARY("library"),
    IMAGES("images"),
    APP("app"),
    API("api");

    companion object {
        /** Decided from the path alone, so nothing else about the request is kept. */
        fun of(method: String, path: String): TrafficCategory = when {
            path.startsWith("/uploaded/splashes/") || path.startsWith("/splash/") -> SPLASH
            path.startsWith("/media/") || path.startsWith("/uploaded/") -> MEDIA
            path == "/api/library" || path.startsWith("/api/library/") -> LIBRARY
            path.startsWith("/apk") -> APP
            path == "/api" || path.startsWith("/api/") -> API
            method.equals("GET", ignoreCase = true) -> IMAGES
            else -> API
        }
    }
}

/** One day's totals, as saved to disk. */
@Serializable
data class TrafficDay(
    val day: String = "",
    val media: Long = 0,
    val splash: Long = 0,
    val library: Long = 0,
    val images: Long = 0,
    val app: Long = 0,
    val api: Long = 0,
    val tx: Long = 0,
    val downloaded: Int = 0,
    val fromDisk: Int = 0,
    val failed: Int = 0,
)

/**
 * The counting itself: plain Kotlin, no Android. Days run on the store's clock
 * and server time, because box clocks and time zones can't be trusted (some
 * start up in 2032). Until the first answer from the server says what the time
 * is, counts wait in [pending]. Thread-safe.
 */
class TrafficCounter(private val boxClockMs: () -> Long = { System.currentTimeMillis() }) {

    private class Bucket {
        val rx = LongArray(TrafficCategory.entries.size)
        var tx = 0L
        var downloaded = 0
        var fromDisk = 0
        var failed = 0

        fun add(other: Bucket) {
            for (i in rx.indices) rx[i] += other.rx[i]
            tx += other.tx
            downloaded += other.downloaded
            fromDisk += other.fromDisk
            failed += other.failed
        }
    }

    private val lock = Any()
    private val days = TreeMap<String, Bucket>()
    private var pending = Bucket()
    private var clockKnown = false
    private var offsetMs = 0L
    private var zone: TimeZone = TimeZone.getDefault()

    /** The current day and the stretch of server time it covers. */
    private var today = ""
    private var todayFrom = 0L
    private var todayUntil = 0L

    /** Bumped when the day changes, so the caller knows to save. */
    @Volatile
    var rolls = 0
        private set

    val ready: Boolean get() = synchronized(lock) { clockKnown }

    fun addRx(category: TrafficCategory, bytes: Long) {
        if (bytes <= 0L) return
        synchronized(lock) { current().rx[category.ordinal] += bytes }
    }

    fun addTx(bytes: Long) {
        if (bytes <= 0L) return
        synchronized(lock) { current().tx += bytes }
    }

    fun addDownloaded() {
        synchronized(lock) { current().downloaded += 1 }
    }

    fun addFromDisk(count: Int) {
        if (count <= 0) return
        synchronized(lock) { current().fromDisk += count }
    }

    fun addFailed() {
        synchronized(lock) { current().failed += 1 }
    }

    /** Server time minus box time, and the store's time zone (null = the box's). */
    fun setClock(zoneId: String?, serverOffsetMs: Long) {
        synchronized(lock) {
            val next = zoneFor(zoneId)
            if (next.id != zone.id) {
                zone = next
                todayUntil = 0L          // work the day out again
            }
            offsetMs = serverOffsetMs
            if (!clockKnown) {
                clockKnown = true
                val waiting = pending
                pending = Bucket()
                current().add(waiting)
            }
        }
    }

    /** Add days saved by an earlier run. Call before the first [setClock]. */
    fun restore(saved: List<TrafficDay>) {
        synchronized(lock) {
            for (d in saved) {
                if (d.day.isBlank()) continue
                val b = days.getOrPut(d.day) { Bucket() }
                b.rx[TrafficCategory.MEDIA.ordinal] += d.media
                b.rx[TrafficCategory.SPLASH.ordinal] += d.splash
                b.rx[TrafficCategory.LIBRARY.ordinal] += d.library
                b.rx[TrafficCategory.IMAGES.ordinal] += d.images
                b.rx[TrafficCategory.APP.ordinal] += d.app
                b.rx[TrafficCategory.API.ordinal] += d.api
                b.tx += d.tx
                b.downloaded += d.downloaded
                b.fromDisk += d.fromDisk
                b.failed += d.failed
            }
        }
    }

    fun snapshot(): List<TrafficDay> = synchronized(lock) {
        days.map { (day, b) ->
            TrafficDay(
                day = day,
                media = b.rx[TrafficCategory.MEDIA.ordinal],
                splash = b.rx[TrafficCategory.SPLASH.ordinal],
                library = b.rx[TrafficCategory.LIBRARY.ordinal],
                images = b.rx[TrafficCategory.IMAGES.ordinal],
                app = b.rx[TrafficCategory.APP.ordinal],
                api = b.rx[TrafficCategory.API.ordinal],
                tx = b.tx,
                downloaded = b.downloaded,
                fromDisk = b.fromDisk,
                failed = b.failed,
            )
        }
    }

    /** The heartbeat's `traffic` object, or null until the time is known. */
    fun heartbeatJson(): String? = synchronized(lock) {
        if (!clockKnown) return@synchronized null
        val now = current()
        val yesterday = dayOf(todayFrom - 1L)
        val prev = days[yesterday]?.let { "{${fields(yesterday, it)}}" } ?: "null"
        "{${fields(today, now)},\"prev\":$prev}"
    }

    private fun fields(day: String, b: Bucket): String {
        val rx = TrafficCategory.entries.joinToString(",") { "\"${it.wire}\":${b.rx[it.ordinal]}" }
        return "\"day\":\"$day\",\"rx\":{$rx},\"tx\":${b.tx}," +
            "\"files\":{\"downloaded\":${b.downloaded},\"fromDisk\":${b.fromDisk},\"failed\":${b.failed}}"
    }

    /** Today's bucket. Call with [lock] held. */
    private fun current(): Bucket {
        if (!clockKnown) return pending
        val t = boxClockMs() + offsetMs
        if (t < todayFrom || t >= todayUntil) roll(t)
        return days.getOrPut(today) { Bucket() }
    }

    private fun roll(t: Long) {
        val cal = calendar(t)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        today = dayOf(t)
        todayFrom = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        todayUntil = cal.timeInMillis
        // A day after today can only be a wrong clock.
        days.keys.filter { it > today }.forEach { days.remove(it) }
        days.getOrPut(today) { Bucket() }
        while (days.size > KEEP_DAYS) days.remove(days.firstKey())
        rolls += 1
    }

    private fun dayOf(t: Long): String {
        val cal = calendar(t)
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun calendar(t: Long): Calendar =
        GregorianCalendar(zone, Locale.US).apply { timeInMillis = t }

    private fun zoneFor(id: String?): TimeZone {
        if (id.isNullOrBlank()) return TimeZone.getDefault()
        val z = TimeZone.getTimeZone(id)
        return if (z.id == id) z else TimeZone.getDefault()   // unknown ids come back as GMT
    }

    private companion object {
        const val KEEP_DAYS = 8
    }
}

/** [TrafficCounter] plus its file and the OkHttp hook that feeds it. */
class TrafficStats(private val file: File) {

    private val counter = TrafficCounter()
    private val json = Json { ignoreUnknownKeys = true }
    private val fileLock = Any()
    private var loaded = false
    private var savedAtNs = 0L
    private var savedRolls = 0
    private var lastSaved = ""

    /** Network interceptor: sees the bytes as they cross the wire. Counts the
     *  body as it's read, without holding on to any of it. */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val category = TrafficCategory.of(request.method, request.url.encodedPath)
        val response: Response = chain.proceed(request)
        val sent = runCatching { request.body?.contentLength() ?: 0L }.getOrDefault(0L)
        counter.addTx(sent)
        val body = response.body
        if (body == null) {
            response
        } else {
            response.newBuilder()
                .body(CountedBody(body) { counter.addRx(category, it) })
                .build()
        }
    }

    fun addDownloaded() = counter.addDownloaded()
    fun addFromDisk(count: Int) = counter.addFromDisk(count)
    fun addFailed() = counter.addFailed()
    fun heartbeatJson(): String? = counter.heartbeatJson()

    /** Reads the saved days the first time, so call it off the main thread. */
    fun setClock(zoneId: String?, serverOffsetMs: Long) {
        synchronized(fileLock) {
            if (!loaded) {
                loaded = true
                runCatching {
                    if (file.exists()) {
                        counter.restore(json.decodeFromString(ListSerializer(TrafficDay.serializer()), file.readText()))
                    }
                }.onFailure { LogBuffer.w(TAG, "Saved traffic totals unreadable: ${it.message}") }
            }
        }
        counter.setClock(zoneId, serverOffsetMs)
    }

    /** Save at most every five minutes, and when the day changes. */
    fun saveIfDue() {
        if (!counter.ready) return
        synchronized(fileLock) {
            val now = System.nanoTime()
            val rolls = counter.rolls
            if (rolls == savedRolls && savedAtNs != 0L && now - savedAtNs < SAVE_EVERY_NS) return
            savedAtNs = now
            savedRolls = rolls
            runCatching {
                val text = json.encodeToString(ListSerializer(TrafficDay.serializer()), counter.snapshot())
                if (text == lastSaved) return@runCatching
                val part = File(file.parentFile, file.name + ".part")
                part.writeText(text)
                if (!part.renameTo(file)) {
                    file.delete()
                    part.renameTo(file)
                }
                lastSaved = text
            }.onFailure { LogBuffer.throttledW(TAG, "traffic-save-fail", "Saving traffic totals failed: ${it.message}") }
        }
    }

    private class CountedBody(
        private val delegate: ResponseBody,
        private val onBytes: (Long) -> Unit,
    ) : ResponseBody() {
        private val counted: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val n = super.read(sink, byteCount)
                    if (n > 0L) onBytes(n)
                    return n
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = counted
    }

    private companion object {
        const val TAG = "TrafficStats"
        const val SAVE_EVERY_NS = 5L * 60L * 1_000_000_000L
    }
}
