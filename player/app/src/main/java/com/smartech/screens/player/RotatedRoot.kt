package com.smartech.screens.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate

/**
 * Rotates the entire player output by a fixed multiple of 90° (v0.2.8).
 *
 * The use case is a panel physically mounted rotated — Android reports, say,
 * landscape, but the screen is turned 90°, so everything comes out sideways.
 * This rotates the whole UI (video + every overlay) to match, driven by the
 * per-screen `rotation` setting pushed from the CMS.
 *
 * At 0° (every screen's default) this is a pure pass-through — the content is
 * emitted with no wrapper at all, so a non-rotated fleet is completely
 * unaffected.
 *
 * For 90°/270° the content is laid out at the SWAPPED dimensions (screen
 * height × width) and then rotated a quarter-turn, so after rotation it fills
 * the real screen exactly rather than being letterboxed into the wrong aspect.
 * 180° keeps the same dimensions. Compose maps pointer input through the
 * rotation, so taps still land where the shopper sees them.
 *
 * Note the video surface itself: a SurfaceView doesn't reliably rotate, so
 * [PlayerScreen] switches to a TextureView whenever rotation is non-zero. This
 * wrapper handles the layout geometry; that handles the pixels.
 */
@Composable
fun RotatedRoot(rotationDegrees: Int, content: @Composable () -> Unit) {
    val norm = ((rotationDegrees % 360) + 360) % 360
    if (norm == 0) {
        content()
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val quarter = norm == 90 || norm == 270
        val w = maxWidth
        val h = maxHeight
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .then(if (quarter) Modifier.size(width = h, height = w) else Modifier.size(width = w, height = h))
                .rotate(norm.toFloat()),
        ) {
            content()
        }
    }
}
