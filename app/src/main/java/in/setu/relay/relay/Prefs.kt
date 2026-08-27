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

    /**
     * The rescuer private key, hex, or empty on an ordinary phone.
     *
     * Its presence is what makes this install a rescuer phone: SOS bodies become
     * readable and arrivals raise an alert. Stored in the app's private
     * preferences, which is the same protection the OS gives every other app
     * secret and is honest about what it is — a credential on a device, not a
     * hardware-sealed key. See crypto/RescuerKey.kt.
     */
    var rescuerKeyHex: String
        get() = p.getString("rescuer_key", "") ?: ""
        set(v) = p.edit().putString("rescuer_key", v).apply()

    /** Whether the rescuer has muted the SOS alert sound. */
    var rescueAlertsOn: Boolean
        get() = p.getBoolean("rescue_alerts", true)
        set(v) = p.edit().putBoolean("rescue_alerts", v).apply()

    /**
     * The authority signing seed, hex, on a phone allowed to publish
     * announcements.
     *
     * Separate from [rescuerKeyHex] on purpose. Reading an SOS and speaking for
     * the authority are different powers, and a field responder holding the
     * first has no business gaining the second — a control room can hand out
     * rescuer keys freely without also handing out the ability to declare an
     * evacuation route.
     */
    var authoritySeedHex: String
        get() = p.getString("authority_seed", "") ?: ""
        set(v) = p.edit().putString("authority_seed", v).apply()

    /** The contact identifier last used for a check-in. */
    var lastContact: String
        get() = p.getString("last_contact", "") ?: ""
        set(v) = p.edit().putString("last_contact", v).apply()
}
