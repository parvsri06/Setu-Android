package `in`.setu.relay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.relay.RelayState

/**
 * Home. The mesh count is the single most reassuring thing the app can show, so
 * it goes first and it is the largest number on the screen.
 */
@Composable
fun HomeScreen(
    state: RelayState,
    relayWanted: Boolean,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleRelay: () -> Unit,
    onGo: (Screen) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_setu_logo),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        }

        SetuCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_relay),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (state.neighbours > 0) {
                        stringResource(R.string.home_nearby, state.neighbours)
                    } else {
                        stringResource(R.string.home_nearby_none)
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                stringResource(if (relayWanted) R.string.home_relay_on else R.string.home_relay_off),
                color = if (relayWanted) Setu.Green else Setu.Grey,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!state.bluetoothOn) {
                Text(
                    stringResource(R.string.home_bt_off),
                    color = Setu.Orange,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!state.locationServicesOn) {
                Text(
                    stringResource(R.string.home_location_off),
                    color = Setu.Orange,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (!permissionsGranted) {
            BigButton(
                label = stringResource(R.string.perm_grant),
                iconRes = R.drawable.ic_relay,
                container = Setu.Orange,
                content = Setu.Navy,
                onClick = onRequestPermissions,
            )
        }

        BigButton(
            label = stringResource(R.string.home_sos),
            iconRes = R.drawable.ic_sos,
            container = Setu.Orange,
            content = Setu.Navy,
            minHeight = Setu.SosTouch,
        ) { onGo(Screen.Sos) }

        BigButton(
            label = stringResource(R.string.home_safe),
            iconRes = R.drawable.ic_safe,
            container = Setu.Green,
            content = Setu.White,
        ) { onGo(Screen.CheckIn) }

        // The quiet line that leads to the trust screen.
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Setu.Touch)
                .clickable { onGo(Screen.Carrying) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_carry),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.home_carrying, state.carrying),
                style = MaterialTheme.typography.bodyLarge,
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

        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.home_diagnostics),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Setu.Touch)
                .clickable { onGo(Screen.Diagnostics) }
                .padding(18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
