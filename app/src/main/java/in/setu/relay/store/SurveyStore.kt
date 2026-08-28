package `in`.setu.relay.store

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import `in`.setu.relay.wire.Codec

/**
 * Survey persistence. Shares the one [SetuDb] with [MessageStore] — a second
 * helper on the same file would give two connections and two write locks for no
 * benefit.
 *
 * Everything here is synchronous and small. A survey is a handful of rows; the
 * expensive part is sealing the Aadhaar, which the caller does off the main
 * thread before arriving here.
 */
class SurveyStore(private val db: SQLiteDatabase) {

    private val lock = Any()

    /**
     * Inserts or replaces a survey and its people.
     *
     * Returns false when the Aadhaar duplicates one already on this phone —
     * caught from the unique index rather than checked first, so two surveyors
     * racing on one device cannot slip between the check and the write.
     */
    fun save(survey: Survey, surveyorKeyId: ByteArray, aadhaarHash: ByteArray?): Boolean =
        synchronized(lock) {
            db.beginTransaction()
            try {
                val v = ContentValues().apply {
                    put("survey_id", survey.surveyId)
                    put("created_at", survey.createdAt)
                    put("updated_at", survey.updatedAt)
                    put("status", survey.status)
                    put("is_proxy", if (survey.isProxy) 1 else 0)
                    put("proxy_consent", if (survey.proxyConsent) 1 else 0)
                    put("surveyor_key", surveyorKeyId)

                    put("name", survey.name)
                    put("father_name", survey.fatherName)
                    put("mobile", survey.mobile)
                    put("aadhaar_sealed", survey.aadhaarSealed)
                    put("aadhaar_hash", aadhaarHash)
                    put("aadhaar_last4", survey.aadhaarLast4)
                    put("family_id", survey.familyId)

                    put("village", survey.village)
                    put("district", survey.district)
                    put("post_office", survey.postOffice)
                    put("police_station", survey.policeStation)
                    put("pin", survey.pin)

                    put("disaster_type", survey.disasterType)
                    put("disaster_other", survey.disasterOther)
                    put("damage_date", survey.damageDate)
                    put("damage_areas", survey.damageAreas)
                    put("damage_other", survey.damageOther)
                    put("damage_desc", survey.damageDescription)

                    put("in_camp", if (survey.inCamp) 1 else 0)
                    put("camp_name", survey.campName)
                    put("camp_location", survey.campLocation)
                    put("needs", survey.needs)

                    // NaN is not representable in SQLite REAL, so an absent fix
                    // is stored as NULL and read back as NaN.
                    if (survey.lat.isNaN()) putNull("lat") else put("lat", survey.lat)
                    if (survey.lon.isNaN()) putNull("lon") else put("lon", survey.lon)
                    put("captured_at", survey.capturedAt)
                }
                // Update-then-insert, NOT insertWithOnConflict(CONFLICT_REPLACE).
                //
                // REPLACE deletes every row that conflicts on *any* unique
                // index, not just the primary key. With a unique index on
                // aadhaar_hash that turned the duplicate guard into a silent
                // delete: entering an Aadhaar that already existed destroyed the
                // earlier survey and reported success, and the catch below was
                // dead code because nothing ever threw. Update-then-insert lets
                // the index raise, which is the entire reason it exists.
                val updated = db.update("survey", v, "survey_id = ?", arrayOf(survey.surveyId))
                if (updated == 0) db.insertOrThrow("survey", null, v)

                // Replace the whole child set: the wizard hands over the current
                // list, and reconciling row by row would be more code for a
                // handful of rows.
                db.delete("person", "survey_id = ?", arrayOf(survey.surveyId))
                for (p in survey.people) {
                    db.insert(
                        "person",
                        null,
                        ContentValues().apply {
                            put("person_id", p.personId)
                            put("survey_id", survey.surveyId)
                            put("ordinal", p.ordinal)
                            put("name", p.name)
                            put("age", p.age)
                            put("gender", p.gender)
                            put("status", p.status)
                            put("location", p.location)
                        },
                    )
                }
                db.setTransactionSuccessful()
                true
            } catch (e: SQLiteConstraintException) {
                // The unique index on aadhaar_hash fired: this person is already
                // on this phone. The transaction rolls back because
                // setTransactionSuccessful was never reached, so the existing
                // survey is untouched.
                false
            } finally {
                db.endTransaction()
            }
        }

    fun setStatus(surveyId: String, status: Int, nowMs: Long) = synchronized(lock) {
        db.execSQL(
            "UPDATE survey SET status = ?, updated_at = ? WHERE survey_id = ?",
            arrayOf<Any>(status, nowMs, surveyId),
        )
    }

    fun delete(surveyId: String) = synchronized(lock) {
        db.beginTransaction()
        try {
            db.delete("person", "survey_id = ?", arrayOf(surveyId))
            db.delete("survey", "survey_id = ?", arrayOf(surveyId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * True when this Aadhaar is already recorded on this phone.
     *
     * Compared through SQLite's `hex()` because `rawQuery` binds every argument
     * as TEXT, and in SQLite a BLOB never compares equal to a TEXT value — the
     * direct `aadhaar_hash = ?` form silently returns "no duplicate" every time.
     * `hex()` emits uppercase, so the argument has to match.
     *
     * This is only for a civil message before saving; [save] still relies on the
     * unique index, which is the check that cannot be raced.
     */
    fun hasAadhaar(hash: ByteArray, exceptSurveyId: String? = null): Boolean = db.rawQuery(
        "SELECT COUNT(*) FROM survey WHERE hex(aadhaar_hash) = ? AND survey_id != ?",
        arrayOf(Codec.hex(hash).uppercase(), exceptSurveyId ?: ""),
    ).use { it.moveToFirst() && it.getInt(0) > 0 }

    /**
     * Removes person rows whose survey no longer exists.
     *
     * Only reachable on a database damaged by the CONFLICT_REPLACE bug described
     * in [save]: a replaced survey took its row with it and left its children
     * behind. New writes cannot create orphans, but an installed phone can
     * already have them.
     */
    fun purgeOrphanPeople(): Int = synchronized(lock) {
        db.delete("person", "survey_id NOT IN (SELECT survey_id FROM survey)", null)
    }

    fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM survey", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun countByStatus(status: Int): Int = db.rawQuery(
        "SELECT COUNT(*) FROM survey WHERE status = ?",
        arrayOf(status.toString()),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun get(surveyId: String): Survey? = db.rawQuery(
        "SELECT * FROM survey WHERE survey_id = ?",
        arrayOf(surveyId),
    ).use { c -> if (c.moveToFirst()) readSurvey(c).copy(people = peopleFor(surveyId)) else null }

    /** Newest first — a surveyor wants what they just entered at the top. */
    fun all(limit: Int = 200): List<Survey> = db.rawQuery(
        "SELECT * FROM survey ORDER BY updated_at DESC LIMIT $limit",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(readSurvey(c).copy(people = peopleFor(c.getString(c.getColumnIndexOrThrow("survey_id")))))
        }
    }

    private fun peopleFor(surveyId: String): List<Person> = db.rawQuery(
        "SELECT * FROM person WHERE survey_id = ? ORDER BY ordinal",
        arrayOf(surveyId),
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Person(
                        personId = c.str("person_id"),
                        surveyId = surveyId,
                        ordinal = c.int("ordinal"),
                        name = c.str("name"),
                        age = c.int("age"),
                        gender = c.int("gender"),
                        status = c.int("status"),
                        location = c.str("location"),
                    ),
                )
            }
        }
    }

    private fun readSurvey(c: Cursor) = Survey(
        surveyId = c.str("survey_id"),
        createdAt = c.long("created_at"),
        updatedAt = c.long("updated_at"),
        status = c.int("status"),
        isProxy = c.int("is_proxy") == 1,
        proxyConsent = c.int("proxy_consent") == 1,
        name = c.str("name"),
        fatherName = c.str("father_name"),
        mobile = c.str("mobile"),
        aadhaarSealed = c.blob("aadhaar_sealed"),
        aadhaarLast4 = c.str("aadhaar_last4"),
        familyId = c.str("family_id"),
        village = c.str("village"),
        district = c.str("district"),
        postOffice = c.str("post_office"),
        policeStation = c.str("police_station"),
        pin = c.str("pin"),
        disasterType = c.int("disaster_type"),
        disasterOther = c.str("disaster_other"),
        damageDate = c.str("damage_date"),
        damageAreas = c.int("damage_areas"),
        damageOther = c.str("damage_other"),
        damageDescription = c.str("damage_desc"),
        inCamp = c.int("in_camp") == 1,
        campName = c.str("camp_name"),
        campLocation = c.str("camp_location"),
        needs = c.str("needs"),
        lat = c.dbl("lat"),
        lon = c.dbl("lon"),
        capturedAt = c.long("captured_at"),
    )

    private fun Cursor.str(name: String): String =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) "" else getString(it) }

    private fun Cursor.int(name: String): Int =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) -1 else getInt(it) }

    private fun Cursor.long(name: String): Long =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) 0L else getLong(it) }

    private fun Cursor.dbl(name: String): Double =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) Double.NaN else getDouble(it) }

    private fun Cursor.blob(name: String): ByteArray? =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getBlob(it) }

}
