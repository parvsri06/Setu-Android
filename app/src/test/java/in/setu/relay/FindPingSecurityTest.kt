package `in`.setu.relay

import `in`.setu.relay.relay.RelayParams
import `in`.setu.relay.wire.Bodies
import `in`.setu.relay.wire.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards on the find-ping blast radius.
 *
 * A find ping makes a stranger's phone scream at full volume, flash its torch
 * and vibrate. It is an unauthenticated packet — key exchange is phase 5, so a
 * beacon from an unknown key verifies as NO_KEY and is still relayed — which
 * means anything able to craft a BLE advertisement can send one.
 *
 * The controls that bound it are a duration cap and a receive cooldown. Both are
 * a single constant each, and both would be easy to widen later without anyone
 * noticing what it cost. These tests exist to make that deliberate.
 */
class FindPingSecurityTest {

    @Test
    fun `a ping cannot ask for more than the capped duration`() {
        // The duration is chosen by the sender, so on an unauthenticated packet
        // it is chosen by an attacker. An absurd request must clamp.
        val body = Bodies.findPingBody(null, seconds = 9_999, issuedAt = 1)
        assertEquals(Bodies.FIND_MAX_SECONDS, Bodies.findPingSeconds(body))
    }

    @Test
    fun `a ping cannot ask for a zero or negative duration`() {
        val body = Bodies.findPingBody(null, seconds = -5, issuedAt = 1)
        assertTrue(Bodies.findPingSeconds(body) >= 1)
    }

    @Test
    fun `a hostile byte in the duration field still decodes inside the cap`() {
        // Straight off the radio, with no encoder in the way.
        val body = ByteArray(Proto.LEN_SEALED_BODY)
        body[8] = 0xFF.toByte()
        val seconds = Bodies.findPingSeconds(body)
        assertTrue("got $seconds", seconds in 1..Bodies.FIND_MAX_SECONDS)
    }

    @Test
    fun `the duration cap stays small enough to survive repetition`() {
        // 30 s of torch, siren and vibration is already the most power-hungry
        // thing a handset can do. Anything approaching a minute per ping makes
        // battery exhaustion cheap for an attacker.
        assertTrue(
            "FIND_MAX_SECONDS is ${Bodies.FIND_MAX_SECONDS}",
            Bodies.FIND_MAX_SECONDS in 1..30,
        )
    }

    @Test
    fun `the cooldown is longer than the alarm it gates`() {
        // If a phone could be re-triggered before the previous burst ended, the
        // rate limit would not bound anything — the alarm would be continuous.
        assertTrue(
            "cooldown ${RelayParams.FIND_COOLDOWN_MS}ms vs burst ${Bodies.FIND_MAX_SECONDS}s",
            RelayParams.FIND_COOLDOWN_MS > Bodies.FIND_MAX_SECONDS * 1000L,
        )
    }

    @Test
    fun `the cooldown bounds duty cycle to a small fraction of the time`() {
        // The number that actually decides how fast a battery can be drained.
        val duty = (Bodies.FIND_MAX_SECONDS * 1000.0) / RelayParams.FIND_COOLDOWN_MS
        assertTrue("duty cycle is $duty", duty <= 0.5)
    }

    @Test
    fun `a broadcast ping is distinguishable from a targeted one`() {
        val broadcast = Bodies.findPingBody(null, 30, 1)
        assertTrue(Bodies.findPingIsBroadcast(broadcast))

        val target = ByteArray(Proto.LEN_ORIGIN_KEY_ID) { (it + 1).toByte() }
        val targeted = Bodies.findPingBody(target, 30, 1)
        assertFalse(Bodies.findPingIsBroadcast(targeted))
        assertTrue(Bodies.findPingTarget(targeted).contentEquals(target))
    }

    @Test
    fun `a ping body is exactly the fixed sealed-body size`() {
        // Anything else would change the envelope length, which is a hard rule.
        assertEquals(Proto.LEN_SEALED_BODY, Bodies.findPingBody(null, 30, 1).size)
    }
}
