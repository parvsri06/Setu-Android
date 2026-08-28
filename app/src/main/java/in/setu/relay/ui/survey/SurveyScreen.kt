package `in`.setu.relay.ui.survey

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import `in`.setu.relay.R
import `in`.setu.relay.crypto.AadhaarId
import `in`.setu.relay.store.DamageArea
import `in`.setu.relay.store.DisasterType
import `in`.setu.relay.store.Gender
import `in`.setu.relay.store.PersonStatus
import `in`.setu.relay.ui.BigButton
import `in`.setu.relay.ui.ScreenHeader
import `in`.setu.relay.ui.Setu
import `in`.setu.relay.ui.SetuCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The survey wizard from `Setu-docs/Basic UI workflow/`.
 *
 * One [Screen][`in`.setu.relay.ui.Screen] entry with a [SurveyStep] sub-state,
 * rather than eight enum values and a navigation graph — docs/08 cuts the
 * Navigation component and a `when` is enough.
 *
 * ### Autosave
 *
 * Every edit is written back as a DRAFT after a short pause. A surveyor working
 * through a flooded village is on a phone that may die, be dropped in water, or
 * be taken from them; losing forty minutes of work to any of those is a product
 * failure, not an edge case. It also means the wizard survives a configuration
 * change: only `surveyId` and the step are held in saveable state, and the
 * content is reloaded from the database.
 */
@Composable
fun SurveyScreen(
    host: SurveyHost,
    startSurveyId: String? = null,
    onExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var step by rememberSaveable(
        saver = Saver(save = { it.value.name }, restore = { mutableStateOf(SurveyStep.valueOf(it)) }),
    ) { mutableStateOf(if (startSurveyId == null) SurveyStep.WELCOME else SurveyStep.PERSONAL) }

    var draft by remember { mutableStateOf(SurveyDraft()) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var savedId by rememberSaveable { mutableStateOf(startSurveyId ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // Reload after a configuration change, or when opening an existing survey.
    LaunchedEffect(savedId) {
        if (!loaded && savedId.isNotEmpty()) {
            withContext(Dispatchers.Default) { host.load(savedId) }?.let {
                draft = SurveyDraft.from(it)
            }
            loaded = true
        }
    }

    // Debounced autosave. Keyed on the draft, so each edit restarts the timer and
    // a burst of typing costs one write rather than one per keystroke.
    LaunchedEffect(draft) {
        // No "is anything filled in yet" guard. The row is written from the
        // first pause in typing even when every field is still empty, so a
        // survey exists in the table from the moment it is started and shows as
        // Pending rather than not existing at all. A surveyor interrupted on the
        // first question still leaves a trace that someone was asked.
        delay(700)
        withContext(Dispatchers.Default) { host.saveDraft(draft) }
        savedId = draft.surveyId
    }

    val dupMessage = stringResource(R.string.survey_dup_aadhaar)

    fun commit() {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            val ok = withContext(Dispatchers.Default) { host.saveComplete(draft) }
            saving = false
            if (ok) {
                savedId = draft.surveyId
                step = SurveyStep.SAVED
            } else {
                error = dupMessage
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (step) {
            SurveyStep.WELCOME -> WelcomeStep(onStart = { step = SurveyStep.PERSONAL }, onBack = onExit)

            SurveyStep.SAVED -> SavedStep(
                surveyId = savedId,
                onNew = {
                    draft = SurveyDraft()
                    savedId = ""
                    loaded = true
                    step = SurveyStep.PERSONAL
                },
                onDone = onExit,
            )

            else -> {
                ScreenHeader(stringResource(R.string.survey_title)) {
                    step = previousStep(step)
                }
                StepHeader(
                    step = step.formIndex(),
                    total = SurveyStep.FORM_STEPS,
                    title = stringResource(stepTitle(step)),
                )

                when (step) {
                    SurveyStep.PERSONAL -> PersonalStep(draft) { draft = it }
                    SurveyStep.LOCATION -> LocationStep(draft) { draft = it }
                    SurveyStep.DAMAGE -> DamageStep(draft) { draft = it }
                    SurveyStep.CASUALTIES -> CasualtiesStep(draft) { draft = it }
                    SurveyStep.CAMP -> CampStep(draft) { draft = it }
                    SurveyStep.REVIEW -> ReviewStep(draft) { step = it }
                    else -> Unit
                }

                error?.let {
                    Text(it, color = Setu.colors.sosText, style = MaterialTheme.typography.bodyLarge)
                }

                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton(
                        label = stringResource(R.string.survey_save_draft),
                        iconRes = R.drawable.ic_carry,
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    ) {
                        scope.launch {
                            withContext(Dispatchers.Default) { host.saveDraft(draft) }
                            savedId = draft.surveyId
                            onExit()
                        }
                    }
                    if (step == SurveyStep.REVIEW) {
                        BigButton(
                            label = stringResource(R.string.survey_save),
                            iconRes = R.drawable.ic_status_delivered,
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.weight(1f),
                            enabled = !saving,
                            onClick = { commit() },
                        )
                    } else {
                        BigButton(
                            label = stringResource(R.string.action_next),
                            iconRes = R.drawable.ic_status_carried,
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.weight(1f),
                        ) { step = nextStep(step) }
                    }
                }
                Text(
                    stringResource(R.string.survey_saves_local),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What the wizard needs from the outside. An interface so the steps can be
 * exercised without a database, and so `ui` keeps not reaching into `store`
 * directly for anything but types.
 */
interface SurveyHost {
    suspend fun load(surveyId: String): `in`.setu.relay.store.Survey?
    fun saveDraft(draft: SurveyDraft)

    /** False when the Aadhaar duplicates one already on this phone. */
    fun saveComplete(draft: SurveyDraft): Boolean
}

// ------------------------------------------------------------------- steps

@Composable
private fun WelcomeStep(onStart: () -> Unit, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(stringResource(R.string.survey_title), onBack)
        Text(
            stringResource(R.string.survey_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        SetuCard {
            // Not green: green means a confirmed delivery receipt and nothing
            // else, and "works offline" is a capability, not a delivery.
            Text(
                stringResource(R.string.survey_offline_ready),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.survey_welcome_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BigButton(
            label = stringResource(R.string.survey_start),
            iconRes = R.drawable.ic_survey,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            onClick = onStart,
        )
    }
}

@Composable
private fun PersonalStep(d: SurveyDraft, set: (SurveyDraft) -> Unit) {
    Text(stringResource(R.string.survey_personal_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    TextField(stringResource(R.string.survey_name), d.name, required = true) { set(d.copy(name = it)) }
    TextField(stringResource(R.string.survey_father), d.fatherName, required = true) { set(d.copy(fatherName = it)) }
    TextField(
        label = stringResource(R.string.survey_mobile),
        value = d.mobile,
        required = true,
        hint = stringResource(R.string.survey_mobile_hint),
        error = if (d.mobile.isNotEmpty() && !SurveyDraft.isValidMobile(d.mobile)) {
            stringResource(R.string.survey_bad_mobile)
        } else {
            null
        },
        keyboard = KeyboardType.Number,
        maxChars = 10,
    ) { v -> set(d.copy(mobile = v.filter { it.isDigit() })) }

    TextField(
        label = stringResource(R.string.survey_aadhaar),
        value = d.aadhaar,
        required = true,
        hint = stringResource(R.string.survey_aadhaar_hint),
        error = if (d.aadhaar.isNotEmpty() && !AadhaarId.isWellFormed(d.aadhaar)) {
            stringResource(R.string.survey_bad_aadhaar)
        } else {
            null
        },
        keyboard = KeyboardType.Number,
        maxChars = AadhaarId.LENGTH,
    ) { v -> set(d.copy(aadhaar = v.filter { it.isDigit() })) }

    TextField(stringResource(R.string.survey_family), d.familyId, hint = stringResource(R.string.survey_optional)) {
        set(d.copy(familyId = it))
    }

    // Proxy entry — the whole reason a surveyor can help someone with no phone.
    SetuCard {
        Chip(stringResource(R.string.survey_proxy), d.isProxy, Modifier.fillMaxWidth()) {
            set(d.copy(isProxy = !d.isProxy, proxyConsent = false))
        }
        if (d.isProxy) {
            Text(
                stringResource(R.string.survey_proxy_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Chip(stringResource(R.string.survey_proxy_consent), d.proxyConsent, Modifier.fillMaxWidth()) {
                set(d.copy(proxyConsent = !d.proxyConsent))
            }
        }
    }
}

@Composable
private fun LocationStep(d: SurveyDraft, set: (SurveyDraft) -> Unit) {
    SetuCard {
        Text(stringResource(R.string.survey_location_note), style = MaterialTheme.typography.bodyMedium)
    }
    TextField(stringResource(R.string.survey_village), d.village, required = true) { set(d.copy(village = it)) }
    TextField(stringResource(R.string.survey_district), d.district, required = true) { set(d.copy(district = it)) }
    TextField(stringResource(R.string.survey_post_office), d.postOffice, required = true) { set(d.copy(postOffice = it)) }
    TextField(stringResource(R.string.survey_police_station), d.policeStation, required = true) { set(d.copy(policeStation = it)) }
    TextField(
        label = stringResource(R.string.survey_pin),
        value = d.pin,
        required = true,
        hint = stringResource(R.string.survey_pin_hint),
        error = if (d.pin.isNotEmpty() && !SurveyDraft.isValidPin(d.pin)) {
            stringResource(R.string.survey_bad_pin)
        } else {
            null
        },
        keyboard = KeyboardType.Number,
        maxChars = 6,
    ) { v -> set(d.copy(pin = v.filter { it.isDigit() })) }
}

@Composable
private fun DamageStep(d: SurveyDraft, set: (SurveyDraft) -> Unit) {
    ChipGroup(
        label = stringResource(R.string.survey_disaster_type),
        required = true,
        options = DisasterType.ALL.map { it to stringResource(disasterLabel(it)) },
        isSelected = { it == d.disasterType },
    ) { set(d.copy(disasterType = it)) }

    if (d.disasterType == DisasterType.OTHER) {
        TextField(stringResource(R.string.survey_disaster_other), d.disasterOther, required = true) {
            set(d.copy(disasterOther = it))
        }
    }

    TextField(
        label = stringResource(R.string.survey_damage_date),
        value = d.damageDate,
        required = true,
        hint = stringResource(R.string.survey_damage_date_hint),
    ) { set(d.copy(damageDate = it)) }

    ChipGroup(
        label = stringResource(R.string.survey_damage_area),
        required = true,
        options = DamageArea.ALL.map { it to stringResource(areaLabel(it)) },
        isSelected = { d.damageAreas and it != 0 },
    ) { set(d.copy(damageAreas = d.damageAreas xor it)) }

    if (d.damageAreas and DamageArea.OTHER != 0) {
        TextField(stringResource(R.string.survey_damage_other), d.damageOther, required = true) {
            set(d.copy(damageOther = it))
        }
    }

    TextField(
        label = stringResource(R.string.survey_damage_desc),
        value = d.damageDescription,
        singleLine = false,
        maxChars = 400,
    ) { set(d.copy(damageDescription = it)) }

    // Says out loud which fields will not use the radio, so nobody assumes a
    // photo is being carried through the mesh.
    SetuCard {
        Text(
            stringResource(R.string.survey_internet_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CasualtiesStep(d: SurveyDraft, set: (SurveyDraft) -> Unit) {
    Text(
        stringResource(R.string.survey_casualties_sub),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    d.people.forEachIndexed { i, p ->
        SetuCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.survey_person_n, i + 1),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (d.people.size > 1) {
                    Text(
                        stringResource(R.string.survey_remove_person),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Setu.colors.sosText,
                        modifier = Modifier
                            .padding(4.dp)
                            .clickableRow { set(d.copy(people = d.people.filterIndexed { j, _ -> j != i })) },
                    )
                }
            }
            fun upd(f: (PersonDraft) -> PersonDraft) =
                set(d.copy(people = d.people.mapIndexed { j, q -> if (j == i) f(q) else q }))

            TextField(stringResource(R.string.survey_person_name), p.name, required = true) { v -> upd { it.copy(name = v) } }
            TextField(
                label = stringResource(R.string.survey_age),
                value = p.age,
                required = true,
                keyboard = KeyboardType.Number,
                maxChars = 3,
            ) { v -> upd { it.copy(age = v.filter { c -> c.isDigit() }) } }

            ChipGroup(
                label = stringResource(R.string.survey_gender),
                required = true,
                options = listOf(
                    Gender.MALE to stringResource(R.string.gender_male),
                    Gender.FEMALE to stringResource(R.string.gender_female),
                    Gender.OTHER to stringResource(R.string.gender_other),
                ),
                isSelected = { it == p.gender },
            ) { v -> upd { it.copy(gender = v) } }

            ChipGroup(
                label = stringResource(R.string.survey_status),
                required = true,
                options = listOf(
                    PersonStatus.ALIVE to stringResource(R.string.status_alive),
                    PersonStatus.MISSING to stringResource(R.string.status_missing),
                    PersonStatus.NOT_ALIVE to stringResource(R.string.status_not_alive),
                ),
                isSelected = { it == p.status },
            ) { v -> upd { it.copy(status = v) } }

            TextField(stringResource(R.string.survey_person_location), p.location, required = true) { v ->
                upd { it.copy(location = v) }
            }
        }
    }

    BigButton(
        label = stringResource(R.string.survey_add_person),
        iconRes = R.drawable.ic_relay,
        container = MaterialTheme.colorScheme.surfaceVariant,
        content = MaterialTheme.colorScheme.onSurface,
    ) { set(d.copy(people = d.people + PersonDraft())) }
}

@Composable
private fun CampStep(d: SurveyDraft, set: (SurveyDraft) -> Unit) {
    Chip(stringResource(R.string.survey_in_camp), d.inCamp, Modifier.fillMaxWidth()) {
        set(d.copy(inCamp = !d.inCamp))
    }
    if (d.inCamp) {
        TextField(stringResource(R.string.survey_camp_name), d.campName, required = true) { set(d.copy(campName = it)) }
        TextField(stringResource(R.string.survey_camp_location), d.campLocation) { set(d.copy(campLocation = it)) }
    }
    TextField(
        label = stringResource(R.string.survey_needs),
        value = d.needs,
        singleLine = false,
        maxChars = 300,
    ) { set(d.copy(needs = it)) }
}

@Composable
private fun ReviewStep(d: SurveyDraft, goto: (SurveyStep) -> Unit) {
    val incomplete = !d.isComplete()
    if (incomplete) {
        SetuCard {
            Text(
                stringResource(R.string.survey_incomplete),
                color = Setu.colors.warnText,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }

    ReviewSection(R.string.survey_step_personal, d.personalMissing().isEmpty(), { goto(SurveyStep.PERSONAL) }) {
        ReviewRow(stringResource(R.string.survey_name), d.name)
        ReviewRow(stringResource(R.string.survey_father), d.fatherName)
        ReviewRow(stringResource(R.string.survey_mobile), d.mobile)
        // Never more than the last four, on any screen.
        ReviewRow(
            stringResource(R.string.survey_aadhaar),
            if (AadhaarId.isWellFormed(d.aadhaar)) AadhaarId.mask(AadhaarId.last4(d.aadhaar)) else "",
        )
        ReviewRow(stringResource(R.string.survey_family), d.familyId)
        if (d.isProxy) ReviewRow(stringResource(R.string.survey_proxy), stringResource(R.string.survey_proxy_yes))
    }

    ReviewSection(R.string.survey_step_location, d.locationMissing().isEmpty(), { goto(SurveyStep.LOCATION) }) {
        ReviewRow(stringResource(R.string.survey_village), d.village)
        ReviewRow(stringResource(R.string.survey_district), d.district)
        ReviewRow(stringResource(R.string.survey_post_office), d.postOffice)
        ReviewRow(stringResource(R.string.survey_police_station), d.policeStation)
        ReviewRow(stringResource(R.string.survey_pin), d.pin)
    }

    ReviewSection(R.string.survey_step_damage, d.damageMissing().isEmpty(), { goto(SurveyStep.DAMAGE) }) {
        ReviewRow(stringResource(R.string.survey_disaster_type), stringResource(disasterLabel(d.disasterType)))
        ReviewRow(stringResource(R.string.survey_damage_date), d.damageDate)
        // map is inline so stringResource is legal inside it; joinToString is
        // not, which is why the names are resolved first.
        val areaNames = DamageArea.ALL
            .filter { d.damageAreas and it != 0 }
            .map { stringResource(areaLabel(it)) }
        ReviewRow(stringResource(R.string.survey_damage_area), areaNames.joinToString(", "))
    }

    ReviewSection(R.string.survey_step_casualties, d.casualtiesMissing().isEmpty(), { goto(SurveyStep.CASUALTIES) }) {
        val filled = d.people.filterNot { it.isBlank() }
        if (filled.isEmpty()) {
            Text(stringResource(R.string.survey_no_people), style = MaterialTheme.typography.bodyMedium)
        }
        for (p in filled) {
            ReviewRow(p.name, stringResource(personStatusLabel(p.status)))
        }
    }

    ReviewSection(R.string.survey_step_camp, d.campMissing().isEmpty(), { goto(SurveyStep.CAMP) }) {
        ReviewRow(
            stringResource(R.string.survey_in_camp),
            stringResource(if (d.inCamp) R.string.survey_yes else R.string.survey_no),
        )
        if (d.inCamp) ReviewRow(stringResource(R.string.survey_camp_name), d.campName)
    }
}

@Composable
private fun ReviewSection(
    titleRes: Int,
    complete: Boolean,
    onEdit: () -> Unit,
    body: @Composable () -> Unit,
) {
    SetuCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row {
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
                if (!complete) {
                    Text(
                        "  " + stringResource(R.string.survey_incomplete_tag),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Setu.colors.warnText,
                    )
                }
            }
            Text(
                stringResource(R.string.survey_edit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(4.dp).clickableRow(onEdit),
            )
        }
        body()
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SavedStep(surveyId: String, onNew: () -> Unit, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.survey_saved_title), style = MaterialTheme.typography.headlineMedium)
        SetuCard {
            Text(stringResource(R.string.survey_saved_body), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.survey_saved_id, surveyId.take(8)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Honest about transport: nothing has left the phone yet.
        SetuCard {
            Text(
                stringResource(R.string.survey_not_sent_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = Setu.colors.warnText,
            )
        }
        BigButton(
            label = stringResource(R.string.survey_new),
            iconRes = R.drawable.ic_relay,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            onClick = onNew,
        )
        BigButton(
            label = stringResource(R.string.survey_done),
            iconRes = R.drawable.ic_status_delivered,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurface,
            onClick = onDone,
        )
    }
}

// ------------------------------------------------------------------ helpers

/** A tappable label inside a row. Kept small deliberately — these sit beside a
 *  heading, and a 64 dp block there would push the content off the screen. */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

internal fun nextStep(s: SurveyStep): SurveyStep = when (s) {
    SurveyStep.PERSONAL -> SurveyStep.LOCATION
    SurveyStep.LOCATION -> SurveyStep.DAMAGE
    SurveyStep.DAMAGE -> SurveyStep.CASUALTIES
    SurveyStep.CASUALTIES -> SurveyStep.CAMP
    SurveyStep.CAMP -> SurveyStep.REVIEW
    else -> s
}

internal fun previousStep(s: SurveyStep): SurveyStep = when (s) {
    SurveyStep.LOCATION -> SurveyStep.PERSONAL
    SurveyStep.DAMAGE -> SurveyStep.LOCATION
    SurveyStep.CASUALTIES -> SurveyStep.DAMAGE
    SurveyStep.CAMP -> SurveyStep.CASUALTIES
    SurveyStep.REVIEW -> SurveyStep.CAMP
    else -> s
}

private fun stepTitle(s: SurveyStep): Int = when (s) {
    SurveyStep.PERSONAL -> R.string.survey_step_personal
    SurveyStep.LOCATION -> R.string.survey_step_location
    SurveyStep.DAMAGE -> R.string.survey_step_damage
    SurveyStep.CASUALTIES -> R.string.survey_step_casualties
    SurveyStep.CAMP -> R.string.survey_step_camp
    else -> R.string.survey_step_review
}

private fun disasterLabel(t: Int): Int = when (t) {
    DisasterType.FLOOD -> R.string.disaster_flood
    DisasterType.EARTHQUAKE -> R.string.disaster_earthquake
    DisasterType.CYCLONE -> R.string.disaster_cyclone
    DisasterType.LANDSLIDE -> R.string.disaster_landslide
    DisasterType.FIRE -> R.string.disaster_fire
    else -> R.string.disaster_other
}

private fun areaLabel(a: Int): Int = when (a) {
    DamageArea.HOUSE -> R.string.area_house
    DamageArea.SHOP -> R.string.area_shop
    DamageArea.AGRICULTURAL_LAND -> R.string.area_land
    DamageArea.ROAD -> R.string.area_road
    DamageArea.VEHICLE -> R.string.area_vehicle
    DamageArea.LIVESTOCK -> R.string.area_livestock
    else -> R.string.area_other
}

internal fun personStatusLabel(s: Int): Int = when (s) {
    PersonStatus.ALIVE -> R.string.status_alive
    PersonStatus.MISSING -> R.string.status_missing
    PersonStatus.NOT_ALIVE -> R.string.status_not_alive
    else -> R.string.survey_status_unset
}
