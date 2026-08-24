package `in`.setu.relay

import `in`.setu.relay.relay.BackoffScheduler
import `in`.setu.relay.relay.RelayParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * docs/09-test-plan.md — relay logic against a fake clock, no radio involved.
 */
class BackoffTest {

    private fun scheduler(seed: Int = 42, suppression: Boolean = false) =
        BackoffScheduler(Random(seed), suppressionEnabled = suppression)

    @Test
    fun `interval follows the schedule as age crosses each boundary`() {
        val s = scheduler()
        assertEquals(500L, s.intervalFor(0))
        assertEquals(500L, s.intervalFor(9_999))
        assertEquals(2_000L, s.intervalFor(10_000))
        assertEquals(2_000L, s.intervalFor(29_999))
        assertEquals(10_000L, s.intervalFor(30_000))
        assertEquals(10_000L, s.intervalFor(119_999))
        assertEquals(30_000L, s.intervalFor(120_000))
    }

    /**
     * Regression test for D4. An earlier design stopped advertising after a
     * fixed 8-second window and lost 23 points of coverage on sparse chains.
     */
    @Test
    fun `a message never reaches interval zero or infinity before TTL`() {
        val s = scheduler()
        var age = 0L
        while (age < 24L * 3_600_000L) {
            val interval = s.intervalFor(age)
            assertTrue("interval went to zero at age $age", interval > 0)
            assertTrue("interval became unbounded at age $age", interval < 60_000L)
            age += 997L * 60   // walk a whole day in uneven steps
        }
    }

    @Test
    fun `the backoff window is random and inside the bound`() {
        val decisions = (1..200).map { seed ->
            val s = scheduler(seed)
            s.track("m$seed", 0, nowMs = 0, expiresAtMs = 3_600_000L).decisionAtMs
        }
        decisions.forEach {
            assertTrue(it in 0 until RelayParams.BACKOFF_MAX_MS)
        }
        assertTrue("backoff must actually vary", decisions.distinct().size > 100)
    }

    @Test
    fun `nothing is advertised before its decision moment`() {
        val s = scheduler()
        val e = s.track("m", 0, nowMs = 0, expiresAtMs = 3_600_000L)
        assertTrue(s.due(0, 100, { 0 }, 0).isEmpty())
        assertEquals(1, s.due(e.decisionAtMs, 100, { 0 }, 0).size)
    }

    @Test
    fun `below the battery floor tier 0 keeps advertising and tiers 1 to 4 stop`() {
        val s = scheduler()
        for (tier in 0..4) {
            assertTrue(s.allowedAtBattery(tier, 100))
            assertTrue(s.allowedAtBattery(tier, RelayParams.BATTERY_FLOOR_PCT))
        }
        assertTrue(s.allowedAtBattery(0, 3))
        for (tier in 1..4) {
            assertFalse("tier $tier must stop below the floor", s.allowedAtBattery(tier, 3))
        }
    }

    @Test
    fun `battery floor is enforced by the due list`() {
        val s = scheduler()
        val sos = s.track("sos", 0, nowMs = 0, expiresAtMs = 3_600_000L)
        val checkIn = s.track("checkin", 1, nowMs = 0, expiresAtMs = 3_600_000L)
        val at = maxOf(sos.decisionAtMs, checkIn.decisionAtMs)

        val healthy = s.due(at, 90, { 0 }, 0).map { it.msgId }.toSet()
        assertEquals(setOf("sos", "checkin"), healthy)

        val flat = s.due(at + 60_000, 5, { 0 }, 0).map { it.msgId }
        assertEquals(listOf("sos"), flat)
    }

    @Test
    fun `due list is ordered by tier then age`() {
        val s = scheduler()
        s.track("profile", 4, nowMs = 0, expiresAtMs = 3_600_000L)
        s.track("sos-new", 0, nowMs = 100, expiresAtMs = 3_600_000L)
        s.track("sos-old", 0, nowMs = 0, expiresAtMs = 3_600_000L)
        s.track("checkin", 1, nowMs = 0, expiresAtMs = 3_600_000L)

        val order = s.due(RelayParams.BACKOFF_MAX_MS + 1, 100, { 0 }, 0).map { it.msgId }
        assertEquals(listOf("sos-old", "sos-new", "checkin", "profile"), order)
    }

    @Test
    fun `expired entries drop out of the due list`() {
        val s = scheduler()
        s.track("m", 0, nowMs = 0, expiresAtMs = 5_000L)
        assertTrue(s.due(10_000L, 100, { 0 }, 0).isEmpty())
        assertEquals(0, s.size())
    }

    // ------------------------------------------------------------ suppression

    @Test
    fun `suppression is off by default per D3`() {
        assertFalse(RelayParams.SUPPRESSION_ENABLED)
        val s = scheduler(suppression = false)
        assertFalse(s.shouldSuppress(duplicates = 99, neighbourCount = 99))
    }

    @Test
    fun `suppression fires at the threshold in a dense neighbourhood`() {
        val s = scheduler(suppression = true)
        assertTrue(
            s.shouldSuppress(
                duplicates = RelayParams.SUPPRESS_THRESHOLD,
                neighbourCount = RelayParams.SUPPRESS_MIN_DEGREE,
            ),
        )
        assertFalse(
            s.shouldSuppress(
                duplicates = RelayParams.SUPPRESS_THRESHOLD - 1,
                neighbourCount = RelayParams.SUPPRESS_MIN_DEGREE,
            ),
        )
    }

    @Test
    fun `suppression never fires below the minimum degree`() {
        val s = scheduler(suppression = true)
        for (degree in 0 until RelayParams.SUPPRESS_MIN_DEGREE) {
            assertFalse(
                "staying quiet at $degree neighbours risks breaking the only chain out",
                s.shouldSuppress(duplicates = 1000, neighbourCount = degree),
            )
        }
    }

    @Test
    fun `a suppressed message is rechecked on the next slot, not silenced`() {
        val s = scheduler(suppression = true)
        val e = s.track("m", 0, nowMs = 0, expiresAtMs = 3_600_000L)
        val at = e.decisionAtMs

        // Dense and noisy: stays quiet.
        assertTrue(s.due(at, 100, { RelayParams.SUPPRESS_THRESHOLD }, 20).isEmpty())
        assertTrue(e.quiet)
        assertTrue("must have a next slot, not be abandoned", e.nextAdvertiseAtMs > at)

        // The crowd thins out. It speaks again.
        val ready = s.due(e.nextAdvertiseAtMs, 100, { RelayParams.SUPPRESS_THRESHOLD }, 2)
        assertEquals(listOf("m"), ready.map { it.msgId })
        assertFalse(e.quiet)
    }

    @Test
    fun `after a burst the next slot follows the schedule`() {
        val s = scheduler()
        val e = s.track("m", 0, nowMs = 0, expiresAtMs = 24L * 3_600_000L)
        s.onAdvertised(e, 1_000L)
        assertEquals(1_000L + 500L, e.nextAdvertiseAtMs)
        s.onAdvertised(e, 40_000L)
        assertEquals(40_000L + 10_000L, e.nextAdvertiseAtMs)
        s.onAdvertised(e, 600_000L)
        assertEquals(600_000L + 30_000L, e.nextAdvertiseAtMs)
    }

    @Test
    fun `next wake never returns a negative delay`() {
        val s = scheduler()
        s.track("m", 0, nowMs = 0, expiresAtMs = 3_600_000L)
        assertTrue(s.nextWakeMs(1_000_000L) >= 0)
    }
}
