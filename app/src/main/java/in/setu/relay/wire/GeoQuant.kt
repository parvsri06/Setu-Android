package `in`.setu.relay.wire

import kotlin.math.roundToInt

/**
 * 6-byte location encoding for the sealed body. See docs/02-wire-protocol.md.
 *
 * latitude  : (lat + 90)  * 93206.75  -> 24 bit, ~1.2 m
 * longitude : (lon + 180) * 46603.375 -> 24 bit, ~2.4 m
 *
 * The longitude scale is written as 46603.4 in the spec table; the exact value
 * that maps the full 360 degree range onto 24 bits without overflow is
 * (2^24 - 1) / 360 = 46603.375, which is what is implemented here. The
 * resolution figure in the spec (~2.4 m) is unchanged.
 */
object GeoQuant {

    const val LAT_SCALE = 93206.75      // (2^24 - 1) / 180
    const val LON_SCALE = 46603.375     // (2^24 - 1) / 360
    const val MAX_U24 = 0xFFFFFF

    const val ENCODED_SIZE = 6

    fun encode(latDeg: Double, lonDeg: Double): ByteArray {
        val lat = latDeg.coerceIn(-90.0, 90.0)
        val lon = lonDeg.coerceIn(-180.0, 180.0)
        val q1 = ((lat + 90.0) * LAT_SCALE).roundToInt().coerceIn(0, MAX_U24)
        val q2 = ((lon + 180.0) * LON_SCALE).roundToInt().coerceIn(0, MAX_U24)
        val out = ByteArray(ENCODED_SIZE)
        Codec.putU24(out, 0, q1)
        Codec.putU24(out, 3, q2)
        return out
    }

    /** Returns (latitude, longitude) in degrees. */
    fun decode(b: ByteArray, off: Int = 0): Pair<Double, Double> {
        require(b.size - off >= ENCODED_SIZE) { "need $ENCODED_SIZE bytes" }
        val lat = Codec.getU24(b, off) / LAT_SCALE - 90.0
        val lon = Codec.getU24(b, off + 3) / LON_SCALE - 180.0
        return lat to lon
    }
}
