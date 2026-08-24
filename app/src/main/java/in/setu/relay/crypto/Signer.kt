package `in`.setu.relay.crypto

import android.util.Log
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Ed25519 sign/verify with a platform fast path.
 *
 * API 33+ ships a native Ed25519 provider, which is roughly two orders of
 * magnitude faster than the BigInteger implementation in [Ed25519]. Verification
 * runs on every beacon that survives dedupe, so the fast path matters. When the
 * provider is missing or misbehaves we fall back to [Ed25519]; the two produce
 * byte-identical signatures because Ed25519 is deterministic, which is asserted
 * in `Ed25519Test`.
 */
object Signer {

    private const val TAG = "SetuSigner"

    /** DER prefix for a PKCS#8 Ed25519 private key wrapping a 32-byte seed. */
    private val PKCS8_PREFIX = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b,
        0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
    )

    /** DER prefix for an X.509 SubjectPublicKeyInfo wrapping a 32-byte Ed25519 key. */
    private val X509_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    /** Null until probed; true when the platform provider works end to end. */
    @Volatile
    private var platformOk: Boolean? = null

    private fun platformAvailable(): Boolean {
        platformOk?.let { return it }
        val ok = try {
            val seed = ByteArray(32) { it.toByte() }
            val msg = "setu-probe".toByteArray()
            val sig = platformSign(seed, msg)
            sig != null && sig.contentEquals(Ed25519.sign(seed, msg))
        } catch (t: Throwable) {
            Log.i(TAG, "platform Ed25519 unavailable: ${t.javaClass.simpleName}")
            false
        }
        platformOk = ok
        Log.i(TAG, "Ed25519 backend = ${if (ok) "platform" else "pure-kotlin"}")
        return ok
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        if (platformAvailable()) platformSign(seed, message)?.let { return it }
        return Ed25519.sign(seed, message)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != Ed25519.PUBLIC_KEY_SIZE) return false
        if (signature.size != Ed25519.SIGNATURE_SIZE) return false
        if (platformAvailable()) platformVerify(publicKey, message, signature)?.let { return it }
        return Ed25519.verify(publicKey, message, signature)
    }

    fun publicKeyFromSeed(seed: ByteArray): ByteArray = Ed25519.publicKeyFromSeed(seed)

    // ------------------------------------------------------------- platform

    private fun platformSign(seed: ByteArray, message: ByteArray): ByteArray? = try {
        val der = PKCS8_PREFIX + seed
        val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(der))
        Signature.getInstance("Ed25519").run {
            initSign(key)
            update(message)
            sign()
        }
    } catch (_: Throwable) {
        null
    }

    private fun platformVerify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean? = try {
        val der = X509_PREFIX + publicKey
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
        Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(message)
            verify(signature)
        }
    } catch (_: java.security.SignatureException) {
        false
    } catch (_: java.security.spec.InvalidKeySpecException) {
        false
    } catch (_: Throwable) {
        null
    }
}
