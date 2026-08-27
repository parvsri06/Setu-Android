package `in`.setu.relay.relay

import `in`.setu.relay.wire.Codec
import kotlin.math.pow

/**
 * The responder scanner: how close is each nearby phone, and am I getting
 * warmer?
 *
 * BLE gives no direction, only signal strength, so this deliberately does not
 * pretend to give a bearing. What it gives is a distance band and a **trend**,
 * which is what an avalanche transceiver actually gives you too — you sweep,
 * you watch the number, you walk toward stronger. A rescuer over rubble does not
 * need a compass arrow; they need to know whether the last three steps helped.
 *
 * Nothing here is precise and the UI must not imply it is. RSSI through mud,
 * bodies and wet clothing is noisy, and a phone in a pocket reads very
 * differently from one held up. Bands, not metres.
 */
class Scanner {

    /** One phone as the scanner sees it right now. */
    class Contact(
        val keyId: ByteArray,
        val rssi: Int,
        val smoothedRssi: Double,
        val lastSeenAt: Long,
        val trend: Trend,
        val samples: Int,
    ) {
        val keyIdHex: String get() = Codec.hex(keyId)

        /**
         * A rough band, never a number in metres.
         *
         * The thresholds come from the log-distance path loss model with the
         * conservative exponent docs/05 uses for wet environments. They are
         * indicative and will be wrong in any specific case.
         */
        val band: Band
            get() = when {
                smoothedRssi >= -55 -> Band.VERY_CLOSE
                smoothedRssi >= -70 -> Band.CLOSE
                smoothedRssi >= -85 -> Band.NEARBY
                else -> Band.FAR
            }

        /** Indicative only. Shown as "about N m", never as a fix. */
        val approxMetres: Double
            get() = 10.0.pow((TX_POWER_AT_1M - smoothedRssi) / (10.0 * PATH_LOSS_EXPONENT))
    }

    enum class Band { VERY_CLOSE, CLOSE, NEARBY, FAR }

    enum class Trend { WARMER, COLDER, STEADY, UNKNOWN }

    private class Track {
        var smoothed = Double.NaN
        var previousSmoothed = Double.NaN
        var lastRssi = 0
        var lastSeenAt = 0L
        var samples = 0
    }

    private val tracks = LinkedHashMap<String, Track>()
    private val lock = Any()

    /** Feeds one sighting in. Called for every presence beacon heard. */
    fun observe(keyId: ByteArray, rssi: Int, nowMs: Long) = synchronized(lock) {
        val t = tracks.getOrPut(Codec.hex(keyId)) { Track() }
        t.previousSmoothed = t.smoothed
        // Exponential moving average. RSSI jumps several dB between adjacent
        // packets even when nothing moves, and an unsmoothed reading makes a
        // rescuer chase noise instead of the signal.
        t.smoothed = if (t.smoothed.isNaN()) rssi.toDouble() else ALPHA * rssi + (1 - ALPHA) * t.smoothed
        t.lastRssi = rssi
        t.lastSeenAt = nowMs
        t.samples++
        Unit
    }

    /** Everything heard within [freshMs], strongest first. */
    fun contacts(nowMs: Long, freshMs: Long = 30_000L): List<Contact> = synchronized(lock) {
        tracks.entries
            .filter { nowMs - it.value.lastSeenAt <= freshMs }
            .map { (hex, t) ->
                Contact(
                    keyId = Codec.unhex(hex),
                    rssi = t.lastRssi,
                    smoothedRssi = t.smoothed,
                    lastSeenAt = t.lastSeenAt,
                    trend = trendOf(t),
                    samples = t.samples,
                )
            }
            .sortedByDescending { it.smoothedRssi }
    }

    private fun trendOf(t: Track): Trend = when {
        t.previousSmoothed.isNaN() || t.samples < 3 -> Trend.UNKNOWN
        t.smoothed - t.previousSmoothed > TREND_DB -> Trend.WARMER
        t.previousSmoothed - t.smoothed > TREND_DB -> Trend.COLDER
        else -> Trend.STEADY
    }

    fun forget() = synchronized(lock) { tracks.clear() }

    private companion object {
        /** Heavier smoothing than usual: a rescuer sweeping wants a stable needle. */
        const val ALPHA = 0.25

        /** Below this the change is indistinguishable from RSSI noise. */
        const val TREND_DB = 1.5

        /** Typical BLE RSSI at one metre. Handset-dependent; a rough anchor. */
        const val TX_POWER_AT_1M = -59.0

        /**
         * 2.7 rather than free space's 2.0. docs/05: the deployment is wet air,
         * wet clothing and bodies, which is why effective range is ~20 m and not
         * the 100 m on a datasheet.
         */
        const val PATH_LOSS_EXPONENT = 2.7
    }
}
