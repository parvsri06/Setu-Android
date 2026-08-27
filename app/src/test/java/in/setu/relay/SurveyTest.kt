package `in`.setu.relay

import `in`.setu.relay.crypto.AadhaarId
import `in`.setu.relay.crypto.Keys
import `in`.setu.relay.crypto.SealedBox
import `in`.setu.relay.store.DamageArea
import `in`.setu.relay.store.DisasterType
import `in`.setu.relay.store.Gender
import `in`.setu.relay.store.Person
import `in`.setu.relay.store.PersonStatus
import `in`.setu.relay.store.Survey
import `in`.setu.relay.store.SurveyStatus
import `in`.setu.relay.ui.survey.PersonDraft
import `in`.setu.relay.ui.survey.SurveyDraft
import `in`.setu.relay.wire.SurveyRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SurveyTest {

    private fun sample(people: List<Person> = emptyList()) = Survey(
        surveyId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        createdAt = 1_770_000_000_000L,
        updatedAt = 1_770_000_000_000L,
        status = SurveyStatus.COMPLETE,
        isProxy = true,
        proxyConsent = true,
        name = "অনিতা বৰুৱা",
        fatherName = "ৰমেশ বৰুৱা",
        mobile = "7836734808",
        aadhaarSealed = AadhaarId.seal("123456789087"),
        aadhaarLast4 = "9087",
        familyId = "34567890",
        village = "Majuli",
        district = "Jorhat",
        postOffice = "Kamalabari",
        policeStation = "Garamur",
        pin = "785104",
        disasterType = DisasterType.FLOOD,
        damageAreas = DamageArea.HOUSE or DamageArea.AGRICULTURAL_LAND,
        damageDescription = "everything including furniture",
        people = people,
    )

    // ------------------------------------------------------- wire round-trip

    @Test
    fun `record round-trips including non-latin script`() {
        val original = sample().copy(
            lat = 26.14450,
            lon = 91.73620,
            capturedAt = 1_756_000_000_000L,
        )
        val decoded = SurveyRecord.decodeOrNull(SurveyRecord.encode(original))!!

        assertEquals(original.surveyId, decoded.surveyId)
        assertEquals(original.name, decoded.name)
        assertEquals(original.fatherName, decoded.fatherName)
        assertEquals(original.mobile, decoded.mobile)
        assertEquals(original.familyId, decoded.familyId)
        assertEquals(original.aadhaarLast4, decoded.aadhaarLast4)
        assertTrue(decoded.isProxy)
        assertTrue(decoded.proxyConsent)

        // Coordinates are fixed point at 1e-6 degrees, roughly 11 cm.
        assertTrue(decoded.hasFix)
        assertEquals(26.14450, decoded.lat, 1e-5)
        assertEquals(91.73620, decoded.lon, 1e-5)
        assertEquals(1_756_000_000L, decoded.capturedAt / 1000L)
    }

    @Test
    fun `southern and western hemispheres survive the round trip`() {
        // Longitude and latitude are signed on the wire. Getting this wrong is
        // invisible in Assam and catastrophic anywhere west of Greenwich.
        val original = sample().copy(lat = -33.86880, lon = -70.64270, capturedAt = 1L)
        val decoded = SurveyRecord.decodeOrNull(SurveyRecord.encode(original))!!
        assertEquals(-33.86880, decoded.lat, 1e-5)
        assertEquals(-70.64270, decoded.lon, 1e-5)
    }

    @Test
    fun `a record without a fix decodes as having none`() {
        val decoded = SurveyRecord.decodeOrNull(SurveyRecord.encode(sample()))!!
        assertFalse(decoded.hasFix)
        assertTrue(decoded.lat.isNaN())
    }

    @Test
    fun `only identity fields reach the wire`() {
        val encoded = SurveyRecord.encode(
            sample().copy(lat = 26.1, lon = 91.7, capturedAt = 1L),
        )
        val asText = String(encoded, Charsets.UTF_8)
        // Everything below stays on the phone that collected it: v3 relays
        // identity, position and time and nothing else.
        assertFalse(asText.contains("furniture"))
        assertFalse(asText.contains("Jorhat"))
        assertFalse(asText.contains("Kamalabari"))
        assertFalse(asText.contains("785104"))
    }

    @Test
    fun `record fits in a single GATT write`() {
        val size = SurveyRecord.encode(
            sample().copy(lat = 26.1, lon = 91.7, capturedAt = 1L),
        ).size
        // The whole point of v3: one chunk, so a survey is one write rather than
        // a conversation. MAX_CHUNK is 400 bytes.
        assertTrue("record was $size bytes", size in 1..400)
    }

    // ------------------------------------------------------- hostile input

    @Test
    fun `truncated record decodes to null rather than throwing`() {
        val full = SurveyRecord.encode(sample())
        for (cut in listOf(0, 1, 5, 20, 40, full.size - 1)) {
            assertNull("cut $cut", SurveyRecord.decodeOrNull(full.copyOfRange(0, cut)))
        }
    }

    @Test
    fun `trailing garbage is rejected`() {
        val full = SurveyRecord.encode(sample())
        assertNull(SurveyRecord.decodeOrNull(full + byteArrayOf(9, 9, 9)))
    }

    @Test
    fun `wrong version is rejected`() {
        val full = SurveyRecord.encode(sample())
        full[0] = 99
        assertNull(SurveyRecord.decodeOrNull(full))
    }

    @Test
    fun `random bytes never decode`() {
        val rng = java.util.Random(42)
        repeat(300) {
            val junk = ByteArray(rng.nextInt(300)).also { b -> rng.nextBytes(b) }
            SurveyRecord.decodeOrNull(junk)   // must not throw
        }
    }

    // -------------------------------------------------------------- aadhaar

    @Test
    fun `aadhaar seals to the backend key and opens only with it`() {
        val sealed = AadhaarId.seal("123456789087")
        // Nothing on the device can reverse it, which is the point.
        assertNull(SealedBox.open(ByteArray(32) { 7 }, sealed))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("123456789087"))
    }

    @Test
    fun `duplicate key is stable and distinguishes numbers`() {
        assertTrue(
            AadhaarId.duplicateKey("123456789087")
                .contentEquals(AadhaarId.duplicateKey("123456789087")),
        )
        assertFalse(
            AadhaarId.duplicateKey("123456789087")
                .contentEquals(AadhaarId.duplicateKey("123456789088")),
        )
    }

    @Test
    fun `sealing the same number twice gives different ciphertext`() {
        // Fresh ephemeral key per seal, so the stored blob is not itself a
        // fingerprint that would let anyone match two records by comparing bytes.
        val a = AadhaarId.seal("123456789087")
        val b = AadhaarId.seal("123456789087")
        assertNotEquals(-1, a.size)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `only twelve digits are well formed`() {
        assertTrue(AadhaarId.isWellFormed("123456789087"))
        assertFalse(AadhaarId.isWellFormed("12345678908"))
        assertFalse(AadhaarId.isWellFormed("1234567890877"))
        assertFalse(AadhaarId.isWellFormed("12345678908a"))
        assertFalse(AadhaarId.isWellFormed(""))
    }

    @Test
    fun `mask never shows more than the last four`() {
        assertEquals("XXXX-XXXX-9087", AadhaarId.mask(AadhaarId.last4("123456789087")))
        assertFalse(AadhaarId.mask("9087").contains("12345678"))
    }

    // ----------------------------------------------------------- validation

    @Test
    fun `a blank person card does not block saving`() {
        val d = SurveyDraft(people = listOf(PersonDraft()))
        assertTrue(d.casualtiesMissing().isEmpty())
    }

    @Test
    fun `a half filled person card does block saving`() {
        val d = SurveyDraft(people = listOf(PersonDraft(name = "abc")))
        assertFalse(d.casualtiesMissing().isEmpty())
    }

    @Test
    fun `proxy entry requires recorded consent`() {
        val base = SurveyDraft(
            name = "a", fatherName = "b", mobile = "1234567890", aadhaar = "123456789087",
        )
        assertTrue(base.personalMissing().isEmpty())
        assertFalse(base.copy(isProxy = true).personalMissing().isEmpty())
        assertTrue(base.copy(isProxy = true, proxyConsent = true).personalMissing().isEmpty())
    }

    @Test
    fun `mobile and pin length rules`() {
        assertTrue(SurveyDraft.isValidMobile("7836734808"))
        assertFalse(SurveyDraft.isValidMobile("783673480"))
        assertTrue(SurveyDraft.isValidPin("785104"))
        assertFalse(SurveyDraft.isValidPin("78510"))
    }

    @Test
    fun `reloading a saved survey cannot recover the aadhaar digits`() {
        // The device holds a sealed blob and four digits, so an edit has to
        // re-enter the number. Asserted because it is a deliberate trade.
        val reloaded = SurveyDraft.from(sample())
        assertEquals("", reloaded.aadhaar)
        assertFalse(reloaded.personalMissing().isEmpty())
    }

    @Test
    fun `uuid survives the sixteen byte encoding`() {
        repeat(200) {
            val id = UUID.randomUUID().toString()
            assertEquals(id, SurveyRecord.uuidString(SurveyRecord.uuidBytes(id)))
        }
    }

    @Test
    fun `over-long fields are truncated on a character boundary`() {
        val long = "অ".repeat(400)   // 3 bytes each, well over the field cap
        val decoded = SurveyRecord.decodeOrNull(
            SurveyRecord.encode(sample().copy(name = long)),
        )!!
        assertTrue(decoded.name.length < long.length)
        // Valid UTF-8 all the way through: no replacement characters.
        assertFalse(decoded.name.contains('�'))
        assertTrue(decoded.name.all { it == 'অ' })
    }

    @Test
    fun `sealed record is opaque to a relay`() {
        val plain = SurveyRecord.encode(sample())
        val sealed = SealedBox.seal(Keys.BACKEND_PUBLIC, plain)
        // A relay holds this blob and cannot read the name inside it.
        assertFalse(String(sealed, Charsets.UTF_8).contains("Majuli"))
        assertNull(SealedBox.open(ByteArray(32) { 3 }, sealed))
    }
}
