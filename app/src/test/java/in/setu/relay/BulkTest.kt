package `in`.setu.relay

import `in`.setu.relay.radio.bulk.Bloom
import `in`.setu.relay.radio.bulk.BulkProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BulkTest {

    private fun id(seed: Long): ByteArray {
        val r = Random(seed)
        return ByteArray(16).also { r.nextBytes(it) }
    }

    // ------------------------------------------------------------- bloom

    @Test
    fun bloomNeverReportsAMemberAsMissing() {
        // The one property the push protocol depends on: no false negatives.
        // A false negative would mean re-sending a record the peer already has,
        // forever, because the filter would keep saying it is absent.
        val bloom = Bloom()
        val members = (0 until 200).map { id(it.toLong()) }
        members.forEach { bloom.add(it) }
        members.forEach { assertTrue("member reported missing", bloom.mightContain(it)) }
    }

    @Test
    fun bloomFalsePositiveRateIsNearTheDesignPoint() {
        // docs/02 sizes this for ~200 records at ~3%. Anything far above that
        // means records are being silently skipped on every contact.
        val bloom = Bloom()
        (0 until 200).forEach { bloom.add(id(it.toLong())) }

        var positives = 0
        val trials = 5_000
        for (i in 0 until trials) {
            if (bloom.mightContain(id(1_000_000L + i))) positives++
        }
        val rate = positives.toDouble() / trials
        assertTrue("false positive rate $rate is too high", rate < 0.08)
    }

    @Test
    fun anEmptyBloomContainsNothing() {
        val bloom = Bloom()
        assertEquals(0, bloom.population())
        assertFalse(bloom.mightContain(id(1)))
    }

    @Test
    fun bloomSurvivesTheDigestRoundTrip() {
        val ids = (0 until 20).map { id(it.toLong()) }
        val encoded = BulkProto.encodeDigest(ids)
        assertEquals(BulkProto.DIGEST_SIZE, encoded.size)

        val digest = BulkProto.decodeDigest(encoded)
        assertNotNull(digest)
        assertEquals(20, digest!!.count)
        ids.forEach { assertTrue(digest.bloom.mightContain(it)) }
    }

    @Test
    fun aDigestOfTheWrongShapeIsRejected() {
        assertNull(BulkProto.decodeDigest(ByteArray(0)))
        assertNull(BulkProto.decodeDigest(ByteArray(BulkProto.DIGEST_SIZE - 1)))
        // Right size, wrong version — a future peer must not be misread.
        val wrongVersion = ByteArray(BulkProto.DIGEST_SIZE).also { it[0] = 9 }
        assertNull(BulkProto.decodeDigest(wrongVersion))
    }

    // -------------------------------------------------------------- push

    @Test
    fun pushFramesRoundTrip() {
        val recordId = id(7)
        val body = ByteArray(300) { (it % 251).toByte() }
        val frame = BulkProto.encodePush(recordId, body.size, 100, body.copyOfRange(100, 250))

        val push = BulkProto.decodePush(frame)
        assertNotNull(push)
        assertTrue(recordId.contentEquals(push!!.recordId))
        assertEquals(300, push.totalLen)
        assertEquals(100, push.offset)
        assertEquals(150, push.data.size)
        assertTrue(body.copyOfRange(100, 250).contentEquals(push.data))
    }

    @Test
    fun chunkingCoversTheWholeRecordExactlyOnce() {
        val body = ByteArray(1000) { it.toByte() }
        val rebuilt = ByteArray(1000)
        var offset = 0
        val chunk = 400
        while (offset < body.size) {
            val end = minOf(offset + chunk, body.size)
            val frame = BulkProto.encodePush(id(1), body.size, offset, body.copyOfRange(offset, end))
            val push = BulkProto.decodePush(frame)!!
            push.data.copyInto(rebuilt, push.offset)
            offset = end
        }
        assertTrue("reassembly differs from the original", body.contentEquals(rebuilt))
    }

    @Test
    fun aPushThatWouldOverrunTheBufferIsRejected() {
        // This is the check that stops a malicious peer writing past the end of
        // a reassembly buffer. offset + data must never exceed total.
        val frame = BulkProto.encodePush(id(3), 100, 90, ByteArray(50))
        assertNull(BulkProto.decodePush(frame))
    }

    @Test
    fun absurdRecordSizesAreRejectedBeforeAllocation() {
        // Without this an attacker allocates 2 GB on a relief worker's phone by
        // claiming a total length and never sending the body.
        val frame = BulkProto.encodePush(id(4), Int.MAX_VALUE, 0, ByteArray(4))
        assertNull(BulkProto.decodePush(frame))
    }

    @Test
    fun aTruncatedPushIsRejected() {
        assertNull(BulkProto.decodePush(ByteArray(BulkProto.PUSH_HEADER_SIZE - 1)))
    }

    @Test
    fun negativeOffsetsAreRejected() {
        val frame = BulkProto.encodePush(id(5), 100, 0, ByteArray(10))
        // Force offset to -1 on the wire.
        for (i in 0 until 4) frame[BulkProto.RECORD_ID_SIZE + 4 + i] = 0xFF.toByte()
        assertNull(BulkProto.decodePush(frame))
    }
}
