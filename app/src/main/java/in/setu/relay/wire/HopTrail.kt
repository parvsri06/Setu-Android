package `in`.setu.relay.wire

/**
 * The path a message actually took: every phone that carried it, when it heard
 * it, and where that phone was.
 *
 * ### Why this is not in the beacon envelope
 *
 * The obvious design is to append each hop to the message as it travels. It is
 * also impossible here. The envelope is fixed at 142 bytes and must not grow
 * (D5 — collision loss is exponential in packet size), and the signature covers
 * bytes 0..77 with only `hop_count` mutable, so a relay that appended anything
 * would invalidate the signature and the next hop would drop the message.
 *
 * So the trail travels as its **own record on the bulk plane**, keyed by the
 * message it describes. Every phone writes the one hop it can honestly attest
 * to — "I heard message X at this time, at this position, at this signal
 * strength" — and trails merge when phones sync. docs/04 anticipates exactly
 * this: "each repeater may append (key_id, first_seen_at) to a bulk-plane
 * receipt", and many independent observations are what bracket an untrusted
 * clock.
 *
 * ### Size
 *
 * One hop is 27 bytes and [Proto.HOP_LIMIT] caps a message at 32 hops, so a
 * complete trail is bounded at 32 × 27 + 18 = 882 bytes. That bound is the
 * reason this is safe to carry: it cannot grow without limit no matter how long
 * the message lives or how crowded the mesh gets.
 *
 * ```
 * 0       u8    format version
 * 1..8    8     msg_id this trail describes
 * 9       u8    hop count present, 0..32
 * then per hop, 27 bytes:
 *   0..7   8    key_id of the phone that heard it
 *   8..11  u32  heard_at, unix seconds — that phone's untrusted clock
 *   12..14 3    latitude,  24-bit quantised, or 0xFFFFFF for no fix
 *   15..17 3    longitude, 24-bit quantised
 *   18     u8   hop_count as seen by that phone
 *   19     i8   RSSI in dBm, or 0 when unknown
 *   20..26 7    reserved, zero
 * ```
 *
 * ### What a trail proves, and what it does not
 *
 * Each entry is a claim by one phone. Nothing here is signed per-hop, so a
 * malicious phone can invent an entry or omit its own. What the trail is good
 * for is the honest case: reconstructing how a message crossed a valley, and
 * bracketing when it was really sent by cross-referencing independent
 * observers. Do not present it as proof of custody.
 */
object HopTrail {

    const val VERSION = 1
    const val PROFILE_ID = "trail.v1"

    const val HEADER_SIZE = 10
    const val HOP_SIZE = 27
    const val MAX_HOPS = Proto.HOP_LIMIT

    /** Sentinel in the latitude field meaning "this phone had no fix". */
    private const val NO_FIX = 0xFFFFFF

    class Hop(
        val keyId: ByteArray,
        val heardAt: Long,
        val lat: Double,
        val lon: Double,
        val hopCount: Int,
        val rssi: Int,
    ) {
        val hasFix: Boolean get() = !lat.isNaN() && !lon.isNaN()
    }

    fun encode(msgId: ByteArray, hops: List<Hop>): ByteArray {
        require(msgId.size == Proto.LEN_MSG_ID) { "msg_id must be 8 bytes" }
        val kept = hops.take(MAX_HOPS)
        val out = ByteArray(HEADER_SIZE + kept.size * HOP_SIZE)
        out[0] = VERSION.toByte()
        msgId.copyInto(out, 1)
        out[9] = kept.size.toByte()

        var off = HEADER_SIZE
        for (h in kept) {
            require(h.keyId.size == Proto.LEN_ORIGIN_KEY_ID) { "key id must be 8 bytes" }
            h.keyId.copyInto(out, off)
            Codec.putU32(out, off + 8, h.heardAt)
            if (h.hasFix) {
                val q = GeoQuant.encode(h.lat, h.lon)
                q.copyInto(out, off + 12)
            } else {
                Codec.putU24(out, off + 12, NO_FIX)
                Codec.putU24(out, off + 15, NO_FIX)
            }
            out[off + 18] = h.hopCount.coerceIn(0, 255).toByte()
            out[off + 19] = h.rssi.coerceIn(-128, 127).toByte()
            off += HOP_SIZE
        }
        return out
    }

    class Decoded(val msgId: ByteArray, val hops: List<Hop>)

    /** Returns null on anything malformed. A trail arrives from a stranger. */
    fun decodeOrNull(src: ByteArray): Decoded? {
        if (src.size < HEADER_SIZE) return null
        if (src[0].toInt() != VERSION) return null
        val count = src[9].toInt() and 0xFF
        if (count > MAX_HOPS) return null
        if (src.size != HEADER_SIZE + count * HOP_SIZE) return null

        val msgId = src.copyOfRange(1, 1 + Proto.LEN_MSG_ID)
        val hops = ArrayList<Hop>(count)
        var off = HEADER_SIZE
        repeat(count) {
            val keyId = src.copyOfRange(off, off + 8)
            val heardAt = Codec.getU32(src, off + 8)
            val latRaw = Codec.getU24(src, off + 12)
            val lonRaw = Codec.getU24(src, off + 15)
            val hasFix = latRaw != NO_FIX || lonRaw != NO_FIX
            var lat = Double.NaN
            var lon = Double.NaN
            if (hasFix) {
                val q = ByteArray(6)
                Codec.putU24(q, 0, latRaw)
                Codec.putU24(q, 3, lonRaw)
                val (a, b) = GeoQuant.decode(q)
                lat = a
                lon = b
            }
            hops.add(
                Hop(
                    keyId = keyId,
                    heardAt = heardAt,
                    lat = lat,
                    lon = lon,
                    hopCount = src[off + 18].toInt() and 0xFF,
                    rssi = src[off + 19].toInt(),
                ),
            )
            off += HOP_SIZE
        }
        return Decoded(msgId, hops)
    }

    /**
     * Combines two views of the same message's path.
     *
     * Keyed on the observing phone, keeping the earliest sighting: the first
     * time a phone heard a message is the useful fact, and a later duplicate
     * tells you nothing new. Sorted by hop count so the result reads as a route
     * rather than an arrival order.
     */
    fun merge(a: List<Hop>, b: List<Hop>): List<Hop> {
        val best = LinkedHashMap<String, Hop>()
        for (h in a + b) {
            val k = Codec.hex(h.keyId)
            val existing = best[k]
            if (existing == null || h.heardAt < existing.heardAt) best[k] = h
        }
        return best.values.sortedWith(compareBy({ it.hopCount }, { it.heardAt })).take(MAX_HOPS)
    }
}
