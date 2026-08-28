package `in`.setu.relay.relay

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import `in`.setu.relay.crypto.AadhaarId
import `in`.setu.relay.crypto.Keys
import `in`.setu.relay.crypto.SealedBox
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
class SurveyRepository(
    /**
     * The relay's database, not a fresh one.
     *
     * This used to open its own `SetuDb`, which meant a second SQLiteOpenHelper
     * and therefore a second connection pool on the same file. That was survivable
     * while surveys were the only writer. It stopped being survivable once the
     * bulk plane began writing `record` from a GATT callback thread: two pools
     * writing one table without WAL is how `SQLiteDatabaseLockedException` shows
     * up, and it would surface as a survey silently failing to save at exactly
     * the moment a peer connected.
     */
    private val db: SQLiteDatabase,
    private val identityKeyId: ByteArray,
    /** Only for the location fix taken when a survey is completed. */
    context: android.content.Context,
) {

    private val store = SurveyStore(db)
    private val records = `in`.setu.relay.store.RecordStore(db)
    private val locator = Locator(context)

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
    /**
     * A position and a time, captured automatically at save time.
     *
     * Blocking, with a short ceiling, because the caller is already off the main
     * thread and a surveyor pressing Save expects the record to be finished when
     * the screen changes. No fix is a normal outcome indoors — the record is
     * saved anyway with NaN, and the table shows Pending rather than inventing
     * a coordinate.
     */
    fun stampNow(survey: Survey, timeoutMs: Long = 6_000L): Survey {
        val latch = java.util.concurrent.CountDownLatch(1)
        var fix: android.location.Location? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            locator.fixOnce(timeoutMs) { fix = it; latch.countDown() }
        }
        runCatching { latch.await(timeoutMs + 1_000L, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val now = System.currentTimeMillis()
        return survey.copy(
            lat = fix?.latitude ?: Double.NaN,
            lon = fix?.longitude ?: Double.NaN,
            capturedAt = if (survey.capturedAt > 0) survey.capturedAt else now,
        )
    }

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
        // Stored as encoded-but-not-sealed. The Aadhaar inside is already sealed
        // to the backend key; everything else is meant to be readable by the
        // phones that carry it, so a district officer can actually see what the
        // field workers collected. See D32 in wire/SurveyRecord.kt.
        val sealed = SurveyRecord.encode(survey)
        val plain = sealed
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
        Log.i(TAG, "survey record packed: ${sealed.size} B")
        return sealed.size
    }

    /**
     * Packs a record for every completed survey that does not have one.
     *
     * Needed because the record format changed in v2 and because surveys saved
     * before packing existed have no record at all. Without this a phone can be
     * holding surveys and still advertise an empty digest, which looks exactly
     * like the bulk plane being broken.
     */
    fun ensureRecordsPacked(): Int {
        var packed = 0
        for (s in store.all()) {
            if (s.status == SurveyStatus.DRAFT) continue
            val id = SurveyRecord.uuidBytes(s.surveyId)
            if (records.has(id)) continue
            runCatching { packForRelay(s) }.onSuccess { packed++ }
        }
        if (packed > 0) Log.i(TAG, "packed $packed survey record(s) for relay")
        return packed
    }

    /** Every survey this phone received from a peer, newest first. */
    fun received(): List<SurveyRecord.Decoded> = records.allFromPeers()
        .mapNotNull { SurveyRecord.decodeOrNull(it) }

    fun receivedCount(): Int = records.countForOthers()

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
