package `in`.setu.relay.wire

/**
 * What is actually wrong, attached to an SOS.
 *
 * ### Why the button did not change
 *
 * The SOS beacon stays exactly what it was: hold two seconds, 142 bytes, gone.
 * Someone in water does not get to fill in a form, and making them would be the
 * worst possible trade. So detail is **optional and additive** — the call goes
 * out first, and anything typed afterwards follows it.
 *
 * ### Why it cannot ride the beacon
 *
 * `sealed_body` is 54 bytes and the sealed-box overhead is 48, leaving exactly
 * six bytes of plaintext — already fully spent on latitude and longitude. There
 * is not one spare byte in the envelope, and growing it is forbidden (D5). So
 * detail travels on the bulk plane, keyed to the `msg_id` of the SOS it belongs
 * to, and a rescuer sees it appear against the call already on their screen.
 *
 * The practical consequence, which the UI must not hide: **the beacon travels
 * further and faster than the detail.** A rescuer will often have the position
 * before the words. That is the correct priority — where beats why — but it
 * means detail must never be something the caller has to complete for help to
 * come.
 *
 * ### Sealed to the rescuer
 *
 * Unlike a survey record, this is sealed. "Two children trapped upstairs, one
 * unconscious" is exactly the information that makes a household a target, and
 * every relay carrying it is a stranger. Only a rescuer-key holder opens it.
 *
 * ```
 * plaintext, before sealing:
 * 0       u8    format version
 * 1..8    8     msg_id of the SOS this describes
 * 9       u8    category
 * 10      u8    people count, 0xFF for unstated
 * 11      u8    flags: bit0 injured, bit1 trapped, bit2 water rising,
 *                      bit3 needs medicine, bit4 cannot move
 * 12..13  u16   text length, then that many bytes of UTF-8
 * ```
 */
object SosDetail {

    const val VERSION = 1
    const val PROFILE_ID = "sosdetail.v1"

    const val MAX_TEXT_BYTES = 400
    private const val HEADER_SIZE = 14
    const val UNSTATED = 0xFF

    object Need {
        const val INJURED = 1 shl 0
        const val TRAPPED = 1 shl 1
        const val WATER_RISING = 1 shl 2
        const val MEDICINE = 1 shl 3
        const val CANNOT_MOVE = 1 shl 4
        val ALL = listOf(INJURED, TRAPPED, WATER_RISING, MEDICINE, CANNOT_MOVE)
    }

    object Category {
        const val GENERAL = 0
        const val MEDICAL = 1
        const val TRAPPED = 2
        const val FOOD_WATER = 3
        const val SHELTER = 4
        val ALL = listOf(GENERAL, MEDICAL, TRAPPED, FOOD_WATER, SHELTER)
    }

    class Decoded(
        val msgId: ByteArray,
        val category: Int,
        val peopleCount: Int,
        val needs: Int,
        val text: String,
    ) {
        val hasPeopleCount: Boolean get() = peopleCount != UNSTATED
        fun has(need: Int): Boolean = needs and need != 0
    }

    fun encode(msgId: ByteArray, category: Int, peopleCount: Int, needs: Int, text: String): ByteArray {
        require(msgId.size == Proto.LEN_MSG_ID) { "msg_id must be 8 bytes" }
        var encoded = text.toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_TEXT_BYTES) {
            var chars = text.length
            while (chars > 0 &&
                text.substring(0, chars).toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES
            ) {
                chars--
            }
            encoded = text.substring(0, chars).toByteArray(Charsets.UTF_8)
        }
        val out = ByteArray(HEADER_SIZE + encoded.size)
        out[0] = VERSION.toByte()
        msgId.copyInto(out, 1)
        out[9] = category.toByte()
        out[10] = (if (peopleCount in 0..254) peopleCount else UNSTATED).toByte()
        out[11] = needs.toByte()
        Codec.putU16(out, 12, encoded.size)
        encoded.copyInto(out, HEADER_SIZE)
        return out
    }

    fun decodeOrNull(src: ByteArray): Decoded? {
        if (src.size < HEADER_SIZE) return null
        if (src[0].toInt() != VERSION) return null
        val len = Codec.getU16(src, 12)
        if (len > MAX_TEXT_BYTES) return null
        if (src.size != HEADER_SIZE + len) return null
        return Decoded(
            msgId = src.copyOfRange(1, 1 + Proto.LEN_MSG_ID),
            category = src[9].toInt() and 0xFF,
            peopleCount = src[10].toInt() and 0xFF,
            needs = src[11].toInt() and 0xFF,
            text = String(src, HEADER_SIZE, len, Charsets.UTF_8),
        )
    }

    /**
     * A record id derived from the SOS msg_id, so the detail lands beside the
     * call it belongs to and a duplicate push is deduplicated by the existing
     * record machinery rather than needing its own.
     */
    fun recordIdFor(msgId: ByteArray): ByteArray {
        require(msgId.size == Proto.LEN_MSG_ID) { "msg_id must be 8 bytes" }
        val out = ByteArray(16)
        msgId.copyInto(out, 0)
        // Tag the tail so a detail id can never collide with a survey UUID.
        out[8] = 0x5D
        out[9] = 0x71
        return out
    }
}
