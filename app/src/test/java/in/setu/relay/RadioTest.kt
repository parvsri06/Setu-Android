package `in`.setu.relay

import `in`.setu.relay.radio.beacon.BeaconFormat
import `in`.setu.relay.relay.RelayParams
import `in`.setu.relay.wire.Frag
import `in`.setu.relay.wire.Proto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Regression tests for the "sometimes not detecting other devices" bug.
 *
 * Two independent causes, both covered here: idle phones sent nothing at all so
 * they were invisible, and a fixed 300 ms burst was too short to reliably land
 * inside a peer's scan window once the schedule slowed down.
 */
class RadioTest {

    // ------------------------------------------------------- presence beacon

    @Test
    fun `presence beacon round-trips the key id`() {
        val keyId = Random(7).nextBytes(Proto.LEN_ORIGIN_KEY_ID)
        val wrapped = BeaconFormat.wrapPresence(keyId, bulkAvailable = false)

        assertEquals(BeaconFormat.PRESENCE_SIZE, wrapped.size)
        assertArrayEquals(keyId, BeaconFormat.unwrapPresence(wrapped))
    }

    @Test
    fun `presence beacon fits a legacy advertisement`() {
        // 31 bytes total, minus 3 for the flags AD and 4 for the manufacturer
        // data header, leaves 24 for the payload. Presence must fit on every
        // handset with no fragmentation, or idle phones stay invisible on
        // exactly the cheap hardware this app is for.
        assertTrue(
            "presence payload ${BeaconFormat.PRESENCE_SIZE} exceeds the legacy budget",
            BeaconFormat.PRESENCE_SIZE <= 24,
        )
    }

    @Test
    fun `presence carries the bulk-plane flag`() {
        val keyId = ByteArray(Proto.LEN_ORIGIN_KEY_ID)
        val off = BeaconFormat.wrapPresence(keyId, bulkAvailable = false)
        val on = BeaconFormat.wrapPresence(keyId, bulkAvailable = true)

        assertEquals(0, off[10].toInt() and BeaconFormat.PRESENCE_FLAG_BULK)
        assertEquals(
            BeaconFormat.PRESENCE_FLAG_BULK,
            on[10].toInt() and BeaconFormat.PRESENCE_FLAG_BULK,
        )
    }

    @Test
    fun `presence and envelope beacons are never confused`() {
        val envelope = Random(11).nextBytes(Proto.ENVELOPE_SIZE)
        val beacon = BeaconFormat.wrapExtended(envelope)
        val presence = BeaconFormat.wrapPresence(Random(12).nextBytes(8), false)

        assertNull("an envelope beacon must not read as presence", BeaconFormat.unwrapPresence(beacon))
        assertNull("a presence beacon must not read as an envelope", BeaconFormat.unwrapExtended(presence))
        assertFalse(BeaconFormat.isLegacyFragment(presence))
    }

    @Test
    fun `a fragment starting with the presence magic is still a fragment`() {
        // The first two bytes of a fragment are msg_id, so they are random and
        // will occasionally be 'S','P'. Discarding those would silently drop
        // about one fragment in 65536.
        val fragment = ByteArray(Frag.FRAG_SIZE)
        fragment[0] = BeaconFormat.MAGIC_0
        fragment[1] = BeaconFormat.MAGIC_P

        assertTrue(BeaconFormat.isLegacyFragment(fragment))
        assertNull(BeaconFormat.unwrapPresence(fragment))
    }

    // -------------------------------------------------------- adaptive burst

    @Test
    fun `burst length grows as the advertising gap grows`() {
        // A young message repeats twice a second, so a short burst is fine — it
        // gets many chances per second. A message on the 30 s tail gets one
        // chance per 30 s and has to be long enough to be heard on that one.
        val young = RelayParams.burstMsFor(500L)
        val old = RelayParams.burstMsFor(30_000L)

        assertEquals(RelayParams.BURST_MIN_MS, young)
        assertEquals(RelayParams.BURST_MAX_MS, old)
        assertTrue(old > young)
    }

    @Test
    fun `the longest burst covers a full balanced scan window`() {
        // SCAN_MODE_BALANCED listens about 1024 ms in every 4096 ms. A burst
        // shorter than that window can fall entirely into a peer's blind spot,
        // which is exactly the bug being fixed.
        assertTrue(
            "max burst ${RelayParams.BURST_MAX_MS}ms is shorter than a 1024ms scan window",
            RelayParams.BURST_MAX_MS >= 1024L,
        )
    }

    @Test
    fun `burst length is bounded for every interval in the schedule`() {
        for ((_, interval) in RelayParams.SCHEDULE) {
            val burst = RelayParams.burstMsFor(interval)
            assertTrue("burst $burst below floor", burst >= RelayParams.BURST_MIN_MS)
            assertTrue("burst $burst above cap", burst <= RelayParams.BURST_MAX_MS)
            // Airtime must stay a small fraction of the gap, or a slow whisper
            // turns into a shout and the collision maths in D5 stops holding.
            assertTrue(
                "burst $burst is more than 10% of a ${interval}ms interval",
                burst * 10 <= interval || burst == RelayParams.BURST_MIN_MS,
            )
        }
    }

    @Test
    fun `an absurd interval still yields a sane burst`() {
        assertEquals(RelayParams.BURST_MIN_MS, RelayParams.burstMsFor(0L))
        assertEquals(RelayParams.BURST_MAX_MS, RelayParams.burstMsFor(Long.MAX_VALUE))
    }
}
