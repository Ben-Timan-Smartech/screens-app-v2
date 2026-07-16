package com.smartech.screens.staff

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Invisible staff-unlock gesture: the four screen corners tapped in order
 * (top-left → top-right → bottom-right → bottom-left). No visual cue.
 *
 * v0.1.92 — corners-only. Previously this was a single full-screen
 * `pointerInput` that saw every tap. That was fine when the only thing behind
 * it was a video, but it can't coexist with interactive content (the guided
 * brand experience WebView): a full-screen pointer sibling swallows or contends
 * for every touch, so the experience couldn't scroll or tap.
 *
 * Now only four small [cornerDp] zones — one per corner — carry a tap
 * detector; the entire middle of the screen has NO pointer input, so touches
 * there fall straight through to whatever is beneath (the experience, the
 * video). The four-corner sequence is unchanged for staff. The cost is four
 * small dead zones in the very corners of any interactive content, which is
 * why customers are pointed at a central "tap to explore" prompt.
 *
 * A tap on the wrong corner resets the sequence; tapping the top-left corner
 * always (re)starts it. Time between corner taps is bounded by [timeoutMs].
 */
@Composable
fun CornerUnlockOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    cornerDp: Int = 180,
    timeoutMs: Long = 4_000,
) {
    // Shared, mutation-in-place sequence state so all four corner zones advance
    // the same counter without triggering recomposition.
    val step = remember { IntArray(1) }
    val lastTap = remember { LongArray(1) }

    fun onCornerTap(index: Int) {
        val now = System.currentTimeMillis()
        if (now - lastTap[0] > timeoutMs) step[0] = 0
        lastTap[0] = now
        when {
            index == step[0] -> {
                step[0]++
                if (step[0] == 4) {
                    step[0] = 0
                    onUnlock()
                }
            }
            index == 0 -> step[0] = 1   // top-left always restarts the sequence
            else -> step[0] = 0
        }
    }

    // Full-screen host with NO pointer input of its own (so it never blocks the
    // content behind it); only the four corner children are touch-sensitive.
    Box(modifier = modifier.fillMaxSize()) {
        CornerZone(Alignment.TopStart, cornerDp) { onCornerTap(0) }
        CornerZone(Alignment.TopEnd, cornerDp) { onCornerTap(1) }
        CornerZone(Alignment.BottomEnd, cornerDp) { onCornerTap(2) }
        CornerZone(Alignment.BottomStart, cornerDp) { onCornerTap(3) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CornerZone(
    alignment: Alignment,
    cornerDp: Int,
    onTap: () -> Unit,
) {
    Box(
        Modifier
            .align(alignment)
            .size(cornerDp.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            }
    )
}
