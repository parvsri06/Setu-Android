package `in`.setu.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.relay.Locator
import `in`.setu.relay.relay.Messages
import `in`.setu.relay.relay.RelayEngine
import `in`.setu.relay.relay.RelayState
import `in`.setu.relay.relay.TimeSource
import `in`.setu.relay.wire.MsgType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val HOLD_MS = 2_000L

/**
 * SOS. A hold, not a tap — a pocket press must not fire it — and not a
 * multi-step dialog, which costs seconds the user may not have.
 */
@Composable
fun SosScreen(engine: RelayEngine, state: RelayState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locator = remember { Locator(context) }

    var holdProgress by remember { mutableFloatStateOf(0f) }
    var sending by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<String?>(null) }
    var hadFix by remember { mutableStateOf<Boolean?>(null) }

    fun fire() {
        if (sending) return
        sending = true
        locator.fixOnce { location ->
            hadFix = location != null
            val envelope = if (location != null) {
                Messages.sos(engine.identity, location.latitude, location.longitude, TimeSource.wallSeconds())
            } else {
                Messages.sosWithoutFix(engine.identity, TimeSource.wallSeconds())
            }
            engine.submitLocal(envelope)
            sending = false
            lastResult = envelope.msgId.joinToString("") { "%02x".format(it) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.sos_title), onBack)

        Box(
            Modifier
                .fillMaxWidth()
                .height(Setu.SosTouch * 2)
                .clip(RoundedCornerShape(20.dp))
                .background(Setu.colors.sos)
                .pointerInput(sending) {
                    detectTapGestures(
                        onPress = {
                            val job = scope.launch {
                                val start = System.currentTimeMillis()
                                while (isActive) {
                                    val elapsed = System.currentTimeMillis() - start
                                    holdProgress = (elapsed.toFloat() / HOLD_MS).coerceAtMost(1f)
                                    if (elapsed >= HOLD_MS) {
                                        fire()
                                        break
                                    }
                                    delay(50)
                                }
                            }
                            tryAwaitRelease()
                            job.cancel()
                            holdProgress = 0f
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painterResource(R.drawable.ic_sos),
                    contentDescription = null,
                    tint = Setu.colors.onSos,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        sending -> stringResource(R.string.sos_sending)
                        holdProgress > 0f -> stringResource(R.string.sos_holding)
                        else -> stringResource(R.string.sos_hold)
                    },
                    color = Setu.colors.onSos,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (holdProgress > 0f) {
            LinearProgressIndicator(
                progress = { holdProgress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = Setu.colors.sos,
            )
        }

        if (lastResult != null) {
            SetuCard {
                // "Sent" here means it left this phone, not that it arrived. It uses
                // the ordinary accent, not the delivered green.
                Text(
                    stringResource(R.string.sos_sent),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(
                        if (hadFix == true) R.string.sos_location_sealed else R.string.sos_no_fix,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(stringResource(R.string.sos_my_messages), style = MaterialTheme.typography.titleMedium)
        val mine = state.myMessages
        if (mine.isEmpty()) {
            Text(
                stringResource(R.string.sos_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            for (m in mine) MessageStatusCard(m.type, m.status, m.carriers, m.hopCount, m.idHex)
        }
    }
}

/**
 * The status ladder. CARRIED is amber and carries an explicit warning line: the
 * most damaging thing this app could do is imply help is coming when it isn't.
 */
@Composable
fun MessageStatusCard(type: Int, status: Int, carriers: Int, hopCount: Int, idHex: String) {
    SetuCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(statusIcon(status)),
                contentDescription = null,
                tint = statusColor(status),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(MsgType.name(type), style = MaterialTheme.typography.titleMedium)
                Text(
                    statusWords(status, carriers),
                    color = statusColor(status),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        BridgeArc(statusProgress(status), fill = statusColor(status))
        if (status == `in`.setu.relay.wire.Status.CARRIED) {
            Text(
                stringResource(R.string.status_carried_warning),
                color = Setu.colors.carriedText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            stringResource(R.string.status_hops, hopCount) + "  ·  " + idHex.take(8),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
