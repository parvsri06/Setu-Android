package `in`.setu.relay.crypto

import java.math.BigInteger
import java.security.SecureRandom

/**
 * X25519 (RFC 7748) Montgomery ladder in pure Kotlin. Used to seal the SOS body
 * to the rescuer key. See docs/04-security-model.md.
 */
object X25519 {

    const val KEY_SIZE = 32

    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)

    private val rng = SecureRandom()

    fun generatePrivateKey(): ByteArray {
        val k = ByteArray(KEY_SIZE)
        rng.nextBytes(k)
        return clamp(k)
    }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, basePoint())

    fun sharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray =
        scalarMult(privateKey, peerPublicKey)

    private fun basePoint(): ByteArray {
        val u = ByteArray(KEY_SIZE)
        u[0] = 9
        return u
    }

    private fun clamp(k: ByteArray): ByteArray {
        val c = k.copyOf()
        c[0] = (c[0].toInt() and 248).toByte()
        c[31] = (c[31].toInt() and 127).toByte()
        c[31] = (c[31].toInt() or 64).toByte()
        return c
    }

    fun scalarMult(scalar: ByteArray, uCoord: ByteArray): ByteArray {
        require(scalar.size == KEY_SIZE) { "scalar must be 32 bytes" }
        require(uCoord.size == KEY_SIZE) { "u coordinate must be 32 bytes" }

        val k = leInt(clamp(scalar))
        // RFC 7748: mask the most significant bit of the u coordinate.
        val uBytes = uCoord.copyOf()
        uBytes[31] = (uBytes[31].toInt() and 0x7F).toByte()
        val u = leInt(uBytes).mod(P)

        var x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            if ((swap xor kt) == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val s1 = da.add(cb).mod(P)
            x3 = s1.multiply(s1).mod(P)
            val s2 = da.subtract(cb).mod(P)
            z3 = x1.multiply(s2.multiply(s2).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e).mod(P)).mod(P)).mod(P)
        }
        if (swap == 1) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }

        val result = if (z2.signum() == 0) BigInteger.ZERO else x2.multiply(z2.modInverse(P)).mod(P)
        return leBytes(result)
    }

    private fun leInt(b: ByteArray): BigInteger {
        val be = ByteArray(b.size + 1)
        for (i in b.indices) be[b.size - i] = b[i]
        return BigInteger(be)
    }

    private fun leBytes(v: BigInteger): ByteArray {
        val out = ByteArray(KEY_SIZE)
        var x = v
        for (i in 0 until KEY_SIZE) {
            out[i] = x.and(BigInteger.valueOf(0xFF)).toInt().toByte()
            x = x.shiftRight(8)
        }
        return out
    }
}
