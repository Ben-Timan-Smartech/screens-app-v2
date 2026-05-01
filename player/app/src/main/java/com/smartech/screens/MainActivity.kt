package com.smartech.screens

import android.os.Bundle
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
import androidx.media3.common.util.UnstableApi
import com.smartech.screens.player.PlayerController
import com.smartech.screens.player.PlayerScreen
import com.smartech.screens.staff.OnboardingScreen
import com.smartech.screens.staff.StaffOverlay
import com.smartech.screens.util.InputMode
import com.smartech.screens.util.LogBuffer
import kotlinx.coroutines.flow.MutableSharedFlow

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var controller: PlayerController

    /**
     * D-pad unlock bus. We emit a Unit on this flow when the staff-unlock
     * gesture fires on a TV-class device — which is "hold OK / Select for
     * [HOLD_THRESHOLD_MS]". [StaffOverlay] collects it and pops the staff
     * PIN screen, mirroring the four-corner gesture used on touchscreens.
     *
     * Buffer capacity 1 so we never lose an emission in the (unlikely) case
     * the collector hasn't subscribed yet at the moment the user holds OK
     * on first boot.
     */
    private val unlockBus = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * State for the hold-to-unlock gesture. We start the clock on the first
     * ACTION_DOWN of OK/Select, ignore the auto-repeats until enough wall
     * time has passed, then fire the unlock once and swallow the eventual
     * ACTION_UP so the focused view's click handler doesn't also run.
     *
     * `holdKeyCode == 0` means "no hold in progress".
     */
    private var holdStartedAt = 0L
    private var holdKeyCode = 0
    private var unlockFiredThisHold = false

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
        LogBuffer.i(
            "MainActivity",
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
                }
            }
        }
    }

    /**
     * Watch for the hold-OK / hold-Select unlock on TV-class devices.
     *
     * We hook at the Activity level rather than inside Compose because
     * key events on a leanback device dispatch to the focused view first;
     * if the focused view consumes the OK key on ACTION_DOWN to do its own
     * thing (Material buttons trigger their click on UP, but a long-press-
     * aware element like a TextField could absorb intermediate events),
     * we'd never see it inside an in-tree handler. `dispatchKeyEvent` runs
     * before any focus traversal, so we can time the hold reliably.
     *
     * Behaviour:
     *   • Tap (down → up under the threshold)  → not consumed; the focused
     *     view gets its normal click. Same as today's TV UX.
     *   • Hold (down ≥ HOLD_THRESHOLD_MS)      → fire unlock once on the
     *     auto-repeat that crosses the line, then consume ACTION_UP so the
     *     focused view does NOT also receive a click. Without this the
     *     button under the cursor would activate the moment the user lets
     *     go after unlocking.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        val isOkLike =
            k == KeyEvent.KEYCODE_DPAD_CENTER ||
                k == KeyEvent.KEYCODE_ENTER ||
                k == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                // Some Android TV / Fire TV remotes report SELECT as BUTTON_A.
                k == KeyEvent.KEYCODE_BUTTON_A

        if (isOkLike) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        // First ACTION_DOWN of a fresh press — start the clock.
                        holdStartedAt = System.currentTimeMillis()
                        holdKeyCode = k
                        unlockFiredThisHold = false
                    } else if (!unlockFiredThisHold && holdKeyCode == k) {
                        // We're inside the auto-repeat stream of an ongoing hold.
                        // Fire exactly once when the threshold is crossed.
                        if (System.currentTimeMillis() - holdStartedAt >= HOLD_THRESHOLD_MS) {
                            unlockFiredThisHold = true
                            LogBuffer.i("MainActivity", "Hold-OK staff unlock fired")
                            unlockBus.tryEmit(Unit)
                            return true
                        }
                    }
                }
                KeyEvent.ACTION_UP -> {
                    val fired = unlockFiredThisHold
                    holdStartedAt = 0L
                    holdKeyCode = 0
                    unlockFiredThisHold = false
                    // Suppress the click that would otherwise fire on the
                    // focused view — user was holding to unlock, not tapping.
                    if (fired) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    private companion object {
        // 1.5 seconds — long enough that you can't trigger it by accident
        // while clicking through the staff UI, short enough that staff
        // members won't dismiss it as broken.
        const val HOLD_THRESHOLD_MS = 1_500L
    }
}
