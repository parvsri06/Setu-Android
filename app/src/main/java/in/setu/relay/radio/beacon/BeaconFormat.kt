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

    // Presence is 11 bytes, a fragment is 27 and an extended beacon is 144, so
    // length alone separates the three. An earlier version of this also checked
    // for the presence magic here, which would have discarded roughly one
    // fragment in 65536 — the first two bytes of a fragment are msg_id, so they
    // are random and can legitimately be 'S','P'.
    fun isLegacyFragment(data: ByteArray): Boolean = data.size == LEGACY_SIZE

    // -------------------------------------------------------------- presence

    /**
     * The presence beacon: "a Setu phone is here", sent whether or not this
     * device has anything to relay.
     *
     *     0      0x53 'S'
     *     1      0x50 'P'          distinguishes presence from a 'T' envelope
     *     2..9   origin_key_id     8 bytes, same identifier the envelope carries
     *     10     flags             bit 0 set when a GATT server is listening
     *     total 11 bytes
     *
     * Wrapped in a legacy connectable advertisement it costs 18 of the 31
     * available bytes, so it fits on every handset with no fragmentation, and it
     * reuses the manufacturer-data scan filter the beacon plane already installs.
     *
     * PRIVACY COST, recorded as D21. `origin_key_id` is stable, and this moves it
     * from "broadcast while I have a message" to "broadcast continuously", which
     * makes a device followable by anyone listening. It is not a new class of
     * exposure — every envelope already carries the same identifier in clear —
     * but the duty cycle is new. It is accepted because the peer table keys on
     * `key_id` for sync history (docs/06) and a rotating id would break that.
     */
    const val MAGIC_P = 0x50.toByte()  // 'P'

    const val PRESENCE_SIZE = 11

    const val PRESENCE_FLAG_BULK = 0x01

    fun wrapPresence(originKeyId: ByteArray, bulkAvailable: Boolean): ByteArray {
        require(originKeyId.size == Proto.LEN_ORIGIN_KEY_ID) { "key id must be 8 bytes" }
        val out = ByteArray(PRESENCE_SIZE)
        out[0] = MAGIC_0
        out[1] = MAGIC_P
        originKeyId.copyInto(out, 2)
        out[10] = if (bulkAvailable) PRESENCE_FLAG_BULK.toByte() else 0
        return out
    }

    /** The advertised key id, or null when this is not a Setu presence beacon. */
    fun unwrapPresence(data: ByteArray): ByteArray? {
        if (data.size != PRESENCE_SIZE) return null
        if (data[0] != MAGIC_0 || data[1] != MAGIC_P) return null
        return data.copyOfRange(2, 10)
    }
}
