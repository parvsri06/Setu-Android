package `in`.setu.relay.relay

import kotlin.random.Random

/**
 * The advertising schedule from docs/03-relay-algorithm.md, with no Android in
 * it, so it can be unit-tested against a fake clock and a seeded RNG.
 *
 * The three details that matter, all enforced here:
 *  1. a random backoff before the first decision, so co-located devices do not
 *     rebroadcast on the same instant and annihilate each other;
 *  2. the interval never reaches zero and never becomes infinite before TTL —
 *     a carried message backs off to a whisper rather than going silent (D4);
 *  3. suppression is disabled below [RelayParams.SUPPRESS_MIN_DEGREE] neighbours.
 *
 * `age` is measured from when *this device* first stored the message, not from
 * the envelope's `created_at`. Recorded as D15 in MEMORY.md: `created_at` is an
 * untrusted claim (docs/04-security-model.md), and deriving radio behaviour from
 * it would let one phone with a wrong or hostile clock pin its whole
 * neighbourhood at a 500 ms interval indefinitely. Local age is monotonic and
 * gives every new carrier its own fresh aggressive window, which is what
 * epidemic spread wants.
 */
class BackoffScheduler(
    private val random: Random = Random.Default,
    private val suppressionEnabled: Boolean = RelayParams.SUPPRESSION_ENABLED,
) {

    class Entry(
        val msgId: String,
        val tier: Int,
        /** Local time this device first stored the message. */
        val storedAtMs: Long,
        /** Absolute local time the message stops being worth carrying. */
        val expiresAtMs: Long,
        /** End of the random backoff window; no advertising happens before this. */
        val decisionAtMs: Long,
    ) {
        var nextAdvertiseAtMs: Long = decisionAtMs
        var quiet: Boolean = false
        var burstCount: Int = 0
    }

    private val entries = LinkedHashMap<String, Entry>()

    /** Snapshot for the UI and for tests. */
    fun entries(): List<Entry> = synchronized(entries) { entries.values.toList() }

    fun entry(msgId: String): Entry? = synchronized(entries) { entries[msgId] }

    fun size(): Int = synchronized(entries) { entries.size }

    fun remove(msgId: String) = synchronized(entries) { entries.remove(msgId); Unit }

    fun clear() = synchronized(entries) { entries.clear() }

    /**
     * Registers a newly stored message. The decision moment is placed uniformly
     * in `[now, now + BACKOFF_MAX_MS)`.
     */
    fun track(msgId: String, tier: Int, nowMs: Long, expiresAtMs: Long): Entry {
        val jitter = random.nextLong(RelayParams.BACKOFF_MAX_MS)
        val e = Entry(msgId, tier, nowMs, expiresAtMs, nowMs + jitter)
        synchronized(entries) { entries[msgId] = e }
        return e
    }

    /** Restores an entry after a service restart, without re-randomising the past. */
    fun restore(msgId: String, tier: Int, storedAtMs: Long, expiresAtMs: Long, nowMs: Long): Entry {
        val e = Entry(msgId, tier, storedAtMs, expiresAtMs, nowMs + random.nextLong(RelayParams.BACKOFF_MAX_MS))
        synchronized(entries) { entries[msgId] = e }
        return e
    }

    /** Advertising interval for a message of the given local age. */
    fun intervalFor(ageMs: Long): Long =
        RelayParams.SCHEDULE.first { ageMs < it.first }.second

    /**
     * The suppression check. Neighbours have already covered my neighbourhood
     * only if I heard enough duplicates AND the neighbourhood is dense. Below
     * [RelayParams.SUPPRESS_MIN_DEGREE] the medium is idle anyway and staying
     * quiet risks breaking the only chain out.
     */
    fun shouldSuppress(duplicates: Int, neighbourCount: Int): Boolean =
        suppressionEnabled &&
            duplicates >= RelayParams.SUPPRESS_THRESHOLD &&
            neighbourCount >= RelayParams.SUPPRESS_MIN_DEGREE

    /**
     * Whether a message may use the radio at this battery level. Tier 0 always
     * may; everything else stops below the floor.
     */
    fun allowedAtBattery(tier: Int, batteryPct: Int): Boolean =
        tier == 0 || batteryPct >= RelayParams.BATTERY_FLOOR_PCT

    /**
     * Messages whose next advertising slot has arrived, in priority order
     * (tier ascending, then oldest first). Expired entries are dropped.
     */
    fun due(
        nowMs: Long,
        batteryPct: Int,
        duplicatesOf: (String) -> Int,
        neighbourCount: Int,
    ): List<Entry> {
        val ready = ArrayList<Entry>()
        synchronized(entries) {
            val it = entries.values.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (nowMs >= e.expiresAtMs) {
                    it.remove()
                    continue
                }
                if (nowMs < e.nextAdvertiseAtMs) continue
                if (!allowedAtBattery(e.tier, batteryPct)) continue
                if (e.burstCount == 0 && shouldSuppress(duplicatesOf(e.msgId), neighbourCount)) {
                    // Decided to stay quiet. Re-check on the next slot rather
                    // than going silent for good: neighbour density changes.
                    e.quiet = true
                    e.nextAdvertiseAtMs = nowMs + intervalFor(nowMs - e.storedAtMs)
                    continue
                }
                e.quiet = false
                ready.add(e)
            }
        }
        ready.sortWith(compareBy({ it.tier }, { it.storedAtMs }))
        return ready
    }

    /** Called after a burst actually went on air. */
    fun onAdvertised(e: Entry, nowMs: Long) {
        e.burstCount++
        e.nextAdvertiseAtMs = nowMs + intervalFor(nowMs - e.storedAtMs)
    }

    /** Shortest wait until any tracked message is next due. Drives the tick rate. */
    fun nextWakeMs(nowMs: Long): Long = synchronized(entries) {
        entries.values.minOfOrNull { (it.nextAdvertiseAtMs - nowMs).coerceAtLeast(0L) }
            ?: RelayParams.SCHEDULE.last().second
    }
}
