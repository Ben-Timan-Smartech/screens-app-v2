package com.smartech.screens.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

/**
 * Network diagnostics for the in-store troubleshooting flow.
 *
 * Probes used (all public, no API keys):
 *   • Latency / packet loss → `https://speed.cloudflare.com/cdn-cgi/trace`
 *   • Download throughput   → `https://speed.cloudflare.com/__down?bytes=N`
 *   • Upload throughput     → `https://speed.cloudflare.com/__up`
 *
 * Total runtime ~10–15 s on a healthy connection. Phases stream through
 * [onPhase] so the UI can show progress.
 */
object NetworkDiagnostics {

    enum class ConnectionType { WIRED, WIRELESS, CELLULAR, OFFLINE, UNKNOWN }

    enum class Phase(val label: String) {
        LINK("Reading link info"),
        SERVER("Checking CMS reachability"),
        LATENCY("Testing latency"),
        DOWNLOAD("Testing download"),
        UPLOAD("Testing upload"),
        DONE("Done"),
    }

    /** CMS-reachability probe result. Null = no liveServerUrl configured. */
    data class ServerProbe(
        val url: String,
        val reachable: Boolean,
        val httpStatus: Int?,        // null if the request never got a response (timeout, DNS, etc.)
        val latencyMs: Long?,        // round-trip time of the GET, only set on success
        val errorMessage: String?,   // populated when reachable=false
    )

    data class Result(
        val connectionType: ConnectionType,
        val ssid: String?,                      // null if wired or permission missing
        val signalStrengthDbm: Int?,            // null if wired
        val signalStrengthLabel: String?,       // null if wired
        val deviceIp: String?,
        val gatewayIp: String?,
        val macAddress: String?,
        val server: ServerProbe?,               // null when liveServerUrl isn't set
        val latencyMs: Long?,                   // mean of successful probes
        val packetLossPct: Float?,              // 0..100
        val downloadMbps: Double?,              // megabits per second
        val uploadMbps: Double?,
        val ssidPermissionMissing: Boolean,
        val errors: List<String>,
    )

    private const val LATENCY_URL  = "https://speed.cloudflare.com/cdn-cgi/trace"
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=25000000"   // 25 MB
    private const val UPLOAD_URL   = "https://speed.cloudflare.com/__up"
    private const val UPLOAD_BYTES = 5_000_000                                              // 5 MB
    private const val LATENCY_PROBES = 20

    suspend fun run(
        context: Context,
        client: OkHttpClient,
        serverUrl: String? = null,
        onPhase: (Phase) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        android.util.Log.i("NetDiag", "Diagnostic run started")
        val errors = mutableListOf<String>()

        // Use a tighter timeout than the shared player client — diagnostics
        // shouldn't hang the UI when the network is busted.
        val probeClient = client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        // ── 1. Link info ─────────────────────────────────────────────
        onPhase(Phase.LINK)
        android.util.Log.i("NetDiag", "Reading link info...")
        val link = try {
            readLink(context)
        } catch (t: Throwable) {
            android.util.Log.e("NetDiag", "readLink failed", t)
            errors.add("Link info: ${t.message}")
            LinkInfo(ConnectionType.UNKNOWN, null, null, null, null, null, false)
        }

        // ── 2. CMS reachability ──────────────────────────────────────
        // Done before the generic Cloudflare probes because the
        // top-line question for staff is "can this tablet talk to the
        // CMS?" — Cloudflare being reachable doesn't answer it (corporate
        // networks often allow general internet but firewall Cloud Run).
        onPhase(Phase.SERVER)
        val serverProbe = serverUrl?.takeIf { it.isNotBlank() }?.let {
            android.util.Log.i("NetDiag", "Probing CMS at $it...")
            probeServer(probeClient, it.trimEnd('/'))
        }
        if (serverProbe != null && !serverProbe.reachable) {
            errors.add("CMS unreachable: ${serverProbe.errorMessage ?: "unknown"}")
        }

        // ── 3. Latency + packet loss ────────────────────────────────
        onPhase(Phase.LATENCY)
        android.util.Log.i("NetDiag", "Measuring latency...")
        val (latencyMs, lossPct, latencyErr) = measureLatency(probeClient)
        latencyErr?.let(errors::add)

        // ── 4. Download throughput ──────────────────────────────────
        onPhase(Phase.DOWNLOAD)
        android.util.Log.i("NetDiag", "Measuring download...")
        val (down, downErr) = measureDownload(probeClient)
        downErr?.let(errors::add)

        // ── 5. Upload throughput ────────────────────────────────────
        onPhase(Phase.UPLOAD)
        android.util.Log.i("NetDiag", "Measuring upload...")
        val (up, upErr) = measureUpload(probeClient)
        upErr?.let(errors::add)

        onPhase(Phase.DONE)
        android.util.Log.i("NetDiag", "Diagnostic run finished")

        Result(
            connectionType = link.type,
            ssid = link.ssid,
            signalStrengthDbm = link.rssiDbm,
            signalStrengthLabel = link.rssiDbm?.let(::rssiLabel),
            deviceIp = link.deviceIp,
            gatewayIp = link.gatewayIp,
            macAddress = link.macAddress,
            server = serverProbe,
            latencyMs = latencyMs,
            packetLossPct = lossPct,
            downloadMbps = down,
            uploadMbps = up,
            ssidPermissionMissing = link.ssidPermissionMissing,
            errors = errors,
        )
    }

    /** Three sequential GETs to /api/release/latest — a cheap public
     *  endpoint that every CMS deployment exposes. Returns the median
     *  RTT and the HTTP status of the last response. */
    private fun probeServer(client: OkHttpClient, base: String): ServerProbe {
        val url = "$base/api/release/latest"
        val rtts = mutableListOf<Long>()
        var lastStatus: Int? = null
        var lastError: String? = null
        repeat(3) {
            val req = Request.Builder().url(url).get().build()
            val started = System.nanoTime()
            try {
                client.newCall(req).execute().use { r ->
                    lastStatus = r.code
                    if (r.isSuccessful) {
                        rtts += (System.nanoTime() - started) / 1_000_000
                    } else {
                        lastError = "HTTP ${r.code}"
                    }
                }
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
            }
        }
        val median = rtts.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }
        return ServerProbe(
            url = url,
            reachable = median != null,
            httpStatus = lastStatus,
            latencyMs = median,
            errorMessage = if (median == null) lastError else null,
        )
    }

    // ── Link info ──────────────────────────────────────────────────

    private data class LinkInfo(
        val type: ConnectionType,
        val ssid: String?,
        val rssiDbm: Int?,
        val deviceIp: String?,
        val gatewayIp: String?,
        val macAddress: String?,
        val ssidPermissionMissing: Boolean,
    )

    private fun readLink(context: Context): LinkInfo {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val props = active?.let { cm.getLinkProperties(it) }

        val type = when {
            caps == null -> ConnectionType.OFFLINE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.WIRED
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> ConnectionType.WIRELESS
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
            else -> ConnectionType.UNKNOWN
        }

        // 1. IP and Gateway
        val deviceIp = props?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress
        val gatewayIp = props?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress

        // 2. MAC Address (Best effort)
        val macAddress = try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val mac = interfaces?.asSequence()
                ?.filter { !it.isLoopback && it.hardwareAddress != null }
                ?.map { it.hardwareAddress.joinToString(":") { b -> "%02X".format(b) } }
                ?.firstOrNull()
            mac ?: "Unavailable"
        } catch (t: Throwable) {
            "Unavailable"
        }

        if (type != ConnectionType.WIRELESS) {
            return LinkInfo(
                type = type,
                ssid = null,
                rssiDbm = null,
                deviceIp = deviceIp,
                gatewayIp = gatewayIp,
                macAddress = macAddress,
                ssidPermissionMissing = false
            )
        }

        val locationGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        @Suppress("DEPRECATION")
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val info = runCatching { wifi?.connectionInfo }.getOrNull()
        @Suppress("DEPRECATION")
        val rssi = info?.rssi
        @Suppress("DEPRECATION")
        val rawSsid = info?.ssid

        // Android returns "<unknown ssid>" when permission is missing, or the
        // string "\"NetworkName\"" with quotes when it's not.
        val ssid = when {
            rawSsid == null -> null
            rawSsid == "<unknown ssid>" -> null
            !locationGranted -> null
            else -> rawSsid.trim('"')
        }

        return LinkInfo(
            type = type,
            ssid = ssid,
            rssiDbm = rssi?.takeIf { it != Int.MIN_VALUE && it != 0 },
            deviceIp = deviceIp,
            gatewayIp = gatewayIp,
            macAddress = macAddress,
            ssidPermissionMissing = !locationGranted,
        )
    }

    private fun rssiLabel(dbm: Int): String = when {
        dbm >= -55 -> "Excellent"
        dbm >= -67 -> "Good"
        dbm >= -75 -> "Fair"
        dbm >= -85 -> "Weak"
        else       -> "Very weak"
    }

    // ── Latency + packet loss ──────────────────────────────────────

    private data class LatencyResult(val meanMs: Long?, val lossPct: Float, val error: String?)

    private fun measureLatency(client: OkHttpClient): LatencyResult {
        val samples = mutableListOf<Long>()
        var failures = 0
        var firstError: String? = null
        repeat(LATENCY_PROBES) {
            val req = Request.Builder().url(LATENCY_URL).get().build()
            var ok = false
            val ns = measureNanoTime {
                try {
                    client.newCall(req).execute().use { r -> ok = r.isSuccessful }
                } catch (e: Throwable) {
                    if (firstError == null) firstError = "Latency probe: ${e.message}"
                }
            }
            if (ok) samples += (ns / 1_000_000) else failures++
        }
        val mean = if (samples.isNotEmpty()) samples.average().toLong() else null
        val loss = (failures.toFloat() / LATENCY_PROBES) * 100f
        return LatencyResult(mean, loss, firstError)
    }

    // ── Download ───────────────────────────────────────────────────

    private fun measureDownload(client: OkHttpClient): Pair<Double?, String?> {
        val req = Request.Builder().url(DOWNLOAD_URL).build()
        return runCatching {
            val start = System.nanoTime()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null to "Download HTTP ${r.code}"
                val body = r.body ?: return null to "Download: empty body"
                var total = 0L
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        total += n
                    }
                }
                val seconds = (System.nanoTime() - start) / 1_000_000_000.0
                val mbps = if (seconds > 0) (total * 8 / 1_000_000.0) / seconds else 0.0
                mbps to null
            }
        }.getOrElse { null to "Download: ${it.message}" }
    }

    // ── Upload ─────────────────────────────────────────────────────

    private fun measureUpload(client: OkHttpClient): Pair<Double?, String?> {
        val payload = ByteArray(UPLOAD_BYTES) // zeros, fine for raw throughput
        val mediaType = "application/octet-stream".toMediaType()
        val req = Request.Builder()
            .url(UPLOAD_URL)
            .post(payload.toRequestBody(mediaType))
            .build()
        return runCatching {
            val start = System.nanoTime()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null to "Upload HTTP ${r.code}"
                val seconds = (System.nanoTime() - start) / 1_000_000_000.0
                val mbps = if (seconds > 0) (UPLOAD_BYTES * 8 / 1_000_000.0) / seconds else 0.0
                mbps to null
            }
        }.getOrElse { null to "Upload: ${it.message}" }
    }
}

