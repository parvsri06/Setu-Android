package `in`.setu.relay.wire

/**
 * Plaintext layouts that go inside `sealed_body`. See docs/02-wire-protocol.md.
 *
 * `sealed_body` is fixed at 54 bytes and the sealed-box overhead is 48 bytes
 * (32-byte ephemeral public key + 16-byte Poly1305 tag), so a sealed plaintext
 * is exactly [SEALED_PLAINTEXT_SIZE] = 6 bytes.
 */
object Bodies {

    const val SEALED_PLAINTEXT_SIZE = 6

    // ------------------------------------------------------------------ SOS

    /** 3-byte latitude + 3-byte longitude, sealed to the rescuer key. */
    fun sosPlaintext(latDeg: Double, lonDeg: Double): ByteArray = GeoQuant.encode(latDeg, lonDeg)

    fun readSosPlaintext(p: ByteArray): Pair<Double, Double> {
        require(p.size == SEALED_PLAINTEXT_SIZE) { "SOS body must be 6 bytes" }
        return GeoQuant.decode(p)
    }

    // ------------------------------------------------------------- CHECK_IN

    const val CONTACT_HASH_BYTES = 5

    const val STATUS_SAFE = 1
    const val STATUS_NEED_HELP = 2
    const val STATUS_MOVING = 3

    /**
     * 5-byte salted contact hash + 1-byte status.
     *
     * DEVIATION FROM THE SPEC TABLE, recorded as D14 in MEMORY.md. The spec says
     * check-in carries "a 16-byte salted hash of the contact identifier plus a
     * 1-byte status, padded", but 17 bytes of plaintext would make `sealed_body`
     * 65 bytes and the envelope 153. The envelope size is a hard rule, so the
     * hash is truncated to its first 5 bytes. The backend matches it against a
     * bounded candidate list, so 40 bits is sufficient for lookup; it is not
     * relied on for authentication, which the envelope signature provides.
     */
    fun checkInPlaintext(contactHash: ByteArray, status: Int): ByteArray {
        require(contactHash.size >= CONTACT_HASH_BYTES) { "contact hash too short" }
        val out = ByteArray(SEALED_PLAINTEXT_SIZE)
        contactHash.copyInto(out, 0, 0, CONTACT_HASH_BYTES)
        out[CONTACT_HASH_BYTES] = status.toByte()
        return out
    }

    fun readCheckInStatus(p: ByteArray): Int = p[CONTACT_HASH_BYTES].toInt() and 0xFF

    // -------------------------------------------------------------- RECEIPT

    const val RECEIPT_CARRIED = 0
    const val RECEIPT_DELIVERED = 1

    /**
     * Receipts are not sealed. A receipt says "message X was observed", which
     * carries no location and no personal data, and relays need to read it to
     * recognise a receipt for a message they originated. The body occupies the
     * same fixed 54-byte field, zero-padded.
     *
     *     0..7   referenced msg_id
     *     8      kind: 0 carried, 1 delivered to backend
     *     9..12  observed_at, unix seconds, untrusted like every device clock
     *     13..53 zero padding
     */
    fun receiptBody(refMsgId: ByteArray, kind: Int, observedAt: Long): ByteArray {
        require(refMsgId.size == Proto.LEN_MSG_ID) { "ref msg_id must be 8 bytes" }
        val out = ByteArray(Proto.LEN_SEALED_BODY)
        refMsgId.copyInto(out, 0)
        out[8] = kind.toByte()
        Codec.putU32(out, 9, observedAt)
        return out
    }

    fun receiptRefMsgId(body: ByteArray): ByteArray = body.copyOfRange(0, Proto.LEN_MSG_ID)

    fun receiptKind(body: ByteArray): Int = body[8].toInt() and 0xFF
}
