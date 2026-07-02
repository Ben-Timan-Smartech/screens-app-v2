package com.smartech.screens.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartech.screens.BuildConfig
import kotlinx.coroutines.delay

/**
 * Full-screen overlay shown while the updater is mid-flight.
 *
 * States that surface UI:
 *   • Downloading — title + progress bar with bytes
 *   • Installing  — title + spinner (system installer is about to open)
 *   • Failed      — message + Dismiss button (only the "update" command
 *                   path sets this; the background tick stays silent)
 *
 * Idle / Checking / UpToDate paint nothing — no flicker during routine
 * version checks.
 */
@Composable
fun UpdaterOverlay(updater: Updater, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsState()
    val show = when (val s = state) {
        is Updater.State.Downloading,
        is Updater.State.Installing,
        is Updater.State.Failed -> true
        // v0.1.72: Checking / UpToDate surface only for a manual check, so
        // the operator gets feedback when they tap Update — background
        // ticks stay invisible.
        is Updater.State.Checking -> s.manual
        is Updater.State.UpToDate -> s.manual
        else -> false
    }
    if (!show) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xCC0A0A0A)),  // ink-9 @ ~80% — sits over the player
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xFF141414), RoundedCornerShape(8.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val s = state) {
                is Updater.State.Checking -> {
                    CircularProgressIndicator(
                        color = Color(0xFFE8A33D),
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = "Checking for updates…",
                        color = Color(0xFFFAFAF9),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Currently on v${BuildConfig.VERSION_NAME}",
                        color = Color(0xFFA1A1A1),
                        fontSize = 12.sp,
                    )
                }
                is Updater.State.UpToDate -> {
                    // Manual check only — auto-dismiss so the overlay
                    // doesn't linger over the player.
                    LaunchedEffect(s) { delay(4000); updater.dismiss() }
                    Text(
                        text = "You're on the latest version",
                        color = Color(0xFFFAFAF9),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "v${s.versionName}",
                        color = Color(0xFFA1A1A1),
                        fontSize = 13.sp,
                    )
                    TextButton(onClick = { updater.dismiss() }) {
                        Text("Done", color = Color(0xFFE8A33D))
                    }
                }
                is Updater.State.Downloading -> {
                    Text(
                        text = "Updating v${BuildConfig.VERSION_NAME} → v${s.versionName}",
                        color = Color(0xFFFAFAF9),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val frac = s.fraction
                    if (frac != null) {
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            color = Color(0xFFE8A33D),
                            trackColor = Color(0xFF2A2A2A),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            color = Color(0xFFE8A33D),
                            trackColor = Color(0xFF2A2A2A),
                        )
                    }
                    Text(
                        text = buildString {
                            append(formatBytes(s.bytes))
                            s.totalBytes?.let { append(" / "); append(formatBytes(it)) }
                            s.bytesPerSec?.let { if (it > 0L) { append("  ·  "); append(formatBytes(it)); append("/s") } }
                            s.etaSeconds?.let { append("  ·  "); append(formatEta(it)); append(" left") }
                        },
                        color = Color(0xFFA1A1A1),
                        fontSize = 12.sp,
                    )
                }
                is Updater.State.Installing -> {
                    // Auto-revert to Idle after a timeout. The system installer
                    // takes over the foreground once launchInstaller() fires;
                    // if the user dismisses that prompt (or it never appears),
                    // nothing else resets Installing, so the full-screen overlay
                    // would otherwise cover the player until the next overnight
                    // update tick (~16 h). Reverting here uncovers the player.
                    LaunchedEffect(s) { delay(INSTALLING_TIMEOUT_MS); updater.dismiss() }
                    CircularProgressIndicator(
                        color = Color(0xFFE8A33D),
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = "Installing v${s.versionName}…",
                        color = Color(0xFFFAFAF9),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Confirm the system prompt to finish.",
                        color = Color(0xFFA1A1A1),
                        fontSize = 12.sp,
                    )
                    TextButton(onClick = { updater.dismiss() }) {
                        Text("Dismiss", color = Color(0xFFE8A33D))
                    }
                }
                is Updater.State.Failed -> {
                    Text(
                        text = "Update failed",
                        color = Color(0xFFEF4444),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = s.message,
                        color = Color(0xFFD4D4D4),
                        fontSize = 13.sp,
                    )
                    TextButton(onClick = { updater.dismiss() }) {
                        Text("Dismiss", color = Color(0xFFE8A33D))
                    }
                }
                else -> Unit  // covered by `show` filter, kept for exhaustiveness
            }
        }
    }
}

/** How long the "Installing…" overlay lingers before auto-reverting to
 *  Idle. Long enough for the system installer prompt to appear and be
 *  acted on; short enough that a dismissed/absent prompt doesn't leave the
 *  player covered for hours. */
private const val INSTALLING_TIMEOUT_MS = 3L * 60L * 1000L  // 3 min

/** Compact ETA formatter — "45s", "2m 05s", "1h 03m". */
private fun formatEta(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val m = seconds / 60
    if (m < 60) return "${m}m ${(seconds % 60).toString().padStart(2, '0')}s"
    val h = m / 60
    return "${h}h ${(m % 60).toString().padStart(2, '0')}m"
}

/** Compact byte formatter — "1.2 MB", "523 KB", etc. */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.size - 1) {
        value /= 1024.0
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}
