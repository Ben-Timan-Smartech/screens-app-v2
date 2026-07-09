package com.smartech.screens.staff

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Invisible overlay that watches for the "four corners tapped in order"
 * unlock gesture used by the on-tablet staff UI. No visual cue.
 *
 * Sequence: top-left → top-right → bottom-right → bottom-left. Any tap more
 * than [cornerDp] from the expected corner resets the sequence.
 * Time between corner taps is bounded by [timeoutMs].
 *
 * v0.1.37: bumped [cornerDp] from 96 → 180. The old corner area was
 * ~24 mm wide on a 1080p TV at typical viewing distance — staff kept
 * missing the corner with a finger and resetting the sequence. 180 dp
 * gives a ~45 mm landing pad, still small enough that a stray tap on
 * the lower-third doesn't trigger.
 *
 * v0.1.89: this overlay is always mounted on touch devices and sits on
 * top of the player, so a full-screen `detectTapGestures` (which always
 * consumes) used to swallow EVERY tap — breaking the shopper product-info
 * card's tap-to-expand beneath it. We now only CLAIM (consume) a tap when
 * it lands on the corner the sequence is currently waiting for; every
 * other tap is left unconsumed so it falls through to whatever is below.
 * The four-corner unlock still works — the corner you need next (including
 * the bottom-left 4th tap, which overlaps the card) is claimed only once
 * you've hit the earlier corners in order.
 */
@Composable
fun CornerUnlockOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    cornerDp: Int = 180,
    timeoutMs: Long = 4_000,
) {
    val density = LocalDensity.current
    val cornerPx = remember(cornerDp) { with(density) { cornerDp.dp.toPx() } }

    val step = remember { IntArray(1) }
    val lastTap = remember { LongArray(1) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Main-pass first-down. Being on top, we get first refusal
                    // on every tap — but we only take the ones that matter to
                    // the unlock sequence and leave the rest for the content
                    // beneath (e.g. the product-info card's tap-to-expand).
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val now = System.currentTimeMillis()
                    if (now - lastTap[0] > timeoutMs) step[0] = 0
                    lastTap[0] = now

                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val expected = when (step[0]) {
                        0 -> Offset(0f, 0f)
                        1 -> Offset(w, 0f)
                        2 -> Offset(w, h)
                        3 -> Offset(0f, h)
                        else -> null
                    }
                    if (expected == null || !down.position.isInCorner(expected, cornerPx)) {
                        // Not the corner we're waiting for — abandon the
                        // sequence and DON'T consume, so the tap passes
                        // through to the card / player beneath this overlay.
                        step[0] = 0
                        return@awaitEachGesture
                    }
                    // On the expected corner: claim the whole tap so the
                    // content beneath doesn't also react to it.
                    down.consume()
                    val up = waitForUpOrCancellation()
                    if (up == null) {
                        step[0] = 0
                        return@awaitEachGesture
                    }
                    up.consume()
                    step[0]++
                    if (step[0] == 4) {
                        step[0] = 0
                        onUnlock()
                    }
                }
            }
    )
}

private fun Offset.isInCorner(corner: Offset, radius: Float): Boolean {
    val dx = x - corner.x
    val dy = y - corner.y
    return dx * dx + dy * dy <= radius * radius
}
