package `in`.setu.relay.ui.survey

import `in`.setu.relay.R
import `in`.setu.relay.crypto.AadhaarId
import `in`.setu.relay.store.DamageArea
import `in`.setu.relay.store.DisasterType
import `in`.setu.relay.store.Gender
import `in`.setu.relay.store.Person
import `in`.setu.relay.store.PersonStatus
import `in`.setu.relay.store.Survey
import `in`.setu.relay.store.SurveyStatus
import java.util.UUID

/**
 * The in-progress survey.
 *
 * Immutable, edited by `copy`, so Compose sees a new value and recomposes
 * without any observable-field machinery.
 *
 * The Aadhaar digits live here and **only** here — in memory, for as long as the
 * wizard is open. They are sealed on the way to the database and the plaintext
 * is never written down, which is the whole point of `crypto/AadhaarId.kt`. The
 * practical consequence, which the UI states plainly: correcting an Aadhaar on a
 * saved survey means typing all twelve digits again, because nothing on the
 * device can recover them.
 */
data class SurveyDraft(
    val surveyId: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),

    val isProxy: Boolean = false,
    val proxyConsent: Boolean = false,

    val name: String = "",
    val fatherName: String = "",
    val mobile: String = "",
    val aadhaar: String = "",
    val familyId: String = "",

    val village: String = "",
    val district: String = "",
    val postOffice: String = "",
    val policeStation: String = "",
    val pin: String = "",

    val disasterType: Int = DisasterType.FLOOD,
    val disasterOther: String = "",
    val damageDate: String = "",
    val damageAreas: Int = 0,
    val damageOther: String = "",
    val damageDescription: String = "",

    val inCamp: Boolean = false,
    val campName: String = "",
    val campLocation: String = "",
    val needs: String = "",

    val people: List<PersonDraft> = listOf(PersonDraft()),
) {

    /**
     * Builds the row to persist. [sealedAadhaar] is passed in rather than
     * computed here because sealing costs an X25519 scalar multiplication and
     * belongs off the main thread.
     */
    fun toSurvey(status: Int, sealedAadhaar: ByteArray?): Survey = Survey(
        surveyId = surveyId,
        createdAt = createdAt,
        updatedAt = System.currentTimeMillis(),
        status = status,
        isProxy = isProxy,
        proxyConsent = proxyConsent,
        name = name.trim(),
        fatherName = fatherName.trim(),
        mobile = mobile.trim(),
        aadhaarSealed = sealedAadhaar,
        aadhaarLast4 = if (AadhaarId.isWellFormed(aadhaar)) AadhaarId.last4(aadhaar) else "",
        familyId = familyId.trim(),
        village = village.trim(),
        district = district.trim(),
        postOffice = postOffice.trim(),
        policeStation = policeStation.trim(),
        pin = pin.trim(),
        disasterType = disasterType,
        disasterOther = disasterOther.trim(),
        damageDate = damageDate.trim(),
        damageAreas = damageAreas,
        damageOther = damageOther.trim(),
        damageDescription = damageDescription.trim(),
        inCamp = inCamp,
        campName = campName.trim(),
        campLocation = campLocation.trim(),
        needs = needs.trim(),
        people = people.mapIndexed { i, p -> p.toPerson(surveyId, i) },
    )

    // ------------------------------------------------------------ validation
    //
    // Each step reports which of its own fields are missing. The review screen
    // asks every step, which is how it marks a section "Incomplete" without
    // duplicating any of these rules.

    fun personalMissing(): List<Int> = buildList {
        if (name.isBlank()) add(Field.NAME)
        if (fatherName.isBlank()) add(Field.FATHER)
        if (!isValidMobile(mobile)) add(Field.MOBILE)
        if (!AadhaarId.isWellFormed(aadhaar)) add(Field.AADHAAR)
        if (isProxy && !proxyConsent) add(Field.PROXY_CONSENT)
    }

    fun locationMissing(): List<Int> = buildList {
        if (village.isBlank()) add(Field.VILLAGE)
        if (district.isBlank()) add(Field.DISTRICT)
        if (postOffice.isBlank()) add(Field.POST_OFFICE)
        if (policeStation.isBlank()) add(Field.POLICE_STATION)
        if (!isValidPin(pin)) add(Field.PIN)
    }

    fun damageMissing(): List<Int> = buildList {
        if (disasterType == DisasterType.OTHER && disasterOther.isBlank()) add(Field.DISASTER_OTHER)
        if (damageDate.isBlank()) add(Field.DAMAGE_DATE)
        if (damageAreas == 0) add(Field.DAMAGE_AREA)
        if (damageAreas and DamageArea.OTHER != 0 && damageOther.isBlank()) {
            add(Field.DAMAGE_OTHER)
        }
    }

    fun casualtiesMissing(): List<Int> = buildList {
        // A survey with nobody listed is legitimate — a damaged shop with no one
        // hurt. What is not legitimate is a half-filled person card.
        for (p in people) {
            if (p.isBlank()) continue
            if (p.name.isBlank()) add(Field.PERSON_NAME)
            if (p.age.toIntOrNull() == null) add(Field.PERSON_AGE)
            if (p.gender == Gender.UNSET) add(Field.PERSON_GENDER)
            if (p.status == PersonStatus.UNSET) add(Field.PERSON_STATUS)
            if (p.location.isBlank()) add(Field.PERSON_LOCATION)
        }
    }

    fun campMissing(): List<Int> = buildList {
        if (inCamp && campName.isBlank()) add(Field.CAMP_NAME)
    }

    fun isComplete(): Boolean =
        personalMissing().isEmpty() &&
            locationMissing().isEmpty() &&
            damageMissing().isEmpty() &&
            casualtiesMissing().isEmpty() &&
            campMissing().isEmpty()

    companion object {
        fun isValidMobile(s: String) = s.length == 10 && s.all { it in '0'..'9' }
        fun isValidPin(s: String) = s.length == 6 && s.all { it in '0'..'9' }

        /** Reloads a saved survey for editing. The Aadhaar cannot come back. */
        fun from(s: Survey): SurveyDraft = SurveyDraft(
            surveyId = s.surveyId,
            createdAt = s.createdAt,
            isProxy = s.isProxy,
            proxyConsent = s.proxyConsent,
            name = s.name,
            fatherName = s.fatherName,
            mobile = s.mobile,
            aadhaar = "",
            familyId = s.familyId,
            village = s.village,
            district = s.district,
            postOffice = s.postOffice,
            policeStation = s.policeStation,
            pin = s.pin,
            disasterType = s.disasterType,
            disasterOther = s.disasterOther,
            damageDate = s.damageDate,
            damageAreas = s.damageAreas,
            damageOther = s.damageOther,
            damageDescription = s.damageDescription,
            inCamp = s.inCamp,
            campName = s.campName,
            campLocation = s.campLocation,
            needs = s.needs,
            people = if (s.people.isEmpty()) {
                listOf(PersonDraft())
            } else {
                s.people.map {
                    PersonDraft(
                        personId = it.personId,
                        name = it.name,
                        age = if (it.age >= 0) it.age.toString() else "",
                        gender = it.gender,
                        status = it.status,
                        location = it.location,
                    )
                }
            },
        )
    }
}

data class PersonDraft(
    val personId: String = UUID.randomUUID().toString(),
    val name: String = "",
    val age: String = "",
    val gender: Int = Gender.UNSET,
    val status: Int = PersonStatus.UNSET,
    val location: String = "",
) {
    /** An untouched card. Saving a survey should not fail because of one. */
    fun isBlank(): Boolean =
        name.isBlank() && age.isBlank() && location.isBlank() &&
            gender == Gender.UNSET && status == PersonStatus.UNSET

    fun toPerson(surveyId: String, ordinal: Int) = Person(
        personId = personId,
        surveyId = surveyId,
        ordinal = ordinal,
        name = name.trim(),
        age = age.toIntOrNull() ?: -1,
        gender = gender,
        status = status,
        location = location.trim(),
    )
}

/** Field identifiers, so validation can report *what* is missing without strings. */
object Field {
    const val NAME = 1
    const val FATHER = 2
    const val MOBILE = 3
    const val AADHAAR = 4
    const val PROXY_CONSENT = 5
    const val VILLAGE = 10
    const val DISTRICT = 11
    const val POST_OFFICE = 12
    const val POLICE_STATION = 13
    const val PIN = 14
    const val DISASTER_OTHER = 20
    const val DAMAGE_DATE = 21
    const val DAMAGE_AREA = 22
    const val DAMAGE_OTHER = 23
    const val PERSON_NAME = 30
    const val PERSON_AGE = 31
    const val PERSON_GENDER = 32
    const val PERSON_STATUS = 33
    const val PERSON_LOCATION = 34
    const val CAMP_NAME = 40
}

/** The six form steps from the mockups, plus the two that bookend them. */
enum class SurveyStep {
    WELCOME,
    PERSONAL,
    LOCATION,
    DAMAGE,
    CASUALTIES,
    CAMP,
    REVIEW,
    SAVED,
    ;

    /** 1-based position among the six numbered steps; 0 for the bookends. */
    fun formIndex(): Int = when (this) {
        PERSONAL -> 1
        LOCATION -> 2
        DAMAGE -> 3
        CASUALTIES -> 4
        CAMP -> 5
        REVIEW -> 6
        else -> 0
    }

    companion object {
        const val FORM_STEPS = 6
    }
}

/** Status shown on a saved survey. Nothing here claims delivery. */
fun surveyStatusLabel(status: Int): Int = when (status) {
    SurveyStatus.DRAFT -> R.string.survey_status_draft
    SurveyStatus.COMPLETE -> R.string.survey_status_complete
    SurveyStatus.QUEUED -> R.string.survey_status_queued
    SurveyStatus.RELAYED -> R.string.survey_status_relayed
    SurveyStatus.UPLOADED -> R.string.survey_status_uploaded
    else -> R.string.survey_status_draft
}
