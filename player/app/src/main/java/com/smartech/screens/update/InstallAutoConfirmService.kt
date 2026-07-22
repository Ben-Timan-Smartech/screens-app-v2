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
 * It clicks only positive buttons (Install / Update / OK / Continue), never
 * Cancel, and the text fallback ignores anything longer than a button label so
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (!InstallAutoConfirm.isExpecting()) return
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
                LogBuffer.i(TAG, "Auto-confirmed install dialog (${root.packageName})")
            }
        } catch (t: Throwable) {
            // Never crash — a dead accessibility service is worse than a
            // single missed auto-confirm (the OS disables a crashing one).
            LogBuffer.w(TAG, "auto-confirm handling failed: ${t.message}")
        }
    }

    override fun onInterrupt() {}

    private fun looksLikeInstaller(pkg: String): Boolean =
        pkg.contains("packageinstaller", ignoreCase = true)

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
        if (s == "ok") return true
        return s.startsWith("install") || s.startsWith("update") || s == "continue"
    }

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
            "android:id/button1",
        )
        private val NEGATIVE_WORDS = listOf("cancel", "don't", "dont", "deny", "back", "close")
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

    @Volatile private var expectingUntilMs: Long = 0L

    /** Called right before the system installer is launched. */
    fun arm() { expectingUntilMs = System.currentTimeMillis() + EXPECT_WINDOW_MS }

    fun disarm() { expectingUntilMs = 0L }

    fun isExpecting(): Boolean = System.currentTimeMillis() < expectingUntilMs

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
