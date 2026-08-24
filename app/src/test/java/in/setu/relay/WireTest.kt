package `in`.setu.relay

import `in`.setu.relay.crypto.Digest
import `in`.setu.relay.crypto.Ed25519
import `in`.setu.relay.wire.Codec
import `in`.setu.relay.wire.Envelope
import `in`.setu.relay.wire.MsgType
import `in`.setu.relay.wire.Proto
import `in`.setu.relay.wire.WireFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** docs/09-test-plan.md — unit tests for `wire/`. */
class WireTest {

    private val seed = ByteArray(32) { (it * 7 + 1).toByte() }
    private val pub = Ed25519.publicKeyFromSeed(seed)

    private fun signedEnvelope(type: Int = MsgType.SOS, hop: Int = 0): Pair<Envelope, ByteArray> {
        val body = ByteArray(Proto.LEN_SEALED_BODY) { (it * 3).toByte() }
        val unsigned = Envelope(
            version = Proto.PROTO_VERSION shl 4,
            type = type,
            tier = MsgType.tierOf(type),
            msgId = ByteArray(8) { (it + 1).toByte() },
            originKeyId = Digest.keyId(pub),
            createdAt = 1_770_000_000L,
            hopCount = hop,
            ttlHours = MsgType.defaultTtlHours(type),
            sealedBody = body,
            signature = ByteArray(64),
        )
        val sig = Ed25519.sign(seed, Envelope.signingBytes(unsigned.encode()))
        val signed = Envelope(
            unsigned.version, unsigned.type, unsigned.tier, unsigned.msgId,
            unsigned.originKeyId, unsigned.createdAt, hop, unsigned.ttlHours,
            unsigned.sealedBody, sig,
        )
        return signed to signed.encode()
    }

    @Test
    fun `encoded length is exactly 142`() {
        val (_, encoded) = signedEnvelope()
        assertEquals(142, encoded.size)
        assertEquals(142, Proto.ENVELOPE_SIZE)
    }

    @Test
    fun `envelope round-trips byte-identically`() {
        val (_, encoded) = signedEnvelope()
        val again = Envelope.decode(encoded).encode()
        assertTrue(Codec.hex(encoded) == Codec.hex(again))
    }

    @Test
    fun `field values survive the round trip`() {
        val (original, encoded) = signedEnvelope(MsgType.CHECK_IN, hop = 5)
        val decoded = Envelope.decode(encoded)
        assertEquals(original.type, decoded.type)
        assertEquals(1, decoded.tier)
        assertEquals(original.createdAt, decoded.createdAt)
        assertEquals(5, decoded.hopCount)
        assertEquals(72, decoded.ttlHours)
        assertTrue(original.msgId.contentEquals(decoded.msgId))
        assertTrue(original.sealedBody.contentEquals(decoded.sealedBody))
        assertTrue(original.signature.contentEquals(decoded.signature))
    }

    @Test
    fun `flipping any byte in the signed prefix fails verification`() {
        val (envelope, encoded) = signedEnvelope()
        assertTrue(Ed25519.verify(pub, Envelope.signingBytes(encoded), envelope.signature))

        for (i in 0 until Proto.SIGNED_PREFIX) {
            if (i == Proto.OFF_HOP_COUNT) continue   // deliberately excluded
            val tampered = encoded.copyOf()
            tampered[i] = (tampered[i].toInt() xor 0x01).toByte()
            val ok = Ed25519.verify(pub, Envelope.signingBytes(tampered), envelope.signature)
            assertFalse("byte $i was not covered by the signature", ok)
        }
    }

    @Test
    fun `mutating hop count alone still verifies`() {
        val (envelope, encoded) = signedEnvelope(hop = 0)
        for (hop in 1..31) {
            val relayed = encoded.copyOf()
            relayed[Proto.OFF_HOP_COUNT] = hop.toByte()
            assertTrue(
                "hop=$hop broke the signature",
                Ed25519.verify(pub, Envelope.signingBytes(relayed), envelope.signature),
            )
        }
    }

    @Test
    fun `signing bytes are the first 78 with hop zeroed`() {
        val (_, encoded) = signedEnvelope(hop = 9)
        val signing = Envelope.signingBytes(encoded)
        assertEquals(78, signing.size)
        assertEquals(0, signing[Proto.OFF_HOP_COUNT].toInt())
    }

    // ------------------------------------------------------------ adversarial

    @Test
    fun `truncated envelope is rejected without crashing`() {
        val (_, encoded) = signedEnvelope()
        assertNull(Envelope.decodeOrNull(encoded.copyOf(100)))
        assertNull(Envelope.decodeOrNull(ByteArray(0)))
    }

    @Test
    fun `over-long envelope is rejected`() {
        val (_, encoded) = signedEnvelope()
        assertNull(Envelope.decodeOrNull(encoded + ByteArray(4)))
    }

    @Test(expected = WireFormatException::class)
    fun `unknown proto version is rejected`() {
        val (_, encoded) = signedEnvelope()
        encoded[Proto.OFF_VERSION] = (9 shl 4).toByte()
        Envelope.decode(encoded)
    }

    @Test
    fun `unknown message type is rejected`() {
        val (_, encoded) = signedEnvelope()
        encoded[Proto.OFF_TYPE_PRIORITY] = ((0xE shl 4) or 4).toByte()
        assertNull(Envelope.decodeOrNull(encoded))
    }

    @Test
    fun `tier that disagrees with type is rejected`() {
        val (_, encoded) = signedEnvelope(MsgType.SOS)
        encoded[Proto.OFF_TYPE_PRIORITY] = ((MsgType.SOS shl 4) or 3).toByte()
        assertNull(Envelope.decodeOrNull(encoded))
    }

    @Test
    fun `zero ttl is rejected`() {
        val (_, encoded) = signedEnvelope()
        encoded[Proto.OFF_TTL_HOURS] = 0
        assertNull(Envelope.decodeOrNull(encoded))
    }

    @Test
    fun `valid signature with garbage body still decodes`() {
        // A relay must store what it cannot read. Only the shape is its business.
        val body = ByteArray(Proto.LEN_SEALED_BODY) { 0xFF.toByte() }
        val e = Envelope(
            Proto.PROTO_VERSION shl 4, MsgType.SOS, 0,
            ByteArray(8) { 2 }, ByteArray(8) { 3 }, 1_770_000_000L, 0, 24,
            body, ByteArray(64) { 4 },
        )
        assertNotNull(Envelope.decodeOrNull(e.encode()))
    }

    // ------------------------------------------------------------- msg_id ids

    @Test
    fun `msg_id collisions stay within birthday bounds over a million ids`() {
        val rng = SecureRandom()
        val seen = HashSet<Long>(1 shl 21)
        var collisions = 0
        val buf = ByteArray(8)
        repeat(1_000_000) {
            rng.nextBytes(buf)
            if (!seen.add(Codec.getU64(buf, 0))) collisions++
        }
        // Expected collisions for 10^6 draws from 2^64 is n^2/2N ~ 2.7e-8.
        // Anything above zero here would mean the generator is not what it claims.
        assertEquals(0, collisions)
    }
}
