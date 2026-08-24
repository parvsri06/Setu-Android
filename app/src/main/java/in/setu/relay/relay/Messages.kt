package `in`.setu.relay.relay

import `in`.setu.relay.crypto.Digest
import `in`.setu.relay.crypto.Identity
import `in`.setu.relay.crypto.Keys
import `in`.setu.relay.crypto.SealedBox
import `in`.setu.relay.crypto.Signer
import `in`.setu.relay.wire.Bodies
import `in`.setu.relay.wire.Envelope
import `in`.setu.relay.wire.MsgType
import `in`.setu.relay.wire.Proto
import java.security.SecureRandom

/**
 * Builds signed envelopes. The only place in the app that creates a message.
 */
object Messages {

    private val rng = SecureRandom()

    fun newMsgId(): ByteArray = ByteArray(Proto.LEN_MSG_ID).also { rng.nextBytes(it) }

    fun sos(identity: Identity, latDeg: Double, lonDeg: Double, nowSec: Long): Envelope {
        val body = SealedBox.seal(Keys.RESCUER_PUBLIC, Bodies.sosPlaintext(latDeg, lonDeg))
        return sign(identity, MsgType.SOS, body, nowSec)
    }

    /**
     * An SOS fired with no GPS fix. The body is sealed to the same key and holds
     * the same 6 bytes, all zero, which decodes to (-90, -180). The UI must say
     * "no location" rather than show that coordinate — see SosScreen.
     */
    fun sosWithoutFix(identity: Identity, nowSec: Long): Envelope {
        val body = SealedBox.seal(Keys.RESCUER_PUBLIC, ByteArray(Bodies.SEALED_PLAINTEXT_SIZE))
        return sign(identity, MsgType.SOS, body, nowSec)
    }

    fun checkIn(identity: Identity, contactId: String, status: Int, nowSec: Long): Envelope {
        val hash = Digest.sha256(CONTACT_SALT, contactId.trim().lowercase().toByteArray())
        val body = SealedBox.seal(Keys.RESCUER_PUBLIC, Bodies.checkInPlaintext(hash, status))
        return sign(identity, MsgType.CHECK_IN, body, nowSec)
    }

    fun receipt(identity: Identity, refMsgId: ByteArray, kind: Int, nowSec: Long): Envelope =
        sign(identity, MsgType.RECEIPT, Bodies.receiptBody(refMsgId, kind, nowSec), nowSec)

    private fun sign(identity: Identity, type: Int, body: ByteArray, nowSec: Long): Envelope {
        require(body.size == Proto.LEN_SEALED_BODY) {
            "sealed_body must be ${Proto.LEN_SEALED_BODY} bytes, got ${body.size}"
        }
        val unsigned = Envelope(
            version = (Proto.PROTO_VERSION shl 4),
            type = type,
            tier = MsgType.tierOf(type),
            msgId = newMsgId(),
            originKeyId = identity.keyId,
            createdAt = nowSec,
            hopCount = 0,
            ttlHours = MsgType.defaultTtlHours(type),
            sealedBody = body,
            signature = ByteArray(Proto.LEN_SIGNATURE),
        )
        val signature = identity.sign(Envelope.signingBytes(unsigned.encode()))
        return Envelope(
            unsigned.version, unsigned.type, unsigned.tier, unsigned.msgId,
            unsigned.originKeyId, unsigned.createdAt, 0, unsigned.ttlHours,
            unsigned.sealedBody, signature,
        )
    }

    /**
     * Verifies the envelope signature.
     *
     * The signature is made by the origin's Ed25519 key, but the envelope only
     * carries `origin_key_id` — the first 8 bytes of SHA-256 of that key — so a
     * relay that has never met the origin cannot check it. Full verification
     * needs the origin public key, which arrives on the bulk plane (phase 5).
     *
     * Until then a relay does what it can and says so honestly: structural
     * validation plus the key-id binding. This function returns the verified
     * result when a public key is known and [VerifyResult.NO_KEY] otherwise.
     * Nothing in the UI presents a NO_KEY message as verified.
     */
    fun verify(envelope: Envelope, encoded: ByteArray, originPublicKey: ByteArray?): VerifyResult {
        if (originPublicKey == null) return VerifyResult.NO_KEY
        if (!Digest.keyId(originPublicKey).contentEquals(envelope.originKeyId)) {
            return VerifyResult.BAD_KEY_ID
        }
        val ok = Signer.verify(
            originPublicKey,
            Envelope.signingBytes(encoded),
            envelope.signature,
        )
        return if (ok) VerifyResult.VALID else VerifyResult.INVALID
    }

    enum class VerifyResult { VALID, INVALID, BAD_KEY_ID, NO_KEY }

    private val CONTACT_SALT = "setu-contact-v1".toByteArray(Charsets.US_ASCII)
}
