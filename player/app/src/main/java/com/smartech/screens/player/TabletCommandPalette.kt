package com.smartech.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartech.screens.data.PlayerRepository
import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Tablet-side command palette (v0.1.29).
 *
 * Mirrors the CMS palette: triggered by `/` on a USB keyboard, lets
 * the operator run quick admin actions without first going through
 * the PIN-gated staff overlay. Designed for sites with no remote and
 * just a keyboard plugged into a generic Android media box.
 *
 * Two classes of command:
 *  - **No-PIN**: safe actions that can fire directly (refresh the
 *    playlist, show the calibration clock for 60 s).
 *  - **PIN-gated**: destructive ones (reboot, clear cache, etc.) —
 *    those route through the existing staff-overlay unlock bus so
 *    the operator hits the PIN screen first.
 *
 * Activated by [externalOpen]; collects on the bus the host activity
 * pumps when `/` is pressed. The overlay manages its own visibility
 * state from there.
 */
@Composable
fun TabletCommandPalette(
    repository: PlayerRepository,
    externalOpen: Flow<Unit>,
    onRequestUnlock: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var activeIdx by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(externalOpen) {
        externalOpen.collect {
            visible = true
            query = TextFieldValue("")
            activeIdx = 0
        }
    }

    // Auto-focus the input every time the overlay opens.
    LaunchedEffect(visible) {
        if (visible) focusRequester.requestFocus()
    }

    if (!visible) return

    // Back key dismisses.
    BackHandler(enabled = true) { visible = false }

    // The available commands. Defined inline so they can close over
    // the local visible/dispatch state.
    data class Cmd(
        val label: String,
        val hint: String,
        val pinGated: Boolean,
        val run: () -> Unit,
    )
    val commands = remember {
        listOf(
            Cmd(
                label = "Refresh playlist now",
                hint = "Re-poll the server immediately",
                pinGated = false,
                run = { repository.refreshNow() },
            ),
            Cmd(
                label = "Show calibration clock (60 s)",
                hint = "Giant ticking server-corrected clock",
                pinGated = false,
                run = {
                    scope.launch {
                        runCatching { repository.triggerLocalCalibration(60) }
                    }
                },
            ),
            Cmd(
                label = "Open device admin",
                hint = "PIN-gated — reboot, clear cache, diagnostics",
                pinGated = true,
                run = { onRequestUnlock() },
            ),
        )
    }

    val filtered = remember(commands, query.text) {
        val q = query.text.trim().lowercase()
        if (q.isEmpty()) commands
        else commands.filter { c ->
            val blob = "${c.label} ${c.hint}".lowercase()
            q.split(Regex("\\s+")).all { blob.contains(it) }
        }
    }
    val safeIdx = activeIdx.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))

    val execute: (Cmd) -> Unit = { cmd ->
        visible = false
        try { cmd.run() } catch (t: Throwable) {
            LogBuffer.w("TabletCommandPalette", "Command failed: ${t.message}", t)
        }
    }

    // Dim scrim behind the card.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC101010))
            .clickable(onClick = { visible = false }),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(top = 96.dp)
                .width(720.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFF7F6F2))
                .border(1.dp, Color(0xFFB8B1A0), RoundedCornerShape(14.dp))
                .clickable(onClick = {})   // swallow scrim clicks inside the card
                .onPreviewKeyEvent { evt ->
                    if (evt.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (evt.key) {
                        Key.DirectionDown -> {
                            activeIdx = (safeIdx + 1).coerceAtMost(filtered.size - 1)
                            true
                        }
                        Key.DirectionUp -> {
                            activeIdx = (safeIdx - 1).coerceAtLeast(0)
                            true
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            val cur = filtered.getOrNull(safeIdx)
                            if (cur != null) execute(cur)
                            true
                        }
                        Key.Escape -> {
                            visible = false
                            true
                        }
                        else -> false
                    }
                }
                .padding(20.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // Search input — a BasicTextField wired to focusRequester
                // so the keyboard input lands here without the user having
                // to click anything.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE6E2D6))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "/",
                        color = Color(0xFFE8A33D),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(24.dp),
                    )
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it; activeIdx = 0 },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color(0xFF141414),
                            fontSize = 16.sp,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }
                Spacer(Modifier.height(14.dp))

                if (filtered.isEmpty()) {
                    Text(
                        "No commands match.",
                        color = Color(0xFF3A3832),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        filtered.forEachIndexed { i, cmd ->
                            val active = i == safeIdx
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) Color(0xFFE6E2D6) else Color.Transparent)
                                    .clickable { execute(cmd) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        cmd.label,
                                        color = Color(0xFF141414),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        cmd.hint,
                                        color = Color(0xFF3A3832),
                                        fontSize = 12.sp,
                                    )
                                }
                                if (cmd.pinGated) {
                                    Text(
                                        "needs PIN",
                                        color = Color(0xFF3A3832),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .border(
                                                1.dp,
                                                Color(0xFFB8B1A0),
                                                RoundedCornerShape(4.dp),
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                if (active) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "↵",
                                        color = Color(0xFF3A3832),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("↑ ↓ navigate", color = Color(0xFF6E6B62), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("↵ run", color = Color(0xFF6E6B62), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("esc close", color = Color(0xFF6E6B62), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    Text("/ to open", color = Color(0xFF6E6B62), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
