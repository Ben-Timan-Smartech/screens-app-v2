package com.smartech.screens

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.smartech.screens.data.PlayerRepository
import com.smartech.screens.player.PlayerController
import com.smartech.screens.player.PlayerScreen
import com.smartech.screens.player.ProductInfoCardOverlay
import com.smartech.screens.player.TabletCommandPalette
import com.smartech.screens.player.TapNextOverlay
import com.smartech.screens.staff.HoldProgressIndicator
import com.smartech.screens.staff.OnboardingScreen
import com.smartech.screens.staff.StaffOverlay
import com.smartech.screens.update.UpdaterOverlay
import com.smartech.screens.util.DisplayModes
import com.smartech.screens.util.InputMode
import com.smartech.screens.util.LogBuffer
import com.smartech.screens.util.cornerZoneDp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var controller: PlayerController
    private lateinit var watchdog: com.smartech.screens.player.PlaybackWatchdog

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
     * v0.1.29: USB-keyboard command-palette bus. Emits Unit every time
     * the user presses `/` on a keyboard plugged into the box (the
     * mirror of the CMS-side `/` palette). Distinct from [unlockBus]
     * because:
     *   • The command palette is a lightweight quick-action launcher
     *     for things that don't need PIN escalation (refresh, show
     *     calibration clock).
     *   • Destructive actions inside the palette still route through
     *     [unlockBus], which fires the existing PIN-gated staff
     *     overlay.
     * Same buffer-capacity-1 reasoning as [unlockBus].
     */
    private val commandPaletteBus = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

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

    // ── Four-corner staff unlock (touch) ─────────────────────────────
    //
    // v0.2.7: detected HERE, at the Activity, instead of by an overlay of
    // pointer-input Boxes sitting in the four corners.
    //
    // The overlay approach had to block to observe. Compose's
    // sharePointerInputWithSiblings defaults to false, so a Box with
    // pointerInput swallows every touch in its bounds before anything behind it
    // is even hit-tested — not-consuming doesn't help. So the four corners of
    // the screen became dead to whatever was underneath. That's fine over a bare
    // video, and actively wrong over interactive content: the WHOOP deck keeps
    // its chapter pips in the top-right and its Back/Next buttons in the bottom
    // corners, and the catcher ate all of them. Every future experience would
    // hit the same thing, because corners are where UI naturally goes.
    //
    // dispatchTouchEvent sees every touch BEFORE the view hierarchy does, and
    // passing it straight to super means we observe without taking anything: the
    // WebView, the product card and the Next control all still get their taps.
    // It also reaches into the WebView, which a Compose overlay never could —
    // that's a native View, so Compose's pointer input can't see inside it
    // regardless of z-order.
    //
    // Same shape as the hold-OK gesture below: the Activity watches the raw
    // input stream and emits on [unlockBus]; StaffOverlay just collects it.
    private var cornerStep = 0
    private var lastCornerTapAtMs = 0L
    /** Cached in onCreate — a TV has no touchscreen, so it uses hold-OK instead. */
    private var isTvLike = false

    /** The keycode that started the current hold; 0 when no hold in progress. */
    private var holdKeyCode = 0

    /**
     * True while the staff overlay is on screen (PIN, playlist, brand picker,
     * etc.). When the overlay is up, the OK key needs to behave normally —
     * pressing buttons in the staff UI — instead of re-triggering the
     * hold-to-unlock gesture. dispatchKeyEvent reads this flag and skips the
     * hold-detection logic when it's true. Updated by StaffOverlay via the
     * onVisibilityChange callback wired in setContent below.
     */
    private val staffOverlayVisible = MutableStateFlow(false)

    /** v0.1.75: maps a screen's saved location to the brand whose landscape
     *  splash is bundled in the APK (Smartech or tm:rw). Mirrors the server's
     *  default city->brand map + the Smartech concept override; only used for
     *  the offline cold-start splash, so the server's splashUrl corrects it
     *  once online. Null → the neutral default bundled splash. */
    private fun bundledBrandFor(city: String?, concept: String?): String? {
        if (concept?.equals("Smartech", ignoreCase = true) == true) return "smartech"
        return when (city?.trim()?.uppercase()) {
            "LDN", "BER" -> "smartech"
            "NYC", "ROM", "GLB" -> "tmrw"
            else -> null
        }
    }

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

        // v0.1.75: choose the bundled (offline cold-start) splash by the
        // screen's brand, derived from its saved location. The server's
        // splashUrl still overrides this once it downloads — this just makes
        // the pre-network splash on-brand for Smartech vs tm:rw stores. Only
        // the landscape Smartech & tm:rw splashes are bundled in the APK.
        lifecycleScope.launch {
            val city = runCatching { repo.store.locCity.first() }.getOrNull()
            val concept = runCatching { repo.store.locConcept.first() }.getOrNull()
            controller.setBundledBrand(bundledBrandFor(city, concept))
        }

        // Background safety net. The watchdog samples ExoPlayer every
        // 30 s; if playback is stuck (frozen position while we think
        // we're playing, stuck buffering, or an unrecovered error) it
        // escalates through prepare() → refreshPlaylist() → activity
        // restart. Zero-cost when playback is healthy.
        watchdog = com.smartech.screens.player.PlaybackWatchdog(
            player = controller.player,
            onKick = { repo.refreshPlaylist() },
            onRestart = { repo.scheduleSelfRestart() },
            isOnSplash = { controller.isOnSplash() },
            // v0.1.76: stuck-on-splash recovery. If a screen sits on the
            // splash while the pushed content is already downloaded, the
            // watchdog first restarts the player in-process, then (once)
            // reboots the activity. shouldBePlayingContent gates this so a
            // screen still downloading never gets needlessly bounced.
            onRestartPlayer = { repo.restartPlayer() },
            shouldBePlayingContent = { repo.hasPlayableContentPending() },
            isOnFallbackSplash = { controller.isOnFallbackSplash() },
            // v0.1.73: a corrupt/truncated cached video → purge it and
            // re-download a clean copy instead of looping on the bad file.
            onBadSource = { mediaId -> repo.invalidateCachedVideo(mediaId) },
            // v0.1.49: feed the operator-readable label of the
            // currently-playing item into every watchdog log line.
            // ExoPlayer is single-threaded — currentMediaItem must
            // be touched on Main — but the watchdog calls this from
            // a Main-dispatched coroutine so we're safe here.
            currentItemLabel = label@{
                val id = controller.player.currentMediaItem?.mediaId ?: return@label null
                if (controller.isOnSplash()) return@label "splash"
                val match = repo.intendedPlaylist.value.firstOrNull { it.id == id }
                if (match != null) "${match.title} ($id)" else id
            },
            // v0.1.80: a video the device can't decode (stuck buffering) gets
            // flagged so the playlist view shows WHY, and skipped so it can't
            // freeze the screen. Cleared once it plays fine again.
            onUnplayable = { id, reason -> repo.markPlaybackFailure(id, reason) },
            onItemPlaying = { id -> repo.clearPlaybackFailure(id) },
        )
        watchdog.start()

        // v0.1.14: apply the CMS-pushed HDMI mode override on every
        // change. Reads PlayerRepository.displayModeFlow — null means
        // "auto, leave the box alone." Non-null is a Display.Mode.modeId
        // the tablet itself reported in its heartbeat. The collector
        // runs on the main thread because Window.attributes must be
        // mutated there; lifecycleScope.launch wraps it safely without
        // a Compose recomposition cost.
        lifecycleScope.launch {
            repo.displayModeFlow.collect { mode ->
                if (mode == null) {
                    DisplayModes.apply(this@MainActivity, 0)
                } else {
                    DisplayModes.apply(this@MainActivity, mode)
                }
            }
        }

        val tvLike = InputMode.isTvLike(this)
        isTvLike = tvLike
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
                    // Catch-all Back interceptor. By default the TV remote's
                    // Back button finishes the activity, which on a HOME app
                    // re-spawns it (looking like a "go home" jump). For a
                    // kiosk we never want Back to leave the player loop —
                    // the staff overlay registers its own BackHandler when
                    // it's visible and takes precedence over this no-op.
                    BackHandler(enabled = true) { /* swallow */ }

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
                        externalUnlock = unlockBus,
                        // Track overlay visibility so dispatchKeyEvent can
                        // gate the hold-OK detection — see the property
                        // declaration up top.
                        onVisibilityChange = { visible ->
                            staffOverlayVisible.value = visible
                        },
                    )

                    // Shopper-facing product-info card. Deliberately composed
                    // ABOVE StaffOverlay so it sits in front of the invisible
                    // four-corner unlock catcher. That catcher is a full-screen
                    // pointerInput; because Compose's
                    // sharePointerInputWithSiblings defaults to false, a
                    // full-screen pointer sibling swallows every tap before it
                    // reaches a layer behind it — which is why tap-to-expand
                    // never fired while the card lived inside PlayerScreen
                    // (below the catcher). In front, the card's own clickable
                    // wins taps on its bounds while corner taps outside it still
                    // reach the catcher. Hidden while the staff overlay is up so
                    // it never paints over the PIN / menu.
                    val cardEnabled by repo.productCardFlow.collectAsState()
                    val cardMediaId by controller.currentMediaIdFlow.collectAsState()
                    val cardCity by repo.store.locCity.collectAsState(initial = null)
                    val cardState by repo.state.collectAsState()
                    val staffUp by staffOverlayVisible.collectAsState()
                    // v0.1.92: a guided experience owns the whole screen (its
                    // WebView is rendered in PlayerScreen), so suppress the card
                    // when one is set — otherwise the card would paint over it.
                    val experienceActive by repo.experienceUrlFlow.collectAsState()
                    if (!staffUp && experienceActive == null) {
                        val playingItems = (cardState as? PlayerRepository.State.Playing)
                            ?.items?.map { it.item } ?: emptyList()
                        val currentItem = cardMediaId?.let { id ->
                            playingItems.firstOrNull { it.id == id }
                        }
                        ProductInfoCardOverlay(
                            enabled = cardEnabled,
                            item = currentItem,
                            city = cardCity,
                        )
                    }

                    // v0.1.98: customer-facing "next video" control. Composed
                    // HERE (above StaffOverlay) for the same reason as the card:
                    // the four-corner unlock catcher is a full-screen pointer
                    // sibling, and anything behind it never receives taps.
                    // Centre-right, so it clears the corner unlock zones, the
                    // product card (bottom-start) and the experience prompt
                    // (top/bottom centre). Hidden while the staff overlay is up
                    // or a guided experience owns the screen; `tapNext` is
                    // already false for sync-group members (server-forced).
                    val tapNextOn by repo.tapNextFlow.collectAsState()
                    if (!staffUp && experienceActive == null) {
                        TapNextOverlay(
                            enabled = tapNextOn && cardState is PlayerRepository.State.Playing,
                            onNext = { controller.skipToNext() },
                        )
                    }

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

                    // v0.1.29: keyboard `/` command palette. Painted
                    // below UpdaterOverlay so an in-flight update
                    // still wins the screen. The palette gates its
                    // own visibility on commandPaletteBus emissions
                    // (fired from dispatchKeyEvent when `/` lands).
                    // PIN-gated commands route into unlockBus to
                    // open the existing staff overlay flow.
                    TabletCommandPalette(
                        repository = repo,
                        externalOpen = commandPaletteBus,
                        onRequestUnlock = { unlockBus.tryEmit(Unit) },
                    )

                    // Self-update overlay — shows during APK download +
                    // install, and on failure. Painted last so it sits on
                    // top of everything else (player, staff, onboarding).
                    UpdaterOverlay(updater = (application as ScreensApp).updater)
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
    /**
     * Watch every touch for the four-corner staff unlock — and take none of them.
     *
     * ALWAYS returns super.dispatchTouchEvent(ev). This method observes; it never
     * consumes. That's the whole point: the corners stay live for whatever is
     * underneath (an experience's own buttons, the product card, Next), while the
     * unlock sequence is still recognised. See the field declarations up top for
     * why the previous overlay couldn't do both.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
            !isTvLike &&                       // TVs unlock by holding OK
            !staffOverlayVisible.value          // don't re-arm inside the staff UI
        ) {
            noteCornerTap(ev.x, ev.y)
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Advance the top-left → top-right → bottom-right → bottom-left sequence.
     *
     * A tap outside every corner is IGNORED rather than treated as a reset: the
     * middle of the screen belongs to the content, and a customer poking a video
     * shouldn't quietly break a staff member's sequence. A tap on the WRONG
     * corner does reset (tapping top-left always restarts), and the sequence
     * expires after [CORNER_TIMEOUT_MS] of no corner taps — so stray corner
     * presses hours apart can never add up to an unlock.
     */
    private fun noteCornerTap(x: Float, y: Float) {
        val w = window.decorView.width.toFloat()
        val h = window.decorView.height.toFloat()
        if (w <= 0f || h <= 0f) return          // not laid out yet

        val size = cornerSizePx(w, h)
        val left = x <= size
        val right = x >= w - size
        val top = y <= size
        val bottom = y >= h - size
        val index = when {
            top && left -> 0
            top && right -> 1
            bottom && right -> 2
            bottom && left -> 3
            else -> return                       // content's touch, not ours
        }

        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastCornerTapAtMs > CORNER_TIMEOUT_MS) cornerStep = 0
        lastCornerTapAtMs = now
        when {
            index == cornerStep -> {
                cornerStep++
                if (cornerStep == 4) {
                    cornerStep = 0
                    Log.i(TAG, "Four-corner staff unlock fired")
                    LogBuffer.i(TAG, "Four-corner staff unlock fired")
                    unlockBus.tryEmit(Unit)
                }
            }
            index == 0 -> cornerStep = 1         // top-left always restarts
            else -> cornerStep = 0
        }
    }

    /** Edge length of each corner zone in px — see [cornerZoneDp] for the rule. */
    private fun cornerSizePx(w: Float, h: Float): Float {
        val density = resources.displayMetrics.density
        val shorterDp = (if (w < h) w else h) / density
        return cornerZoneDp(shorterDp) * density
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        // v0.1.29: USB-keyboard `/` opens the tablet-side command
        // palette — same shortcut as the CMS. Fires on ACTION_DOWN
        // (single-tap, not hold). Suppressed when the staff overlay
        // is already up, because then `/` should just be a character
        // entering whatever field has focus there.
        if (k == KeyEvent.KEYCODE_SLASH || k == KeyEvent.KEYCODE_NUMPAD_DIVIDE) {
            if (event.action == KeyEvent.ACTION_DOWN
                && event.repeatCount == 0
                && !staffOverlayVisible.value
            ) {
                LogBuffer.i(TAG, "Command palette hotkey (/) fired")
                commandPaletteBus.tryEmit(Unit)
                return true   // consume so the keystroke doesn't bleed elsewhere
            }
            if (!staffOverlayVisible.value) return true
        }
        if (isOkLikeKey(k)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // Only START a new hold-timer when (a) it's the very first
                    // DOWN of a press (not an auto-repeat), (b) we don't have
                    // a hold already running, and (c) the staff overlay isn't
                    // up. The third gate is what makes OK presses inside the
                    // staff UI behave normally — they fall straight through
                    // to the focused view without spinning up a re-unlock
                    // timer underneath.
                    if (
                        event.repeatCount == 0
                        && holdKeyCode == 0
                        && !staffOverlayVisible.value
                    ) {
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
                    // Cleanup is NOT gated — if we have a hold in progress we
                    // need to finish it regardless of overlay visibility (the
                    // overlay may have just opened because the unlock fired).
                    // Without this branch, the eventual UP after a successful
                    // hold would fall through to the focused view in the new
                    // staff UI and click whatever button happens to be there.
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
        if (::watchdog.isInitialized) watchdog.stop()
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
         * How long a partial corner sequence stays alive, in ms.
         *
         * Matches the timeout the old overlay used. Without it, four corner
         * taps spread across a whole day would eventually add up to an unlock;
         * with it, the taps have to look deliberate.
         */
        const val CORNER_TIMEOUT_MS = 4_000L

        /**
         * Every keycode we treat as "OK / Select" across the various TV
         * remotes. Most send DPAD_CENTER; some Fire TV remotes send
         * BUTTON_A; air mice + Bluetooth keyboards send ENTER /
         * NUMPAD_ENTER. KEYCODE_BUTTON_SELECT is included because a few
         * generic gamepad-style remotes use it for the centre key.
         *
         * Keyboard support: a USB keyboard's Enter / NumpadEnter is
         * already in this list, so plugging a keyboard into a generic
         * Android media box (Sumvision Cyclone, no-name TV stick,
         * etc.) lets the operator hold Enter to unlock the staff
         * overlay. Space is intentionally NOT included — Enter is the
         * standard "OK" key on a keyboard and giving two keys for the
         * same gesture invites accidental unlocks. Arrow keys +
         * tabbing inside the overlay are handled by Compose's default
         * focus traversal; the amber `TvFocusIndication` ring follows
         * focus regardless of input device.
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
