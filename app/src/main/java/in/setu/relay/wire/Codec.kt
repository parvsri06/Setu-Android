package `in`.setu.relay.wire

/**
 * Hand-rolled little-endian binary codec. No serialization library — see the
 * dependency allow-list in CLAUDE.md.
 */
object Codec {

    fun putU16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun getU16(src: ByteArray, off: Int): Int =
        (src[off].toInt() and 0xFF) or ((src[off + 1].toInt() and 0xFF) shl 8)

    fun putU24(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((v ushr 16) and 0xFF).toByte()
    }

    fun getU24(src: ByteArray, off: Int): Int =
        (src[off].toInt() and 0xFF) or
            ((src[off + 1].toInt() and 0xFF) shl 8) or
            ((src[off + 2].toInt() and 0xFF) shl 16)

    fun putU32(dst: ByteArray, off: Int, v: Long) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((v ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun getU32(src: ByteArray, off: Int): Long =
        (src[off].toLong() and 0xFF) or
            ((src[off + 1].toLong() and 0xFF) shl 8) or
            ((src[off + 2].toLong() and 0xFF) shl 16) or
            ((src[off + 3].toLong() and 0xFF) shl 24)

    fun putU64(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) dst[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    fun getU64(src: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((src[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((digit(s[2 * i]) shl 4) or digit(s[2 * i + 1])).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("bad hex char $c")
    }

    /** Constant-time-ish comparison. Not timing critical here, but cheap to do right. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
