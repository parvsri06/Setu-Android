package `in`.setu.relay.relay

/** Constants from docs/03-relay-algorithm.md. */
object RelayParams {
    /** Random wait before deciding whether to repeat. Not optional — see detail 1. */
    const val BACKOFF_MAX_MS = 3_000L

    /** Duplicates heard before a device may stay quiet. */
    const val SUPPRESS_THRESHOLD = 3

    /** Below this many neighbours, always repeat. */
    const val SUPPRESS_MIN_DEGREE = 12

    const val HOP_LIMIT = 32

    /** SCAN_MODE_BALANCED, roughly 15% duty. The lever that decides battery. */
    const val SCAN_DUTY = 0.15

    /** Below this battery level, only tier 0 advertises. */
    const val BATTERY_FLOOR_PCT = 15

    /**
     * (message age upper bound, advertising interval).
     * Never zero, never infinite — a carried message keeps whispering until TTL.
     * Regression guard for D4 in MEMORY.md.
     */
    val SCHEDULE: List<Pair<Long, Long>> = listOf(
        10_000L to 500L,
        30_000L to 2_000L,
        120_000L to 10_000L,
        Long.MAX_VALUE to 30_000L,
    )

    /**
     * Suppression is phase 8. Per D3 in MEMORY.md the backoff schedule alone
     * already delivers 100% at every crowd size tested; suppression only halves
     * channel load on top of that. The logic exists and is unit-tested, but it
     * stays off until field data shows load is a real problem.
     */
    const val SUPPRESSION_ENABLED = false

    /** Shortest burst. Used when a message is young and repeats often anyway. */
    const val BURST_MIN_MS = 300L

    /**
     * Longest burst. SCAN_MODE_BALANCED listens for roughly 1024 ms out of every
     * 4096 ms, so a burst at least that long is almost certain to overlap a
     * peer's listening window rather than land in its blind spot.
     */
    const val BURST_MAX_MS = 1_200L

    /**
     * How long a burst stays on air, given the message's current advertising
     * interval from [SCHEDULE].
     *
     * A fixed 300 ms burst was wrong and it is why devices "sometimes" failed to
     * see each other. Against a ~25% duty-cycle scanner a 300 ms burst overlaps a
     * listening window only about a quarter of the time. A young message repeats
     * every 500 ms so it gets many chances per second and always arrived; but
     * past 120 s the schedule drops to one burst per 30 s, and one 25% chance
     * every 30 s stretches expected detection into minutes.
     *
     * So the burst grows as the gap grows: rare whispers are made long enough to
     * be heard, frequent ones stay short. Airtime is bounded either way — at the
     * 30 s interval this is 1.2 s on air per 30 s, or 4% duty.
     *
     * This is a demo-scale trade. It buys detection probability with channel
     * load, which is the opposite of D5's instinct at 400-phone density, so it
     * is capped and flagged for revisiting with field data.
     */
    fun burstMsFor(intervalMs: Long): Long =
        (intervalMs / 25).coerceIn(BURST_MIN_MS, BURST_MAX_MS)

    /** Neighbour freshness window for neighbourCount(). */
    const val NEIGHBOUR_WINDOW_MS = 60_000L

    /**
     * How often the presence beacon repeats. Small, cheap, and independent of
     * whether this phone has anything to say — see PresenceAdvertiser.
     */
    const val PRESENCE_INTERVAL_MS = 1_000L
}
