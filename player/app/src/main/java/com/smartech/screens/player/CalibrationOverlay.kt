package com.smartech.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Full-screen calibration overlay. Renders a giant ticking clock
 * showing the latency-corrected server time so staff can stand in
 * front of two screens and visually confirm they tick on the same
 * wall-clock second.
 *
 * Active when [calibrateUntilMs] is in the future relative to the
 * corrected server time; the composable polls every ~50 ms so the
 * overlay disappears within a frame or two of the cutoff.
 *
 * Why this exists: the v0.1.13 NTP-style clock sync removes the ~1 s
 * RTT/2 bias from each tablet's offset, but you can't see whether
 * it's actually working unless you can compare timestamps across
 * tablets. This overlay surfaces that directly. If two screens
 * show the same HH:MM:SS to the same fractional second, clock sync
 * is healthy and any remaining drift in real playback is a content
 * / queue issue rather than a clock issue.
 *
 * Display: HH:MM:SS in huge digits + a smaller "ms" tail underneath
 * so you can also eye sub-second alignment.
 */
@Composable
fun CalibrationOverlay(
    calibrateUntilMs: Long?,
    serverOffsetMs: Long,
    modifier: Modifier = Modifier,
) {
    if (calibrateUntilMs == null) return

    // Tick a local state so the composable recomposes every ~50 ms.
    // We don't trust System.currentTimeMillis() inside the composable
    // alone — Compose won't recompose on its own when wall-clock ticks.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(calibrateUntilMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(50L)
        }
    }

    val correctedNow = nowMs + serverOffsetMs
    if (correctedNow >= calibrateUntilMs) return

    val hms = remember {
        SimpleDateFormat("HH:mm:ss", Locale.UK).apply { timeZone = TimeZone.getDefault() }
    }
    val timeStr = hms.format(Date(correctedNow))
    val ms = (correctedNow % 1000L).toInt().toString().padStart(3, '0')
    val remainingSec = ((calibrateUntilMs - correctedNow) / 1000).coerceAtLeast(0)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "CALIBRATING — SCREENS SHOULD MATCH",
                color = Color(0xFFE8A33D),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(40.dp))
            // Monospaced enormous digits — the actual calibration
            // signal. If two screens show the same number to the same
            // tenth of a second, clock sync is working.
            Text(
                timeStr,
                color = Color(0xFFF7F6F2),
                fontSize = 220.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                ".$ms",
                color = Color(0xFFE8A33D),
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(40.dp))
            Text(
                "Server-corrected time (offset ${if (serverOffsetMs >= 0) "+" else ""}${serverOffsetMs} ms)",
                color = Color(0x99FFFFFF),
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Closing in ${remainingSec}s",
                color = Color(0x66FFFFFF),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
