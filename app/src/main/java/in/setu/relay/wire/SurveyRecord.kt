package `in`.setu.relay.wire

import `in`.setu.relay.store.Survey

/**
 * The part of a survey that travels over the mesh.
 *
 * ### v3 is deliberately tiny
 *
 * v2 relayed the address block and every affected person as well. On real
 * hardware that pushed a record to roughly 700 bytes, and at a 400-byte chunk
 * with a write-with-response per chunk it needed several round trips per record
 * — noticeably slow when a surveyor is standing next to someone waiting.
 *
 * v3 carries only what identifies a person and where they were found:
 *
 * ```
 * 0       u8    format version
 * 1..16   16    survey_id (UUID bytes)
 * 17..20  u32   captured_at, unix seconds
 * 21      u8    flags: bit0 is_proxy, bit1 proxy_consent, bit2 has GPS fix
 * 22..25  i32   latitude  x 1e6   (absent when bit2 is clear)
 * 26..29  i32   longitude x 1e6
 * 30      u16   aadhaar_sealed length, then that many bytes
 * ...     str   name, father_name, mobile, family_id, aadhaar_last4
 * ```
 *
 * That is one chunk on any phone that negotiates a sane MTU, so a record is a
 * single write instead of a conversation.
 *
 * Damage detail, relief camp, the address block and the affected-person list
 * stay on the phone that collected them. They are not lost — they are simply not
 * radio traffic, which is the whole transport split.
 *
 * ### Who can read this
 *
 * The operational fields relay **in the clear** so any phone can list and show
 * them; the Aadhaar number stays sealed to the backend key inside. See D32. The
 * GPS is in the clear too, which is a real exposure and the price of the
 * "where did this come from" requirement — it is a surveyor's working position,
 * not a vulnerable person's home, and it expires with the record.
 */
object SurveyRecord {

    /** 3 since the record was cut down to identity, position and time. */
    const val VERSION = 3

    /** The profile this record is packed against, stored beside it in `record`. */
    const val PROFILE_ID = "survey.assam.v3"

    /** Long enough for a real name in Assamese at 3 bytes per character. */
    const val MAX_FIELD_BYTES = 120

    private const val FLAG_PROXY = 1
    private const val FLAG_CONSENT = 2
    private const val FLAG_GPS = 4

    /** Fixed-point scale for coordinates: 1e-6 degrees is about 11 cm. */
    private const val COORD_SCALE = 1_000_000.0

    fun encode(survey: Survey): ByteArray {
        val w = Writer()
        w.u8(VERSION)
        w.bytes(uuidBytes(survey.surveyId))

        val captured = if (survey.capturedAt > 0) survey.capturedAt else survey.createdAt
        w.u32(captured / 1000L)

        val hasFix = !survey.lat.isNaN() && !survey.lon.isNaN()
        var flags = 0
        if (survey.isProxy) flags = flags or FLAG_PROXY
        if (survey.proxyConsent) flags = flags or FLAG_CONSENT
        if (hasFix) flags = flags or FLAG_GPS
        w.u8(flags)

        // Written even without a fix so the layout stays fixed-width up to the
        // strings; a reader that skipped four bytes conditionally would be one
        // off-by-one away from misparsing every later field.
        w.i32(if (hasFix) Math.round(survey.lat * COORD_SCALE).toInt() else 0)
        w.i32(if (hasFix) Math.round(survey.lon * COORD_SCALE).toInt() else 0)

        val sealed = survey.aadhaarSealed ?: ByteArray(0)
        w.u16(sealed.size)
        w.bytes(sealed)

        w.str(survey.name)
        w.str(survey.fatherName)
        w.str(survey.mobile)
        w.str(survey.familyId)
        w.str(survey.aadhaarLast4)
        return w.toByteArray()
    }

    /** Returns null on anything malformed. A relayed record is hostile input. */
    fun decodeOrNull(src: ByteArray): Decoded? {
        return try {
            val r = Reader(src)
            if (r.u8() != VERSION) return null
            val surveyId = uuidString(r.bytes(16))
            val captured = r.u32()
            val flags = r.u8()
            val latRaw = r.i32()
            val lonRaw = r.i32()

            val sealedLen = r.u16()
            if (sealedLen > MAX_FIELD_BYTES + 64) return null
            val aadhaarSealed = r.bytes(sealedLen)

            val name = r.str()
            val fatherName = r.str()
            val mobile = r.str()
            val familyId = r.str()
            val last4 = r.str()
            if (!r.atEnd()) return null

            val hasFix = flags and FLAG_GPS != 0
            Decoded(
                surveyId = surveyId,
                capturedAt = captured * 1000L,
                isProxy = flags and FLAG_PROXY != 0,
                proxyConsent = flags and FLAG_CONSENT != 0,
                lat = if (hasFix) latRaw / COORD_SCALE else Double.NaN,
                lon = if (hasFix) lonRaw / COORD_SCALE else Double.NaN,
                aadhaarSealed = aadhaarSealed,
                aadhaarLast4 = last4,
                name = name,
                fatherName = fatherName,
                mobile = mobile,
                familyId = familyId,
            )
        } catch (e: IndexOutOfBoundsException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    class Decoded(
        val surveyId: String,
        val capturedAt: Long,
        val isProxy: Boolean,
        val proxyConsent: Boolean,
        val lat: Double,
        val lon: Double,
        val aadhaarSealed: ByteArray,
        val aadhaarLast4: String,
        val name: String,
        val fatherName: String,
        val mobile: String,
        val familyId: String,
    ) {
        val hasFix: Boolean get() = !lat.isNaN() && !lon.isNaN()
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = surveyId.hashCode()
    }

    // ------------------------------------------------------------------ uuid

    fun uuidBytes(id: String): ByteArray {
        val u = java.util.UUID.fromString(id)
        val out = ByteArray(16)
        Codec.putU64(out, 0, u.mostSignificantBits)
        Codec.putU64(out, 8, u.leastSignificantBits)
        return out
    }

    fun uuidString(b: ByteArray): String =
        java.util.UUID(Codec.getU64(b, 0), Codec.getU64(b, 8)).toString()

    // --------------------------------------------------------------- codecs

    private class Writer {
        private var buf = ByteArray(256)
        private var len = 0

        private fun need(n: Int) {
            if (len + n <= buf.size) return
            var size = buf.size
            while (size < len + n) size *= 2
            buf = buf.copyOf(size)
        }

        fun u8(v: Int) {
            need(1); buf[len++] = (v and 0xFF).toByte()
        }

        fun u16(v: Int) {
            need(2); Codec.putU16(buf, len, v); len += 2
        }

        fun u32(v: Long) {
            need(4); Codec.putU32(buf, len, v); len += 4
        }

        /** Two's complement, so negative latitudes survive the round trip. */
        fun i32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)

        fun bytes(b: ByteArray) {
            need(b.size); b.copyInto(buf, len); len += b.size
        }

        fun str(s: String) {
            // Truncate on a boundary that is also a character boundary, so a
            // clipped Assamese name stays valid UTF-8 rather than a broken tail.
            var encoded = s.toByteArray(Charsets.UTF_8)
            if (encoded.size > MAX_FIELD_BYTES) {
                var chars = s.length
                while (chars > 0 &&
                    s.substring(0, chars).toByteArray(Charsets.UTF_8).size > MAX_FIELD_BYTES
                ) {
                    chars--
                }
                encoded = s.substring(0, chars).toByteArray(Charsets.UTF_8)
            }
            u16(encoded.size)
            bytes(encoded)
        }

        fun toByteArray(): ByteArray = buf.copyOf(len)
    }

    private class Reader(private val src: ByteArray) {
        private var pos = 0

        fun u8(): Int {
            require(pos + 1 <= src.size) { "short read" }
            return src[pos++].toInt() and 0xFF
        }

        fun u16(): Int {
            require(pos + 2 <= src.size) { "short read" }
            return Codec.getU16(src, pos).also { pos += 2 }
        }

        fun u32(): Long {
            require(pos + 4 <= src.size) { "short read" }
            return Codec.getU32(src, pos).also { pos += 4 }
        }

        fun i32(): Int = u32().toInt()

        fun bytes(n: Int): ByteArray {
            require(n >= 0 && pos + n <= src.size) { "short read" }
            return src.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun str(): String {
            val n = u16()
            require(n <= MAX_FIELD_BYTES) { "field too long" }
            return String(bytes(n), Charsets.UTF_8)
        }

        fun atEnd(): Boolean = pos == src.size
    }
}
