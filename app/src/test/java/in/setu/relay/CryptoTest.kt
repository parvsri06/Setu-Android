package `in`.setu.relay

import `in`.setu.relay.crypto.ChaCha20Poly1305
import `in`.setu.relay.crypto.Ed25519
import `in`.setu.relay.crypto.SealedBox
import `in`.setu.relay.crypto.X25519
import `in`.setu.relay.wire.Bodies
import `in`.setu.relay.wire.Codec
import `in`.setu.relay.wire.Proto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The hand-rolled primitives are cross-checked against the JDK's own
 * implementations rather than against hex vectors typed from memory. If these
 * pass, the Kotlin code agrees with a reference implementation byte for byte.
 */
class CryptoTest {

    private val rng = SecureRandom()

    // ---------------------------------------------------------------- Ed25519

    private val PKCS8_PREFIX = Codec.unhex("302e020100300506032b657004220420")
    private val X509_PREFIX = Codec.unhex("302a300506032b6570032100")

    /**
     * The JDK signs, we verify with the public key we derived ourselves. If our
     * scalar clamping, base-point multiplication or point compression were
     * wrong, the derived key would not match the one the JDK signed under and
     * this would fail.
     */
    @Test
    fun `we verify signatures the JDK produced, under a key we derived`() {
        repeat(5) {
            val seed = ByteArray(32).also { s -> rng.nextBytes(s) }
            val ours = Ed25519.publicKeyFromSeed(seed)
            val msg = ByteArray(142).also { m -> rng.nextBytes(m) }

            val jdkSig = Signature.getInstance("Ed25519").run {
                initSign(
                    KeyFactory.getInstance("Ed25519")
                        .generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + seed)),
                )
                update(msg)
                sign()
            }
            assertTrue(Ed25519.verify(ours, msg, jdkSig))
            assertFalse(Ed25519.verify(ours, msg + byteArrayOf(1), jdkSig))
        }
    }

    @Test
    fun `signatures are byte-identical to the JDK`() {
        repeat(5) {
            val seed = ByteArray(32).also { s -> rng.nextBytes(s) }
            val msg = ByteArray(78).also { m -> rng.nextBytes(m) }

            val ours = Ed25519.sign(seed, msg)
            val jdk = Signature.getInstance("Ed25519").run {
                initSign(
                    KeyFactory.getInstance("Ed25519")
                        .generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + seed)),
                )
                update(msg)
                sign()
            }
            assertArrayEquals("Ed25519 is deterministic; these must match", jdk, ours)
        }
    }

    @Test
    fun `the JDK accepts our signatures and we accept the JDK's`() {
        val seed = ByteArray(32).also { rng.nextBytes(it) }
        val pub = Ed25519.publicKeyFromSeed(seed)
        val msg = "a message that crosses a bridge".toByteArray()

        val ours = Ed25519.sign(seed, msg)
        val verifiedByJdk = Signature.getInstance("Ed25519").run {
            initVerify(
                KeyFactory.getInstance("Ed25519")
                    .generatePublic(X509EncodedKeySpec(X509_PREFIX + pub)),
            )
            update(msg)
            verify(ours)
        }
        assertTrue(verifiedByJdk)
        assertTrue(Ed25519.verify(pub, msg, ours))
    }

    @Test
    fun `verification rejects a wrong key, a wrong message and a wrong length`() {
        val seed = ByteArray(32).also { rng.nextBytes(it) }
        val other = ByteArray(32).also { rng.nextBytes(it) }
        val msg = "x".toByteArray()
        val sig = Ed25519.sign(seed, msg)

        assertFalse(Ed25519.verify(Ed25519.publicKeyFromSeed(other), msg, sig))
        assertFalse(Ed25519.verify(Ed25519.publicKeyFromSeed(seed), "y".toByteArray(), sig))
        assertFalse(Ed25519.verify(ByteArray(31), msg, sig))
        assertFalse(Ed25519.verify(Ed25519.publicKeyFromSeed(seed), msg, ByteArray(64)))
    }

    // ---------------------------------------------------------------- X25519

    @Test
    fun `X25519 agrees with the JDK in both directions`() {
        repeat(5) {
            val aSk = X25519.generatePrivateKey()
            val bSk = X25519.generatePrivateKey()
            val aPk = X25519.publicKey(aSk)
            val bPk = X25519.publicKey(bSk)

            assertArrayEquals(X25519.sharedSecret(aSk, bPk), X25519.sharedSecret(bSk, aPk))

            val jdk = KeyAgreement.getInstance("XDH").run {
                init(
                    KeyFactory.getInstance("XDH").generatePrivate(
                        XECPrivateKeySpec(NamedParameterSpec.X25519, aSk),
                    ),
                )
                doPhase(
                    KeyFactory.getInstance("XDH").generatePublic(
                        XECPublicKeySpec(NamedParameterSpec.X25519, leToBigInteger(bPk)),
                    ),
                    true,
                )
                generateSecret()
            }
            assertArrayEquals(jdk, X25519.sharedSecret(aSk, bPk))
        }
    }

    private fun leToBigInteger(le: ByteArray): BigInteger {
        val be = ByteArray(le.size + 1)
        for (i in le.indices) be[le.size - i] = le[i]
        be[1] = (be[1].toInt() and 0x7F).toByte()
        return BigInteger(be)
    }

    // ------------------------------------------------------ ChaCha20-Poly1305

    @Test
    fun `ChaCha20-Poly1305 matches the JDK`() {
        repeat(5) {
            val key = ByteArray(32).also { k -> rng.nextBytes(k) }
            val nonce = ByteArray(12).also { n -> rng.nextBytes(n) }
            val plaintext = ByteArray(6).also { p -> rng.nextBytes(p) }

            val ours = ChaCha20Poly1305.seal(key, nonce, plaintext)
            val jdk = Cipher.getInstance("ChaCha20-Poly1305").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
                doFinal(plaintext)
            }
            assertArrayEquals(jdk, ours)
            assertArrayEquals(plaintext, ChaCha20Poly1305.open(key, nonce, jdk))
        }
    }

    @Test
    fun `a modified tag or ciphertext fails cleanly rather than returning garbage`() {
        val key = ByteArray(32).also { rng.nextBytes(it) }
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val sealed = ChaCha20Poly1305.seal(key, nonce, byteArrayOf(1, 2, 3, 4, 5, 6))
        for (i in sealed.indices) {
            val bad = sealed.copyOf()
            bad[i] = (bad[i].toInt() xor 0x40).toByte()
            assertNull("byte $i was not authenticated", ChaCha20Poly1305.open(key, nonce, bad))
        }
    }

    // -------------------------------------------------------------- SealedBox

    @Test
    fun `sealed body is exactly 54 bytes for a 6 byte plaintext`() {
        val sk = X25519.generatePrivateKey()
        val pk = X25519.publicKey(sk)
        val sealed = SealedBox.seal(pk, ByteArray(Bodies.SEALED_PLAINTEXT_SIZE))
        assertEquals(Proto.LEN_SEALED_BODY, sealed.size)
        assertEquals(54, sealed.size)
    }

    @Test
    fun `only the correct private key opens the box`() {
        val sk = X25519.generatePrivateKey()
        val pk = X25519.publicKey(sk)
        val wrong = X25519.generatePrivateKey()
        val plaintext = byteArrayOf(9, 8, 7, 6, 5, 4)

        val sealed = SealedBox.seal(pk, plaintext)
        assertArrayEquals(plaintext, SealedBox.open(sk, sealed))
        assertNull(SealedBox.open(wrong, sealed))
    }

    @Test
    fun `two seals of the same plaintext differ`() {
        val sk = X25519.generatePrivateKey()
        val pk = X25519.publicKey(sk)
        val a = SealedBox.seal(pk, byteArrayOf(1, 1, 1, 1, 1, 1))
        val b = SealedBox.seal(pk, byteArrayOf(1, 1, 1, 1, 1, 1))
        assertFalse("a fresh ephemeral key must make every seal unique", a.contentEquals(b))
        assertNotNull(SealedBox.open(sk, a))
        assertNotNull(SealedBox.open(sk, b))
    }

    @Test
    fun `a truncated or garbage box fails cleanly`() {
        val sk = X25519.generatePrivateKey()
        assertNull(SealedBox.open(sk, ByteArray(10)))
        assertNull(SealedBox.open(sk, ByteArray(54)))
        assertNull(SealedBox.open(ByteArray(31), ByteArray(54)))
    }
}
