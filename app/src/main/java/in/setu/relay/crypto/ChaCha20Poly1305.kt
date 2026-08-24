package `in`.setu.relay.crypto

import java.math.BigInteger

/**
 * ChaCha20-Poly1305 AEAD (RFC 8439) in pure Kotlin.
 *
 * The platform provides this from API 28, but minSdk is 26 and the allow-list
 * forbids a crypto library, so it is implemented here and used on every API
 * level for a single deterministic code path. Cross-checked against the JDK
 * provider in `ChaChaTest`.
 */
object ChaCha20Poly1305 {

    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == KEY_SIZE) { "key must be 32 bytes" }
        require(nonce.size == NONCE_SIZE) { "nonce must be 12 bytes" }
        val ciphertext = chacha20(key, nonce, 1, plaintext)
        val tag = poly1305Tag(poly1305Key(key, nonce), aad, ciphertext)
        val out = ByteArray(ciphertext.size + TAG_SIZE)
        ciphertext.copyInto(out, 0)
        tag.copyInto(out, ciphertext.size)
        return out
    }

    /** Returns null if the tag does not verify. Never returns garbage. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (key.size != KEY_SIZE || nonce.size != NONCE_SIZE || sealed.size < TAG_SIZE) return null
        val ctLen = sealed.size - TAG_SIZE
        val ciphertext = sealed.copyOfRange(0, ctLen)
        val tag = sealed.copyOfRange(ctLen, sealed.size)
        val expected = poly1305Tag(poly1305Key(key, nonce), aad, ciphertext)
        var diff = 0
        for (i in 0 until TAG_SIZE) diff = diff or (tag[i].toInt() xor expected[i].toInt())
        if (diff != 0) return null
        return chacha20(key, nonce, 1, ciphertext)
    }

    // -------------------------------------------------------------- ChaCha20

    private fun chacha20(key: ByteArray, nonce: ByteArray, counter: Int, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var block = 0
        while (block * 64 < input.size) {
            val stream = chachaBlock(key, nonce, counter + block)
            val offset = block * 64
            val n = minOf(64, input.size - offset)
            for (i in 0 until n) out[offset + i] = (input[offset + i].toInt() xor stream[i].toInt()).toByte()
            block++
        }
        return out
    }

    private fun chachaBlock(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val state = IntArray(16)
        state[0] = 0x61707865; state[1] = 0x3320646e
        state[2] = 0x79622d32; state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = leInt32(key, i * 4)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = leInt32(nonce, i * 4)

        val w = state.copyOf()
        repeat(10) {
            quarter(w, 0, 4, 8, 12); quarter(w, 1, 5, 9, 13)
            quarter(w, 2, 6, 10, 14); quarter(w, 3, 7, 11, 15)
            quarter(w, 0, 5, 10, 15); quarter(w, 1, 6, 11, 12)
            quarter(w, 2, 7, 8, 13); quarter(w, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) putLeInt32(out, i * 4, w[i] + state[i])
        return out
    }

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }

    // -------------------------------------------------------------- Poly1305

    private fun poly1305Key(key: ByteArray, nonce: ByteArray): ByteArray =
        chachaBlock(key, nonce, 0).copyOfRange(0, 32)

    private val P130_5: BigInteger = BigInteger.valueOf(2).pow(130).subtract(BigInteger.valueOf(5))
    private val TWO_128: BigInteger = BigInteger.valueOf(2).pow(128)

    private fun poly1305Tag(oneTimeKey: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val rBytes = oneTimeKey.copyOfRange(0, 16)
        rBytes[3] = (rBytes[3].toInt() and 15).toByte()
        rBytes[7] = (rBytes[7].toInt() and 15).toByte()
        rBytes[11] = (rBytes[11].toInt() and 15).toByte()
        rBytes[15] = (rBytes[15].toInt() and 15).toByte()
        rBytes[4] = (rBytes[4].toInt() and 252).toByte()
        rBytes[8] = (rBytes[8].toInt() and 252).toByte()
        rBytes[12] = (rBytes[12].toInt() and 252).toByte()

        val r = leBig(rBytes)
        val s = leBig(oneTimeKey.copyOfRange(16, 32))

        var acc = BigInteger.ZERO
        val msg = aeadInput(aad, ciphertext)
        var i = 0
        while (i < msg.size) {
            val n = minOf(16, msg.size - i)
            val chunk = ByteArray(n + 1)
            msg.copyInto(chunk, 0, i, i + n)
            chunk[n] = 1
            acc = acc.add(leBig(chunk)).multiply(r).mod(P130_5)
            i += 16
        }
        acc = acc.add(s).mod(TWO_128)

        val out = ByteArray(TAG_SIZE)
        var x = acc
        for (j in 0 until TAG_SIZE) {
            out[j] = x.and(BigInteger.valueOf(0xFF)).toInt().toByte()
            x = x.shiftRight(8)
        }
        return out
    }

    /** RFC 8439 section 2.8: aad || pad16 || ct || pad16 || len(aad) || len(ct) */
    private fun aeadInput(aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val aadPad = (16 - aad.size % 16) % 16
        val ctPad = (16 - ciphertext.size % 16) % 16
        val out = ByteArray(aad.size + aadPad + ciphertext.size + ctPad + 16)
        var o = 0
        aad.copyInto(out, o); o += aad.size + aadPad
        ciphertext.copyInto(out, o); o += ciphertext.size + ctPad
        putLeInt64(out, o, aad.size.toLong()); o += 8
        putLeInt64(out, o, ciphertext.size.toLong())
        return out
    }

    // ----------------------------------------------------------------- utils

    private fun leInt32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun putLeInt32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun putLeInt64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun leBig(b: ByteArray): BigInteger {
        val be = ByteArray(b.size + 1)
        for (i in b.indices) be[b.size - i] = b[i]
        return BigInteger(be)
    }
}
