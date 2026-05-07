package com.smartech.screens.staff

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Custom [Indication] for the staff overlay on TV-class devices.
 *
 * Replaces the default Material ripple with a thick bone-coloured border
 * drawn around the focused element. The standard Material focus halo is
 * a faint translucent overlay — fine on a phone where you can tap, useless
 * on a TV where the only navigation cue is "where's the focus right now?"
 *
 * Provided via [androidx.compose.foundation.LocalIndication] at the
 * StaffOverlay root, so every `Modifier.clickable {}` in the staff tree
 * picks it up automatically. No per-element wiring needed.
 *
 * Press / hover get a thinner border so touch users still see feedback.
 * A real ripple would require pulling Material's RippleIndication into
 * the draw stack which doesn't compose cleanly here — we accept "no
 * ripple, but always-visible focus" as the trade-off.
 */
object TvFocusIndication : Indication {
    @Composable
    override fun rememberUpdatedInstance(interactionSource: InteractionSource): IndicationInstance {
        val focused = interactionSource.collectIsFocusedAsState()
        val pressed = interactionSource.collectIsPressedAsState()
        val hovered = interactionSource.collectIsHoveredAsState()
        return remember(interactionSource) {
            TvFocusIndicationInstance(focused, pressed, hovered)
        }
    }
}

private class TvFocusIndicationInstance(
    private val focused: State<Boolean>,
    private val pressed: State<Boolean>,
    private val hovered: State<Boolean>,
) : IndicationInstance {
    override fun ContentDrawScope.drawIndication() {
        // Always draw the underlying content first; the indication is
        // an overlay border, not a replacement.
        drawContent()
        val strokeWidth = when {
            focused.value -> 4.dp.toPx()
            pressed.value || hovered.value -> 2.dp.toPx()
            else -> 0f
        }
        if (strokeWidth > 0f) {
            // Bone (#F7F6F2) — same colour the brand uses for primary
            // marks on dark backgrounds. Stands out on Ink and on most
            // splash thumbnails.
            drawRect(
                color = Color(0xFFF7F6F2),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}
