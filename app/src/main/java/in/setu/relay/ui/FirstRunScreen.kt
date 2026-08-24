package `in`.setu.relay.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * First run, in the order docs/07-ui-spec.md sets out: language first as native
 * script, then plain-language purpose and consent, then permissions, then the
 * battery-optimisation walkthrough.
 *
 * The language step comes before any other text because a user who cannot read
 * the consent notice has not consented to anything.
 */
@Composable
fun FirstRunScreen(
    prefs: Prefs,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onDone: () -> Unit,
) {
    var step by remember { mutableIntStateOf(if (prefs.language.isEmpty()) 0 else 1) }
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(
            painterResource(R.drawable.ic_setu_logo),
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.size(72.dp),
        )

        when (step) {
            0 -> {
                Text(stringResource(R.string.lang_title), style = MaterialTheme.typography.headlineMedium)
                for ((tag, native) in LocaleWrap.SUPPORTED) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = Setu.Touch)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable {
                                prefs.language = tag
                                // Recreating is the cheapest correct way to
                                // re-read resources in the new locale.
                                (context as? Activity)?.recreate()
                            }
                            .padding(18.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(native, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }

            1 -> {
                Text(stringResource(R.string.consent_title), style = MaterialTheme.typography.headlineMedium)
                SetuCard {
                    Text(stringResource(R.string.consent_body_1), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.consent_body_2), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.consent_body_3), style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    stringResource(R.string.consent_purpose),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                BigButton(
                    label = stringResource(R.string.consent_agree),
                    iconRes = R.drawable.ic_safe,
                    container = Setu.Green,
                    content = Setu.White,
                ) { step = 2 }
            }

            2 -> {
                Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.headlineMedium)
                SetuCard { Text(stringResource(R.string.perm_body), style = MaterialTheme.typography.bodyLarge) }
                Text(
                    stringResource(
                        if (permissionsGranted) R.string.perm_granted else R.string.perm_missing,
                    ),
                    color = if (permissionsGranted) Setu.Green else Setu.Orange,
                    style = MaterialTheme.typography.bodyMedium,
                )
                BigButton(
                    label = stringResource(R.string.perm_grant),
                    iconRes = R.drawable.ic_relay,
                    container = if (permissionsGranted) Setu.Grey else Setu.Orange,
                    content = Setu.Navy,
                    onClick = onRequestPermissions,
                )
                BigButton(
                    label = stringResource(R.string.action_next),
                    iconRes = R.drawable.ic_status_carried,
                    container = MaterialTheme.colorScheme.surface,
                    content = MaterialTheme.colorScheme.onSurface,
                    enabled = permissionsGranted,
                ) { step = 3 }
            }

            else -> {
                Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.headlineMedium)
                SetuCard { Text(stringResource(R.string.battery_body), style = MaterialTheme.typography.bodyLarge) }
                BigButton(
                    label = stringResource(R.string.battery_open),
                    iconRes = R.drawable.ic_relay,
                    container = Setu.Orange,
                    content = Setu.Navy,
                    onClick = onOpenBatterySettings,
                )
                BigButton(
                    label = stringResource(R.string.battery_done),
                    iconRes = R.drawable.ic_safe,
                    container = Setu.Green,
                    content = Setu.White,
                    onClick = onDone,
                )
                Text(
                    stringResource(R.string.battery_skip),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Setu.Touch)
                        .clickable(onClick = onDone)
                        .padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
