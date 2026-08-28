package `in`.setu.relay.radio.bulk

import `in`.setu.relay.wire.Codec
import java.util.UUID

/**
 * The bulk plane — GATT, for records too big for a beacon.
 *
 * A survey is 300–700 bytes sealed. A beacon carries 6 bytes of plaintext and
 * must not grow (D5), so records were never going to ride the beacon plane.
 * They ride a connection instead, which is exactly the two-plane split in
 * docs/01-architecture.md.
 *
 * ### Push, not pull — and why the docs' third characteristic is not here
 *
 * docs/02 sketches three characteristics: DIGEST, REQUEST and PAYLOAD, with each
 * side reading the other's digest and requesting what it lacks. That works for a
 * digest that can be *enumerated*. It does not work for the digest the same doc
 * specifies, which is a **Bloom filter**.
 *
 * A Bloom filter answers exactly one question: *do you have this id?* It cannot
 * be walked to produce "here is what I hold", so a peer cannot look at it and
 * discover what it is missing. Pull is unimplementable on a Bloom digest.
 *
 * Push is, and it needs one characteristic instead of two:
 *
 * ```
 * client reads DIGEST      -> Bloom of every record the server holds
 * client tests its own ids -> the ones the Bloom says no to are the gap
 * client writes PUSH       -> those records, chunked
 * ```
 *
 * Both devices run both roles — everyone advertises connectably and everyone
 * scans — so A pushes to B on one connection and B pushes to A on another. The
 * result is the same reconciliation the docs describe, with less protocol.
 *
 * Recorded as **D31**. The false-positive rate is the cost: ~3% of records are
 * wrongly believed present and skipped, and the next contact carries them
 * instead, which docs/02 already accepts.
 *
 * ### The service UUID
 *
 * docs/02 asks for the full 128-bit UUID to be allocated once and written down.
 * This is that allocation. iOS must declare the service UUID in `Info.plist` to
 * scan for it in the background, so it is load-bearing beyond Android.
 */
object BulkProto {

    val SERVICE_UUID: UUID = UUID.fromString("5e701000-9b2a-4f6d-8c31-2f7a1d0e4b55")

    /** Read. Bloom filter of every `record_id` this device holds. */
    val DIGEST_UUID: UUID = UUID.fromString("5e701001-9b2a-4f6d-8c31-2f7a1d0e4b55")

    /** Write. One chunk of one record. */
    val PUSH_UUID: UUID = UUID.fromString("5e701002-9b2a-4f6d-8c31-2f7a1d0e4b55")

    const val RECORD_ID_SIZE = 16

    // ------------------------------------------------------------- digest

    const val DIGEST_VERSION = 1

    /** `version(1) + count(2) + bloom(256)`. */
    const val DIGEST_SIZE = 3 + Bloom.SIZE_BYTES

    fun encodeDigest(ids: List<ByteArray>): ByteArray {
        val bloom = Bloom()
        for (id in ids) bloom.add(id)
        val out = ByteArray(DIGEST_SIZE)
        out[0] = DIGEST_VERSION.toByte()
        Codec.putU16(out, 1, ids.size.coerceAtMost(0xFFFF))
        bloom.bits.copyInto(out, 3)
        return out
    }

    /** null when the peer is speaking something else. Hostile input. */
    fun decodeDigest(data: ByteArray): Digest? {
        if (data.size != DIGEST_SIZE) return null
        if (data[0].toInt() != DIGEST_VERSION) return null
        return Digest(
            count = Codec.getU16(data, 1),
            bloom = Bloom(data.copyOfRange(3, DIGEST_SIZE)),
        )
    }

    class Digest(val count: Int, val bloom: Bloom)

    // --------------------------------------------------------------- push

    /**
     * `record_id(16) + total_len(4) + offset(4) + data`.
     *
     * The header repeats on every chunk so the server needs no per-connection
     * state machine beyond a reassembly buffer keyed by record id, and a dropped
     * connection mid-record costs only that record.
     */
    const val PUSH_HEADER_SIZE = RECORD_ID_SIZE + 4 + 4

    /**
     * Upper bound on the data in one chunk.
     *
     * 400 rather than the ~490 a 517-byte MTU would allow: some OEM stacks
     * quietly truncate long writes near the limit, and a record arriving
     * silently short would fail to open with no clue why. The cost is one extra
     * round trip per record.
     */
    const val MAX_CHUNK = 400

    /** Records above this are refused rather than buffered. Storage DoS, docs/04. */
    const val MAX_RECORD_SIZE = 16 * 1024

    fun encodePush(recordId: ByteArray, totalLen: Int, offset: Int, data: ByteArray): ByteArray {
        require(recordId.size == RECORD_ID_SIZE) { "record id must be 16 bytes" }
        val out = ByteArray(PUSH_HEADER_SIZE + data.size)
        recordId.copyInto(out, 0)
        Codec.putU32(out, RECORD_ID_SIZE, totalLen.toLong())
        Codec.putU32(out, RECORD_ID_SIZE + 4, offset.toLong())
        data.copyInto(out, PUSH_HEADER_SIZE)
        return out
    }

    fun decodePush(data: ByteArray): Push? {
        if (data.size < PUSH_HEADER_SIZE) return null
        val total = Codec.getU32(data, RECORD_ID_SIZE).toInt()
        val offset = Codec.getU32(data, RECORD_ID_SIZE + 4).toInt()
        if (total < 0 || total > MAX_RECORD_SIZE) return null
        if (offset < 0 || offset > total) return null
        val body = data.copyOfRange(PUSH_HEADER_SIZE, data.size)
        if (offset + body.size > total) return null
        return Push(data.copyOfRange(0, RECORD_ID_SIZE), total, offset, body)
    }

    class Push(
        val recordId: ByteArray,
        val totalLen: Int,
        val offset: Int,
        val data: ByteArray,
    )
}
