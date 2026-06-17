package com.smartech.screens.data

/**
 * On-tablet user directory — drives the PIN check on the staff overlay.
 *
 * **For testing only.** In production this list is pushed from the backend
 * via `/device/settings`, scoped to the screen's store. The hardcoded
 * fallback below is what ships in the demo APK so the team can test PIN
 * roles without a backend running.
 *
 * Mirrors the seed users in `app/components/data.jsx` on the CMS side.
 */
object UserDirectory {

    enum class Role { SUPER_ADMIN, ADMIN, BRAND_MANAGER, USER, VIEWER }

    data class User(
        val id: String,
        val name: String,
        val role: Role,
        val pin: String,
    )

    // v0.1.56: seed list reset to the real people who use the app.
    // PINs come from auth.py / db.json on the server in v0.1.57+; this
    // hardcoded list is the offline-fallback identity store the tablet
    // uses when /api/users hasn't been pulled yet (or the server is
    // unreachable).
    //
    //  - Ben Timan (owner)      PIN 9999
    //  - Store Team (kiosk PIN) PIN 1111
    //  - Chris                  no PIN yet — set from the CMS Users page
    private val seedUsers: List<User> = listOf(
        User("u-owner",      "Ben Timan",  Role.SUPER_ADMIN, "9999"),
        User("u-store-team", "Store Team", Role.USER,        "1111"),
        User("u-chris",      "Chris",      Role.ADMIN,       ""),
    )

    /** Returns the user matching [pin], or null. Empty PINs never
     *  authenticate (so the "Chris" placeholder doesn't grant access
     *  to anyone who types an empty string). */
    fun authenticate(pin: String): User? =
        if (pin.isEmpty()) null
        else seedUsers.firstOrNull { it.pin.isNotEmpty() && it.pin == pin }

    fun roleLabel(role: Role): String = when (role) {
        Role.SUPER_ADMIN   -> "Super admin"
        Role.ADMIN         -> "Admin"
        Role.BRAND_MANAGER -> "Brand manager"
        Role.USER          -> "In-store user"
        Role.VIEWER        -> "Viewer"
    }
}
