package `in`.setu.relay.radio.bulk

import `in`.setu.relay.crypto.Digest

/**
 * 256-byte Bloom filter, 3 hashes — the digest size docs/02-wire-protocol.md
 * specifies, sized for ~200 records at roughly 3% false positive.
 *
 * 256 bytes is 2048 bits, so each hash is an 11-bit index. All three come from
 * one SHA-256 of the record id: the id is already a UUID, so its bits are
 * uniform and slicing one digest is as good as three independent hashes while
 * costing one pass.
 *
 * ### What a false positive costs, and why it is acceptable
 *
 * A false positive means "the peer already has this" when it does not, so the
 * record is skipped on this contact. It is not lost — the next contact with a
 * different filter population, or a different peer entirely, carries it. In a
 * store-carry-forward network a skipped record is a delay, not a failure, which
 * is why a probabilistic digest is the right trade here.
 *
 * A false *negative* is impossible, which is the property that matters: the
 * filter never claims a record is missing when the peer holds it, so nothing is
 * pushed that the peer already has beyond the deliberate 3%.
 *
 * Pure Kotlin and free of Android types, so it is unit-testable on the JVM.
 */
class Bloom(val bits: ByteArray = ByteArray(SIZE_BYTES)) {

    init {
        require(bits.size == SIZE_BYTES) { "bloom must be $SIZE_BYTES bytes" }
    }

    fun add(id: ByteArray) {
        for (bit in indices(id)) {
            bits[bit ushr 3] = (bits[bit ushr 3].toInt() or (1 shl (bit and 7))).toByte()
        }
    }

    /**
     * False positives are possible; false negatives are not. Read this as "the
     * peer probably has it" rather than "the peer has it".
     */
    fun mightContain(id: ByteArray): Boolean = indices(id).all { bit ->
        bits[bit ushr 3].toInt() and (1 shl (bit and 7)) != 0
    }

    /** Set bits — only useful for tests and diagnostics. */
    fun population(): Int = bits.sumOf { Integer.bitCount(it.toInt() and 0xFF) }

    private fun indices(id: ByteArray): IntArray {
        val h = Digest.sha256(DOMAIN, id)
        val out = IntArray(HASHES)
        for (k in 0 until HASHES) {
            // 11 bits per index, taken from a fresh pair of digest bytes.
            val v = ((h[2 * k].toInt() and 0xFF) shl 8) or (h[2 * k + 1].toInt() and 0xFF)
            out[k] = v and (SIZE_BITS - 1)
        }
        return out
    }

    companion object {
        const val SIZE_BYTES = 256
        const val SIZE_BITS = SIZE_BYTES * 8   // 2048
        const val HASHES = 3

        /** Domain separation, so these hashes can never collide with a key id. */
        private val DOMAIN = "setu-bloom-v1".toByteArray(Charsets.US_ASCII)
    }
}
