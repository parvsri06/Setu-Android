package `in`.setu.relay.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.relay.Prefs

/**
 * Language, theme and the relay switch. docs/07-ui-spec.md caps settings at
 * "language and battery" for the MVP, and this stays close to that: nothing here
 * changes how the protocol behaves, only how the app is read and whether it is
 * carrying for other people.
 *
 * Turning the relay off is the DPDP consent withdrawal, so it sits here as a
 * plainly labelled control rather than being buried.
 */
@Composable
fun SettingsScreen(
    prefs: Prefs,
    relayWanted: Boolean,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onToggleRelay: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var language by remember { mutableStateOf(prefs.language) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.settings_title), onBack)

        // ---------------------------------------------------------- language
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
        SetuCard {
            for ((tag, native) in LocaleWrap.SUPPORTED) {
                ChoiceRow(
                    // Each language stays in its own script at every point in the
                    // app, so someone who cannot read the current one can still
                    // find their way out. It is why lang_* is translatable="false".
                    label = native,
                    selected = language == tag,
                ) {
                    if (language != tag) {
                        language = tag
                        prefs.language = tag
                        // Recreating is the cheapest correct way to re-read
                        // resources in the new locale — same route as first run.
                        (context as? Activity)?.recreate()
                    }
                }
            }
        }

        // ------------------------------------------------------------- theme
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium)
        SetuCard {
            ChoiceRow(stringResource(R.string.theme_system), themeMode == ThemeMode.SYSTEM) {
                onThemeMode(ThemeMode.SYSTEM)
            }
            ChoiceRow(stringResource(R.string.theme_light), themeMode == ThemeMode.LIGHT) {
                onThemeMode(ThemeMode.LIGHT)
            }
            ChoiceRow(stringResource(R.string.theme_dark), themeMode == ThemeMode.DARK) {
                onThemeMode(ThemeMode.DARK)
            }
        }
        Text(
            stringResource(R.string.settings_theme_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ------------------------------------------------------------- relay
        Text(stringResource(R.string.settings_relay), style = MaterialTheme.typography.titleMedium)
        SetuCard {
            Text(
                stringResource(if (relayWanted) R.string.home_relay_on else R.string.home_relay_off),
                color = if (relayWanted) Setu.colors.deliveredText else Setu.colors.muted,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.settings_relay_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BigButton(
            label = stringResource(if (relayWanted) R.string.home_stop else R.string.home_start),
            iconRes = if (relayWanted) R.drawable.ic_status_expired else R.drawable.ic_relay,
            container = MaterialTheme.colorScheme.surface,
            content = MaterialTheme.colorScheme.onSurface,
            onClick = onToggleRelay,
        )
    }
}

/**
 * A radio row. Selection is carried by a tick *and* by the fill, never by colour
 * alone — docs/07-ui-spec.md, and the reason every state in this app has an icon
 * and a word.
 */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Setu.Touch)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    painterResource(R.drawable.ic_status_delivered),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
