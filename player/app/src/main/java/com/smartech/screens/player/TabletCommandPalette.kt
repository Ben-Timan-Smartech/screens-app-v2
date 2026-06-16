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
import androidx.compose.foundation.focusable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    var query by remember { mutableStateOf("") }
    var activeIdx by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // v0.1.32: live state flows so toggle commands can show the
    // correct verb in their label — "Mute" vs "Unmute" etc.
    val audioOn by repository.audioOnFlow.collectAsState()
    val mixSplash by repository.mixSplashFlow.collectAsState()

    LaunchedEffect(externalOpen) {
        externalOpen.collect {
            visible = true
            query = ""
            activeIdx = 0
        }
    }

    // Auto-focus the palette card every time the overlay opens. We
    // focus the outer Box (which is `.focusable()`), NOT a text
    // input — see the [Box] block below. That gives us keystrokes
    // via `onPreviewKeyEvent` without ever invoking the IME.
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
    // Keyed on the live flags so the toggle labels rebuild whenever
    // the state flips — otherwise the palette would show "Mute" even
    // after audio was already muted by a CMS push.
    val commands = remember(audioOn, mixSplash) {
        listOf(
            // ── Safe, no-PIN actions ─────────────────────────────
            Cmd(
                label = "Refresh playlist now",
                hint = "refresh · sync · re-poll the server immediately",
                pinGated = false,
                run = { repository.refreshNow() },
            ),
            Cmd(
                label = "Update player APK",
                hint = "update · upgrade · self-install latest build",
                pinGated = false,
                run = {
                    scope.launch {
                        runCatching {
                            repository.updater?.checkAndUpdate(surfaceFailures = true)
                                ?: LogBuffer.w("TabletCommandPalette", "Updater not wired")
                        }
                    }
                },
            ),
            Cmd(
                label = if (audioOn) "Mute screen audio" else "Unmute screen audio",
                hint = if (audioOn)
                    "audio · sound · silence the screen"
                else
                    "audio · sound · let videos play with sound",
                pinGated = false,
                run = {
                    scope.launch {
                        runCatching { repository.setAudioOnServer(!audioOn) }
                    }
                },
            ),
            Cmd(
                label = if (mixSplash) "Stop mixing splash" else "Mix splash with playlist",
                hint = if (mixSplash)
                    "splash · branding · play only pushed videos"
                else
                    "splash · branding · interleave the bundled splash between videos",
                pinGated = false,
                run = {
                    scope.launch {
                        runCatching { repository.setMixSplashOnServer(!mixSplash) }
                    }
                },
            ),
            Cmd(
                label = "Show calibration clock (60 s)",
                hint = "calibrate · clock · sync diagnostic · giant ticking server-corrected clock",
                pinGated = false,
                run = {
                    scope.launch {
                        runCatching { repository.triggerLocalCalibration(60) }
                    }
                },
            ),
            // ── Restart (no-PIN, but use with care) ──────────────
            Cmd(
                label = "Reboot screen",
                hint = "reboot · restart · relaunch the player activity (cache + registration survive)",
                pinGated = false,
                run = {
                    LogBuffer.w("TabletCommandPalette", "Reboot from / palette")
                    repository.scheduleSelfRestart()
                },
            ),
            // ── PIN escalation ───────────────────────────────────
            Cmd(
                label = "Open device admin",
                hint = "admin · settings · pin · clear cache, location, diagnostics",
                pinGated = true,
                run = { onRequestUnlock() },
            ),
        )
    }

    val filtered = remember(commands, query) {
        val q = query.trim().lowercase()
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
                // v0.1.31: focusable + key capture replaces the
                // BasicTextField that used to live below. On TV-class
                // devices BasicTextField was popping the on-screen
                // IME — useless on a kiosk box and visually awful.
                // Capturing characters via nativeKeyEvent.unicodeChar
                // means we get USB keyboard input WITHOUT invoking
                // any IME at all. The focusable + focusRequester
                // pair gives the Box itself keystroke focus.
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { evt ->
                    if (evt.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (evt.key) {
                        Key.DirectionDown -> {
                            activeIdx = (safeIdx + 1).coerceAtMost(filtered.size - 1)
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp -> {
                            activeIdx = (safeIdx - 1).coerceAtLeast(0)
                            return@onPreviewKeyEvent true
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            val cur = filtered.getOrNull(safeIdx)
                            if (cur != null) execute(cur)
                            return@onPreviewKeyEvent true
                        }
                        Key.Escape -> {
                            visible = false
                            return@onPreviewKeyEvent true
                        }
                        Key.Backspace -> {
                            if (query.isNotEmpty()) {
                                query = query.dropLast(1)
                                activeIdx = 0
                            }
                            return@onPreviewKeyEvent true
                        }
                    }
                    // Printable characters: read off the native key
                    // event's unicode mapping, which already handles
                    // Shift + locale layout for us. Skip if any
                    // modifier other than Shift is held — Ctrl-K /
                    // Alt-something shouldn't type into the field.
                    val unicode = evt.nativeKeyEvent.unicodeChar
                    if (unicode != 0
                        && !evt.isCtrlPressed
                        && !evt.isAltPressed
                        && !evt.isMetaPressed
                    ) {
                        val ch = unicode.toChar()
                        if (ch >= ' ') {
                            query += ch
                            activeIdx = 0
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
                .padding(20.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // v0.1.31: search input is now a Text row, not a
                // BasicTextField. Characters land here via the outer
                // Box's `onPreviewKeyEvent` so we never invoke the
                // soft keyboard / IME — important on TV-class boxes
                // where the IME is both useless and visually awful.
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
                    if (query.isEmpty()) {
                        Text(
                            "Type to filter…",
                            color = Color(0xFF6E6B62),
                            fontSize = 16.sp,
                        )
                    } else {
                        Text(
                            query,
                            color = Color(0xFF141414),
                            fontSize = 16.sp,
                        )
                    }
                    // Blinking caret indicator so the operator knows
                    // the field is "live" without an IME.
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "▌",
                        color = Color(0xFFE8A33D),
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
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
