package `in`.setu.relay.relay

import android.content.Context

/**
 * Small persisted flags. docs/07-ui-spec.md caps settings at "language and
 * battery"; theme is the one addition, and it earns its place because a relief
 * worker reading this outdoors in daylight and a household reading it at night
 * in a shelter need opposite screens.
 */
class Prefs(context: Context) {

    private val p = context.applicationContext.getSharedPreferences("setu_app", Context.MODE_PRIVATE)

    /** First run is done once the user has read the purpose notice and consented. */
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    /** Consent is revocable: turning the relay off withdraws it. */
    var consented: Boolean
        get() = p.getBoolean("consented", false)
        set(v) = p.edit().putBoolean("consented", v).apply()

    /** Whether the user wants the relay running. Survives reboot. */
    var relayWanted: Boolean
        get() = p.getBoolean("relay_wanted", false)
        set(v) = p.edit().putBoolean("relay_wanted", v).apply()

    /** BCP-47 tag chosen on the language screen, or empty for the system default. */
    var language: String
        get() = p.getString("language", "") ?: ""
        set(v) = p.edit().putString("language", v).apply()

    /**
     * "system", "light" or "dark". Stored as a plain string rather than a UI
     * enum so `relay` keeps not importing `ui` — dependency direction is
     * strictly downward, per docs/01-architecture.md. `ui/Theme.kt` parses it.
     */
    var themeMode: String
        get() = p.getString("theme_mode", "system") ?: "system"
        set(v) = p.edit().putString("theme_mode", v).apply()

    /** The contact identifier last used for a check-in. */
    var lastContact: String
        get() = p.getString("last_contact", "") ?: ""
        set(v) = p.edit().putString("last_contact", v).apply()
}
