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
 *
 * ### Contrast
 *
 * Values come from `Setu-docs/design/tokens.md`. Body text is 18 sp regular,
 * which is roughly 13.5 pt and therefore **not** WCAG "large text", so every
 * text pair needs 4.5:1 rather than 3:1. Two pairs in the design fall short and
 * are kept as specified rather than quietly altered:
 *
 * - light `carried` on the page ground — 4.25:1 (`#A86200` on `#F1F2F3`).
 *   On a raised surface it is fine at 4.76:1.
 * - dark `sos` as text on a raised surface — 4.15:1 (`#E0453E` on `#191C1F`).
 *   On the page ground it is fine at 4.62:1.
 *
 * Both are text-colour uses only; the fills those tokens paint, with their
 * matching `on-` colours, all clear AA comfortably. Neither affects a state a
 * user has to read to stay safe, but both are worth a design decision rather
 * than a code workaround.
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

// Values from Setu-docs/design/tokens.md, verified against the swatches in
// design/light/00a-colour-tokens.png — all ten sampled pixels match the table.
//
// The design names one colour per role; this object keeps the fill and the text
// form as separate fields, so both take the same token value.
private val LightColors = SetuColors(
    sos = Color(0xFFC22B22),
    onSos = Color(0xFFFFFFFF),
    sosText = Color(0xFFC22B22),
    carried = Color(0xFFA86200),
    onCarried = Color(0xFFFFFFFF),
    // 4.25:1 against the page — see the contrast note on SetuColors.
    carriedText = Color(0xFFA86200),
    delivered = Color(0xFF1A7A44),
    onDelivered = Color(0xFFFFFFFF),
    deliveredText = Color(0xFF1A7A44),
    warnText = Color(0xFF8A4A10),
    muted = Color(0xFF5B6165),
)

// On a dark ground the same hues invert: fills go lighter with dark content.
private val DarkColors = SetuColors(
    sos = Color(0xFFE0453E),
    onSos = Color(0xFF2A0505),
    // 4.15:1 on a raised surface — see the contrast note on SetuColors.
    sosText = Color(0xFFE0453E),
    carried = Color(0xFFE3982A),
    onCarried = Color(0xFF2A1A00),
    carriedText = Color(0xFFE3982A),
    delivered = Color(0xFF4DBD7B),
    onDelivered = Color(0xFF06240F),
    deliveredText = Color(0xFF4DBD7B),
    warnText = Color(0xFFE0A04B),
    muted = Color(0xFF9EA5A9),
)

private val LocalSetuColors = staticCompositionLocalOf { LightColors }

object Setu {
    /** Semantic colours for the current theme. Never pick a raw hex in a screen. */
    val colors: SetuColors
        @Composable @ReadOnlyComposable get() = LocalSetuColors.current

    /** Wet fingers, shaking hands. Minimum touch target — docs/07-ui-spec.md. */
    val Touch = 64.dp

    /**
     * Primary buttons. Between a row and the SOS control, per the metrics in
     * `Setu-docs/design/tokens.md`: 64 dp rows, 72 dp primary buttons, 88 dp SOS.
     */
    val Primary = 72.dp

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

// The design's `surface` is the page ground and `surface-raised` is a card, so
// they map to `surface`/`background` and `surfaceVariant` respectively.
// `error` follows `sos`: Material draws error states with it, and a failure is
// the one other place that has earned the red.
private val Light = lightColorScheme(
    primary = Color(0xFF1F5C9E),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF41556F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF1F2F3),
    onBackground = Color(0xFF15181A),
    surface = Color(0xFFF1F2F3),
    onSurface = Color(0xFF15181A),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF5B6165),
    outline = Color(0xFFC7CBCF),
    error = Color(0xFFC22B22),
    onError = Color(0xFFFFFFFF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF79AEE9),
    onPrimary = Color(0xFF0A1B2E),
    secondary = Color(0xFFA9BBD3),
    onSecondary = Color(0xFF12202F),
    background = Color(0xFF0E1012),
    onBackground = Color(0xFFF0F2F3),
    surface = Color(0xFF0E1012),
    onSurface = Color(0xFFF0F2F3),
    surfaceVariant = Color(0xFF191C1F),
    onSurfaceVariant = Color(0xFF9EA5A9),
    outline = Color(0xFF383E43),
    error = Color(0xFFE0453E),
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
