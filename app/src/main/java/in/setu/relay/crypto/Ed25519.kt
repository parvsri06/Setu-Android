package `in`.setu.relay.crypto

import java.math.BigInteger

/**
 * Ed25519 (RFC 8032) in pure Kotlin, structured after the RFC's own reference
 * implementation. Uses [BigInteger] field arithmetic — a few tens of
 * milliseconds per operation, which is far below the beacon rate this app
 * produces and keeps the code small enough to audit.
 *
 * The dependency allow-list forbids Tink, BouncyCastle and libsodium. On API 33+
 * the platform has a native Ed25519 provider; [Signer] prefers it and falls back
 * to this. Both paths are cross-checked in `Ed25519Test`.
 */
object Ed25519 {

    const val SEED_SIZE = 32
    const val PUBLIC_KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64

    private val TWO = BigInteger.valueOf(2)
    private val P: BigInteger = TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** Group order L = 2^252 + 27742317777372353535851937790883648493 */
    private val Q: BigInteger = TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)

    /** sqrt(-1) mod p */
    private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    private val G_Y: BigInteger = BigInteger.valueOf(4)
        .multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
    private val G_X: BigInteger = recoverX(G_Y, 0)!!

    /** Extended homogeneous coordinates (X : Y : Z : T), x = X/Z, y = Y/Z, xy = T/Z. */
    private class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val G = Pt(G_X, G_Y, BigInteger.ONE, G_X.multiply(G_Y).mod(P))
    private val NEUTRAL = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    // ---------------------------------------------------------------- curve

    private fun add(p1: Pt, p2: Pt): Pt {
        val a = p1.y.subtract(p1.x).multiply(p2.y.subtract(p2.x)).mod(P)
        val b = p1.y.add(p1.x).multiply(p2.y.add(p2.x)).mod(P)
        val c = TWO.multiply(D).multiply(p1.t).multiply(p2.t).mod(P)
        val d = TWO.multiply(p1.z).multiply(p2.z).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Pt(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P),
        )
    }

    private fun mul(scalar: BigInteger, point: Pt): Pt {
        var s = scalar
        var p = point
        var q = NEUTRAL
        while (s.signum() > 0) {
            if (s.testBit(0)) q = add(q, p)
            p = add(p, p)
            s = s.shiftRight(1)
        }
        return q
    }

    private fun equal(p1: Pt, p2: Pt): Boolean =
        p1.x.multiply(p2.z).mod(P) == p2.x.multiply(p1.z).mod(P) &&
            p1.y.multiply(p2.z).mod(P) == p2.y.multiply(p1.z).mod(P)

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val y2 = y.multiply(y).mod(P)
        val denom = D.multiply(y2).add(BigInteger.ONE).mod(P)
        if (denom.signum() == 0) return null
        val x2 = y2.subtract(BigInteger.ONE).multiply(denom.modInverse(P)).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return x
    }

    private fun compress(p: Pt): ByteArray {
        val zi = p.z.modInverse(P)
        val x = p.x.multiply(zi).mod(P)
        val y = p.y.multiply(zi).mod(P)
        val out = leBytes(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun decompress(b: ByteArray): Pt? {
        if (b.size != 32) return null
        val raw = leInt(b)
        val sign = if (raw.testBit(255)) 1 else 0
        val y = raw.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Pt(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    // ------------------------------------------------------------- encoding

    private fun leInt(b: ByteArray): BigInteger {
        val be = ByteArray(b.size + 1)
        for (i in b.indices) be[b.size - i] = b[i]
        return BigInteger(be)
    }

    private fun leBytes(v: BigInteger, len: Int): ByteArray {
        val out = ByteArray(len)
        var x = v
        for (i in 0 until len) {
            out[i] = x.and(BigInteger.valueOf(0xFF)).toInt().toByte()
            x = x.shiftRight(8)
        }
        return out
    }

    private fun hashModQ(vararg parts: ByteArray): BigInteger =
        leInt(Digest.sha512(*parts)).mod(Q)

    /** RFC 8032 secret expansion: clamped scalar plus the nonce prefix. */
    private fun expand(seed: ByteArray): Pair<BigInteger, ByteArray> {
        require(seed.size == SEED_SIZE) { "seed must be 32 bytes" }
        val h = Digest.sha512(seed)
        var a = leInt(h.copyOfRange(0, 32))
        a = a.and(TWO.pow(254).subtract(BigInteger.valueOf(8)))
        a = a.or(TWO.pow(254))
        return a to h.copyOfRange(32, 64)
    }

    // ----------------------------------------------------------------- API

    fun publicKeyFromSeed(seed: ByteArray): ByteArray = compress(mul(expand(seed).first, G))

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val (a, prefix) = expand(seed)
        val pub = compress(mul(a, G))
        val r = hashModQ(prefix, message)
        val rs = compress(mul(r, G))
        val k = hashModQ(rs, pub, message)
        val s = r.add(k.multiply(a)).mod(Q)
        val out = ByteArray(SIGNATURE_SIZE)
        rs.copyInto(out, 0)
        leBytes(s, 32).copyInto(out, 32)
        return out
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
        return try {
            val bigA = decompress(publicKey) ?: return false
            val rs = signature.copyOfRange(0, 32)
            val bigR = decompress(rs) ?: return false
            val s = leInt(signature.copyOfRange(32, 64))
            if (s >= Q) return false
            val k = hashModQ(rs, publicKey, message)
            equal(mul(s, G), add(bigR, mul(k, bigA)))
        } catch (_: ArithmeticException) {
            false
        }
    }
}
