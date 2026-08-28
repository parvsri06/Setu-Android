package `in`.setu.relay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.relay.RelayEngine
import `in`.setu.relay.wire.Codec
import `in`.setu.relay.wire.SosDetail

/**
 * Adds detail to an SOS **after** it has already gone out.
 *
 * The order matters and the screen is built around it. The call left the phone
 * the moment the button was held; nothing here is required, nothing here delays
 * anything, and a person who closes the app right now has still called for help.
 * What this adds is the difference between a rescuer knowing where to go and
 * knowing what to bring.
 *
 * Detail rides the bulk plane, so it moves slower than the beacon and needs a
 * connection to a peer. The screen says so rather than implying the words travel
 * with the position.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SosDetailScreen(
    engine: RelayEngine,
    msgIdHex: String,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var people by remember { mutableStateOf("") }
    var needs by remember { mutableStateOf(0) }
    var category by remember { mutableStateOf(SosDetail.Category.GENERAL) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.sosdetail_title), onBack)

        SetuCard {
            Text(
                stringResource(R.string.sosdetail_already_sent),
                style = MaterialTheme.typography.bodyLarge,
                color = Setu.colors.deliveredText,
            )
            Text(
                stringResource(R.string.sosdetail_slower),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(stringResource(R.string.sosdetail_what), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in SosDetail.Need.ALL) {
                Chip(
                    label = stringResource(needLabel(n)),
                    selected = needs and n != 0,
                ) { needs = needs xor n }
            }
        }

        Text(stringResource(R.string.sosdetail_kind), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in SosDetail.Category.ALL) {
                Chip(
                    label = stringResource(categoryLabel(c)),
                    selected = category == c,
                ) { category = c }
            }
        }

        OutlinedTextField(
            value = people,
            onValueChange = { people = it.filter { ch -> ch.isDigit() }.take(3) },
            label = { Text(stringResource(R.string.sosdetail_people)) },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.sosdetail_text)) },
            modifier = Modifier.fillMaxWidth(),
        )

        BigButton(
            label = stringResource(R.string.sosdetail_send),
            iconRes = R.drawable.ic_status_carried,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
        ) {
            engine.attachSosDetail(
                msgId = Codec.unhex(msgIdHex),
                category = category,
                peopleCount = people.toIntOrNull() ?: SosDetail.UNSTATED,
                needs = needs,
                text = text.trim(),
            )
            onDone()
        }

        Text(
            stringResource(R.string.sosdetail_sealed_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

private fun needLabel(n: Int): Int = when (n) {
    SosDetail.Need.INJURED -> R.string.need_injured
    SosDetail.Need.TRAPPED -> R.string.need_trapped
    SosDetail.Need.WATER_RISING -> R.string.need_water
    SosDetail.Need.MEDICINE -> R.string.need_medicine
    else -> R.string.need_cannot_move
}

private fun categoryLabel(c: Int): Int = when (c) {
    SosDetail.Category.MEDICAL -> R.string.soscat_medical
    SosDetail.Category.TRAPPED -> R.string.soscat_trapped
    SosDetail.Category.FOOD_WATER -> R.string.soscat_food
    SosDetail.Category.SHELTER -> R.string.soscat_shelter
    else -> R.string.soscat_general
}
