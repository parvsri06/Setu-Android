package `in`.setu.relay.radio.beacon

import `in`.setu.relay.wire.Frag
import `in`.setu.relay.wire.Proto

/**
 * How a 142-byte envelope is wrapped for the air. docs/02-wire-protocol.md
 * specifies the envelope and the fragment layout but not the advertising data
 * structure that carries them, so it is defined here and written up in
 * docs/02-wire-protocol.md under "Advertising data structure".
 *
 * Both paths use BLE manufacturer-specific data with company identifier 0xFFFF,
 * which is the identifier reserved for testing and internal use. Setu has no
 * assigned company ID; using 0xFFFF with a magic prefix is the standard practice
 * for an unassigned application, and the magic plus a signature check is what
 * separates Setu traffic from anyone else on 0xFFFF.
 *
 * Extended path (BLE 5, 250 usable bytes):
 *
 *     0      0x53 'S'
 *     1      0x54 'T'
 *     2..143 the 142-byte envelope
 *     total 144 bytes of manufacturer data
 *
 * Legacy path (27 usable bytes): one [Frag] fragment per advertisement, with no
 * magic prefix — the fragment's own 8-byte msg_id group key plus the envelope
 * signature is the discriminator, and the spec's 27-byte fragment layout leaves
 * no room for a prefix.
 *
 *     0..26  one fragment, exactly 27 bytes
 */
object BeaconFormat {

    /** Reserved-for-testing company identifier. */
    const val COMPANY_ID = 0xFFFF

    const val MAGIC_0 = 0x53.toByte()  // 'S'
    const val MAGIC_1 = 0x54.toByte()  // 'T'

    const val EXTENDED_SIZE = 2 + Proto.ENVELOPE_SIZE  // 144
    const val LEGACY_SIZE = Frag.FRAG_SIZE             // 27

    fun wrapExtended(envelope: ByteArray): ByteArray {
        require(envelope.size == Proto.ENVELOPE_SIZE) { "envelope must be 142 bytes" }
        val out = ByteArray(EXTENDED_SIZE)
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        envelope.copyInto(out, 2)
        return out
    }

    /** null when the payload is not a Setu extended beacon. */
    fun unwrapExtended(data: ByteArray): ByteArray? {
        if (data.size != EXTENDED_SIZE) return null
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1) return null
        return data.copyOfRange(2, EXTENDED_SIZE)
    }

    fun isLegacyFragment(data: ByteArray): Boolean = data.size == LEGACY_SIZE
}
