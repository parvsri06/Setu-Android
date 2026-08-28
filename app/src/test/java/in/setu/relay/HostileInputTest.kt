package `in`.setu.relay

import `in`.setu.relay.radio.bulk.BulkProto
import `in`.setu.relay.wire.Announcement
import `in`.setu.relay.wire.Envelope
import `in`.setu.relay.wire.HopTrail
import `in`.setu.relay.wire.SosDetail
import `in`.setu.relay.wire.SurveyRecord
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Every decoder here is fed by a stranger's radio.
 *
 * A relay accepts arbitrary bytes from devices it has never met and, because key
 * exchange is phase 5, cannot authenticate most of them. docs/04 is explicit
 * that this is a hostile input surface and that mesh apps have been broken on it
 * before. So the contract for all of these is the same and it is absolute:
 *
 *   - return null, never throw
 *   - never allocate on an attacker's say-so
 *   - never accept a length the header merely claims
 *
 * A thrown exception here is not a cosmetic failure. These run on the scanner
 * callback and inside the GATT server, so an escaped exception takes down the
 * relay for everyone that phone was carrying for.
 */
class HostileInputTest {

    /** Fixed seed: a failure must be reproducible, not a story about one run. */
    private val rng = Random(SEED)

    private fun random(size: Int) = ByteArray(size).also { rng.nextBytes(it) }

    @Test
    fun `every decoder survives random bytes at every plausible length`() {
        val lengths = listOf(0, 1, 2, 7, 8, 15, 16, 27, 53, 54, 141, 142, 143, 259, 400, 1024, 8192)
        var rounds = 0
        for (len in lengths) {
            repeat(120) {
                val junk = random(len)
                // None of these may throw. Returning null is the whole contract.
                Envelope.decodeOrNull(junk)
                SurveyRecord.decodeOrNull(junk)
                HopTrail.decodeOrNull(junk)
                Announcement.decodeOrNull(junk)
                SosDetail.decodeOrNull(junk)
                BulkProto.decodePush(junk)
                BulkProto.decodeDigest(junk)
                rounds++
            }
        }
        assertTrue(rounds > 2000)
    }

    @Test
    fun `a truncated but otherwise valid envelope is refused`() {
        val whole = random(142)
        for (cut in 0 until 142) {
            assertNull("accepted a $cut-byte envelope", Envelope.decodeOrNull(whole.copyOf(cut)))
        }
    }

    @Test
    fun `an over-long envelope is refused rather than silently trimmed`() {
        assertNull(Envelope.decodeOrNull(random(143)))
        assertNull(Envelope.decodeOrNull(random(1024)))
    }

    @Test
    fun `a push frame cannot claim a length it did not send`() {
        // The classic heap-exhaustion shape: a tiny packet declaring a huge body.
        val frame = ByteArray(BulkProto.PUSH_HEADER_SIZE + 4)
        // record_id stays zero; total_len = 2 GB, offset = 0
        frame[16] = 0xFF.toByte(); frame[17] = 0xFF.toByte()
        frame[18] = 0xFF.toByte(); frame[19] = 0x7F.toByte()
        assertNull("accepted a 2 GB claim", BulkProto.decodePush(frame))
    }

    @Test
    fun `a push frame cannot write past the end of the record it declares`() {
        // total_len = 16, offset = 12, but 32 bytes of payload: the copy would
        // run 28 bytes off the end of the reassembly buffer.
        val payload = 32
        val frame = ByteArray(BulkProto.PUSH_HEADER_SIZE + payload)
        frame[16] = 16
        frame[20] = 12
        assertNull("accepted an out-of-bounds write", BulkProto.decodePush(frame))
    }

    @Test
    fun `a push frame cannot use a negative offset`() {
        val frame = ByteArray(BulkProto.PUSH_HEADER_SIZE + 4)
        frame[16] = 64
        // offset = 0xFFFFFFFF, which is -1 as a signed int
        for (i in 20..23) frame[i] = 0xFF.toByte()
        assertNull("accepted a negative offset", BulkProto.decodePush(frame))
    }

    @Test
    fun `a well formed push frame is still accepted`() {
        // The bounds checks must reject hostile input without rejecting real work.
        val id = ByteArray(16) { it.toByte() }
        val data = ByteArray(50) { 7 }
        val ok = BulkProto.decodePush(BulkProto.encodePush(id, 100, 50, data))
        assertNotNull(ok)
        assertTrue(ok!!.recordId.contentEquals(id))
    }

    @Test
    fun `a hop trail cannot declare more hops than the protocol allows`() {
        // Bounded by the hop limit, which is what stops a trail growing forever.
        val frame = ByteArray(HopTrail.HEADER_SIZE)
        frame[0] = HopTrail.VERSION.toByte()
        frame[9] = 0xFF.toByte()     // 255 hops claimed, none supplied
        assertNull(HopTrail.decodeOrNull(frame))
    }

    @Test
    fun `an announcement cannot claim a body longer than the cap`() {
        val frame = ByteArray(36 + Announcement.SIGNATURE_BYTES)
        frame[0] = Announcement.VERSION.toByte()
        frame[34] = 0xFF.toByte(); frame[35] = 0xFF.toByte()   // 65535-byte body
        assertNull(Announcement.decodeOrNull(frame))
    }

    @Test
    fun `an sos detail cannot claim a text longer than the cap`() {
        val frame = ByteArray(14)
        frame[0] = SosDetail.VERSION.toByte()
        frame[12] = 0xFF.toByte(); frame[13] = 0xFF.toByte()
        assertNull(SosDetail.decodeOrNull(frame))
    }

    @Test
    fun `decoders reject a version they do not implement`() {
        // A future or forged version must not be parsed with today's offsets.
        val trail = ByteArray(HopTrail.HEADER_SIZE).also { it[0] = 99 }
        assertNull(HopTrail.decodeOrNull(trail))

        val ann = ByteArray(36 + Announcement.SIGNATURE_BYTES).also { it[0] = 99 }
        assertNull(Announcement.decodeOrNull(ann))

        val detail = ByteArray(14).also { it[0] = 99 }
        assertNull(SosDetail.decodeOrNull(detail))
    }

    private companion object {
        /** Fixed, so a failure is reproducible rather than a story about one run. */
        const val SEED = 0x53455455L
    }
}
