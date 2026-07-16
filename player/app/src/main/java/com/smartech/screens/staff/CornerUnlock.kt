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
import androidx.compose.ui.unit.Dp
import com.smartech.screens.util.ScreenMetrics
import com.smartech.screens.util.rememberScreenMetrics

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
 * Now only four small corner zones — one per corner — carry a tap detector;
 * the entire middle of the screen has NO pointer input, so touches there fall
 * straight through to whatever is beneath (the experience, the video). The
 * four-corner sequence is unchanged for staff. The cost is four small dead
 * zones in the very corners of any interactive content, which is why customers
 * are pointed at a central "tap to explore" prompt.
 *
 * v0.2.0 — the zone size is derived from the screen instead of being a fixed
 * 180dp, because "small" was only ever true on a tablet. On a 360dp-wide phone
 * two 180dp zones meet in the middle with no gap: the top band and bottom band
 * each become one contiguous strip that eats every tap, and in landscape (360dp
 * tall) the left and right zones tile top-to-bottom and swallow 45% of the
 * screen. The docstring above promised the middle always falls through; at a
 * fixed 180dp that promise was false on a phone, which is what killed the "tap
 * to explore" prompt, the experience's own exit button, and the Next control —
 * all three sit inside what had quietly become an unlock zone. Capping at a
 * quarter of the shorter edge means two opposing zones span half of it at most,
 * so a live gap always survives. See [ScreenMetrics.cornerZone]; on the fleet's
 * tablets the cap holds at the original 180dp, so their gesture is unchanged.
 *
 * A tap on the wrong corner resets the sequence; tapping the top-left corner
 * always (re)starts it. Time between corner taps is bounded by [timeoutMs].
 */
@Composable
fun CornerUnlockOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    cornerSize: Dp = rememberScreenMetrics().cornerZone,
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
        CornerZone(Alignment.TopStart, cornerSize) { onCornerTap(0) }
        CornerZone(Alignment.TopEnd, cornerSize) { onCornerTap(1) }
        CornerZone(Alignment.BottomEnd, cornerSize) { onCornerTap(2) }
        CornerZone(Alignment.BottomStart, cornerSize) { onCornerTap(3) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CornerZone(
    alignment: Alignment,
    cornerSize: Dp,
    onTap: () -> Unit,
) {
    Box(
        Modifier
            .align(alignment)
            .size(cornerSize)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            }
    )
}
