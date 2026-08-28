package `in`.setu.relay.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import `in`.setu.relay.wire.Codec

/**
 * The `record` table — sealed bulk-plane payloads, from docs/06-data-model.md.
 *
 * From record format v2 a record is **readable** by the phones that carry it —
 * see D32 in wire/SurveyRecord.kt. The operational fields are in the clear so a
 * district officer's phone can display what field workers collected; only the
 * Aadhaar number stays sealed to the backend key inside.
 *
 * That is a deliberate reversal of the v1 position and it changes what the UI is
 * allowed to say. The carrying screen may no longer claim "your phone cannot
 * read them" about survey records, because it can.
 */
class RecordStore(private val db: SQLiteDatabase) {

    private val lock = Any()

    /** Every record id held, for building the digest. */
    fun allIds(limit: Int = MAX_RECORDS): List<ByteArray> = db.rawQuery(
        "SELECT record_id FROM record ORDER BY created_at DESC LIMIT $limit",
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.getBlob(0)) } }

    fun sealedFor(recordId: ByteArray): ByteArray? = db.rawQuery(
        "SELECT sealed FROM record WHERE hex(record_id) = ?",
        arrayOf(Codec.hex(recordId).uppercase()),
    ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }

    fun has(recordId: ByteArray): Boolean = db.rawQuery(
        "SELECT 1 FROM record WHERE hex(record_id) = ? LIMIT 1",
        arrayOf(Codec.hex(recordId).uppercase()),
    ).use { it.moveToFirst() }

    fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM record", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Records this device did not originate — what it is carrying for others. */
    fun countForOthers(): Int = db.rawQuery(
        "SELECT COUNT(*) FROM record WHERE status = ?",
        arrayOf(RecordStatus.CARRIED.toString()),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** The bodies of every record that came from a peer, newest first. */
    fun allFromPeers(limit: Int = MAX_RECORDS): List<ByteArray> = db.rawQuery(
        "SELECT sealed FROM record WHERE status = ? ORDER BY created_at DESC LIMIT $limit",
        arrayOf(RecordStatus.CARRIED.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getBlob(0)) } }

    /** Record bodies for one profile, newest first. Announcements, trails, details. */
    fun bodiesOfProfile(profileId: String, limit: Int = MAX_RECORDS): List<ByteArray> = db.rawQuery(
        "SELECT sealed FROM record WHERE profile_id = ? ORDER BY created_at DESC LIMIT $limit",
        arrayOf(profileId),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getBlob(0)) } }

    /** Writes a record this device originated. Replaces its own earlier version. */
    fun putMine(recordId: ByteArray, profileId: String, body: ByteArray, nowMs: Long) =
        synchronized(lock) {
            val v = ContentValues().apply {
                put("record_id", recordId)
                put("profile_id", profileId)
                put("sealed", body)
                put("created_at", nowMs)
                put("status", RecordStatus.MINE)
            }
            db.insertWithOnConflict("record", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            Unit
        }

    fun totalBytes(): Long = db.rawQuery("SELECT SUM(LENGTH(sealed)) FROM record", null)
        .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L }

    /**
     * Stores a record arriving from a peer. Returns false when it was already
     * held, which is the common case once a mesh has settled.
     *
     * Deliberately does **not** overwrite: a record id is the survey's UUID and
     * the body is signed-and-sealed, so a second copy carries no new information
     * and accepting one would let any peer replace a record it did not create.
     */
    fun acceptFromPeer(
        recordId: ByteArray,
        profileId: String,
        sealed: ByteArray,
        nowMs: Long,
    ): Boolean = synchronized(lock) {
        if (recordId.size != 16) return false
        if (has(recordId)) return false
        evictIfFull()
        val v = ContentValues().apply {
            put("record_id", recordId)
            put("profile_id", profileId)
            put("sealed", sealed)
            put("created_at", nowMs)
            put("status", RecordStatus.CARRIED)
        }
        db.insertOrThrow("record", null, v)
        true
    }

    /**
     * Tier 4 in the eviction order of docs/01: records are the lowest priority
     * thing in the store, so they go before any message does. Oldest first, and
     * a record this device originated is kept over one it is merely carrying —
     * losing your own survey to make room for a stranger's would be indefensible.
     */
    private fun evictIfFull() {
        val n = count()
        if (n < MAX_RECORDS) return
        db.execSQL(
            """
            DELETE FROM record WHERE record_id IN (
              SELECT record_id FROM record
              ORDER BY status = ${RecordStatus.CARRIED} DESC, created_at ASC
              LIMIT ${n - MAX_RECORDS + 1}
            )
            """.trimIndent(),
        )
    }

    companion object {
        /** Matches the Bloom filter's design point in docs/02. */
        const val MAX_RECORDS = 200
    }
}

object RecordStatus {
    /** Created on this device. */
    const val MINE = SurveyStatus.COMPLETE

    /** Received from a peer and being carried onward. Cannot be read here. */
    const val CARRIED = 9
}
