package `in`.setu.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.relay.Messages
import `in`.setu.relay.relay.Prefs
import `in`.setu.relay.relay.RelayEngine
import `in`.setu.relay.relay.RelayState
import `in`.setu.relay.relay.TimeSource
import `in`.setu.relay.wire.Bodies
import `in`.setu.relay.wire.MsgType

/**
 * Check-in. The contact identifier never leaves the phone in clear: it is
 * salted, hashed and truncated to 5 bytes inside the sealed body, so a relay
 * learns that someone checked in, not who they told.
 */
@Composable
fun CheckInScreen(engine: RelayEngine, state: RelayState, prefs: Prefs, onBack: () -> Unit) {
    var contact by remember { mutableStateOf(prefs.lastContact) }
    var status by remember { mutableIntStateOf(Bodies.STATUS_SAFE) }
    var sent by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.checkin_title), onBack)

        OutlinedTextField(
            value = contact,
            onValueChange = { contact = it; sent = false },
            label = { Text(stringResource(R.string.checkin_contact)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(stringResource(R.string.checkin_status_safe), status == Bodies.STATUS_SAFE, Setu.Green, Modifier.weight(1f)) {
                status = Bodies.STATUS_SAFE
            }
            StatusChip(stringResource(R.string.checkin_status_help), status == Bodies.STATUS_NEED_HELP, Setu.Orange, Modifier.weight(1f)) {
                status = Bodies.STATUS_NEED_HELP
            }
            StatusChip(stringResource(R.string.checkin_status_moving), status == Bodies.STATUS_MOVING, Setu.Grey, Modifier.weight(1f)) {
                status = Bodies.STATUS_MOVING
            }
        }

        BigButton(
            label = stringResource(R.string.checkin_send),
            iconRes = R.drawable.ic_safe,
            container = Setu.Green,
            content = Setu.White,
            enabled = contact.isNotBlank(),
        ) {
            prefs.lastContact = contact
            engine.submitLocal(
                Messages.checkIn(engine.identity, contact, status, TimeSource.wallSeconds()),
            )
            sent = true
        }

        if (sent) {
            Text(stringResource(R.string.checkin_sent), color = Setu.Green, style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(4.dp))
        val mine = state.myMessages.filter { it.type == MsgType.CHECK_IN }
        for (m in mine) MessageStatusCard(m.type, m.status, m.carriers, m.hopCount, m.idHex)
    }
}

@Composable
private fun StatusChip(
    label: String,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(Setu.Touch)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Setu.White else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
