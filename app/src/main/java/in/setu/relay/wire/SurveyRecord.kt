package `in`.setu.relay.wire

import `in`.setu.relay.store.Person
import `in`.setu.relay.store.Survey

/**
 * The part of a survey that travels over the mesh.
 *
 * ### Why this is not the whole survey
 *
 * A beacon carries **6 bytes** of plaintext (`sealed_body` is 54 bytes, of which
 * 48 is sealed-box overhead), and growing the 142-byte envelope is forbidden —
 * collision loss is exponential in packet size, D5. So a survey can never ride
 * the beacon plane. It rides the **bulk plane** over GATT as a `SURVEY_REF`
 * pointer plus this record, which is why phase 5 is what makes surveys relay at
 * all.
 *
 * Even there, photos and damage description are excluded on purpose. Photo bytes
 * on a radio that also carries SOS traffic would starve the thing that saves
 * lives to move something that can wait for a tower. Those fields go over the
 * internet instead — the transport split the user asked for.
 *
 * ### Encoding
 *
 * Hand-rolled, little-endian, length-prefixed UTF-8, on [Codec]. No JSON and no
 * serialization library, per the dependency allow-list in `CLAUDE.md`; field
 * names would cost more than the values.
 *
 * ```
 * 0       u8    format version
 * 1..16   16    survey_id (UUID bytes)
 * 17..20  u32   created_at, unix seconds
 * 21      u8    flags: bit0 is_proxy, bit1 proxy_consent
 * 22      u16   aadhaar_sealed length, then that many bytes
 * ...     str   name, father_name, mobile, family_id, aadhaar_last4
 * ...     str   village, district, post_office, police_station, pin
 * ...     u8    person count, then per person:
 *                 u8 status, u8 gender, u8 age, str name, str location
 * ```
 *
 * `str` is a u16 byte length followed by UTF-8.
 *
 * The Aadhaar travels as the **already-sealed** blob from the database rather
 * than as digits. The device never holds the plaintext, so it could not include
 * it even if that were wanted; the backend opens the outer record seal and then
 * this inner one. It costs 48 bytes and means a compromised relay — or a
 * compromised backend *record* key alone — still learns nobody's Aadhaar.
 */
object SurveyRecord {

    const val VERSION = 1

    /** The profile this record is packed against, stored beside it in `record`. */
    const val PROFILE_ID = "survey.assam.v1"

    /** Long enough for a real name in Assamese at 3 bytes per character. */
    const val MAX_FIELD_BYTES = 200
    const val MAX_PEOPLE = 32

    fun encode(survey: Survey): ByteArray {
        val w = Writer()
        w.u8(VERSION)
        w.bytes(uuidBytes(survey.surveyId))
        w.u32(survey.createdAt / 1000L)
        w.u8((if (survey.isProxy) 1 else 0) or (if (survey.proxyConsent) 2 else 0))

        val sealed = survey.aadhaarSealed ?: ByteArray(0)
        w.u16(sealed.size)
        w.bytes(sealed)

        w.str(survey.name)
        w.str(survey.fatherName)
        w.str(survey.mobile)
        w.str(survey.familyId)
        w.str(survey.aadhaarLast4)

        w.str(survey.village)
        w.str(survey.district)
        w.str(survey.postOffice)
        w.str(survey.policeStation)
        w.str(survey.pin)

        val people = survey.people.take(MAX_PEOPLE)
        w.u8(people.size)
        for (p in people) {
            // -1 means "not answered"; on the wire that is 0xFF, so a reader can
            // tell an unanswered status from a deliberate "alive".
            w.u8(if (p.status < 0) 0xFF else p.status)
            w.u8(if (p.gender < 0) 0xFF else p.gender)
            w.u8(if (p.age < 0 || p.age > 254) 0xFF else p.age)
            w.str(p.name)
            w.str(p.location)
        }
        return w.toByteArray()
    }

    /** Returns null on anything malformed. A relayed record is hostile input. */
    fun decodeOrNull(src: ByteArray): Decoded? {
        return try {
            val r = Reader(src)
            val version = r.u8()
            if (version != VERSION) return null
            val surveyId = uuidString(r.bytes(16))
            val createdAt = r.u32()
            val flags = r.u8()
            val sealedLen = r.u16()
            if (sealedLen > MAX_FIELD_BYTES + 64) return null
            val aadhaarSealed = r.bytes(sealedLen)

            val name = r.str()
            val fatherName = r.str()
            val mobile = r.str()
            val familyId = r.str()
            val last4 = r.str()

            val village = r.str()
            val district = r.str()
            val postOffice = r.str()
            val policeStation = r.str()
            val pin = r.str()

            val count = r.u8()
            if (count > MAX_PEOPLE) return null
            val people = buildList {
                repeat(count) { i ->
                    val status = r.u8().let { if (it == 0xFF) -1 else it }
                    val gender = r.u8().let { if (it == 0xFF) -1 else it }
                    val age = r.u8().let { if (it == 0xFF) -1 else it }
                    add(
                        Person(
                            personId = "$surveyId#$i",
                            surveyId = surveyId,
                            ordinal = i,
                            name = r.str(),
                            age = age,
                            gender = gender,
                            status = status,
                            location = r.str(),
                        ),
                    )
                }
            }
            if (!r.atEnd()) return null
            Decoded(
                surveyId = surveyId,
                createdAt = createdAt * 1000L,
                isProxy = flags and 1 != 0,
                proxyConsent = flags and 2 != 0,
                aadhaarSealed = aadhaarSealed,
                aadhaarLast4 = last4,
                name = name,
                fatherName = fatherName,
                mobile = mobile,
                familyId = familyId,
                village = village,
                district = district,
                postOffice = postOffice,
                policeStation = policeStation,
                pin = pin,
                people = people,
            )
        } catch (e: IndexOutOfBoundsException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    data class Decoded(
        val surveyId: String,
        val createdAt: Long,
        val isProxy: Boolean,
        val proxyConsent: Boolean,
        val aadhaarSealed: ByteArray,
        val aadhaarLast4: String,
        val name: String,
        val fatherName: String,
        val mobile: String,
        val familyId: String,
        val village: String,
        val district: String,
        val postOffice: String,
        val policeStation: String,
        val pin: String,
        val people: List<Person>,
    ) {
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
        private var buf = ByteArray(512)
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

        fun bytes(b: ByteArray) {
            need(b.size); b.copyInto(buf, len); len += b.size
        }

        fun str(s: String) {
            // Truncate on a byte boundary that is also a character boundary, so a
            // clipped Assamese name is still valid UTF-8 rather than a mojibake tail.
            var encoded = s.toByteArray(Charsets.UTF_8)
            if (encoded.size > MAX_FIELD_BYTES) {
                var chars = s.length
                while (chars > 0 && s.substring(0, chars).toByteArray(Charsets.UTF_8).size > MAX_FIELD_BYTES) {
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
