package com.smartech.screens

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.media3.common.util.UnstableApi
import com.smartech.screens.player.PlayerController
import com.smartech.screens.player.PlayerScreen
import com.smartech.screens.staff.HoldProgressIndicator
import com.smartech.screens.staff.OnboardingScreen
import com.smartech.screens.staff.StaffOverlay
import com.smartech.screens.util.InputMode
import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var controller: PlayerController

    /**
     * Staff-unlock event bus. Emits Unit every time the unlock gesture
     * fires — on TV-class devices that's "hold OK / Select for
     * [HOLD_THRESHOLD_MS]". [StaffOverlay] collects it and pops the staff
     * PIN screen, mirroring the four-corner gesture used on touchscreens.
     *
     * Buffer capacity 1 keeps the (theoretical) early-press case safe if
     * the collector hasn't subscribed yet on first composition.
     */
    private val unlockBus = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * The wall-clock timestamp at which the current hold-OK gesture began,
     * or null when no hold is in progress. Drives the visual fill indicator
     * in the top-right of the player screen — Compose collects this and
     * animates a ring from 0% to 100% over [HOLD_THRESHOLD_MS]. Cleared on
     * release (early or successful) and on activity pause.
     */
    private val _holdStartedAtFlow = MutableStateFlow<Long?>(null)
    private val holdStartedAtFlow: StateFlow<Long?> = _holdStartedAtFlow.asStateFlow()

    /**
     * Hold detection runs off a posted Runnable rather than counting
     * ACTION_DOWN auto-repeats. Why: on Android TV / Fire TV the input
     * flinger only auto-repeats keys that some view has "claimed" via
     * `event.startTracking()`. When the player is showing a video with
     * no focused element, there's nothing claiming the key — you get
     * one DOWN, silence, then one UP when the user releases. Counting
     * repeats produces zero events and the unlock never fires.
     *
     * The posted-Runnable approach sidesteps that entirely: on the first
     * ACTION_DOWN we schedule [unlockRunnable] for HOLD_THRESHOLD_MS in
     * the future. If the matching ACTION_UP arrives before the runnable
     * runs, we cancel it (the user just tapped). If the user keeps the
     * key held, the runnable fires on the main thread, emits on the bus,
     * and flips [unlockFiredThisHold] so the eventual UP can suppress
     * the focused view's click.
     */
    private val handler = Handler(Looper.getMainLooper())
    private val unlockRunnable = Runnable {
        unlockFiredThisHold = true
        // Indicator stops feeding new frames; the staff overlay is about to
        // cover the player anyway so it disappears with the rest.
        _holdStartedAtFlow.value = null
        Log.i(TAG, "Hold-OK staff unlock fired (held ≥${HOLD_THRESHOLD_MS}ms)")
        LogBuffer.i(TAG, "Hold-OK staff unlock fired")
        unlockBus.tryEmit(Unit)
    }

    /** True between unlock-firing and the matching ACTION_UP. */
    private var unlockFiredThisHold = false

    /** The keycode that started the current hold; 0 when no hold in progress. */
    private var holdKeyCode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen awake and full-bleed. Retail tablets / TVs should never dim.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        controller = PlayerController(this)
        val repo = (application as ScreensApp).repository

        val tvLike = InputMode.isTvLike(this)
        Log.i(
            TAG,
            "Input mode: tvLike=$tvLike hasTouch=${InputMode.hasTouch(this)} " +
                "isTelevision=${InputMode.isTelevision(this)}",
        )
        LogBuffer.i(
            TAG,
            "Input mode: tvLike=$tvLike hasTouch=${InputMode.hasTouch(this)} " +
                "isTelevision=${InputMode.isTelevision(this)}",
        )

        setContent {
            androidx.compose.material3.MaterialTheme {
                // Initial value `true` keeps already-onboarded screens from
                // briefly showing the wizard while DataStore loads. First-run
                // tablets see the player flash for a beat before the wizard.
                val onboarded by repo.store.isOnboarded.collectAsState(initial = true)

                Box(Modifier.fillMaxSize()) {
                    PlayerScreen(controller = controller, repository = repo)

                    // Staff overlay sits on top — invisible unless either the
                    // four-corner tap (touch) or the hold-OK gesture (TV)
                    // fires. Video selections from the overlay just get
                    // logged; the playlist itself is still driven by the
                    // server.
                    val onPick = remember<(com.smartech.screens.data.VideoItem) -> Unit> {
                        { _ -> /* TODO: backend endpoint for "staff override" */ }
                    }
                    StaffOverlay(
                        repository = repo,
                        onPickVideo = onPick,
                        tvLike = tvLike,
                        externalUnlock = unlockBus,
                    )

                    if (!onboarded) {
                        OnboardingScreen(repository = repo, onDone = { /* state flow flips automatically */ })
                    }

                    // Visual feedback for the hold-OK gesture. Renders a
                    // small filling ring in the top-right while the user
                    // holds an OK-like key; vanishes on release. Painted
                    // last so it sits above the player but below any modal
                    // staff stage that opens on completion.
                    HoldProgressIndicator(
                        holdStartedAtFlow = holdStartedAtFlow,
                        durationMs = HOLD_THRESHOLD_MS,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }
    }

    /**
     * Watch for the hold-OK / hold-Select staff unlock on TV-class devices.
     *
     * We hook at the Activity level rather than inside Compose because
     * key events on a leanback device dispatch to the focused view first;
     * `dispatchKeyEvent` runs before any focus traversal, so we can time
     * the hold reliably regardless of which (if any) view has focus.
     *
     * Behaviour:
     *   • Tap (down → up under the threshold)   → not consumed; the focused
     *     view gets its normal click. Tablet four-corner tap and the staff
     *     UI's button presses still work.
     *   • Hold (down ≥ HOLD_THRESHOLD_MS)        → posted Runnable fires the
     *     unlock at threshold, then the matching UP is consumed so the
     *     focused view does NOT also receive a click. Without this, the
     *     button under the cursor would activate the moment the user lets
     *     go.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        if (isOkLikeKey(k)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // Only the *first* DOWN starts a fresh timer. Auto-repeat
                    // DOWNs (repeatCount > 0) are ignored — the timer is
                    // already running.
                    if (event.repeatCount == 0 && holdKeyCode == 0) {
                        holdKeyCode = k
                        unlockFiredThisHold = false
                        // Surface the hold start to Compose so the indicator
                        // can paint a filling ring. Same timestamp drives both
                        // the visual and the unlock-firing Runnable below.
                        _holdStartedAtFlow.value = System.currentTimeMillis()
                        handler.postDelayed(unlockRunnable, HOLD_THRESHOLD_MS)
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (k == holdKeyCode) {
                        handler.removeCallbacks(unlockRunnable)
                        val fired = unlockFiredThisHold
                        holdKeyCode = 0
                        unlockFiredThisHold = false
                        // Hide the indicator on release whether or not the
                        // user crossed the threshold.
                        _holdStartedAtFlow.value = null
                        // Consume the UP so the focused view doesn't also
                        // receive a click — the user was holding to unlock,
                        // not tapping.
                        if (fired) return true
                    }
                }
                KeyEvent.ACTION_MULTIPLE -> {
                    // Some old Android TV remotes batch repeats into MULTIPLE.
                    // Treat the same as DOWN: don't restart the timer.
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        // If the user backgrounds the app mid-hold, drop the pending unlock
        // and reset state so a stray UP after foregrounding can't fire it.
        handler.removeCallbacks(unlockRunnable)
        holdKeyCode = 0
        unlockFiredThisHold = false
        _holdStartedAtFlow.value = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(unlockRunnable)
        controller.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainActivity"

        /**
         * 1.5 seconds — long enough that you can't trigger it by accident
         * while clicking through the staff UI, short enough that staff
         * members won't dismiss it as broken.
         */
        const val HOLD_THRESHOLD_MS = 1_500L

        /**
         * Every keycode we treat as "OK / Select" across the various TV
         * remotes. Most send DPAD_CENTER; some Fire TV remotes send
         * BUTTON_A; air mice + Bluetooth keyboards send ENTER /
         * NUMPAD_ENTER. KEYCODE_BUTTON_SELECT is included because a few
         * generic gamepad-style remotes use it for the centre key.
         */
        fun isOkLikeKey(k: Int): Boolean = when (k) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_SELECT -> true
            else -> false
        }
    }
}
