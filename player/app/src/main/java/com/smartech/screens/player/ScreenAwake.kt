package com.smartech.screens.player

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import com.smartech.screens.data.KeepAwakeConfig
import com.smartech.screens.data.LocationTaxonomy
import com.smartech.screens.util.LogBuffer

/**
 * v0.2.23: keeps a dashboard (web page) screen awake and on top of Android's
 * lock screen. The office Elo box dropped to its lock screen after a while,
 * and FLAG_KEEP_SCREEN_ON alone can't help once something else turns the
 * screen off (the power key, an admin policy, a reboot).
 *
 * While holding:
 *  - FLAG_KEEP_SCREEN_ON, as every screen already has;
 *  - show-when-locked + turn-screen-on, so if the box locks anyway the page
 *    draws over the lock screen instead of behind it;
 *  - a swipe-only lock screen is dismissed outright. A PIN/pattern lock stays
 *    underneath (an app can't clear it) with the page drawn over it;
 *  - a screen wake lock, renewed on every [hold] call. When the screen is off
 *    it's taken with ACQUIRE_CAUSES_WAKEUP, which turns the screen back on.
 *
 * Released (hours mode outside its window, or "off"): all of the above is
 * dropped, KEEP_SCREEN_ON included, so the box sleeps and locks on its own
 * timeout. [restoreDefault] is the playlist path — plain keep-screen-on, which
 * is exactly what playlist screens had before this existed.
 */
class ScreenAwake(private val activity: Activity) {

    private enum class State { DEFAULT, HELD, RELEASED }

    private val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private var state = State.DEFAULT
    private var wakeLock: PowerManager.WakeLock? = null
    private var dismissFailLogged = false

    val isHolding: Boolean get() = state == State.HELD

    /** Call every [TICK_MS] while the dashboard should be up. Cheap when the
     *  screen is already on and unlocked. */
    fun hold() {
        if (state != State.HELD) {
            state = State.HELD
            dismissFailLogged = false
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setOverLockScreen(true)
            LogBuffer.i(TAG, "Holding the screen awake and over the lock screen")
        }
        val wasOff = !power.isInteractive
        if (wasOff) LogBuffer.w(TAG, "Screen was off — waking it for the dashboard")
        renewWakeLock(wake = wasOff)
        dismissSwipeLock()
    }

    /** Let the box sleep and lock on its own timeout. */
    fun release() {
        if (state == State.RELEASED) return
        state = State.RELEASED
        dropWakeLock()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setOverLockScreen(false)
        LogBuffer.i(TAG, "Released — the box can sleep and lock on its own timeout")
    }

    /** Back to plain keep-screen-on (playlist mode). */
    fun restoreDefault() {
        if (state == State.DEFAULT) return
        state = State.DEFAULT
        dropWakeLock()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setOverLockScreen(false)
    }

    fun destroy() = dropWakeLock()

    private fun setOverLockScreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(on)
            activity.setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) activity.window.addFlags(flags) else activity.window.clearFlags(flags)
        }
    }

    /** A fresh timed lock each tick, then let the old one go, so the screen is
     *  never left unheld between them and a crashed tick loop can't pin the
     *  screen on forever. */
    @Suppress("DEPRECATION")   // SCREEN_BRIGHT + ACQUIRE_CAUSES_WAKEUP: still the only way to wake from an app
    private fun renewWakeLock(wake: Boolean) {
        val levels = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE or
            (if (wake) PowerManager.ACQUIRE_CAUSES_WAKEUP else 0)
        val next = runCatching {
            power.newWakeLock(levels, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.onFailure { LogBuffer.w(TAG, "Couldn't take the screen wake lock: ${it.message}") }.getOrNull()
        dropWakeLock()
        wakeLock = next
    }

    private fun dropWakeLock() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    /** Only a lock screen with no PIN/pattern — dismissing a secure one would
     *  pop its PIN pad over the dashboard. Needs the screen on and the page
     *  showing over the lock, so a miss just retries on the next tick. */
    private fun dismissSwipeLock() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!power.isInteractive || !keyguard.isKeyguardLocked || keyguard.isKeyguardSecure) return
        keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                LogBuffer.i(TAG, "Swipe lock screen dismissed")
            }
            override fun onDismissError() {
                if (!dismissFailLogged) {
                    dismissFailLogged = true
                    LogBuffer.w(TAG, "Couldn't dismiss the lock screen — the page stays drawn over it")
                }
            }
        })
    }

    companion object {
        private const val TAG = "ScreenAwake"
        private const val WAKE_LOCK_TAG = "screens:dashboard"
        const val TICK_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * TICK_MS

        /** True when the store's clock is inside [cfg]'s hours. Wraps midnight
         *  (22:00–06:00). Unparseable times fail open (awake) — a dark
         *  dashboard is the bug this exists to fix. Uses Calendar for the
         *  same reason as the updater window: it works on every API level. */
        fun shouldHold(cfg: KeepAwakeConfig, cityCode: String?): Boolean = when (cfg.mode) {
            KeepAwakeConfig.MODE_OFF -> false
            KeepAwakeConfig.MODE_HOURS -> {
                val from = minutesOf(cfg.from)
                val to = minutesOf(cfg.to)
                if (from == null || to == null || from == to) true
                else {
                    val cal = java.util.Calendar.getInstance(storeZone(cityCode))
                    val now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                    if (from < to) now in from until to else now >= from || now < to
                }
            }
            else -> true
        }

        private fun minutesOf(hhmm: String): Int? {
            val parts = hhmm.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return if (h in 0..23 && m in 0..59) h * 60 + m else null
        }

        /** The store's city zone (the CMS hours are store time), else the
         *  box's own. Same rule as the overnight update window. */
        private fun storeZone(cityCode: String?): java.util.TimeZone {
            val id = LocationTaxonomy.timeZoneOfCity(cityCode) ?: return java.util.TimeZone.getDefault()
            val zone = java.util.TimeZone.getTimeZone(id)
            return if (zone.id == id) zone else java.util.TimeZone.getDefault()
        }
    }
}
