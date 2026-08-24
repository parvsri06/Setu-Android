package `in`.setu.relay.crypto

import java.security.SecureRandom

/**
 * Anonymous sealed box: X25519 to a recipient public key, then ChaCha20-Poly1305.
 *
 * Layout, matching the 54-byte `sealed_body` in docs/02-wire-protocol.md:
 *
 *     ephemeral_pubkey(32) || ciphertext(N) || poly1305_tag(16)
 *
 * Key derivation (Setu-specific, recorded in docs/04-security-model.md):
 *
 *     shared = X25519(eph_sk, recipient_pk)
 *     key    = SHA-256("setu-seal-v1"  || shared || eph_pk || recipient_pk)
 *     nonce  = SHA-256("setu-nonce-v1" || eph_pk || recipient_pk)[0..11]
 *
 * The nonce is deterministic, which is safe because the symmetric key is unique
 * per message: a fresh ephemeral key produces a fresh `shared`, so no (key,
 * nonce) pair is ever reused.
 *
 * The sender is anonymous at this layer — the envelope signature is what binds a
 * message to an identity.
 */
object SealedBox {

    const val OVERHEAD = X25519.KEY_SIZE + ChaCha20Poly1305.TAG_SIZE  // 48

    private val KDF_KEY = "setu-seal-v1".toByteArray(Charsets.US_ASCII)
    private val KDF_NONCE = "setu-nonce-v1".toByteArray(Charsets.US_ASCII)

    private val rng = SecureRandom()

    fun seal(recipientPublicKey: ByteArray, plaintext: ByteArray): ByteArray {
        require(recipientPublicKey.size == X25519.KEY_SIZE) { "recipient key must be 32 bytes" }
        val ephSk = ByteArray(X25519.KEY_SIZE).also { rng.nextBytes(it) }
        val ephPk = X25519.publicKey(ephSk)
        val shared = X25519.sharedSecret(ephSk, recipientPublicKey)
        check(!isAllZero(shared)) { "degenerate X25519 shared secret" }

        val key = Digest.sha256(KDF_KEY, shared, ephPk, recipientPublicKey)
        val nonce = Digest.sha256(KDF_NONCE, ephPk, recipientPublicKey)
            .copyOfRange(0, ChaCha20Poly1305.NONCE_SIZE)
        val body = ChaCha20Poly1305.seal(key, nonce, plaintext)

        val out = ByteArray(ephPk.size + body.size)
        ephPk.copyInto(out, 0)
        body.copyInto(out, ephPk.size)
        return out
    }

    /** Returns null when the box was not sealed to this key. Never returns garbage. */
    fun open(recipientPrivateKey: ByteArray, sealed: ByteArray): ByteArray? {
        if (recipientPrivateKey.size != X25519.KEY_SIZE) return null
        if (sealed.size < OVERHEAD) return null
        val ephPk = sealed.copyOfRange(0, X25519.KEY_SIZE)
        val body = sealed.copyOfRange(X25519.KEY_SIZE, sealed.size)
        val recipientPk = X25519.publicKey(recipientPrivateKey)
        val shared = X25519.sharedSecret(recipientPrivateKey, ephPk)
        if (isAllZero(shared)) return null

        val key = Digest.sha256(KDF_KEY, shared, ephPk, recipientPk)
        val nonce = Digest.sha256(KDF_NONCE, ephPk, recipientPk)
            .copyOfRange(0, ChaCha20Poly1305.NONCE_SIZE)
        return ChaCha20Poly1305.open(key, nonce, body)
    }

    /** A body of the right shape that carries nothing — used to pad the wire format. */
    fun sealedSizeFor(plaintextSize: Int): Int = OVERHEAD + plaintextSize

    private fun isAllZero(b: ByteArray): Boolean {
        var acc = 0
        for (x in b) acc = acc or x.toInt()
        return acc == 0
    }
}
