package `in`.setu.relay.wire

/**
 * Legacy advertising fragmentation. See docs/02-wire-protocol.md.
 *
 * Phones without BLE 5 extended advertising get 27 usable payload bytes, so the
 * 142-byte envelope is split across several legacy advertisements.
 *
 * Fragment layout — exactly as specified:
 *
 *     0..7   msg_id, the fragment group key
 *     8      frag_index high nibble, frag_total low nibble
 *     9..26  18 bytes of payload
 *
 * DEVIATION FROM THE SPEC TABLE, recorded as D13 in MEMORY.md. The spec says
 * "6 data fragments plus 2 XOR parity fragments — any 6 of 8 reconstruct", but
 * 6 x 18 = 108 bytes, which cannot carry a 142-byte envelope. 142 needs
 * ceil(142/18) = 8 data fragments. Two XOR parity fragments over a single group
 * also do not recover two losses — plain XOR recovers exactly one erasure, and
 * two-erasure recovery needs Reed-Solomon, which is not worth the code here.
 *
 * So this implements 8 data fragments + 1 XOR parity = 9 fragments, any 8 of 9
 * reconstruct. The header layout, the group key and the 18-byte payload width
 * are unchanged from the spec.
 */
object Frag {

    const val FRAG_SIZE = 27
    const val HEADER_SIZE = 9
    const val PAYLOAD_SIZE = FRAG_SIZE - HEADER_SIZE   // 18

    /** ceil(142 / 18) */
    const val DATA_FRAGS = 8
    const val PARITY_FRAGS = 1
    const val TOTAL_FRAGS = DATA_FRAGS + PARITY_FRAGS  // 9

    /** DATA_FRAGS * PAYLOAD_SIZE; the tail past ENVELOPE_SIZE is zero padding. */
    const val PADDED_SIZE = DATA_FRAGS * PAYLOAD_SIZE  // 144

    init {
        check(PADDED_SIZE >= Proto.ENVELOPE_SIZE) { "fragment plan cannot carry an envelope" }
        check(TOTAL_FRAGS <= 15) { "frag_total must fit in a nibble" }
    }

    /** Splits a 142-byte envelope into [TOTAL_FRAGS] advertisable fragments. */
    fun split(envelope: ByteArray): List<ByteArray> {
        require(envelope.size == Proto.ENVELOPE_SIZE) {
            "envelope must be ${Proto.ENVELOPE_SIZE} bytes"
        }
        val msgId = envelope.copyOfRange(Proto.OFF_MSG_ID, Proto.OFF_MSG_ID + Proto.LEN_MSG_ID)
        val padded = envelope.copyOf(PADDED_SIZE)

        val out = ArrayList<ByteArray>(TOTAL_FRAGS)
        val parity = ByteArray(PAYLOAD_SIZE)
        for (i in 0 until DATA_FRAGS) {
            val slice = padded.copyOfRange(i * PAYLOAD_SIZE, (i + 1) * PAYLOAD_SIZE)
            for (j in 0 until PAYLOAD_SIZE) parity[j] = (parity[j].toInt() xor slice[j].toInt()).toByte()
            out.add(frame(msgId, i, slice))
        }
        out.add(frame(msgId, DATA_FRAGS, parity))
        return out
    }

    private fun frame(msgId: ByteArray, index: Int, payload: ByteArray): ByteArray {
        val f = ByteArray(FRAG_SIZE)
        msgId.copyInto(f, 0)
        f[8] = (((index and 0xF) shl 4) or (TOTAL_FRAGS and 0xF)).toByte()
        payload.copyInto(f, HEADER_SIZE)
        return f
    }

    class Header(val groupKey: ByteArray, val index: Int, val total: Int)

    fun parseHeader(fragment: ByteArray): Header? {
        if (fragment.size != FRAG_SIZE) return null
        val b = fragment[8].toInt() and 0xFF
        val index = (b ushr 4) and 0xF
        val total = b and 0xF
        if (total != TOTAL_FRAGS || index >= total) return null
        return Header(fragment.copyOfRange(0, 8), index, total)
    }

    /**
     * Reassembles from a sparse fragment set. Returns the 142-byte envelope, or
     * null when fewer than [DATA_FRAGS] fragments are present or the missing set
     * is not recoverable by the single parity fragment.
     */
    fun reassemble(fragments: Array<ByteArray?>): ByteArray? {
        if (fragments.size != TOTAL_FRAGS) return null
        val missing = (0 until DATA_FRAGS).filter { fragments[it] == null }
        when {
            missing.isEmpty() -> Unit
            missing.size == 1 && fragments[DATA_FRAGS] != null -> {
                val recovered = fragments[DATA_FRAGS]!!.copyOfRange(HEADER_SIZE, FRAG_SIZE)
                for (i in 0 until DATA_FRAGS) {
                    if (i == missing[0]) continue
                    val f = fragments[i] ?: return null
                    for (j in 0 until PAYLOAD_SIZE) {
                        recovered[j] = (recovered[j].toInt() xor f[HEADER_SIZE + j].toInt()).toByte()
                    }
                }
                fragments[missing[0]] = frame(fragments.first { it != null }!!.copyOfRange(0, 8), missing[0], recovered)
            }
            else -> return null
        }

        val padded = ByteArray(PADDED_SIZE)
        for (i in 0 until DATA_FRAGS) {
            val f = fragments[i] ?: return null
            f.copyInto(padded, i * PAYLOAD_SIZE, HEADER_SIZE, FRAG_SIZE)
        }
        return padded.copyOfRange(0, Proto.ENVELOPE_SIZE)
    }
}
