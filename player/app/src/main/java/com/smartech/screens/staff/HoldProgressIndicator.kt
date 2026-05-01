package com.smartech.screens.staff

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

/**
 * Top-right hold-to-unlock indicator. Renders a small ring that fills
 * clockwise from 0° to 360° over the lifetime of an OK-key hold.
 *
 *   • While [holdStartedAtFlow] holds a non-null timestamp the indicator
 *     is visible and fills based on elapsed time vs [durationMs].
 *   • When the flow goes back to null (user released early, or unlock
 *     completed) the indicator disappears immediately. The staff overlay
 *     covers the player on completion anyway, so a "release" animation
 *     would never actually be seen.
 *
 * Visual notes:
 *   • Bone fill on a translucent ink slab so it reads against bright
 *     videos *and* against dark videos without baking a solid colour.
 *   • Sized to be visible to a staff member operating a remote but
 *     small enough to be unobtrusive if it accidentally fires while a
 *     customer is watching.
 *
 * Note that we intentionally don't gate this on `tvLike`. On a touch
 * device with a Bluetooth keyboard plugged in, holding ENTER also
 * unlocks; the indicator is the right feedback in that case too.
 */
@Composable
fun HoldProgressIndicator(
    holdStartedAtFlow: StateFlow<Long?>,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val started by holdStartedAtFlow.collectAsState()

    if (started == null) return

    var progress by remember { mutableFloatStateOf(0f) }

    // Re-launched each time `started` changes (i.e. each new hold) — drives
    // a frame-paced animation that reads the wall clock so it stays in sync
    // with the activity's posted unlock Runnable even if we drop a frame.
    LaunchedEffect(started) {
        val s = started ?: return@LaunchedEffect
        progress = 0f
        while (true) {
            val elapsed = System.currentTimeMillis() - s
            progress = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            if (progress >= 1f) break
            withFrameNanos { /* wait one frame */ }
        }
    }

    Box(
        modifier = modifier
            .padding(20.dp)
            .size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Translucent ink puck behind the ring so it stays legible against
        // either bright or dark video frames without an opaque chip.
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(0x99141414))
        )

        // Faint track + bone-coloured filling arc. Drawn in a Canvas
        // because Material's CircularProgressIndicator doesn't expose the
        // start angle and we want the fill to begin at 12 o'clock.
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = 3.5.dp.toPx()
            val sz = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)

            // Faint track (full circle) so the user sees the canvas exists
            // even at progress = 0.
            drawArc(
                color = Color(0x33F7F6F2),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = sz,
                style = Stroke(width = stroke),
            )
            // Filling arc.
            drawArc(
                color = Color(0xFFF7F6F2),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = topLeft,
                size = sz,
                style = Stroke(width = stroke),
            )
        }
    }
}
