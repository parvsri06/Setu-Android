package `in`.setu.relay.crypto

import java.security.MessageDigest

/** Platform hashing. `java.security` is on the dependency allow-list. */
object Digest {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** First 8 bytes of SHA-256 of a public key — the `origin_key_id` on the wire. */
    fun keyId(publicKey: ByteArray): ByteArray = sha256(publicKey).copyOfRange(0, 8)
}
