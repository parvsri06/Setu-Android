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
    received: List<`in`.setu.relay.wire.SurveyRecord.Decoded>,
    onOpen: (String) -> Unit,
    onOpenReceived: (String) -> Unit,
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

        Text(
            stringResource(R.string.survey_local_count, surveys.size) + "   ·   " +
                stringResource(R.string.survey_received_count, received.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        // Surveys that arrived over the mesh from other phones. They are read
        // only: this device did not collect them and must not rewrite someone
        // else's record, which would also break the signature story.
        Text(
            stringResource(R.string.survey_received_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (received.isEmpty()) {
            Text(
                stringResource(R.string.survey_received_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (r in received) {
            SetuCard(Modifier.clickable { onOpenReceived(r.surveyId) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        r.name.ifBlank { stringResource(R.string.survey_unnamed) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.survey_received_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Record v3 relays position rather than an address block, so
                // "where did this come from" is coordinates now.
                if (r.hasFix) {
                    Text(
                        String.format(java.util.Locale.US, "%.4f, %.4f", r.lat, r.lon),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (r.aadhaarLast4.isNotBlank()) {
                    Text(
                        AadhaarId.mask(r.aadhaarLast4),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (r.capturedAt > 0L) {
                    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                    Text(
                        java.text.SimpleDateFormat("dd MMM HH:mm", locale)
                            .format(java.util.Date(r.capturedAt)),
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
