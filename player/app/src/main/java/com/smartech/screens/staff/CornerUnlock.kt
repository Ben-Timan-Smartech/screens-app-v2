package com.smartech.screens.staff

import androidx.compose.foundation.gestures.detectTapGestures
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
                detectTapGestures(
                    onTap = { pos ->
                        val now = System.currentTimeMillis()
                        if (now - lastTap[0] > timeoutMs) step[0] = 0
                        lastTap[0] = now

                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val inCorner = when (step[0]) {
                            0 -> pos.isInCorner(Offset(0f, 0f), cornerPx)
                            1 -> pos.isInCorner(Offset(w, 0f), cornerPx)
                            2 -> pos.isInCorner(Offset(w, h), cornerPx)
                            3 -> pos.isInCorner(Offset(0f, h), cornerPx)
                            else -> false
                        }
                        if (inCorner) {
                            step[0]++
                            if (step[0] == 4) {
                                step[0] = 0
                                onUnlock()
                            }
                        } else {
                            step[0] = 0
                        }
                    },
                )
            }
    )
}

private fun Offset.isInCorner(corner: Offset, radius: Float): Boolean {
    val dx = x - corner.x
    val dy = y - corner.y
    return dx * dx + dy * dy <= radius * radius
}
