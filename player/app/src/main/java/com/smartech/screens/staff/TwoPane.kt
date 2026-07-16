package com.smartech.screens.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.smartech.screens.util.rememberScreenMetrics

/**
 * The staff UI's dark-rail-beside-content shell, made to survive a small screen
 * (v0.2.0).
 *
 * Every staff screen was built as `Row { Box(width = 420.dp) { … }; Box { … } }`
 * — the rail width hard-coded for the 1280x800 tablet the fleet runs. On a
 * phone that arithmetic collapses: at 360-430dp wide the rail is wider than the
 * whole display, so it takes 100% and the pane holding the actual UI is laid out
 * at **zero width**. Not clipped — gone. That's why a phone in portrait showed a
 * dark slab with no PIN pad, no playlist, and no way forward.
 *
 * Two adaptations, each keyed to the thing that actually runs out:
 *
 *  • **Width.** Above 600dp the rail stays beside the content but takes a
 *    proportional width capped at the original 420dp, so it can't crowd out a
 *    narrow pane. Below 600dp there's no split worth making and the rail
 *    becomes a header above the content instead.
 *  • **Height.** The rail only becomes scrollable when the screen is genuinely
 *    too short (or stacked) to hold it. This is deliberate and load-bearing:
 *    several rails use `Spacer(Modifier.weight(1f))` to push controls to the
 *    bottom, and a weight inside a scrolling column resolves to zero — so
 *    scrolling unconditionally would silently re-flow the rail on every tablet
 *    in the fleet. Gating on [ScreenMetrics.isShort] keeps the 800dp-tall fleet
 *    on the exact bounded-height path it has today.
 *
 * Taken together with [railWidth][com.smartech.screens.util.ScreenMetrics.railWidth]'s
 * 420dp cap, this whole file is a no-op at 1000dp+ wide and 480dp+ tall — i.e.
 * on every device currently deployed. It only changes what happens on the small
 * screens that were broken anyway.
 *
 * The pane is deliberately left alone: it gets a box and its own space, and
 * decides for itself how to scroll. Several panes host a `LazyColumn`, and
 * wrapping one of those in a parent `verticalScroll` is an immediate crash
 * (infinite height constraint), so that call has to stay with the caller.
 */
@Composable
fun TwoPaneScaffold(
    rail: @Composable () -> Unit,
    pane: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    railPadding: PaddingValues = PaddingValues(horizontal = 48.dp, vertical = 56.dp),
    /** Tighter by default — a stacked header has far less room to spend. */
    stackedRailPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    railColor: Color = Color(0xFF141414),
    paneColor: Color = Color(0xFFF7F6F2),
) {
    val metrics = rememberScreenMetrics()
    val railScroll = rememberScrollState()
    val railWidth = metrics.railWidth

    if (railWidth == null) {
        // Stacked: rail on top as a header. Capped at a third of the height so
        // a long rail (the playlist view's toggles, poll mode, restart…) can
        // never squeeze out the pane below it — past the cap the rail scrolls
        // within its own band instead of growing.
        Column(modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = (metrics.heightDp * STACKED_RAIL_MAX_FRACTION).dp)
                    .background(railColor)
                    .verticalScroll(railScroll)
                    .padding(stackedRailPadding),
            ) { rail() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(paneColor),
            ) { pane() }
        }
    } else {
        Row(modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .background(railColor)
                    .then(if (metrics.isShort) Modifier.verticalScroll(railScroll) else Modifier)
                    .padding(railPadding),
            ) { rail() }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(paneColor),
            ) { pane() }
        }
    }
}

/** A third of the screen: enough for a heading + a control or two, never enough
 *  to starve the pane holding the thing the user came for. */
private const val STACKED_RAIL_MAX_FRACTION = 0.33f

/**
 * True when the content pane has to earn its space rather than spend it —
 * either it's stacked under the rail, or the screen is too short for the
 * generous vertical rhythm the staff screens were written with.
 */
@Composable
fun compactPane(): Boolean {
    val m = rememberScreenMetrics()
    return m.isNarrow || m.isShort
}

/**
 * Inset for a content pane. The tablet's 64/56dp margin reads as composed on a
 * 1280dp panel; on a 360dp one it would spend a third of the width on empty
 * edges before a single pixel of content is drawn.
 */
@Composable
fun paneInset(): PaddingValues =
    if (compactPane()) PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    else PaddingValues(horizontal = 64.dp, vertical = 56.dp)

/** As [paneInset], for the screens whose tablet inset is 56/40 rather than 64/56. */
@Composable
fun paneInsetSnug(): PaddingValues =
    if (compactPane()) PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    else PaddingValues(horizontal = 56.dp, vertical = 40.dp)
