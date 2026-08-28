package `in`.setu.relay.ui.survey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.crypto.AadhaarId
import `in`.setu.relay.store.DamageArea
import `in`.setu.relay.store.DisasterType
import `in`.setu.relay.store.Gender
import `in`.setu.relay.store.Person
import `in`.setu.relay.store.PersonStatus
import `in`.setu.relay.store.Survey
import `in`.setu.relay.ui.BigButton
import `in`.setu.relay.ui.ScreenHeader
import `in`.setu.relay.ui.Setu
import `in`.setu.relay.ui.SetuCard
import `in`.setu.relay.wire.SurveyRecord

/**
 * The whole record as a table, every field shown whether or not it was answered.
 *
 * Unanswered fields read **Pending** rather than being hidden. A relief officer
 * looking at fifty surveys needs to see what is missing as clearly as what is
 * present — a blank row is information, and quietly omitting it turns "nobody
 * asked" and "answered nothing" into the same thing on screen.
 */
@Composable
fun SurveyDetailScreen(
    survey: Survey,
    editable: Boolean,
    onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.survey_detail_title), onBack)

        if (editable) {
            BigButton(
                label = stringResource(R.string.survey_edit),
                iconRes = R.drawable.ic_survey,
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = onEdit,
            )
        }

        TableSection(R.string.survey_step_personal) {
            Cell(R.string.survey_name, survey.name)
            Cell(R.string.survey_father, survey.fatherName)
            Cell(R.string.survey_mobile, survey.mobile)
            Cell(
                R.string.survey_aadhaar,
                if (survey.aadhaarLast4.isBlank()) "" else AadhaarId.mask(survey.aadhaarLast4),
            )
            Cell(R.string.survey_family_id, survey.familyId)
            Cell(
                R.string.survey_proxy,
                stringResource(if (survey.isProxy) R.string.yes else R.string.no),
            )
        }

        TableSection(R.string.survey_step_location) {
            Cell(
                R.string.survey_gps,
                if (survey.lat.isNaN() || survey.lon.isNaN()) {
                    ""
                } else {
                    String.format(java.util.Locale.US, "%.5f, %.5f", survey.lat, survey.lon)
                },
            )
            // The locale is read through the configuration rather than
            // Locale.getDefault(), so switching language actually reformats the
            // date instead of leaving a stale one until the next process start.
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            Cell(
                R.string.survey_captured_at,
                if (survey.capturedAt <= 0L) {
                    ""
                } else {
                    java.text.SimpleDateFormat("dd MMM yyyy HH:mm", locale)
                        .format(java.util.Date(survey.capturedAt))
                },
            )
            Cell(R.string.survey_village, survey.village)
            Cell(R.string.survey_district, survey.district)
            Cell(R.string.survey_post_office, survey.postOffice)
            Cell(R.string.survey_police_station, survey.policeStation)
            Cell(R.string.survey_pin, survey.pin)
        }

        TableSection(R.string.survey_step_damage) {
            Cell(R.string.survey_disaster_type, disasterName(survey.disasterType, survey.disasterOther))
            Cell(R.string.survey_damage_date, survey.damageDate)
            Cell(R.string.survey_damage_area, damageAreaNames(survey.damageAreas, survey.damageOther))
            Cell(R.string.survey_damage_desc, survey.damageDescription)
        }

        TableSection(R.string.survey_step_casualties) {
            if (survey.people.isEmpty()) {
                Cell(R.string.survey_people, "")
            } else {
                for ((i, p) in survey.people.withIndex()) PersonRows(i + 1, p)
            }
        }

        TableSection(R.string.survey_step_camp) {
            Cell(
                R.string.survey_in_camp,
                stringResource(if (survey.inCamp) R.string.yes else R.string.no),
            )
            Cell(R.string.survey_camp_name, survey.campName)
            Cell(R.string.survey_camp_location, survey.campLocation)
            Cell(R.string.survey_needs, survey.needs)
        }

        Text(
            stringResource(R.string.survey_detail_id, survey.surveyId.take(8)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The same table for a survey that arrived from another phone. */
@Composable
fun ReceivedSurveyDetailScreen(record: SurveyRecord.Decoded, onBack: () -> Unit) {
    SurveyDetailScreen(
        survey = record.toSurvey(),
        editable = false,
        onEdit = {},
        onBack = onBack,
    )
}

// ------------------------------------------------------------------- pieces

@Composable
private fun TableSection(titleRes: Int, rows: @Composable () -> Unit) {
    Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
    SetuCard { rows() }
}

@Composable
private fun Cell(labelRes: Int, value: String) {
    val blank = value.isBlank()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp),
        )
        Text(
            if (blank) stringResource(R.string.survey_pending) else value,
            style = MaterialTheme.typography.bodyMedium,
            // Amber, the same colour every other "not finished" state uses.
            color = if (blank) Setu.colors.warnText else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PersonRows(index: Int, p: Person) {
    Text(
        stringResource(R.string.survey_person_n, index),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 6.dp),
    )
    Cell(R.string.survey_person_name, p.name)
    Cell(R.string.survey_person_age, if (p.age < 0) "" else p.age.toString())
    Cell(R.string.survey_person_gender, genderName(p.gender))
    Cell(R.string.survey_person_status, personStatusName(p.status))
    Cell(R.string.survey_person_location, p.location)
}

// ------------------------------------------------------------------ naming

@Composable
private fun genderName(g: Int): String = when (g) {
    Gender.MALE -> stringResource(R.string.gender_male)
    Gender.FEMALE -> stringResource(R.string.gender_female)
    Gender.OTHER -> stringResource(R.string.gender_other)
    else -> ""
}

@Composable
private fun personStatusName(s: Int): String = when (s) {
    PersonStatus.ALIVE -> stringResource(R.string.person_alive)
    PersonStatus.MISSING -> stringResource(R.string.person_missing)
    PersonStatus.NOT_ALIVE -> stringResource(R.string.person_not_alive)
    else -> ""
}

@Composable
private fun disasterName(type: Int, other: String): String = when (type) {
    DisasterType.FLOOD -> stringResource(R.string.disaster_flood)
    DisasterType.EARTHQUAKE -> stringResource(R.string.disaster_earthquake)
    DisasterType.CYCLONE -> stringResource(R.string.disaster_cyclone)
    DisasterType.LANDSLIDE -> stringResource(R.string.disaster_landslide)
    DisasterType.FIRE -> stringResource(R.string.disaster_fire)
    DisasterType.OTHER -> other
    else -> ""
}

@Composable
private fun damageAreaNames(mask: Int, other: String): String {
    val names = buildList {
        if (mask and DamageArea.HOUSE != 0) add(stringResource(R.string.damage_house))
        if (mask and DamageArea.SHOP != 0) add(stringResource(R.string.damage_shop))
        if (mask and DamageArea.AGRICULTURAL_LAND != 0) add(stringResource(R.string.damage_land))
        if (mask and DamageArea.ROAD != 0) add(stringResource(R.string.damage_road))
        if (mask and DamageArea.VEHICLE != 0) add(stringResource(R.string.damage_vehicle))
        if (mask and DamageArea.LIVESTOCK != 0) add(stringResource(R.string.damage_livestock))
        if (mask and DamageArea.OTHER != 0 && other.isNotBlank()) add(other)
    }
    return names.joinToString(", ")
}

/**
 * A received record wearing the same shape as a local survey, so one table
 * renders both. Damage and camp fields stay empty because they never relay —
 * they are internet-only by design, and showing them as Pending is honest.
 */
fun SurveyRecord.Decoded.toSurvey(): Survey = Survey(
    surveyId = surveyId,
    createdAt = capturedAt,
    updatedAt = capturedAt,
    status = `in`.setu.relay.store.SurveyStatus.RELAYED,
    isProxy = isProxy,
    proxyConsent = proxyConsent,
    name = name,
    fatherName = fatherName,
    mobile = mobile,
    aadhaarLast4 = aadhaarLast4,
    familyId = familyId,
    lat = lat,
    lon = lon,
    capturedAt = capturedAt,
    // The address block, damage detail and the affected-person list are not
    // relayed in record v3 — they stay on the phone that collected them. Showing
    // them as Pending is honest: this phone genuinely does not have them.
)
