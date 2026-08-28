package `in`.setu.relay.crypto

import `in`.setu.relay.wire.Codec

/**
 * Turns an ordinary Setu install into a **rescuer phone**.
 *
 * ### Why the key is typed in rather than shipped
 *
 * Every build seals SOS locations to [Keys.RESCUER_PUBLIC]. Only the matching
 * private key opens them. If that private key were compiled into the APK, every
 * copy of the app could read every SOS location the mesh carries — which is
 * exactly the "location privacy inversion" that D6 exists to prevent, and it is
 * why D19 took the key out of release builds in the first place.
 *
 * So rescue mode is not a build flavour, it is a **credential**. The same APK
 * everyone has becomes a rescuer phone when, and only when, someone enters the
 * rescuer private key into it. A civilian phone and a rescuer phone run
 * identical code; what separates them is a secret one of them holds.
 *
 * That is also what makes it testable: install the same file on two phones and
 * enter the key on one.
 *
 * ### What this is not
 *
 * There is no key *distribution* here. A real deployment would issue rescuer
 * keys to verified responders and rotate them through signed profile updates.
 * This is a text field. Say so plainly in any demo — the sealing is real, the
 * key management is not built.
 */
object RescuerKey {

    /** X25519 private keys are 32 bytes, so 64 hex characters. */
    const val HEX_LENGTH = 64

    /**
     * True when this private key actually opens what this build seals.
     *
     * Checked by deriving the public key and comparing it against the one
     * compiled in, rather than by trying to decrypt something. That way a wrong
     * key is rejected immediately with a clear message, instead of silently
     * failing to open every message later and looking like a bug in the radio.
     */
    fun matches(privateKey: ByteArray): Boolean {
        if (privateKey.size != X25519.KEY_SIZE) return false
        return Codec.constantTimeEquals(X25519.publicKey(privateKey), Keys.RESCUER_PUBLIC)
    }

    /** Parses and validates in one step. Returns null on anything unusable. */
    fun parseOrNull(hex: String): ByteArray? {
        val cleaned = hex.trim().replace(" ", "").replace("\n", "")
        if (cleaned.length != HEX_LENGTH) return null
        val key = runCatching { Codec.unhex(cleaned) }.getOrNull() ?: return null
        return if (matches(key)) key else null
    }
}
