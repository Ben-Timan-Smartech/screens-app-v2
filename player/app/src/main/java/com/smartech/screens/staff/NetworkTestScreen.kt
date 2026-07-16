package com.smartech.screens.staff

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.smartech.screens.data.PlayerRepository
import com.smartech.screens.util.NetworkDiagnostics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Ink      = Color(0xFF141414)
private val Bone     = Color(0xFFF7F6F2)
private val BoneSoft = Color(0xFFEFEDE6)
private val BoneLine = Color(0xFFE2DED3)
private val Muted    = Color(0xFF6E6B62)
private val Ok       = Color(0xFF2F6B3B)
private val Warn     = Color(0xFF8A5A12)
private val Err      = Color(0xFFA63824)

/**
 * Connection diagnostics screen.
 */
@Composable
fun NetworkTestScreen(
    repository: PlayerRepository,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<NetworkDiagnostics.Phase?>(null) }
    var result by remember { mutableStateOf<NetworkDiagnostics.Result?>(null) }
    var running by remember { mutableStateOf(false) }

    val locationGranted = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> locationGranted.value = granted }

    fun runTest() {
        if (running) return
        running = true
        result = null
        scope.launch {
            try {
                // Read the configured CMS URL once per run so the test can
                // probe the actual backend the player polls — not just
                // generic-internet endpoints. Null URL → ServerPanel
                // renders an explicit "not configured" state.
                val serverUrl = runCatching { repository.store.liveServerUrl.first() }.getOrNull()
                val r = NetworkDiagnostics.run(
                    ctx,
                    repository.httpClient,
                    serverUrl = serverUrl,
                ) { phase = it }
                result = r
            } catch (t: Throwable) {
                android.util.Log.e("NetTestUI", "Diagnostics failed", t)
            } finally {
                running = false
                phase = null
            }
        }
    }

    // Auto-run on first paint.
    LaunchedEffect(Unit) { if (result == null && !running) runTest() }

    val compact = compactPane()

    // v0.2.0: 420dp rail → 0dp pane on a portrait phone. Same collapse as the
    // other staff screens; here it hid the entire test readout.
    TwoPaneScaffold(
        railColor = Ink,
        paneColor = Bone,
        rail = {
            Column {
                Text("Diagnostics".uppercase(), color = Color(0x73FFFFFF), fontSize = 11.sp, letterSpacing = 1.6.sp)
                Spacer(Modifier.height(14.dp))
                Text(
                    "Network test",
                    color = Bone,
                    fontSize = if (compact) 20.sp else 30.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Confirms the tablet can reach the CMS, then measures latency, " +
                        "packet loss, and download / upload throughput. " +
                        "Throughput uses Cloudflare's public speed-test endpoints.",
                    color = Color(0x99FFFFFF), fontSize = 14.sp,
                )

                Spacer(Modifier.height(if (compact) 16.dp else 40.dp))
                Text("Status", color = Color(0x66FFFFFF), fontSize = 11.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        running -> phase?.label ?: "Running…"
                        result != null -> "Done"
                        else -> "Idle"
                    },
                    color = Bone, fontSize = 14.sp,
                )
            }
        },
        pane = {
        // Right pane. v0.2.0: scrolls when compact — the panels stack to ~600dp
        // against a landscape phone's ~380dp, which put "Run again" (the point
        // of the screen) below the fold with no way to reach it.
        Column(
            Modifier
                .fillMaxSize()
                .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(paneInsetSnug()),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp),
        ) {
            Row {
                Text("Back", color = Muted, fontSize = 16.sp,
                    modifier = Modifier.clickable { onBack() }.padding(8.dp))
                Spacer(Modifier.weight(1f))
                Text("Cancel", color = Muted, fontSize = 16.sp,
                    modifier = Modifier.clickable { onCancel() }.padding(8.dp))
            }

            // Top panel: can the tablet talk to the CMS? Rendered first because
            // it's the most actionable result — Cloudflare being reachable
            // doesn't tell staff anything useful if the CMS is firewalled.
            ServerPanel(result = result, running = running, phase = phase)

            // v0.2.0: side by side these two get ~118dp each on a phone, and a
            // StatLine's "MAC Address / aa:bb:cc:dd:ee:ff" can't fit in that at
            // any font size. Stacked, each gets the full width.
            val linkPanel: @Composable () -> Unit = {
                LinkPanel(
                    result = result,
                    running = running,
                    ssidPermissionMissing = result?.ssidPermissionMissing == true && !locationGranted.value,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                )
            }
            val throughputPanel: @Composable () -> Unit = {
                ThroughputPanel(result = result, running = running)
            }
            if (compact) {
                linkPanel()
                throughputPanel()
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f)) { linkPanel() }
                    Box(Modifier.weight(1f)) { throughputPanel() }
                }
            }

            // Run again
            Row {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (running) BoneSoft else Ink)
                        .clickable(enabled = !running) { runTest() }
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (running) "Running…" else (if (result == null) "Run test" else "Run again"),
                        color = if (running) Muted else Bone,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        },
    )
}

@Composable
fun LinkPanel(
    result: NetworkDiagnostics.Result?,
    running: Boolean,
    ssidPermissionMissing: Boolean,
    onRequestPermission: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, BoneLine, RoundedCornerShape(12.dp))
            .padding(20.dp),
    ) {
        Text("Connection", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))

        when {
            running && result == null -> Text("Reading link info…", color = Muted, fontSize = 13.sp)
            result == null -> Text("—", color = Muted, fontSize = 13.sp)
            else -> {
                val r = result
                StatLine("Type", r.connectionType.label())
                if (r.connectionType == NetworkDiagnostics.ConnectionType.WIRELESS) {
                    StatLine("SSID", r.ssid ?: if (ssidPermissionMissing) "Permission required" else "—")
                    if (r.signalStrengthDbm != null) {
                        StatLine(
                            "Signal",
                            "${r.signalStrengthDbm} dBm · ${r.signalStrengthLabel ?: ""}",
                            valueColor = signalColor(r.signalStrengthDbm),
                        )
                    } else {
                        StatLine("Signal", "—")
                    }
                }
                StatLine("IP Address", r.deviceIp ?: "—")
                StatLine("Gateway", r.gatewayIp ?: "—")
                StatLine("MAC Address", r.macAddress ?: "—")

                if (ssidPermissionMissing && r.connectionType == NetworkDiagnostics.ConnectionType.WIRELESS) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(BoneSoft)
                            .clickable { onRequestPermission() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text("Allow location to read SSID", fontSize = 12.sp, color = Ink)
                    }
                }
            }
        }
    }
}

@Composable
fun ThroughputPanel(result: NetworkDiagnostics.Result?, running: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, BoneLine, RoundedCornerShape(12.dp))
            .padding(20.dp),
    ) {
        Text("Performance", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))

        if (result == null) {
            Text(if (running) "Probing…" else "—", color = Muted, fontSize = 13.sp)
        } else {
            val r = result
            StatLine(
                "Latency",
                r.latencyMs?.let { "$it ms" } ?: "—",
                valueColor = r.latencyMs?.let { latencyColor(it) } ?: Muted,
            )
            StatLine(
                "Packet loss",
                r.packetLossPct?.let { "${"%.1f".format(it)}%" } ?: "—",
                valueColor = r.packetLossPct?.let { lossColor(it) } ?: Muted,
            )
            StatLine(
                "Download",
                r.downloadMbps?.let { "${"%.1f".format(it)} Mbps" } ?: "—",
                valueColor = r.downloadMbps?.let { downColor(it) } ?: Muted,
            )
            StatLine(
                "Upload",
                r.uploadMbps?.let { "${"%.1f".format(it)} Mbps" } ?: "—",
                valueColor = r.uploadMbps?.let { upColor(it) } ?: Muted,
            )
        }
    }
}

/**
 * CMS-reachability panel. The first thing staff should see — answers
 * "is this tablet actually able to talk to its server?" without making
 * them parse Cloudflare throughput numbers first.
 *
 * States:
 *   • Server URL not configured → orange "no server set" state, points
 *     the user at the Configuration card to set it.
 *   • Probe in flight → "Checking…" with the URL.
 *   • Reachable → green dot, HTTP status, latency.
 *   • Unreachable → red dot, error message.
 */
@Composable
fun ServerPanel(
    result: NetworkDiagnostics.Result?,
    running: Boolean,
    phase: NetworkDiagnostics.Phase?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, BoneLine, RoundedCornerShape(12.dp))
            .padding(20.dp),
    ) {
        Text("CMS reachability", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))

        val server = result?.server
        when {
            // No completed run yet AND we're currently in the SERVER phase.
            running && phase == NetworkDiagnostics.Phase.SERVER && result == null ->
                Text("Probing the CMS…", color = Muted, fontSize = 13.sp)
            running && result == null ->
                Text("Queued — waiting for earlier phases", color = Muted, fontSize = 13.sp)
            result == null ->
                Text("—", color = Muted, fontSize = 13.sp)
            server == null -> {
                // The run completed but no liveServerUrl was configured.
                // Don't show this as a network failure — it's a config gap.
                StatLine("Server URL", "Not configured", valueColor = Warn)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Set the server URL in Device admin → Configuration before testing reachability.",
                    color = Muted, fontSize = 12.sp,
                )
            }
            else -> {
                StatLine(
                    "Status",
                    if (server.reachable) "Reachable" else "Unreachable",
                    valueColor = if (server.reachable) Ok else Err,
                )
                StatLine("URL", server.url)
                StatLine(
                    "HTTP",
                    server.httpStatus?.toString() ?: "—",
                    valueColor = when (server.httpStatus) {
                        in 200..299 -> Ok
                        in 300..399 -> Warn
                        null -> Err
                        else -> Err
                    },
                )
                StatLine(
                    "Round-trip",
                    server.latencyMs?.let { "${it} ms" } ?: "—",
                    valueColor = server.latencyMs?.let { latencyColor(it) } ?: Muted,
                )
                if (!server.reachable && server.errorMessage != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(server.errorMessage, color = Err, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun StatLine(label: String, value: String, valueColor: Color = Ink) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .height(8.dp).width(8.dp)
                .clip(CircleShape)
                .background(valueColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(value, color = Ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun NetworkDiagnostics.ConnectionType.label(): String = when (this) {
    NetworkDiagnostics.ConnectionType.WIRED    -> "Wired (Ethernet)"
    NetworkDiagnostics.ConnectionType.WIRELESS -> "Wireless (Wi-Fi)"
    NetworkDiagnostics.ConnectionType.CELLULAR -> "Cellular"
    NetworkDiagnostics.ConnectionType.OFFLINE  -> "Offline"
    NetworkDiagnostics.ConnectionType.UNKNOWN  -> "Unknown"
}

private fun signalColor(dbm: Int): Color = when {
    dbm >= -67 -> Ok
    dbm >= -75 -> Warn
    else       -> Err
}

private fun latencyColor(ms: Long): Color = when {
    ms < 60   -> Ok
    ms < 150  -> Warn
    else      -> Err
}

private fun lossColor(pct: Float): Color = when {
    pct < 1f  -> Ok
    pct < 5f  -> Warn
    else      -> Err
}

private fun downColor(mbps: Double): Color = when {
    mbps >= 25 -> Ok
    mbps >= 10 -> Warn
    else       -> Err
}

private fun upColor(mbps: Double): Color = when {
    mbps >= 5  -> Ok
    mbps >= 2  -> Warn
    else       -> Err
}
