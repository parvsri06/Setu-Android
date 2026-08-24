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

    /** How long one advertising burst stays on air. */
    const val BURST_MS = 300L

    /** Neighbour freshness window for neighbourCount(). */
    const val NEIGHBOUR_WINDOW_MS = 60_000L
}
