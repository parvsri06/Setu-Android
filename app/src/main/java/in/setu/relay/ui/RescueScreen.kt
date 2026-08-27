package `in`.setu.relay.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.crypto.RescuerKey
import `in`.setu.relay.crypto.SealedBox
import `in`.setu.relay.relay.Prefs
import `in`.setu.relay.relay.RelayState
import `in`.setu.relay.wire.Bodies
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The rescuer view: every SOS this phone has heard, with the location opened.
 *
 * A civilian phone and a rescuer phone run identical code. What separates them
 * is whether the rescuer private key has been entered here — see
 * crypto/RescuerKey.kt for why it is a credential rather than a build flavour.
 *
 * Everything on this screen is the same data every relay is already carrying.
 * The difference is that this phone can open the sealed body and read where the
 * person is; a relay without the key sees an opaque 54 bytes.
 */
@Composable
fun RescueScreen(
    prefs: Prefs,
    state: RelayState,
    onKeyChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var keyInput by remember { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }
    val unlocked = prefs.rescuerKeyHex.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.rescue_title), onBack)

        if (!unlocked) {
            // -------------------------------------------------------- locked
            Text(
                stringResource(R.string.rescue_locked_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            SetuCard {
                Text(
                    stringResource(R.string.rescue_key_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it; rejected = false },
                    label = { Text(stringResource(R.string.rescue_key_label)) },
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (rejected) {
                    Text(
                        stringResource(R.string.rescue_key_bad),
                        color = Setu.colors.sosText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            BigButton(
                label = stringResource(R.string.rescue_unlock),
                iconRes = R.drawable.ic_status_delivered,
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
            ) {
                val key = RescuerKey.parseOrNull(keyInput)
                if (key == null) {
                    rejected = true
                } else {
                    prefs.rescuerKeyHex = keyInput.trim().replace(" ", "").replace("\n", "")
                    onKeyChanged()
                }
            }
            return@Column
        }

        // ------------------------------------------------------------ unlocked
        val key = RescuerKey.parseOrNull(prefs.rescuerKeyHex)
        if (key == null) {
            Text(stringResource(R.string.rescue_key_bad), color = Setu.colors.sosText)
            BigButton(
                label = stringResource(R.string.rescue_forget),
                iconRes = R.drawable.ic_status_expired,
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurface,
            ) { prefs.rescuerKeyHex = ""; onKeyChanged() }
            return@Column
        }

        // Every SOS this phone has heard, its own included. RelayEngine only
        // builds this list in rescue mode, so on an ordinary phone it is empty.
        val calls = state.sosCalls.sortedByDescending { it.receivedAt }

        SetuCard {
            Text(
                stringResource(R.string.rescue_active),
                color = Setu.colors.deliveredText,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.rescue_active_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            stringResource(R.string.rescue_calls, calls.size),
            style = MaterialTheme.typography.titleMedium,
        )

        if (calls.isEmpty()) {
            Text(
                stringResource(R.string.rescue_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        for (m in calls) {
            SetuCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(R.string.rescue_sos_from, m.originKeyIdHex.take(8)),
                        style = MaterialTheme.typography.titleMedium,
                        color = Setu.colors.sosText,
                    )
                    Text(
                        stringResource(R.string.status_hops, m.hopCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // The claimed time is the sender's device clock, which docs/04
                // says is untrusted. Both are shown, labelled, and never merged.
                LabelValue(
                    stringResource(R.string.rescue_claimed_at),
                    formatTime(m.createdAt * 1000L),
                )
                LabelValue(
                    stringResource(R.string.rescue_heard_at),
                    formatTime(m.receivedAt),
                )

                val opened = SealedBox.open(key, m.sealedBody)
                when {
                    opened == null -> Text(
                        stringResource(R.string.rescue_not_for_us),
                        color = Setu.colors.muted,
                    )

                    opened.all { it.toInt() == 0 } -> Text(
                        stringResource(R.string.rescue_no_fix),
                        color = Setu.colors.warnText,
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    else -> {
                        val (lat, lon) = Bodies.readSosPlaintext(opened)
                        val coords = String.format(Locale.US, "%.5f, %.5f", lat, lon)
                        Text(
                            coords,
                            color = Setu.colors.deliveredText,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(4.dp))
                        BigButton(
                            label = stringResource(R.string.rescue_open_map),
                            iconRes = R.drawable.ic_relay,
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            // A geo: URI is handled by any installed map app and
                            // works offline with a cached map. No map library,
                            // no network call, nothing added to the APK.
                            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(SOS)")
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        BigButton(
            label = stringResource(R.string.rescue_forget),
            iconRes = R.drawable.ic_status_expired,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurface,
        ) { prefs.rescuerKeyHex = ""; onKeyChanged() }
    }
}

private fun formatTime(ms: Long): String =
    if (ms <= 0L) "—" else SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(ms))
