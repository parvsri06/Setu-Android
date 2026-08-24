package `in`.setu.relay.ui.survey

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import `in`.setu.relay.R
import `in`.setu.relay.ui.Setu

/**
 * Form parts for the survey wizard.
 *
 * Everything here obeys the same rules as the rest of the app: a 64 dp minimum
 * target because the user has wet hands, a required marker that is a word and a
 * symbol rather than a colour, and no state signalled by colour alone.
 */

/** `Step 2 of 6` plus a bar. Tells a stressed user how much is left. */
@Composable
fun StepHeader(step: Int, total: Int, title: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.survey_step_of, step, total),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { step.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
    }
}

@Composable
fun FieldLabel(label: String, required: Boolean) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (required) {
            // A star and, on the review screen, the word "incomplete". Never the
            // colour on its own.
            Text(" *", style = MaterialTheme.typography.bodyMedium, color = Setu.colors.sosText)
        }
    }
}

@Composable
fun TextField(
    label: String,
    value: String,
    required: Boolean = false,
    hint: String? = null,
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    maxChars: Int = 120,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(label, required)
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= maxChars) onChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Setu.Touch),
            singleLine = singleLine,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            shape = RoundedCornerShape(12.dp),
        )
        when {
            error != null -> Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = Setu.colors.sosText,
            )

            hint != null -> Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A pill. Selection is carried by the fill *and* by a tick, never colour alone.
 *
 * The tick sits in a slot that is always present, so selecting a chip does not
 * change its width. An earlier version prefixed "✓ " to the label, which made
 * the chip grow, reflowed the whole `FlowRow`, and moved the neighbouring chips
 * out from under the user's finger mid-tap — precisely the wrong behaviour for
 * someone tapping quickly with wet hands.
 */
@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Text(
                if (selected) "✓" else "",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            label,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipGroup(
    label: String,
    required: Boolean,
    options: List<Pair<Int, String>>,
    isSelected: (Int) -> Boolean,
    onToggle: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label, required)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((value, text) in options) {
                Chip(text, isSelected(value)) { onToggle(value) }
            }
        }
    }
}
