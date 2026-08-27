package `in`.setu.relay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.relay.RelayEngine
import `in`.setu.relay.relay.RelayState
import `in`.setu.relay.relay.Scanner
import `in`.setu.relay.store.HopStore
import `in`.setu.relay.wire.Codec
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The responder's working screen: find a buried phone, sweep for proximity, and
 * see who has gone quiet.
 *
 * Plain layout on purpose — this is the functional skeleton, styled later.
 */
@Composable
fun RescueToolsScreen(engine: RelayEngine, state: RelayState, onBack: () -> Unit) {
    var contacts by remember { mutableStateOf<List<Scanner.Contact>>(emptyList()) }
    var quiet by remember { mutableStateOf<List<HopStore.Silence>>(emptyList()) }

    // A rescuer sweeping ground needs the number to move as they walk, so this
    // repolls rather than waiting for a relay event.
    LaunchedEffect(Unit) {
        while (true) {
            contacts = engine.scanner2.contacts(System.currentTimeMillis())
            delay(1_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            quiet = engine.goneQuiet()
            delay(15_000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.tools_title), onBack)

        // ------------------------------------------------------- find a phone
        Text(stringResource(R.string.tools_find_title), style = MaterialTheme.typography.titleMedium)
        SetuCard {
            Text(
                stringResource(R.string.tools_find_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BigButton(
            label = stringResource(R.string.tools_ping_all),
            iconRes = R.drawable.ic_sos,
            container = Setu.colors.sos,
            content = Setu.colors.onSos,
        ) { engine.sendFindPing(null, seconds = 30) }

        if (state.findMeActive) {
            SetuCard {
                Text(
                    stringResource(R.string.tools_screaming),
                    color = Setu.colors.sosText,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            BigButton(
                label = stringResource(R.string.tools_silence),
                iconRes = R.drawable.ic_status_expired,
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurface,
            ) { engine.silenceFindMe() }
        }

        // ---------------------------------------------------------- proximity
        Text(stringResource(R.string.tools_scan_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.tools_scan_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (contacts.isEmpty()) {
            Text(stringResource(R.string.tools_scan_none), style = MaterialTheme.typography.bodyLarge)
        }
        for (c in contacts) {
            SetuCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(c.keyIdHex.take(8), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(bandLabel(c.band)),
                        color = bandColour(c.band),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                LabelValue(
                    stringResource(R.string.tools_signal),
                    "${c.rssi} dBm  ·  ~${c.approxMetres.toInt()} m",
                )
                Text(
                    stringResource(trendLabel(c.trend)),
                    color = when (c.trend) {
                        Scanner.Trend.WARMER -> Setu.colors.deliveredText
                        Scanner.Trend.COLDER -> Setu.colors.warnText
                        else -> Setu.colors.muted
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                BigButton(
                    label = stringResource(R.string.tools_ping_this),
                    iconRes = R.drawable.ic_relay,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                ) { engine.sendFindPing(c.keyId, seconds = 30) }
            }
        }

        // ------------------------------------------------------ gone quiet
        Text(stringResource(R.string.tools_quiet_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.tools_quiet_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (quiet.isEmpty()) {
            Text(stringResource(R.string.tools_quiet_none), style = MaterialTheme.typography.bodyLarge)
        }
        for (q in quiet) {
            SetuCard {
                Text(Codec.hex(q.keyId).take(8), style = MaterialTheme.typography.titleMedium)
                LabelValue(stringResource(R.string.tools_last_heard), ago(q.lastHeardAt))
                LabelValue(
                    stringResource(R.string.tools_last_position),
                    if (q.hasFix) {
                        String.format(Locale.US, "%.5f, %.5f", q.lat, q.lon)
                    } else {
                        stringResource(R.string.tools_no_position)
                    },
                )
                if (q.hasFix) {
                    LabelValue(stringResource(R.string.tools_position_at), stamp(q.fixAt))
                }
                LabelValue(stringResource(R.string.tools_sightings), q.sightings.toString())
            }
        }
    }
}

private fun bandLabel(b: Scanner.Band): Int = when (b) {
    Scanner.Band.VERY_CLOSE -> R.string.band_very_close
    Scanner.Band.CLOSE -> R.string.band_close
    Scanner.Band.NEARBY -> R.string.band_nearby
    Scanner.Band.FAR -> R.string.band_far
}

@Composable
private fun bandColour(b: Scanner.Band) = when (b) {
    Scanner.Band.VERY_CLOSE -> Setu.colors.sosText
    Scanner.Band.CLOSE -> Setu.colors.warnText
    else -> Setu.colors.muted
}

private fun trendLabel(t: Scanner.Trend): Int = when (t) {
    Scanner.Trend.WARMER -> R.string.trend_warmer
    Scanner.Trend.COLDER -> R.string.trend_colder
    Scanner.Trend.STEADY -> R.string.trend_steady
    Scanner.Trend.UNKNOWN -> R.string.trend_unknown
}

private fun stamp(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(ms))

private fun ago(ms: Long): String {
    if (ms <= 0) return "—"
    val mins = (System.currentTimeMillis() - ms) / 60_000L
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 60 * 24 -> "${mins / 60} h ago"
        else -> "${mins / (60 * 24)} d ago"
    }
}
