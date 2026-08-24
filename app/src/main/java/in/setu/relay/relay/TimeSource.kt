package `in`.setu.relay.relay

import android.os.SystemClock
import android.util.Log

/**
 * Untrusted-time handling from docs/04-security-model.md.
 *
 * An offline phone has no trusted clock: a user can change it and a dead battery
 * resets it. So `created_at` is a claim, not a fact. This class does the third
 * of the three corroborations the spec asks for — the monotonic delta — and
 * exposes the result so the UI can say "claimed" rather than presenting a device
 * timestamp as established fact.
 *
 * Relay observations and server arrival time are the other two, and belong to
 * phases 4 and 5.
 */
object TimeSource {

    private const val TAG = "SetuTime"

    /** Wall clock at app start, paired with the monotonic clock at the same instant. */
    private val bootWallMs = System.currentTimeMillis()
    private val bootElapsedMs = SystemClock.elapsedRealtime()

    @Volatile
    var wallClockJumped: Boolean = false
        private set

    @Volatile
    var lastJumpMs: Long = 0L
        private set

    fun wallMs(): Long {
        val now = System.currentTimeMillis()
        val expected = bootWallMs + (SystemClock.elapsedRealtime() - bootElapsedMs)
        val drift = now - expected
        if (kotlin.math.abs(drift) > TOLERANCE_MS && !wallClockJumped) {
            wallClockJumped = true
            lastJumpMs = drift
            Log.w(TAG, "wall clock moved by ${drift}ms relative to elapsedRealtime")
        }
        return now
    }

    fun wallSeconds(): Long = wallMs() / 1000L

    /** Never goes backwards. Used for every scheduling decision. */
    fun monotonicMs(): Long = SystemClock.elapsedRealtime()

    /**
     * A claimed `created_at` converted to a local expiry, clamped so a phone
     * claiming a far-future creation time cannot make a message immortal, and a
     * phone claiming the distant past cannot make it expire on arrival.
     */
    fun expiryFromClaim(createdAtSec: Long, ttlHours: Int, receivedAtMs: Long): Long {
        val ttlMs = ttlHours.toLong() * 3_600_000L
        val claimed = createdAtSec * 1000L + ttlMs
        val floor = receivedAtMs + MIN_CARRY_MS
        val ceiling = receivedAtMs + ttlMs
        return claimed.coerceIn(minOf(floor, ceiling), ceiling)
    }

    /** Difference in seconds between a claim and this device's clock. */
    fun claimSkewSeconds(createdAtSec: Long): Long = wallSeconds() - createdAtSec

    private const val TOLERANCE_MS = 5_000L

    /** Even a message with an absurd claimed time gets carried this long. */
    private const val MIN_CARRY_MS = 10 * 60_000L
}
