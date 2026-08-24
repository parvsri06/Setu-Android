package `in`.setu.relay.ui

import android.content.Context
import android.content.res.Configuration
import `in`.setu.relay.relay.Prefs
import java.util.Locale

/**
 * Applies the language chosen on the first-run screen.
 *
 * `AppCompatDelegate.setApplicationLocales` would be the modern route but needs
 * `appcompat`, which is not on the dependency allow-list, and the per-app
 * language API is API 33+ while minSdk is 26. Wrapping the base context works
 * everywhere and costs nothing.
 */
object LocaleWrap {

    fun wrap(base: Context): Context {
        val tag = Prefs(base).language
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }

    val SUPPORTED: List<Pair<String, String>> = listOf(
        "en" to "English",
        "hi" to "हिन्दी",
        "bn" to "বাংলা",
        "as" to "অসমীয়া",
        "brx" to "बर'",
    )
}
