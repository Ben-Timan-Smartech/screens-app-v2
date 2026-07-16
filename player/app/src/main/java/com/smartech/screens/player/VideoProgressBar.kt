package com.smartech.screens.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay

/**
 * Slim playback progress bar along the very bottom of the video (v0.2.0).
 *
 * Opt-in per screen via the `progressBar` flag — off by default, same
 * reasoning as [TapNextOverlay]: this is visible chrome on a shop-floor
 * screen and shouldn't appear fleet-wide by surprise. Unlike tap-to-skip it is
 * NOT forced off in a sync group; it doesn't drive playback, and members share
 * a position, so their bars agree.
 *
 * Renders nothing when [PlayerController.progressFraction] returns null — the
 * splash loop, a not-yet-prepared item, an empty queue. A splash has no
 * meaningful "duration" to show progress through, and a bar that filled and
 * reset under a looping splash would read as broken.
 *
 * Not interactive: no pointerInput, no clickable, nothing focusable. That's
 * why — unlike the product card and the tap-next control — it does NOT need to
 * be composed above the staff-unlock catcher in MainActivity, and instead
 * lives inside [PlayerScreen] with the rest of the video decoration. It's
 * composed there BEFORE the guided-experience overlay, so an open experience's
 * full-screen WebView paints over it with no state plumbing between the two;
 * during the attract loop (experience set but not opened) the bar shows
 * normally, since that's still just video playing.
 *
 * Sampling, not observing: position advances continuously, so there's nothing
 * to subscribe to — the bar polls ExoPlayer on its own tick. [TICK_MS] is a
 * pixel-driven choice: at 60fps a 15s clip advances the bar by well under a
 * pixel per frame on a 1080p screen, so 10 samples/sec is already smoother
 * than the eye resolves, and the loop costs nothing while the flag is off
 * because the composable returns before starting it.
 *
 * Drawing goes through [drawBehind] rather than a Box background so the tick
 * only invalidates the draw phase — no recomposition, no relayout, ten times a
 * second, forever, on a device that must stay smooth for months.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoProgressBar(
    enabled: Boolean,
    controller: PlayerController,
) {
    if (!enabled) return

    // -1f = "nothing to show" (splash / unknown duration). Kept as a float
    // rather than a nullable Float so the sampling loop doesn't box on every
    // tick; the draw lambda treats anything < 0 as hidden.
    var fraction by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(Unit) {
        while (true) {
            fraction = controller.progressFraction() ?: -1f
            delay(TICK_MS)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .drawBehind {
                    val f = fraction
                    if (f < 0f) return@drawBehind
                    // Track first, then fill. The track is dim enough to read as
                    // part of the video's letterbox on a dark clip but still
                    // gives the fill something to travel along on a bright one.
                    drawRect(color = TRACK_COLOR)
                    drawRect(
                        color = FILL_COLOR,
                        size = Size(size.width * f, size.height),
                    )
                },
        )
    }
}

/** ~10 samples/sec — see the class doc; smoother than the eye resolves. */
private const val TICK_MS = 100L

/**
 * 3dp. Deliberately slim: this is a glance-able hint of "how much is left",
 * not a scrubber. It sits flush against the bottom edge, where it clears the
 * product card (bottom-start, which has its own padding) and the guided-
 * experience attract pill (bottom-centre, likewise padded).
 */
private val BAR_HEIGHT = 3.dp

private val TRACK_COLOR = Color(0x33FFFFFF)
private val FILL_COLOR = Color(0xF2F7F6F2)
