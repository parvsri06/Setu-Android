package `in`.setu.relay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.crypto.RescuerDemo
import `in`.setu.relay.crypto.SealedBox
import `in`.setu.relay.relay.RelayEngine
import `in`.setu.relay.relay.RelayService
import `in`.setu.relay.relay.RelayState
import `in`.setu.relay.wire.Bodies
import `in`.setu.relay.wire.MsgType
import java.util.Locale

/**
 * Field-test data on screen. docs/09-test-plan.md asks for the advertising path,
 * the service restart count and the packet counters — those are the numbers that
 * either confirm or embarrass the model, and hiding them helps nobody.
 */
@Composable
fun DiagnosticsScreen(
    engine: RelayEngine,
    state: RelayState,
    onRescuer: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.diag_title), onBack)

        SetuCard {
            LabelValue(stringResource(R.string.diag_identity), state.identityKeyId)
            Text(
                stringResource(
                    if (state.hardwareBackedKey) R.string.diag_key_hw else R.string.diag_key_sw,
                ),
                color = if (state.hardwareBackedKey) Setu.colors.deliveredText else Setu.colors.warnText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SetuCard {
            Text(
                stringResource(
                    if (state.extendedAdvertising) R.string.diag_path_ext else R.string.diag_path_legacy,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(
                    if (state.scanning) R.string.diag_scanning else R.string.diag_not_scanning,
                ),
                color = if (state.scanning) Setu.colors.deliveredText else Setu.colors.warnText,
                style = MaterialTheme.typography.bodyMedium,
            )
            state.radioError?.let {
                Text(it, color = Setu.colors.warnText, style = MaterialTheme.typography.bodyMedium)
            }
        }

        SetuCard {
            LabelValue(stringResource(R.string.diag_presence), state.presenceSeen.toString())
            LabelValue(stringResource(R.string.diag_packets), state.packetsSeen.toString())
            LabelValue(stringResource(R.string.diag_bursts), state.burstsSent.toString())
            LabelValue(stringResource(R.string.diag_duplicates), state.duplicatesHeard.toString())
            LabelValue(stringResource(R.string.diag_malformed), state.malformedDropped.toString())
            LabelValue(stringResource(R.string.diag_badsig), state.signatureDropped.toString())
            LabelValue(stringResource(R.string.diag_stored), state.totalStored.toString())
            LabelValue(stringResource(R.string.diag_known_keys), state.knownKeys.toString())
            LabelValue(stringResource(R.string.diag_battery), "${state.batteryPct}%")
            LabelValue(stringResource(R.string.diag_restarts), RelayService.restartCount(context).toString())
            if (state.fragmentsSeen > 0) {
                LabelValue("Fragments heard / reassembled", "${state.fragmentsSeen} / ${state.reassembled}")
            }
        }

        SetuCard {
            Text(
                stringResource(
                    if (state.bulkServerUp) R.string.diag_bulk_up else R.string.diag_bulk_down,
                ),
                color = if (state.bulkServerUp) Setu.colors.deliveredText else Setu.colors.warnText,
                style = MaterialTheme.typography.bodyLarge,
            )
            LabelValue(stringResource(R.string.diag_records_held), state.recordsHeld.toString())
            LabelValue(stringResource(R.string.diag_records_others), state.recordsForOthers.toString())
            LabelValue(stringResource(R.string.diag_bulk_sessions), state.bulkSessions.toString())
            LabelValue(stringResource(R.string.diag_records_pushed), state.recordsPushed.toString())
            LabelValue(stringResource(R.string.diag_records_received), state.recordsReceived.toString())
            state.bulkLastResult?.let {
                Text(it, color = Setu.colors.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (state.wallClockJumped) {
            Text(
                stringResource(R.string.diag_clock_jump),
                color = Setu.colors.warnText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            stringResource(R.string.diag_verify_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BigButton(
            label = stringResource(R.string.rescuer_title),
            iconRes = R.drawable.ic_status_delivered,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurface,
            onClick = onRescuer,
        )
    }
}

/**
 * Opens what this phone sealed, using the demo rescuer private key that ships in
 * the APK. It exists so a demo can show that the SOS body really is encrypted
 * and really does contain the position — and the screen says out loud that key
 * distribution is not implemented. Claiming end-to-end sealing while running on
 * a hardcoded test key would be worse than saying so.
 */
@Composable
fun RescuerScreen(state: RelayState, onBack: () -> Unit) {
    val rescuerKey = RescuerDemo.privateKey

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.rescuer_title), onBack)

        Text(
            stringResource(
                if (rescuerKey != null) R.string.rescuer_body else R.string.rescuer_no_key,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val sealedOnes = state.myMessages.filter {
            it.type == MsgType.SOS || it.type == MsgType.CHECK_IN
        }
        if (sealedOnes.isEmpty()) {
            Text(stringResource(R.string.rescuer_none), style = MaterialTheme.typography.bodyLarge)
        }

        for (m in sealedOnes) {
            SetuCard {
                Text("${MsgType.name(m.type)}  ${m.idHex.take(8)}", style = MaterialTheme.typography.titleMedium)
                // A shared build has no rescuer key, so the body stays sealed —
                // which is the correct outcome, not a failure.
                val opened = rescuerKey?.let { SealedBox.open(it, m.sealedBody) }
                when {
                    rescuerKey == null ->
                        Text(
                            stringResource(R.string.rescuer_sealed),
                            color = Setu.colors.muted,
                            style = MaterialTheme.typography.bodyLarge,
                        )

                    opened == null ->
                        Text("Could not open — not sealed to this key", color = Setu.colors.warnText)

                    m.type == MsgType.SOS && opened.all { it.toInt() == 0 } ->
                        Text(stringResource(R.string.rescuer_no_fix), color = Setu.colors.muted)

                    m.type == MsgType.SOS -> {
                        val (lat, lon) = Bodies.readSosPlaintext(opened)
                        Text(
                            stringResource(
                                R.string.rescuer_coords,
                                String.format(Locale.US, "%.5f", lat),
                                String.format(Locale.US, "%.5f", lon),
                            ),
                            color = Setu.colors.deliveredText,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    else -> Text(
                        "Check-in status ${Bodies.readCheckInStatus(opened)}",
                        color = Setu.colors.deliveredText,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
