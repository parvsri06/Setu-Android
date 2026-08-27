package `in`.setu.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * The pad from the artboards: 260 dp tall, 16 dp corners, measured at 2.0 px/dp
 * on the 391 dp canvas of `Setu-docs/design/07a-sos-idle.png`.
 *
 * Far larger than a button needs to be, and that is the point — a wet hand,
 * in the dark, without aiming.
 */
private val PadHeight = 260.dp
private val PadRadius = 16.dp

/**
 * SOS. A hold, not a tap — a pocket press must not fire it — and not a
 * multi-step dialog, which costs seconds the user may not have.
 */
@Composable
fun SosScreen(
    engine: RelayEngine,
    state: RelayState,
    onAddDetail: (String) -> Unit,
    onBack: () -> Unit,
) {
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

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader(stringResource(R.string.sos_title), onBack)

        // The artboards leave a deliberate run of empty space above the pad, so
        // the control lands under the thumb rather than under the title.
        Spacer(Modifier.height(8.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(PadHeight)
                .clip(RoundedCornerShape(PadRadius))
                .background(Setu.colors.sos)
                // Keyed on Unit, not on `sending`.
                //
                // BUG THIS FIXES: firing sets `sending = true`, which changed the
                // pointerInput key mid-press and tore down the gesture detector.
                // tryAwaitRelease() was cancelled with it, so the two lines after
                // it never ran and holdProgress stayed pinned at 1.0 — the pad
                // kept its progress fill and read "Keep holding" forever after a
                // send. fire() already guards against re-entry on its own, so the
                // key bought nothing.
                .pointerInput(Unit) {
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
                            // finally, so the pad resets even if this handler is
                            // cancelled rather than released normally.
                            try {
                                tryAwaitRelease()
                            } finally {
                                job.cancel()
                                holdProgress = 0f
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // Progress fills the pad itself rather than a separate bar beneath
            // it. Same value, but the feedback is under the finger that is
            // producing it, which is where a user holding a button is looking.
            if (holdProgress > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(holdProgress)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                        .background(Color.White.copy(alpha = 0.28f)),
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painterResource(R.drawable.ic_sos),
                    contentDescription = null,
                    tint = Setu.colors.onSos,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        sending -> stringResource(R.string.sos_sending)
                        holdProgress > 0f -> stringResource(R.string.sos_holding)
                        else -> stringResource(R.string.sos_hold)
                    },
                    color = Setu.colors.onSos,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                    ),
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (lastResult != null) {
            SetuCard {
                // "Sent" here means it left this phone, not that it arrived. It uses
                // the ordinary accent, not the delivered green.
                Text(
                    stringResource(R.string.sos_sent),
                    style = MaterialTheme.typography.titleLarge
                        .copy(fontFamily = FontFamily.SansSerif),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(
                        if (hadFix == true) R.string.sos_location_sealed else R.string.sos_no_fix,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Outlined, not filled: adding detail is optional and must not
            // compete with the pad above it, which is the only control on this
            // screen that has to be found in a hurry.
            OutlinedAction(
                label = stringResource(R.string.sosdetail_add),
                onClick = { onAddDetail(lastResult!!) },
            )
        }

        SectionLabel(stringResource(R.string.sos_my_messages))

        val mine = state.myMessages
        if (mine.isEmpty()) {
            Text(
                stringResource(R.string.sos_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            for (m in mine) MessageStatusCard(m.type, m.status, m.carriers, m.hopCount, m.idHex)
        }
    }
}

/** A secondary action: primary edge and label, page-coloured inside. */
@Composable
private fun OutlinedAction(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Setu.Primary)
            .clip(shape)
            .border(2.dp, MaterialTheme.colorScheme.primary, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
            ),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The status ladder. CARRIED is amber and carries an explicit warning line: the
 * most damaging thing this app could do is imply help is coming when it isn't.
 *
 * Laid out as the artboards draw it: what the message is on the left, the state
 * as a pill on the right. The pill is filled only for CARRIED and DELIVERED —
 * the two states the mesh actually confirmed — and outlined for HELD and
 * EXPIRED, which are local facts. That is the same rule `StatusPill` documents.
 */
@Composable
fun MessageStatusCard(type: Int, status: Int, carriers: Int, hopCount: Int, idHex: String) {
    val confirmed = status == `in`.setu.relay.wire.Status.CARRIED ||
        status == `in`.setu.relay.wire.Status.DELIVERED

    SetuCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(statusIcon(status)),
                contentDescription = null,
                tint = statusColor(status),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    MsgType.name(type),
                    style = MaterialTheme.typography.titleLarge
                        .copy(fontFamily = FontFamily.SansSerif),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.status_hops, hopCount) + "  ·  " + idHex.take(8),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                        .copy(fontFamily = FontFamily.Monospace),
                )
            }
            Spacer(Modifier.size(12.dp))
            StatusPill(
                text = statusWords(status, carriers),
                fill = if (confirmed) statusColor(status) else null,
                textColour = if (confirmed) null else statusColor(status),
            )
        }

        BridgeArc(statusProgress(status), fill = statusColor(status))

        if (status == `in`.setu.relay.wire.Status.CARRIED) {
            Text(
                stringResource(R.string.status_carried_warning),
                color = Setu.colors.carriedText,
                style = MaterialTheme.typography.bodyLarge
                    .copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}
