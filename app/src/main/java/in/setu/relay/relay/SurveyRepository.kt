package `in`.setu.relay.relay

import android.content.Context
import android.util.Log
import `in`.setu.relay.crypto.AadhaarId
import `in`.setu.relay.crypto.Keys
import `in`.setu.relay.crypto.SealedBox
import `in`.setu.relay.store.SetuDb
import `in`.setu.relay.store.Survey
import `in`.setu.relay.store.SurveyStatus
import `in`.setu.relay.store.SurveyStore
import `in`.setu.relay.wire.SurveyRecord

/**
 * Everything the survey wizard needs, in one place below the UI.
 *
 * Sealing is the expensive part: an X25519 scalar multiplication in pure Kotlin
 * BigInteger, tens of milliseconds on a cheap handset. It happens here, off the
 * main thread, and only when the digits are complete and have actually changed —
 * autosave fires on every pause in typing and must not re-seal each time.
 */
class SurveyRepository(context: Context, private val identityKeyId: ByteArray) {

    private val db = SetuDb(context.applicationContext).writableDatabase
    private val store = SurveyStore(db)

    init {
        // Clears child rows stranded on any phone that ran the build where a
        // duplicate Aadhaar silently replaced an existing survey.
        store.purgeOrphanPeople()
    }

    /** Last (digits -> sealed) pair, so autosave does not repeat the work. */
    private var sealCacheDigits: String = ""
    private var sealCacheValue: ByteArray? = null

    fun all(): List<Survey> = store.all()

    fun get(surveyId: String): Survey? = store.get(surveyId)

    fun count(): Int = store.count()

    fun countDrafts(): Int = store.countByStatus(SurveyStatus.DRAFT)

    /**
     * [claimAadhaar] decides whether this write takes ownership of the number.
     *
     * Drafts pass false. Autosave fires on every pause in typing, and a draft
     * that claimed the Aadhaar would collide with an existing survey while the
     * surveyor was still mid-form — failing the write, or worse, being reported
     * as a duplicate long before they asked to save anything. A draft is not a
     * commitment, so it does not reserve the number.
     *
     * The completed save passes true, and that is the single point where the
     * unique index decides.
     */
    fun save(survey: Survey, aadhaarDigits: String, claimAadhaar: Boolean): Boolean {
        val sealed = sealAadhaar(aadhaarDigits)
        val hash = if (claimAadhaar && AadhaarId.isWellFormed(aadhaarDigits)) {
            AadhaarId.duplicateKey(aadhaarDigits)
        } else {
            null
        }
        val row = if (sealed != null) survey.copy(aadhaarSealed = sealed) else survey
        return store.save(row, identityKeyId, hash)
    }

    /**
     * True when this Aadhaar already exists on the phone under a different
     * survey. Only used to phrase the message; the unique index is the actual
     * guard, and it cannot be raced.
     */
    fun isDuplicate(aadhaarDigits: String, exceptSurveyId: String): Boolean =
        AadhaarId.isWellFormed(aadhaarDigits) &&
            store.hasAadhaar(AadhaarId.duplicateKey(aadhaarDigits), exceptSurveyId)

    /**
     * Packs the relay subset and parks it in `record`, sealed to the backend key.
     *
     * Nothing transmits it yet — the bulk plane is phase 5. Writing it at save
     * time anyway means the record exists, is the right size, and is verifiably
     * unreadable without the backend key, which is what makes the claim
     * demonstrable rather than aspirational.
     */
    fun packForRelay(survey: Survey): Int {
        val plain = SurveyRecord.encode(survey)
        val sealed = SealedBox.seal(Keys.BACKEND_PUBLIC, plain)
        val id = SurveyRecord.uuidBytes(survey.surveyId)
        val values = android.content.ContentValues().apply {
            put("record_id", id)
            put("profile_id", SurveyRecord.PROFILE_ID)
            put("sealed", sealed)
            put("created_at", survey.createdAt)
            put("status", SurveyStatus.COMPLETE)
        }
        db.insertWithOnConflict(
            "record",
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
        Log.i(TAG, "survey record packed: ${plain.size} B plain, ${sealed.size} B sealed")
        return sealed.size
    }

    private fun sealAadhaar(digits: String): ByteArray? {
        if (!AadhaarId.isWellFormed(digits)) return null
        if (digits == sealCacheDigits) return sealCacheValue
        val sealed = AadhaarId.seal(digits)
        sealCacheDigits = digits
        sealCacheValue = sealed
        return sealed
    }

    companion object {
        private const val TAG = "SetuSurvey"
    }
}
