package `in`.setu.relay.wire

/**
 * The 142-byte beacon envelope. See docs/02-wire-protocol.md.
 *
 * Everything except [hopCount] is immutable end to end. A relay that alters any
 * other byte breaks the signature and the next hop drops the message.
 */
class Envelope(
    /** high nibble = proto version, low nibble = flags */
    val version: Int,
    val type: Int,
    val tier: Int,
    val msgId: ByteArray,
    val originKeyId: ByteArray,
    /** unix seconds, taken from the origin device clock. Untrusted. */
    val createdAt: Long,
    val hopCount: Int,
    val ttlHours: Int,
    val sealedBody: ByteArray,
    val signature: ByteArray,
) {
    init {
        require(msgId.size == Proto.LEN_MSG_ID) { "msgId must be 8 bytes" }
        require(originKeyId.size == Proto.LEN_ORIGIN_KEY_ID) { "originKeyId must be 8 bytes" }
        require(sealedBody.size == Proto.LEN_SEALED_BODY) { "sealedBody must be 54 bytes" }
        require(signature.size == Proto.LEN_SIGNATURE) { "signature must be 64 bytes" }
    }

    val protoVersion: Int get() = (version ushr 4) and 0xF
    val flags: Int get() = version and 0xF

    fun encode(): ByteArray {
        val out = ByteArray(Proto.ENVELOPE_SIZE)
        out[Proto.OFF_VERSION] = version.toByte()
        out[Proto.OFF_TYPE_PRIORITY] = (((type and 0xF) shl 4) or (tier and 0xF)).toByte()
        msgId.copyInto(out, Proto.OFF_MSG_ID)
        originKeyId.copyInto(out, Proto.OFF_ORIGIN_KEY_ID)
        Codec.putU32(out, Proto.OFF_CREATED_AT, createdAt)
        out[Proto.OFF_HOP_COUNT] = hopCount.toByte()
        out[Proto.OFF_TTL_HOURS] = ttlHours.toByte()
        sealedBody.copyInto(out, Proto.OFF_SEALED_BODY)
        signature.copyInto(out, Proto.OFF_SIGNATURE)
        return out
    }

    fun withHopCount(n: Int): Envelope = Envelope(
        version, type, tier, msgId, originKeyId, createdAt,
        n.coerceIn(0, 255), ttlHours, sealedBody, signature,
    )

    /** Milliseconds this message stays alive from its claimed creation time. */
    val ttlMillis: Long get() = ttlHours.toLong() * 3_600_000L

    override fun toString(): String =
        "Envelope(${MsgType.name(type)} tier=$tier id=${Codec.hex(msgId)} " +
            "hop=$hopCount ttl=${ttlHours}h)"

    companion object {

        /**
         * Bytes 0..77 with `hop_count` zeroed. This is what the origin signs and
         * what every receiver verifies. Repeaters mutate `hop_count`, so it is
         * excluded; everything else is covered.
         */
        fun signingBytes(encoded: ByteArray): ByteArray {
            require(encoded.size >= Proto.SIGNED_PREFIX) { "buffer too short" }
            val out = encoded.copyOfRange(0, Proto.SIGNED_PREFIX)
            out[Proto.OFF_HOP_COUNT] = 0
            return out
        }

        fun decode(b: ByteArray): Envelope {
            if (b.size != Proto.ENVELOPE_SIZE) {
                throw WireFormatException("envelope must be exactly ${Proto.ENVELOPE_SIZE} bytes, got ${b.size}")
            }
            val version = b[Proto.OFF_VERSION].toInt() and 0xFF
            val proto = (version ushr 4) and 0xF
            if (proto != Proto.PROTO_VERSION) {
                throw WireFormatException("unsupported proto version $proto")
            }
            val tp = b[Proto.OFF_TYPE_PRIORITY].toInt() and 0xFF
            val type = (tp ushr 4) and 0xF
            val tier = tp and 0xF
            if (type < MsgType.SOS || type > MsgType.PROFILE_REF) {
                throw WireFormatException("unknown message type $type")
            }
            if (tier != MsgType.tierOf(type)) {
                throw WireFormatException("tier $tier does not match type $type")
            }
            val hop = b[Proto.OFF_HOP_COUNT].toInt() and 0xFF
            val ttl = b[Proto.OFF_TTL_HOURS].toInt() and 0xFF
            if (ttl == 0) throw WireFormatException("ttl_hours must be > 0")
            return Envelope(
                version = version,
                type = type,
                tier = tier,
                msgId = b.copyOfRange(Proto.OFF_MSG_ID, Proto.OFF_MSG_ID + Proto.LEN_MSG_ID),
                originKeyId = b.copyOfRange(
                    Proto.OFF_ORIGIN_KEY_ID,
                    Proto.OFF_ORIGIN_KEY_ID + Proto.LEN_ORIGIN_KEY_ID,
                ),
                createdAt = Codec.getU32(b, Proto.OFF_CREATED_AT),
                hopCount = hop,
                ttlHours = ttl,
                sealedBody = b.copyOfRange(
                    Proto.OFF_SEALED_BODY,
                    Proto.OFF_SEALED_BODY + Proto.LEN_SEALED_BODY,
                ),
                signature = b.copyOfRange(
                    Proto.OFF_SIGNATURE,
                    Proto.OFF_SIGNATURE + Proto.LEN_SIGNATURE,
                ),
            )
        }

        /** Decode without throwing. Returns null on any malformed input. */
        fun decodeOrNull(b: ByteArray): Envelope? = try {
            decode(b)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }
}
