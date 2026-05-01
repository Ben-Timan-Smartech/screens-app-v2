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

    private val seedUsers: List<User> = listOf(
        User("u-owner",  "Owner",       Role.SUPER_ADMIN,   "9999"),
        User("u-staff",  "Floor staff", Role.USER,          "1111"),
        User("u-alex",   "Alex Mendez", Role.ADMIN,         "4218"),
        User("u-jordan", "Jordan Park", Role.ADMIN,         "7741"),
        User("u-mia",    "Mia Chen",    Role.BRAND_MANAGER, "6302"),
        User("u-theo",   "Theo Reyes",  Role.VIEWER,        "3556"),
    )

    /** Returns the user matching [pin], or null. */
    fun authenticate(pin: String): User? = seedUsers.firstOrNull { it.pin == pin }

    fun roleLabel(role: Role): String = when (role) {
        Role.SUPER_ADMIN   -> "Super admin"
        Role.ADMIN         -> "Admin"
        Role.BRAND_MANAGER -> "Brand manager"
        Role.USER          -> "In-store user"
        Role.VIEWER        -> "Viewer"
    }
}
