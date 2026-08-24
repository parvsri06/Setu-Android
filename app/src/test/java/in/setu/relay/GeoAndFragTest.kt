package `in`.setu.relay

import `in`.setu.relay.wire.Frag
import `in`.setu.relay.wire.GeoQuant
import `in`.setu.relay.wire.Proto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.random.Random

class GeoAndFragTest {

    // ------------------------------------------------------------------- geo

    private fun metresApart(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat1 - lat2) * 111_320.0
        val dLon = (lon1 - lon2) * 111_320.0 * cos(Math.toRadians(lat1))
        return max(abs(dLat), abs(dLon))
    }

    @Test
    fun `lat lon quantisation round-trips within 2 point 5 metres`() {
        val rng = Random(20260824)
        var worst = 0.0
        repeat(20_000) {
            val lat = rng.nextDouble(-90.0, 90.0)
            val lon = rng.nextDouble(-180.0, 180.0)
            val (dLat, dLon) = GeoQuant.decode(GeoQuant.encode(lat, lon))
            worst = max(worst, metresApart(lat, lon, dLat, dLon))
        }
        assertTrue("worst error was ${worst}m", worst < 2.5)
    }

    @Test
    fun `poles and the antimeridian round-trip`() {
        val corners = listOf(
            90.0 to 180.0, -90.0 to -180.0, 90.0 to -180.0, -90.0 to 180.0,
            0.0 to 0.0, 26.1445 to 91.7362,   // Guwahati
        )
        for ((lat, lon) in corners) {
            val (dLat, dLon) = GeoQuant.decode(GeoQuant.encode(lat, lon))
            assertTrue(
                "($lat,$lon) -> ($dLat,$dLon)",
                metresApart(lat, lon, dLat, dLon) < 2.5,
            )
        }
    }

    @Test
    fun `encoded location is exactly six bytes`() {
        assertEquals(6, GeoQuant.encode(26.14, 91.73).size)
    }

    @Test
    fun `out of range input is clamped rather than wrapping`() {
        val (lat, lon) = GeoQuant.decode(GeoQuant.encode(120.0, 400.0))
        assertTrue(lat <= 90.0 && lon <= 180.0)
    }

    // ---------------------------------------------------------- fragmentation

    private fun envelope(): ByteArray =
        ByteArray(Proto.ENVELOPE_SIZE) { ((it * 31 + 7) and 0xFF).toByte() }

    @Test
    fun `the fragment plan can carry a full envelope`() {
        assertEquals(27, Frag.FRAG_SIZE)
        assertEquals(18, Frag.PAYLOAD_SIZE)
        assertEquals(9, Frag.TOTAL_FRAGS)
        assertTrue(Frag.PADDED_SIZE >= Proto.ENVELOPE_SIZE)
    }

    @Test
    fun `all fragments reconstruct the envelope`() {
        val e = envelope()
        val parts = Frag.split(e)
        assertEquals(Frag.TOTAL_FRAGS, parts.size)
        parts.forEach { assertEquals(Frag.FRAG_SIZE, it.size) }
        assertArrayEquals(e, Frag.reassemble(parts.toTypedArray()))
    }

    @Test
    fun `any eight of nine reconstruct`() {
        val e = envelope()
        val parts = Frag.split(e)
        for (drop in 0 until Frag.TOTAL_FRAGS) {
            val sparse = arrayOfNulls<ByteArray>(Frag.TOTAL_FRAGS)
            parts.forEachIndexed { i, p -> if (i != drop) sparse[i] = p }
            assertArrayEquals("dropping fragment $drop", e, Frag.reassemble(sparse))
        }
    }

    @Test
    fun `seven of nine fails cleanly`() {
        val parts = Frag.split(envelope())
        val sparse = arrayOfNulls<ByteArray>(Frag.TOTAL_FRAGS)
        parts.forEachIndexed { i, p -> if (i != 0 && i != 3) sparse[i] = p }
        assertNull(Frag.reassemble(sparse))
    }

    @Test
    fun `fragment headers carry the group key and the index`() {
        val e = envelope()
        val parts = Frag.split(e)
        val expectedGroup = e.copyOfRange(Proto.OFF_MSG_ID, Proto.OFF_MSG_ID + 8)
        parts.forEachIndexed { i, p ->
            val h = Frag.parseHeader(p)
            assertNotNull(h)
            assertEquals(i, h!!.index)
            assertEquals(Frag.TOTAL_FRAGS, h.total)
            assertArrayEquals(expectedGroup, h.groupKey)
        }
    }

    @Test
    fun `a malformed fragment is rejected`() {
        assertNull(Frag.parseHeader(ByteArray(20)))
        val bad = ByteArray(Frag.FRAG_SIZE)
        bad[8] = ((14 shl 4) or 3).toByte()   // impossible index and total
        assertNull(Frag.parseHeader(bad))
    }
}
