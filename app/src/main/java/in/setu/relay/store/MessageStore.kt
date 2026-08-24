package `in`.setu.relay.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import `in`.setu.relay.wire.AdvertState
import `in`.setu.relay.wire.Codec
import `in`.setu.relay.wire.Envelope
import `in`.setu.relay.wire.Status

/** One row of `message`, plus the decoded envelope. */
class StoredMessage(
    val msgId: ByteArray,
    val type: Int,
    val tier: Int,
    val originKeyId: ByteArray,
    val createdAt: Long,
    val receivedAt: Long,
    val ttlHours: Int,
    val hopCount: Int,
    val envelope: ByteArray,
    val isMine: Boolean,
    val status: Int,
    val advertState: Int,
) {
    val idHex: String get() = Codec.hex(msgId)
    fun decode(): Envelope? = Envelope.decodeOrNull(envelope)
    fun expiresAt(): Long = createdAt * 1000L + ttlHours * 3_600_000L
}

/**
 * The only shared state in the app. A message enters from the local user, the
 * beacon plane or the bulk plane; it leaves via the beacon plane, the bulk plane
 * or the backend. See docs/01-architecture.md.
 *
 * Every method is synchronous and safe to call off the main thread. SQLite
 * serialises writers itself; the explicit lock exists only to keep read-then-
 * write sequences (dedupe, eviction) atomic.
 */
class MessageStore(context: Context) {

    private val helper = SetuDb(context.applicationContext)
    private val lock = Any()

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ------------------------------------------------------------------ seen

    /**
     * Records that [msgId] was observed. Returns true the first time it is seen
     * and false on every duplicate, incrementing `dup_count` in that case.
     * The dedupe check runs before signature verification, which is what keeps
     * a replayed beacon cheap.
     */
    fun markSeen(msgId: ByteArray, nowMs: Long): Boolean = synchronized(lock) {
        val d = db
        d.beginTransaction()
        try {
            val updated = d.compileStatement(
                "UPDATE seen SET dup_count = dup_count + 1 WHERE msg_id = ?",
            ).use { st ->
                st.bindBlob(1, msgId)
                st.executeUpdateDelete()
            }
            val first = updated == 0
            if (first) {
                d.insertWithOnConflict(
                    "seen",
                    null,
                    ContentValues().apply {
                        put("msg_id", msgId)
                        put("first_seen", nowMs)
                        put("dup_count", 0)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            d.setTransactionSuccessful()
            first
        } finally {
            d.endTransaction()
        }
    }

    // Android's SQLite bindings cannot pass a BLOB through selectionArgs, so
    // every blob comparison in this file goes through an x'..' hex literal.
    // msg_id values are generated locally as hex from bytes, so there is no
    // string-injection surface here.
    fun duplicateCount(msgId: ByteArray): Int = db.rawQuery(
        "SELECT dup_count FROM seen WHERE msg_id = x'${Codec.hex(msgId)}'",
        null,
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** `seen` grows forever unless reaped. Purge rows older than 30 days. */
    fun purgeSeen(nowMs: Long, maxAgeMs: Long = 30L * 24 * 3_600_000L): Int = synchronized(lock) {
        db.delete("seen", "first_seen < ?", arrayOf((nowMs - maxAgeMs).toString()))
    }

    // --------------------------------------------------------------- message

    fun contains(msgId: ByteArray): Boolean =
        db.rawQuery("SELECT 1 FROM message WHERE msg_id = x'${Codec.hex(msgId)}'", null)
            .use { it.moveToFirst() }

    fun insert(
        envelope: Envelope,
        encoded: ByteArray,
        nowMs: Long,
        isMine: Boolean,
        status: Int = Status.HELD,
        advertState: Int = AdvertState.BACKOFF,
    ): Boolean = synchronized(lock) {
        val cv = ContentValues().apply {
            put("msg_id", envelope.msgId)
            put("type", envelope.type)
            put("tier", envelope.tier)
            put("origin_key_id", envelope.originKeyId)
            put("created_at", envelope.createdAt)
            put("received_at", nowMs)
            put("ttl_hours", envelope.ttlHours)
            put("hop_count", envelope.hopCount)
            put("envelope", encoded)
            put("is_mine", if (isMine) 1 else 0)
            put("status", status)
            put("advert_state", advertState)
        }
        val rowId = db.insertWithOnConflict("message", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        rowId != -1L
    }

    fun updateHopCount(msgId: ByteArray, hop: Int, encoded: ByteArray) = synchronized(lock) {
        db.update(
            "message",
            ContentValues().apply {
                put("hop_count", hop)
                put("envelope", encoded)
            },
            "msg_id = x'${Codec.hex(msgId)}'",
            null,
        )
    }

    fun setStatus(msgId: ByteArray, status: Int) = synchronized(lock) {
        db.update(
            "message",
            ContentValues().apply { put("status", status) },
            "msg_id = x'${Codec.hex(msgId)}'",
            null,
        )
    }

    fun setAdvertState(msgId: ByteArray, state: Int) = synchronized(lock) {
        db.update(
            "message",
            ContentValues().apply { put("advert_state", state) },
            "msg_id = x'${Codec.hex(msgId)}'",
            null,
        )
    }

    fun get(msgId: ByteArray): StoredMessage? =
        query("SELECT * FROM message WHERE msg_id = x'${Codec.hex(msgId)}'").firstOrNull()

    /** Everything still worth advertising, in priority order: tier asc, oldest first. */
    fun liveMessages(): List<StoredMessage> = query(
        "SELECT * FROM message WHERE advert_state != ${AdvertState.DONE} " +
            "ORDER BY tier ASC, created_at ASC",
    )

    fun myMessages(): List<StoredMessage> =
        query("SELECT * FROM message WHERE is_mine = 1 ORDER BY created_at DESC")

    fun carriedForOthers(): List<StoredMessage> =
        query("SELECT * FROM message WHERE is_mine = 0 ORDER BY tier ASC, created_at DESC")

    fun countCarriedForOthers(): Int =
        db.rawQuery("SELECT COUNT(*) FROM message WHERE is_mine = 0", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun countAll(): Int = db.rawQuery("SELECT COUNT(*) FROM message", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun query(sql: String): List<StoredMessage> {
        val out = ArrayList<StoredMessage>()
        db.rawQuery(sql, null).use { c ->
            val iId = c.getColumnIndexOrThrow("msg_id")
            val iType = c.getColumnIndexOrThrow("type")
            val iTier = c.getColumnIndexOrThrow("tier")
            val iOrigin = c.getColumnIndexOrThrow("origin_key_id")
            val iCreated = c.getColumnIndexOrThrow("created_at")
            val iReceived = c.getColumnIndexOrThrow("received_at")
            val iTtl = c.getColumnIndexOrThrow("ttl_hours")
            val iHop = c.getColumnIndexOrThrow("hop_count")
            val iEnv = c.getColumnIndexOrThrow("envelope")
            val iMine = c.getColumnIndexOrThrow("is_mine")
            val iStatus = c.getColumnIndexOrThrow("status")
            val iAdvert = c.getColumnIndexOrThrow("advert_state")
            while (c.moveToNext()) {
                out.add(
                    StoredMessage(
                        msgId = c.getBlob(iId),
                        type = c.getInt(iType),
                        tier = c.getInt(iTier),
                        originKeyId = c.getBlob(iOrigin),
                        createdAt = c.getLong(iCreated),
                        receivedAt = c.getLong(iReceived),
                        ttlHours = c.getInt(iTtl),
                        hopCount = c.getInt(iHop),
                        envelope = c.getBlob(iEnv),
                        isMine = c.getInt(iMine) == 1,
                        status = c.getInt(iStatus),
                        advertState = c.getInt(iAdvert),
                    ),
                )
            }
        }
        return out
    }

    // --------------------------------------------------------------- receipt

    /** Returns true when this is a receipt we had not already recorded. */
    fun addReceipt(msgId: ByteArray, keyId: ByteArray, seenAt: Long, kind: Int): Boolean =
        synchronized(lock) {
            val rowId = db.insertWithOnConflict(
                "receipt",
                null,
                ContentValues().apply {
                    put("msg_id", msgId)
                    put("key_id", keyId)
                    put("seen_at", seenAt)
                    put("kind", kind)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            rowId != -1L
        }

    /** Distinct devices that reported carrying this message. */
    fun carrierCount(msgId: ByteArray): Int = db.rawQuery(
        "SELECT COUNT(DISTINCT key_id) FROM receipt WHERE msg_id = x'${Codec.hex(msgId)}'",
        null,
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun hasDeliveryReceipt(msgId: ByteArray): Boolean = db.rawQuery(
        "SELECT 1 FROM receipt WHERE msg_id = x'${Codec.hex(msgId)}' AND kind = 1",
        null,
    ).use { it.moveToFirst() }

    fun hasAnyReceipt(msgId: ByteArray): Boolean = db.rawQuery(
        "SELECT 1 FROM receipt WHERE msg_id = x'${Codec.hex(msgId)}'",
        null,
    ).use { it.moveToFirst() }

    // ------------------------------------------------------------------ peer

    fun touchPeer(keyId: ByteArray, nowMs: Long, platform: Int = 1) = synchronized(lock) {
        db.insertWithOnConflict(
            "peer",
            null,
            ContentValues().apply {
                put("key_id", keyId)
                put("last_seen", nowMs)
                put("platform", platform)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Distinct `origin_key_id`s heard in the last [windowMs]. A local estimate. */
    fun neighbourCount(nowMs: Long, windowMs: Long = 60_000L): Int = db.rawQuery(
        "SELECT COUNT(*) FROM peer WHERE last_seen >= ?",
        arrayOf((nowMs - windowMs).toString()),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun purgePeers(nowMs: Long, maxAgeMs: Long = 24 * 3_600_000L) = synchronized(lock) {
        db.delete("peer", "last_seen < ?", arrayOf((nowMs - maxAgeMs).toString()))
    }

    // ---------------------------------------------------- reaping + eviction

    /**
     * Marks expired messages EXPIRED and deletes them, with one exception from
     * docs/01-architecture.md: never delete a tier-0 message that has no
     * delivery receipt. Such a message is marked EXPIRED and kept.
     */
    fun reapExpired(nowMs: Long): Int = synchronized(lock) {
        var removed = 0
        for (m in query("SELECT * FROM message WHERE status != ${Status.EXPIRED}")) {
            if (nowMs < m.expiresAt()) continue
            db.update(
                "message",
                ContentValues().apply {
                    put("status", Status.EXPIRED)
                    put("advert_state", AdvertState.DONE)
                },
                "msg_id = x'${m.idHex}'",
                null,
            )
            if (m.tier == 0 && !hasDeliveryReceipt(m.msgId)) continue
            db.delete("message", "msg_id = x'${m.idHex}'", null)
            removed++
        }
        if (removed > 0) Log.i(TAG, "reaped $removed expired messages")
        removed
    }

    /**
     * Storage cap. Evict by tier descending, then oldest first. Never a tier-0
     * message lacking a receipt, and never one of ours.
     */
    fun evictIfFull(cap: Int = STORE_CAP): Int = synchronized(lock) {
        var over = countAll() - cap
        if (over <= 0) return 0
        var removed = 0
        val candidates = query(
            "SELECT * FROM message WHERE is_mine = 0 ORDER BY tier DESC, created_at ASC",
        )
        for (m in candidates) {
            if (over <= 0) break
            if (m.tier == 0 && !hasAnyReceipt(m.msgId)) continue
            db.delete("message", "msg_id = x'${m.idHex}'", null)
            over--
            removed++
        }
        if (removed > 0) Log.i(TAG, "evicted $removed messages to stay under $cap")
        removed
    }

    fun close() = helper.close()

    companion object {
        private const val TAG = "SetuStore"
        const val STORE_CAP = 2000
    }
}
