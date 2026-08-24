package `in`.setu.relay.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand tokens from docs/07-ui-spec.md. */
object Setu {
    val Orange = Color(0xFFF39F0B)
    val Green = Color(0xFF15A44A)
    val Navy = Color(0xFF1C2536)
    val Surface = Color(0xFFF6F7FB)
    val White = Color(0xFFFFFFFF)
    val Grey = Color(0xFF6B7280)
    val DarkBg = Color(0xFF111827)
    val DarkCard = Color(0xFF1C2536)

    /** Wet fingers, shaking hands. Minimum touch target. */
    val Touch = 64.dp

    /** SOS is bigger still. */
    val SosTouch = 88.dp
}

private val Light = lightColorScheme(
    primary = Setu.Orange,
    onPrimary = Setu.Navy,
    secondary = Setu.Green,
    onSecondary = Setu.White,
    background = Setu.Surface,
    onBackground = Setu.Navy,
    surface = Setu.White,
    onSurface = Setu.Navy,
    surfaceVariant = Setu.White,
    onSurfaceVariant = Setu.Grey,
    error = Setu.Orange,
)

private val Dark = darkColorScheme(
    primary = Setu.Orange,
    onPrimary = Setu.Navy,
    secondary = Setu.Green,
    onSecondary = Setu.White,
    background = Setu.DarkBg,
    onBackground = Setu.White,
    surface = Setu.DarkCard,
    onSurface = Setu.White,
    surfaceVariant = Setu.DarkCard,
    onSurfaceVariant = Color(0xFFB6BCC8),
    error = Setu.Orange,
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
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = SetuTypography,
        content = content,
    )
}
