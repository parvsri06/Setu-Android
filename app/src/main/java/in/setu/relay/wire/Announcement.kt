package `in`.setu.relay.wire

/**
 * Information travelling **outward** — from responders to people whose towers
 * are down.
 *
 * Everything else in Setu moves inward: SOS, check-ins, survey records, all
 * flowing from people toward a relief office. That is half a system. A flood
 * warning that cannot reach the village, an evacuation route that is still
 * passable, "a boat is coming to sector 4 at 3pm", "distribution at the school,
 * bring your household card" — those move the other way, and the same mesh
 * carries them.
 *
 * The most valuable case is correcting a rumour. A false report that a road is
 * open can send two hundred people down a washed-out road, and in a blackout a
 * rumour travels faster than a correction unless something carries the
 * correction.
 *
 * ### Authenticity is the whole problem
 *
 * Which is why an announcement is worth nothing unless people can tell a real
 * one from an invented one. Anyone can install this app; if any install could
 * broadcast "the bridge is safe", the feature would be a rumour amplifier with
 * better reach — strictly worse than having no feature.
 *
 * So an announcement carries an **Ed25519 signature by an authority key**, whose
 * public half is compiled into the app. A phone verifies before displaying. An
 * announcement that does not verify is not silently dropped — it is shown
 * marked **unverified**, because in a disaster an unverified message may still
 * be true and hiding it is its own kind of lie. It just never gets to look
 * official.
 *
 * ```
 * 0       u8    format version
 * 1..16   16    announcement id, random, the dedupe key
 * 17..20  u32   issued_at, unix seconds
 * 21      u8    severity: 0 info, 1 advisory, 2 urgent
 * 22      u8    category
 * 23..26  i32   latitude  x 1e6, or 0 with the no-area flag
 * 27..30  i32   longitude x 1e6
 * 31..32  u16   radius in metres this applies to, 0 for everywhere
 * 33      u8    flags: bit0 has area
 * 34..35  u16   body length, then that many bytes of UTF-8
 * ...     64    Ed25519 signature over every byte above
 * ```
 */
object Announcement {

    const val VERSION = 1
    const val PROFILE_ID = "announce.v1"

    const val ID_BYTES = 16
    const val MAX_BODY_BYTES = 700
    const val SIGNATURE_BYTES = 64
    private const val HEADER_SIZE = 36
    private const val FLAG_HAS_AREA = 1
    private const val COORD_SCALE = 1_000_000.0

    object Category {
        const val GENERAL = 0
        const val WARNING = 1
        const val EVACUATION = 2
        const val RELIEF = 3
        const val TRANSPORT = 4
        const val RUMOUR_CORRECTION = 5
        val ALL = listOf(GENERAL, WARNING, EVACUATION, RELIEF, TRANSPORT, RUMOUR_CORRECTION)
    }

    class Decoded(
        val id: ByteArray,
        val issuedAt: Long,
        val severity: Int,
        val category: Int,
        val lat: Double,
        val lon: Double,
        val radiusMetres: Int,
        val body: String,
        val signature: ByteArray,
        /** Bytes the signature covers, kept so the caller can verify. */
        val signedBytes: ByteArray,
    ) {
        val hasArea: Boolean get() = !lat.isNaN() && !lon.isNaN()
    }

    /**
     * Builds the bytes an authority signs. Split from [encode] so the signing
     * key never has to be handed to this object.
     */
    fun signingBytes(
        id: ByteArray,
        issuedAt: Long,
        severity: Int,
        category: Int,
        lat: Double,
        lon: Double,
        radiusMetres: Int,
        body: String,
    ): ByteArray {
        require(id.size == ID_BYTES) { "announcement id must be 16 bytes" }
        var encoded = body.toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_BODY_BYTES) {
            var chars = body.length
            while (chars > 0 &&
                body.substring(0, chars).toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES
            ) {
                chars--
            }
            encoded = body.substring(0, chars).toByteArray(Charsets.UTF_8)
        }

        val hasArea = !lat.isNaN() && !lon.isNaN()
        val out = ByteArray(HEADER_SIZE + encoded.size)
        out[0] = VERSION.toByte()
        id.copyInto(out, 1)
        Codec.putU32(out, 17, issuedAt)
        out[21] = severity.coerceIn(0, 2).toByte()
        out[22] = category.toByte()
        Codec.putU32(out, 23, if (hasArea) Math.round(lat * COORD_SCALE).toLong() and 0xFFFFFFFFL else 0L)
        Codec.putU32(out, 27, if (hasArea) Math.round(lon * COORD_SCALE).toLong() and 0xFFFFFFFFL else 0L)
        Codec.putU16(out, 31, radiusMetres.coerceIn(0, 65535))
        out[33] = (if (hasArea) FLAG_HAS_AREA else 0).toByte()
        Codec.putU16(out, 34, encoded.size)
        encoded.copyInto(out, HEADER_SIZE)
        return out
    }

    /** Appends the signature produced over [signingBytes]. */
    fun encode(signingBytes: ByteArray, signature: ByteArray): ByteArray {
        require(signature.size == SIGNATURE_BYTES) { "signature must be 64 bytes" }
        val out = ByteArray(signingBytes.size + SIGNATURE_BYTES)
        signingBytes.copyInto(out, 0)
        signature.copyInto(out, signingBytes.size)
        return out
    }

    /**
     * Structural decode only. **Does not verify the signature** — the caller
     * does that, because whether an announcement is trusted is a decision the UI
     * has to show, not one this object may quietly make.
     */
    fun decodeOrNull(src: ByteArray): Decoded? {
        if (src.size < HEADER_SIZE + SIGNATURE_BYTES) return null
        if (src[0].toInt() != VERSION) return null
        val bodyLen = Codec.getU16(src, 34)
        if (bodyLen > MAX_BODY_BYTES) return null
        val expected = HEADER_SIZE + bodyLen + SIGNATURE_BYTES
        if (src.size != expected) return null

        val hasArea = src[33].toInt() and FLAG_HAS_AREA != 0
        val latRaw = Codec.getU32(src, 23).toInt()
        val lonRaw = Codec.getU32(src, 27).toInt()

        return Decoded(
            id = src.copyOfRange(1, 1 + ID_BYTES),
            issuedAt = Codec.getU32(src, 17),
            severity = src[21].toInt() and 0xFF,
            category = src[22].toInt() and 0xFF,
            lat = if (hasArea) latRaw / COORD_SCALE else Double.NaN,
            lon = if (hasArea) lonRaw / COORD_SCALE else Double.NaN,
            radiusMetres = Codec.getU16(src, 31),
            body = String(src, HEADER_SIZE, bodyLen, Charsets.UTF_8),
            signature = src.copyOfRange(src.size - SIGNATURE_BYTES, src.size),
            signedBytes = src.copyOfRange(0, src.size - SIGNATURE_BYTES),
        )
    }
}
