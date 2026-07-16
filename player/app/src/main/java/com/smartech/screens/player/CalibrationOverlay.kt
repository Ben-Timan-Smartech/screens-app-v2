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
import com.smartech.screens.util.rememberScreenMetrics
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

    // v0.2.0: the clock is sized from the screen rather than fixed at 220sp.
    // "HH:mm:ss" is 8 monospace glyphs at roughly 0.6em each — 1056dp of text
    // at 220sp, which a 1280dp tablet carries and nothing else does. On a phone
    // it ran off both sides and the digits you're supposed to compare were the
    // first thing lost. Bounded by width (8 glyphs must fit) and by height (the
    // seconds and the countdown below it have to fit too), then capped at the
    // original 220sp so the fleet's tablets render exactly as they do today.
    val metrics = rememberScreenMetrics()
    val pad = if (metrics.isNarrow || metrics.isShort) 16.dp else 48.dp
    val padPx = if (metrics.isNarrow || metrics.isShort) 32 else 96
    val clockSp = minOf(
        (metrics.widthDp - padPx) / GLYPHS_EM,
        metrics.heightDp * CLOCK_MAX_HEIGHT_FRACTION,
        CLOCK_MAX_SP,
    ).coerceAtLeast(CLOCK_MIN_SP)
    val msSp = clockSp * MS_RATIO
    val compact = metrics.isNarrow || metrics.isShort

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .padding(pad),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "CALIBRATING — SCREENS SHOULD MATCH",
                color = Color(0xFFE8A33D),
                fontSize = if (compact) 12.sp else 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = if (compact) 1.5.sp else 4.sp,
            )
            Spacer(Modifier.height(if (compact) 12.dp else 40.dp))
            // Monospaced enormous digits — the actual calibration
            // signal. If two screens show the same number to the same
            // tenth of a second, clock sync is working.
            Text(
                timeStr,
                color = Color(0xFFF7F6F2),
                fontSize = clockSp.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                ".$ms",
                color = Color(0xFFE8A33D),
                fontSize = msSp.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Spacer(Modifier.height(if (compact) 12.dp else 40.dp))
            Text(
                "Server-corrected time (offset ${if (serverOffsetMs >= 0) "+" else ""}${serverOffsetMs} ms)",
                color = Color(0x99FFFFFF),
                fontSize = if (compact) 11.sp else 18.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Closing in ${remainingSec}s",
                color = Color(0x66FFFFFF),
                fontSize = if (compact) 11.sp else 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Monospace advance width per glyph, in em, times the 8 glyphs of "HH:mm:ss".
 *  Divide the available width by this to get the largest font size that fits. */
private const val GLYPHS_EM = 4.8f     // 8 glyphs x ~0.6em
/** The clock can't own more than this share of the height — the milliseconds
 *  and the two footer lines still have to fit under it. */
private const val CLOCK_MAX_HEIGHT_FRACTION = 0.38f
/** The original hard-coded size. Every fleet tablet still lands here. */
private const val CLOCK_MAX_SP = 220f
/** Below this the digits stop being readable across a room, which is the whole
 *  point of the overlay — better to clip slightly than to render a clock nobody
 *  can compare. */
private const val CLOCK_MIN_SP = 40f
/** Milliseconds tail, proportional to the clock (was 96sp against 220sp). */
private const val MS_RATIO = 96f / 220f
