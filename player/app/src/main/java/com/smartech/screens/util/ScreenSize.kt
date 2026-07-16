package com.smartech.screens.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * How much room the player actually has to lay out in (v0.2.0).
 *
 * Until now nothing in this module read the screen size at all — every staff
 * screen hard-coded dimensions for the 1280x800 retail tablet it was written
 * on. That's fine on the fleet and falls apart anywhere else: on a 360dp-wide
 * phone the 420dp rail alone is wider than the display, which squeezes the pane
 * holding the actual UI down to zero.
 *
 * The rules here are deliberately few. Every screen asks the same two
 * questions — "am I too narrow for a side-by-side rail?" and "do I need to
 * scroll?" — and this is where those are answered once, rather than each screen
 * inventing its own threshold.
 *
 * Everything is derived from [LocalConfiguration], which Compose updates on
 * rotation even though MainActivity declares `configChanges="orientation|…"`
 * and so never gets recreated. That's what makes a rotating phone re-lay-out
 * rather than stay stuck in its launch orientation.
 */
data class ScreenMetrics(
    val widthDp: Int,
    val heightDp: Int,
) {
    /**
     * Too narrow to put a rail beside content: the two-pane screens stack into
     * one scrolling column instead.
     *
     * 600dp is the standard Android compact/medium boundary, and it lands in
     * the right place here for a physical reason rather than a fashionable one:
     * below it, [railWidth]'s share of the screen would leave the rail too
     * cramped to read AND the pane too cramped to use, so there's no split
     * worth making. Phones are ~360-430dp portrait — comfortably below. A phone
     * in landscape (~800-900dp) is NOT narrow, and correctly keeps the rail.
     */
    val isNarrow: Boolean get() = widthDp < STACK_BELOW_DP

    /**
     * Not enough height to trust that a column of content fits. A landscape
     * phone is ~360-410dp tall, against staff screens that routinely stack
     * 500dp+ of content. Screens use this to decide layout — the scrolling
     * itself is unconditional, because a Column that fits loses nothing by
     * being scrollable and a Column that doesn't fit is unusable without it.
     */
    val isShort: Boolean get() = heightDp < SHORT_BELOW_DP

    /** Shorter edge, i.e. the one that runs out first. */
    val smallestDimensionDp: Int get() = min(widthDp, heightDp)

    /**
     * Width of the dark left rail, or null when the screen is [isNarrow] and
     * callers should stack instead.
     *
     * Proportional, capped at the original 420dp. The cap is what keeps the
     * existing fleet bit-identical: at 1000dp+ wide — every retail tablet and
     * signage box we run — this returns exactly the 420dp that was hard-coded
     * before, so this whole change is a no-op there. Below that it shrinks
     * rather than eating the pane: 891dp landscape phone → 374dp rail, leaving
     * 517dp of usable pane instead of 471dp.
     */
    val railWidth: Dp? get() =
        if (isNarrow) null else min(RAIL_MAX_DP, (widthDp * RAIL_FRACTION).toInt()).dp

    /**
     * Edge length of each of the four staff-unlock corner zones.
     *
     * Capped at a quarter of the shorter edge, and that cap is the whole point:
     * at a fixed 180dp on a 360dp-wide phone the top-left and top-right zones
     * meet in the middle with no gap, turning "four corners" into a full-width
     * tap-eating band — and in landscape (360dp tall) the left and right zones
     * tile top-to-bottom, killing 45% of the screen. A quarter of the shorter
     * edge guarantees two opposing zones can never span it: they occupy half,
     * always leaving a live gap between them.
     *
     * At 800dp (tablet, shorter edge) a quarter would be 200dp, so the 180dp
     * cap holds and the fleet's unlock gesture is untouched.
     */
    val cornerZone: Dp get() =
        min(CORNER_MAX_DP, (smallestDimensionDp * CORNER_FRACTION).toInt()).dp

    private companion object {
        const val STACK_BELOW_DP = 600
        const val SHORT_BELOW_DP = 480
        const val RAIL_MAX_DP = 420
        const val RAIL_FRACTION = 0.42f
        const val CORNER_MAX_DP = 180
        const val CORNER_FRACTION = 0.25f
    }
}

/** The current window's metrics; recomposes callers on rotation / resize. */
@Composable
fun rememberScreenMetrics(): ScreenMetrics {
    val config = LocalConfiguration.current
    return remember(config.screenWidthDp, config.screenHeightDp) {
        ScreenMetrics(config.screenWidthDp, config.screenHeightDp)
    }
}
