package `in`.setu.relay.ui.survey

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.crypto.AadhaarId
import `in`.setu.relay.store.Survey
import `in`.setu.relay.store.SurveyStatus
import `in`.setu.relay.ui.BigButton
import `in`.setu.relay.ui.ScreenHeader
import `in`.setu.relay.ui.Setu
import `in`.setu.relay.ui.SetuCard

/**
 * What this phone is holding. Without it a saved survey is invisible, and the
 * upload queue in phase 3 needs somewhere to show progress anyway.
 *
 * Status wording here follows the same rule as the message ladder: nothing says
 * "sent" until it actually has been.
 */
@Composable
fun SurveyListScreen(
    surveys: List<Survey>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.survey_list_title), onBack)

        BigButton(
            label = stringResource(R.string.survey_new),
            iconRes = R.drawable.ic_survey,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            onClick = onNew,
        )

        if (surveys.isEmpty()) {
            Text(
                stringResource(R.string.survey_list_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        for (s in surveys) {
            SetuCard(Modifier.clickable { onOpen(s.surveyId) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        s.name.ifBlank { stringResource(R.string.survey_unnamed) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(surveyStatusLabel(s.status)),
                        style = MaterialTheme.typography.bodyMedium,
                        // A draft is not a warning, but it is not done either.
                        color = if (s.status == SurveyStatus.DRAFT) {
                            Setu.colors.warnText
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                val place = listOf(s.village, s.district).filter { it.isNotBlank() }.joinToString(", ")
                if (place.isNotBlank()) {
                    Text(place, style = MaterialTheme.typography.bodyMedium)
                }
                if (s.aadhaarLast4.isNotBlank()) {
                    Text(
                        AadhaarId.mask(s.aadhaarLast4),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val people = s.people.count { it.name.isNotBlank() }
                if (people > 0) {
                    Text(
                        stringResource(R.string.survey_people_count, people),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (surveys.isNotEmpty()) {
            Text(
                stringResource(R.string.survey_list_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
