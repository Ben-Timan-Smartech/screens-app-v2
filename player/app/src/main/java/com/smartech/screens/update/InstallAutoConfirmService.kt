package com.smartech.screens.update

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.smartech.screens.util.LogBuffer

/**
 * Auto-confirms Android's "Install this update?" dialog so an unattended kiosk
 * updates itself without anyone tapping INSTALL.
 *
 * WHY THIS EXISTS
 * ---------------
 * A normal (non-privileged, non-device-owner) app cannot suppress the system
 * PackageInstaller's final confirmation. [Updater.launchInstaller] fires the
 * standard install intent and Android puts up a "Do you want to install an
 * update to this existing application?" dialog with CANCEL / INSTALL. On a
 * shop-floor screen ("please do not touch") nobody taps INSTALL — so the
 * update never lands and the screen sits parked on the dialog instead of
 * playing content.
 *
 * Device-owner provisioning would let us install silently, but that needs
 * every existing box re-provisioned (factory reset / `adb dpm set-device-owner`
 * on an account-free device). This service is the retrofit that works on the
 * already-deployed fleet: it watches for the installer dialog and presses the
 * confirm button itself.
 *
 * SAFETY
 * ------
 * It only ever acts when BOTH hold:
 *   1. [InstallAutoConfirm.isExpecting] is true — armed by [Updater] right
 *      before it launches the installer, and only for a few minutes — so a
 *      stray install dialog from any other source is never auto-confirmed; and
 *   2. the foreground window belongs to a package installer.
 * It clicks only positive buttons (Install / Update / OK / Continue / Open, and
 * Play Protect's Scan app), never Cancel or "Install anyway", and the text fallback ignores anything longer than a button label so
 * the dialog's body copy ("Do you want to install…") can't be mistaken for a
 * button. Everything runs inside a try/catch: a crashing accessibility service
 * gets disabled by the OS, which would silently break auto-update.
 *
 * ENABLEMENT
 * ----------
 * Turning an accessibility service on is a one-time manual step per device
 * (Settings → Accessibility → Screens) that survives reboots. The Device admin
 * screen has a one-tap shortcut to that page plus a live status. The very first
 * update that carries this service still needs a manual tap — the service isn't
 * enabled yet when its own update arrives — but every update after that is
 * hands-free.
 */
class InstallAutoConfirmService : AccessibilityService() {

    private var lastClickAtMs = 0L

    /**
     * v0.2.21: the install kills our process, and Android rebinds this service
     * in the freshly installed version. A bound accessibility service is one of
     * the few things allowed to start an activity from the background, so this
     * is the reliable moment to bring the player back on Android 10+.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            InstallAutoConfirm.relaunchIfPending(this, "auto-confirm service reconnected")
        } catch (t: Throwable) {
            LogBuffer.w(TAG, "relaunch on connect failed: ${t.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            // The update receiver may mark a relaunch after we've connected.
            InstallAutoConfirm.relaunchIfPending(this, "auto-confirm service")
            if (!InstallAutoConfirm.isExpecting(this)) return
            event ?: return

            // Cheap pre-filter on the event's package; the definitive check is
            // against the active window's root below.
            val eventPkg = event.packageName?.toString().orEmpty()
            if (eventPkg.isNotEmpty() && !looksLikeInstaller(eventPkg)) return

            val root = rootInActiveWindow ?: return
            if (!looksLikeInstaller(root.packageName?.toString().orEmpty())) return

            // A single dialog fires several content-changed events as it
            // settles; one click is enough. Also stops us fighting a genuinely
            // greyed-out button in a tight loop.
            val now = System.currentTimeMillis()
            if (now - lastClickAtMs < CLICK_DEBOUNCE_MS) return

            val button = findConfirmButton(root) ?: return
            if (clickNode(button)) {
                lastClickAtMs = now
                if (isOpenText(nodeText(button))) {
                    // "App installed — Open": the update is done, so stop
                    // auto-confirming anything else.
                    InstallAutoConfirm.disarm(this)
                    LogBuffer.i(TAG, "Auto-pressed Open after update (${root.packageName})")
                } else {
                    LogBuffer.i(TAG, "Auto-confirmed install dialog (${root.packageName})")
                }
            }
        } catch (t: Throwable) {
            // Never crash — a dead accessibility service is worse than a
            // single missed auto-confirm (the OS disables a crashing one).
            LogBuffer.w(TAG, "auto-confirm handling failed: ${t.message}")
        }
    }

    override fun onInterrupt() {}

    // v0.2.22: Google Play Protect can stop a sideloaded install with "App
    // scan recommended — Scan app / Don't install app". Its dialog comes from
    // Play services or the Play Store, so those count as part of the install.
    private fun looksLikeInstaller(pkg: String): Boolean =
        pkg.contains("packageinstaller", ignoreCase = true) ||
            pkg == "com.google.android.gms" || pkg == "com.android.vending"

    /**
     * Locate the positive confirm button, preferring stable view-ids and
     * falling back to a short-text match. Never returns a Cancel/negative node.
     */
    private fun findConfirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. Known installer button ids (AOSP + Google installer + the
        //    framework positive button). Most reliable, locale-independent.
        for (id in BUTTON_IDS) {
            val hits = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()
            hits?.firstOrNull { it.isEnabled && !isNegativeText(nodeText(it)) }?.let { return it }
        }
        // 2. Text fallback — first clickable node whose short label is a
        //    positive confirm word. English only (the APK ships en), matched
        //    case-insensitively against text + contentDescription.
        return firstPositiveClickable(root)
    }

    private fun firstPositiveClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        val text = nodeText(node)
        if (node.isEnabled && isPositiveText(text) && !isNegativeText(text) &&
            hasClickableSelfOrParent(node)
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            firstPositiveClickable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun nodeText(node: AccessibilityNodeInfo): String =
        ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).trim()

    private fun isPositiveText(raw: String): Boolean {
        val s = raw.trim().lowercase()
        // Button labels are short; the dialog body ("Do you want to install an
        // update…") is long — the length gate keeps body copy out.
        if (s.isEmpty() || s.length > 16) return false
        if (s == "ok" || s == "scan app" || isOpenText(s)) return true
        return s.startsWith("install") || s.startsWith("update") || s == "continue"
    }

    /** The installer's success screen offers "Open" (relaunch) and "Done". */
    private fun isOpenText(raw: String): Boolean = raw.trim().lowercase() == "open"

    private fun isNegativeText(raw: String): Boolean {
        val s = raw.lowercase()
        return NEGATIVE_WORDS.any { s.contains(it) }
    }

    private fun hasClickableSelfOrParent(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < MAX_PARENT_HOPS) {
            if (n.isClickable) return true
            n = n.parent
            hops++
        }
        return false
    }

    /**
     * Click the node, or the nearest clickable ancestor if the labelled node
     * isn't itself the click target (common: a TextView inside a Button).
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < MAX_PARENT_HOPS) {
            if (n.isClickable && n.isEnabled) {
                return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            n = n.parent
            hops++
        }
        return false
    }

    companion object {
        private const val TAG = "AutoUpdate"
        private const val CLICK_DEBOUNCE_MS = 1_500L
        private const val MAX_PARENT_HOPS = 6
        private val BUTTON_IDS = listOf(
            "com.android.packageinstaller:id/ok_button",
            "com.google.android.packageinstaller:id/ok_button",
            // Pre-Android 10 success screen's "Open".
            "com.android.packageinstaller:id/launch_button",
            "com.google.android.packageinstaller:id/launch_button",
            // Positive dialog button: INSTALL, and "Open" on 10+'s success dialog.
            "android:id/button1",
        )
        // "anyway" keeps "Install anyway" (overriding a Play Protect warning)
        // a person's decision.
        private val NEGATIVE_WORDS = listOf("cancel", "don't", "don’t", "dont", "deny", "back", "close", "anyway")
    }
}

/**
 * Small shared gate between [Updater] and [InstallAutoConfirmService]. The
 * updater "arms" it just before launching the system installer; the service
 * only auto-confirms while armed, so no unrelated install dialog is ever
 * touched. Also holds the enabled-state check + settings deep-link the Device
 * admin UI uses.
 */
object InstallAutoConfirm {
    // How long after arming we keep auto-confirming. Long enough to cover a
    // slow dialog on a legacy box; short enough that a manual sideload minutes
    // later isn't auto-tapped.
    private const val EXPECT_WINDOW_MS = 5 * 60 * 1000L

    // How long after the update lands we'll still bring the player back.
    private const val RELAUNCH_WINDOW_MS = 3 * 60 * 1000L
    private const val PREFS = "install_auto_confirm"
    private const val KEY_EXPECTING_UNTIL = "expecting_until_ms"

    @Volatile private var expectingUntilMs: Long = -1L // -1 = not loaded yet
    @Volatile private var relaunchPendingUntilMs: Long = 0L

    /**
     * Called right before the system installer is launched. v0.2.21: also
     * persisted, because installing kills this process and the freshly
     * installed one needs to know to press "Open" on the success screen.
     */
    fun arm(ctx: Context) = setExpectingUntil(ctx, System.currentTimeMillis() + EXPECT_WINDOW_MS)

    fun disarm(ctx: Context) = setExpectingUntil(ctx, 0L)

    fun isExpecting(ctx: Context): Boolean {
        if (expectingUntilMs < 0L) {
            expectingUntilMs = prefs(ctx).getLong(KEY_EXPECTING_UNTIL, 0L)
        }
        return System.currentTimeMillis() < expectingUntilMs
    }

    private fun setExpectingUntil(ctx: Context, untilMs: Long) {
        expectingUntilMs = untilMs
        prefs(ctx).edit().putLong(KEY_EXPECTING_UNTIL, untilMs).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called by [UpdateRelaunchReceiver] when our own package was just replaced. */
    fun markRelaunchPending() {
        relaunchPendingUntilMs = System.currentTimeMillis() + RELAUNCH_WINDOW_MS
    }

    /** MainActivity is up, so there's nothing left to bring back. */
    fun clearRelaunchPending() { relaunchPendingUntilMs = 0L }

    @Volatile private var lastRelaunchAttemptMs: Long = 0L

    /**
     * Start the player if an update just landed and it isn't up yet. Android
     * 10+ silently drops a background start (no exception), so the pending
     * flag is only cleared once MainActivity actually runs; until then the
     * accessibility service retries, throttled. MainActivity is singleTask, so
     * a doubled start just resumes it.
     */
    fun relaunchIfPending(ctx: Context, why: String) {
        val now = System.currentTimeMillis()
        if (now >= relaunchPendingUntilMs) return
        if (now - lastRelaunchAttemptMs < RELAUNCH_RETRY_MS) return
        lastRelaunchAttemptMs = now
        val intent = Intent(ctx, com.smartech.screens.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
            LogBuffer.i("AutoUpdate", "Reopening the player after update ($why)")
        } catch (t: Throwable) {
            LogBuffer.w("AutoUpdate", "Couldn't reopen after update ($why): ${t.message}")
        }
    }

    private const val RELAUNCH_RETRY_MS = 5_000L

    /** True when the operator has enabled our accessibility service. */
    fun isServiceEnabled(ctx: Context): Boolean {
        val expected = ComponentName(ctx, InstallAutoConfirmService::class.java)
        val flat = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        // The setting is a ':'-separated list of flattened ComponentNames.
        return flat.split(':').any { entry ->
            ComponentName.unflattenFromString(entry)?.let {
                it.packageName == expected.packageName && it.className == expected.className
            } ?: false
        }
    }

    /** Open the system Accessibility settings so an operator can enable us. */
    fun openAccessibilitySettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }
}
