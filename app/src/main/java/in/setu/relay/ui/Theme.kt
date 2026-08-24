package `in`.setu.relay.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The palette is deliberately **not** the logo palette.
 *
 * Orange, green and navy are the mark; using them as the whole interface meant
 * three saturated hues competing for attention on every screen, which left
 * nothing loud enough to mean "emergency". Surfaces are now neutral and there is
 * a single blue accent for ordinary actions, so the three colours that carry
 * meaning are the only saturated things on screen:
 *
 * - **red** — SOS, and nothing else
 * - **amber** — CARRIED, the state that must never look like success
 * - **green** — DELIVERED, and only on a real delivery receipt
 *
 * That is the honesty rule from docs/07-ui-spec.md expressed as a palette: if
 * green appears anywhere a user can reach without a confirmed receipt, the
 * colour stops meaning anything. Every state still carries an icon and a word,
 * so colour is never the only signal.
 */
@Immutable
data class SetuColors(
    /** Fill for the SOS control, and the content colour that sits on it. */
    val sos: Color,
    val onSos: Color,
    /** The same meaning as text on a card or a background. */
    val sosText: Color,

    val carried: Color,
    val onCarried: Color,
    val carriedText: Color,

    val delivered: Color,
    val onDelivered: Color,
    val deliveredText: Color,

    /** Something needs attention but nothing has gone wrong: Bluetooth off, etc. */
    val warnText: Color,
    /** Secondary and inactive text. Replaces the old hardcoded grey. */
    val muted: Color,
)

private val LightColors = SetuColors(
    sos = Color(0xFFC62828),
    onSos = Color(0xFFFFFFFF),
    sosText = Color(0xFFC62828),
    carried = Color(0xFF9A5B00),
    onCarried = Color(0xFFFFFFFF),
    carriedText = Color(0xFF9A5B00),
    delivered = Color(0xFF1B7F3B),
    onDelivered = Color(0xFFFFFFFF),
    deliveredText = Color(0xFF1B7F3B),
    warnText = Color(0xFF9A5B00),
    muted = Color(0xFF5A6270),
)

// On a dark ground the same hues have to invert: fills go lighter with dark
// content, text goes lighter still. Every pair below clears WCAG AA at body size.
private val DarkColors = SetuColors(
    sos = Color(0xFFFF6B6B),
    onSos = Color(0xFF2A0505),
    sosText = Color(0xFFFF8A85),
    carried = Color(0xFFF2A93B),
    onCarried = Color(0xFF2A1A00),
    carriedText = Color(0xFFF2A93B),
    delivered = Color(0xFF4ED17F),
    onDelivered = Color(0xFF06240F),
    deliveredText = Color(0xFF4ED17F),
    warnText = Color(0xFFF2A93B),
    muted = Color(0xFF99A2AE),
)

private val LocalSetuColors = staticCompositionLocalOf { LightColors }

object Setu {
    /** Semantic colours for the current theme. Never pick a raw hex in a screen. */
    val colors: SetuColors
        @Composable @ReadOnlyComposable get() = LocalSetuColors.current

    /** Wet fingers, shaking hands. Minimum touch target — docs/07-ui-spec.md. */
    val Touch = 64.dp

    /** SOS is bigger still. */
    val SosTouch = 88.dp
}

/** What the user chose in Settings, as stored by [`in`.setu.relay.relay.Prefs]. */
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromKey(key: String): ThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

private val Light = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF41556F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF171A1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171A1F),
    surfaceVariant = Color(0xFFECEFF3),
    onSurfaceVariant = Color(0xFF5A6270),
    outline = Color(0xFFD5DAE1),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6EA8FF),
    onPrimary = Color(0xFF0A1B2E),
    secondary = Color(0xFFA9BBD3),
    onSecondary = Color(0xFF12202F),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE9EDF2),
    surface = Color(0xFF1A1F26),
    onSurface = Color(0xFFE9EDF2),
    surfaceVariant = Color(0xFF232A33),
    onSurfaceVariant = Color(0xFF99A2AE),
    outline = Color(0xFF2A313A),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0505),
)

/**
 * Deliberately larger than the Material defaults. The reader is outdoors, under
 * stress, possibly not a confident reader, and may be holding the phone at
 * arm's length.
 */
private val SetuTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun SetuTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSetuColors provides if (dark) DarkColors else LightColors) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = SetuTypography,
            content = content,
        )
    }
}
