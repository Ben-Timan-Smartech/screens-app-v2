package com.smartech.screens.player

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration

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
 * For 90°/270° two things happen (both are needed, v0.2.11):
 *  1. The content is laid out at the SWAPPED dimensions (screen height × width)
 *     and rotated a quarter-turn, so after rotation it fills the real screen
 *     exactly. This uses [requiredSize], NOT [size]: on a landscape screen the
 *     parent's max-height is the short edge, and plain `size` would be coerced
 *     back into it — collapsing the portrait canvas into a square band (the
 *     v0.2.10 bug). `requiredSize` ignores the incoming constraints so the
 *     content gets its full, overflowing portrait size before it's rotated.
 *  2. The content is handed a Configuration with width/height swapped, so its
 *     own responsive layout (rememberScreenMetrics / LocalConfiguration) sizes
 *     for the portrait canvas it actually occupies — otherwise it lays out for
 *     the unrotated landscape screen and gets clipped.
 *
 * 180° keeps the same dimensions and config (a plain flip). Compose maps
 * pointer input through the rotation, so taps still land where the shopper sees
 * them.
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
                // requiredSize (not size) so the parent's short-edge max height
                // can't coerce the swapped portrait canvas back into a square.
                .then(
                    if (quarter) Modifier.requiredSize(width = h, height = w)
                    else Modifier.requiredSize(width = w, height = h)
                )
                .rotate(norm.toFloat()),
        ) {
            if (quarter) {
                val cfg = LocalConfiguration.current
                // Hand the content a portrait view of the world so its own
                // responsive layout fits the space it actually has.
                val swapped = remember(cfg.screenWidthDp, cfg.screenHeightDp) {
                    Configuration(cfg).apply {
                        val tmp = screenWidthDp
                        screenWidthDp = screenHeightDp
                        screenHeightDp = tmp
                    }
                }
                CompositionLocalProvider(LocalConfiguration provides swapped) { content() }
            } else {
                content()
            }
        }
    }
}
