package `in`.setu.relay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.relay.RelayEngine
import `in`.setu.relay.relay.RelayState
import `in`.setu.relay.wire.MsgType

/**
 * The trust screen. Count and sizes only, never content — relays hold sealed
 * blobs and this screen exists to make that visible.
 *
 * People deserve to know their device is a courier. Saying it plainly converts a
 * creepy surprise into a reason to trust the app.
 */
@Composable
fun CarryingScreen(engine: RelayEngine, state: RelayState, onBack: () -> Unit) {
    // Recomputed whenever the relay publishes a new count.
    val items = remember(state.carrying, state.totalStored) { engine.carriedForOthers() }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.carrying_title), onBack)

        SetuCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_carry),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.carrying_body, state.carrying),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                stringResource(R.string.carrying_encrypted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Survey records arrive over the bulk plane and are sealed to the
        // backend key, so this device genuinely cannot read one it is carrying.
        // Saying so is the entire point of this screen.
        if (state.recordsForOthers > 0) {
            SetuCard {
                Text(
                    stringResource(R.string.carrying_records, state.recordsForOthers),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.carrying_records_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (items.isEmpty()) {
            Text(
                stringResource(R.string.carrying_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        for (item in items) {
            SetuCard {
                Text(
                    stringResource(
                        R.string.carrying_row,
                        MsgType.name(item.type),
                        item.sizeBytes,
                        item.hopCount,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    item.idHex.take(8),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
