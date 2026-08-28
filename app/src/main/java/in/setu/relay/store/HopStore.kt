package `in`.setu.relay.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import `in`.setu.relay.wire.Codec
import `in`.setu.relay.wire.HopTrail
import `in`.setu.relay.wire.Proto

/**
 * Every observation of every message: which phone heard it, when, where, and how
 * strongly.
 *
 * This is the raw material for two separate things, and it is worth being clear
 * that they are the same data read two ways:
 *
 * - **The hop trail** — for one message, the route it took across the mesh.
 * - **Silence detection** — for one phone, the last time and place anyone heard
 *   it. When a phone that was relaying goes quiet, the newest row naming it is
 *   the centre of a search area. That is the difference between searching a
 *   valley and searching a hillside, and it costs nothing extra: the mesh was
 *   already producing these observations and simply throwing them away.
 *
 * Bounded by construction. A message is capped at [Proto.HOP_LIMIT] hops and
 * rows are keyed `(msg_id, key_id)`, so one message can never hold more than 32
 * observations no matter how long it circulates.
 */
class HopStore(private val db: SQLiteDatabase) {

    private val lock = Any()

    /**
     * Records that this phone, or a phone we learned about, heard a message.
     *
     * `CONFLICT_IGNORE` keeps the **first** sighting. A message heard again five
     * minutes later says nothing new about where it was, and overwriting would
     * quietly destroy the earliest — and therefore most useful — timestamp.
     */
    fun observe(
        msgId: ByteArray,
        keyId: ByteArray,
        heardAt: Long,
        lat: Double,
        lon: Double,
        hopCount: Int,
        rssi: Int,
    ) = synchronized(lock) {
        val v = ContentValues().apply {
            put("msg_id", msgId)
            put("key_id", keyId)
            put("heard_at", heardAt)
            if (lat.isNaN()) putNull("lat") else put("lat", lat)
            if (lon.isNaN()) putNull("lon") else put("lon", lon)
            put("hop_count", hopCount)
            put("rssi", rssi)
        }
        db.insertWithOnConflict("hop", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        Unit
    }

    /** The route one message took, earliest hop first. */
    fun trailFor(msgId: ByteArray): List<HopTrail.Hop> = db.rawQuery(
        "SELECT key_id, heard_at, lat, lon, hop_count, rssi FROM hop " +
            "WHERE hex(msg_id) = ? ORDER BY hop_count ASC, heard_at ASC",
        arrayOf(Codec.hex(msgId).uppercase()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    HopTrail.Hop(
                        keyId = c.getBlob(0),
                        heardAt = c.getLong(1),
                        lat = if (c.isNull(2)) Double.NaN else c.getDouble(2),
                        lon = if (c.isNull(3)) Double.NaN else c.getDouble(3),
                        hopCount = c.getInt(4),
                        rssi = c.getInt(5),
                    ),
                )
            }
        }
    }

    /** Merges a trail that arrived from a peer into what this phone already knows. */
    fun mergeTrail(msgId: ByteArray, incoming: List<HopTrail.Hop>) = synchronized(lock) {
        for (h in incoming) {
            observe(msgId, h.keyId, h.heardAt, h.lat, h.lon, h.hopCount, h.rssi)
        }
    }

    /** Message ids this phone holds observations for, newest first. */
    fun messagesWithTrails(limit: Int = 100): List<ByteArray> = db.rawQuery(
        "SELECT msg_id, MAX(heard_at) h FROM hop GROUP BY msg_id ORDER BY h DESC LIMIT $limit",
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.getBlob(0)) } }

    // ------------------------------------------------------ silence detection

    /**
     * One phone's last known whereabouts, assembled from every observation
     * naming it.
     *
     * `lastHeardAt` is the newest sighting by anyone. The position is the newest
     * sighting that actually carried a fix, which is deliberately not the same
     * row — a phone heard indoors five minutes ago with no fix is still best
     * located by the fix from twenty minutes earlier.
     */
    class Silence(
        val keyId: ByteArray,
        val lastHeardAt: Long,
        val lat: Double,
        val lon: Double,
        val fixAt: Long,
        val lastRssi: Int,
        val sightings: Int,
    ) {
        val hasFix: Boolean get() = !lat.isNaN() && !lon.isNaN()
        fun quietForMs(nowMs: Long): Long = nowMs - lastHeardAt
    }

    /**
     * Phones nobody has heard from in [quietForMs], worst first.
     *
     * @param excludeKeyId this device's own id, which is never "missing"
     */
    fun goneQuiet(
        nowMs: Long,
        quietForMs: Long = 10 * 60_000L,
        excludeKeyId: ByteArray? = null,
        limit: Int = 200,
    ): List<Silence> {
        val cutoff = nowMs - quietForMs
        val mine = excludeKeyId?.let { Codec.hex(it).uppercase() } ?: ""
        return db.rawQuery(
            """
            SELECT key_id,
                   MAX(heard_at)                                    AS last_heard,
                   COUNT(*)                                         AS sightings,
                   (SELECT lat      FROM hop h2 WHERE h2.key_id = hop.key_id
                      AND h2.lat IS NOT NULL ORDER BY h2.heard_at DESC LIMIT 1) AS fix_lat,
                   (SELECT lon      FROM hop h2 WHERE h2.key_id = hop.key_id
                      AND h2.lat IS NOT NULL ORDER BY h2.heard_at DESC LIMIT 1) AS fix_lon,
                   (SELECT heard_at FROM hop h2 WHERE h2.key_id = hop.key_id
                      AND h2.lat IS NOT NULL ORDER BY h2.heard_at DESC LIMIT 1) AS fix_at,
                   (SELECT rssi     FROM hop h2 WHERE h2.key_id = hop.key_id
                      ORDER BY h2.heard_at DESC LIMIT 1)                        AS last_rssi
            FROM hop
            WHERE hex(key_id) != ?
            GROUP BY key_id
            HAVING last_heard < ?
            ORDER BY last_heard DESC
            LIMIT $limit
            """.trimIndent(),
            arrayOf(mine, cutoff.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Silence(
                            keyId = c.getBlob(0),
                            lastHeardAt = c.getLong(1),
                            sightings = c.getInt(2),
                            lat = if (c.isNull(3)) Double.NaN else c.getDouble(3),
                            lon = if (c.isNull(4)) Double.NaN else c.getDouble(4),
                            fixAt = if (c.isNull(5)) 0L else c.getLong(5),
                            lastRssi = if (c.isNull(6)) 0 else c.getInt(6),
                        ),
                    )
                }
            }
        }
    }

    /** Everything known about one phone, whether or not it has gone quiet. */
    fun lastKnown(keyId: ByteArray, nowMs: Long): Silence? =
        goneQuiet(nowMs, quietForMs = -1L, excludeKeyId = null, limit = 500)
            .firstOrNull { it.keyId.contentEquals(keyId) }

    fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM hop", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Observations outlive the messages that produced them on purpose: a message
     * expires in 24 hours, but "who was last heard where" is the thing a search
     * team needs on day three. Kept for 30 days, same as the dedupe set.
     */
    fun purge(nowMs: Long, maxAgeMs: Long = 30L * 24 * 3_600_000L): Int = synchronized(lock) {
        db.delete("hop", "heard_at < ?", arrayOf((nowMs - maxAgeMs).toString()))
    }
}
