package com.smartech.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Customer-facing "next video" control (v0.1.98).
 *
 * Opt-in per screen via the `tapNext` flag — off by default, because this puts
 * a visible tappable control on a shop-floor screen and that shouldn't change
 * fleet-wide by surprise.
 *
 * Never shown for a screen in a sync group: the server forces `tapNext` false
 * for group members, so [enabled] is already group-aware and this composable
 * doesn't re-check. A lone skip would break the group's lockstep and get
 * snapped back by the next sync tick anyway.
 *
 * Placement — centre-right, and that's deliberate:
 *  - Clear of all four corners, which are the staff-unlock zones (a tap there
 *    would drive the unlock sequence instead of skipping).
 *  - Clear of the product-info card (bottom-start) and the guided-experience
 *    attract prompt (top/bottom centre), so the three can coexist.
 * Vertically centred means it stays clear of the 180dp corner zones on any
 * screen taller than ~360dp — i.e. all of them.
 *
 * MUST be composed ABOVE StaffOverlay (see MainActivity): the unlock catcher is
 * a full-screen pointer sibling, and anything behind it never receives taps at
 * all — the bug that cost three attempts on the product card.
 */
@Composable
fun TapNextOverlay(
    enabled: Boolean,
    onNext: () -> Unit,
) {
    if (!enabled) return
    Box(
        Modifier
            .fillMaxSize()
            .padding(end = 28.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            "NEXT ›",
            color = Color(0xFFF7F6F2),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xCC101010))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onNext() }
                // ~44dp tall / comfortably wide — an easy target from a step back.
                .padding(horizontal = 22.dp, vertical = 14.dp),
        )
    }
}
