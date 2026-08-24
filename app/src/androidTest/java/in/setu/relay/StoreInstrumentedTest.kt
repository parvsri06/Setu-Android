package `in`.setu.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.setu.relay.crypto.Identity
import `in`.setu.relay.relay.Messages
import `in`.setu.relay.relay.TimeSource
import `in`.setu.relay.store.MessageStore
import `in`.setu.relay.wire.Bodies
import `in`.setu.relay.wire.Envelope
import `in`.setu.relay.wire.MsgType
import `in`.setu.relay.wire.Status
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The store and the identity need a real device, so they live here rather than
 * in the JVM suite. Run with:
 *
 *     ./gradlew :app:connectedDebugAndroidTest
 *
 * The two- and three-device relay tests in docs/09-test-plan.md cannot be
 * automated from one handset; they are the manual procedure in README.md.
 */
@RunWith(AndroidJUnit4::class)
class StoreInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: MessageStore
    private lateinit var identity: Identity

    @Before
    fun setUp() {
        context.deleteDatabase("setu.db")
        store = MessageStore(context)
        identity = Identity.get(context)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("setu.db")
    }

    private fun sos(now: Long = TimeSource.wallSeconds()): Envelope =
        Messages.sos(identity, 26.1445, 91.7362, now)

    @Test
    fun identityIsStableAcrossCalls() {
        val again = Identity.get(context)
        assertTrue(identity.publicKey.contentEquals(again.publicKey))
        assertEquals(8, identity.keyId.size)
    }

    @Test
    fun aSignedSosVerifiesAgainstItsOwnKey() {
        val e = sos()
        val encoded = e.encode()
        assertEquals(142, encoded.size)
        assertEquals(
            Messages.VerifyResult.VALID,
            Messages.verify(e, encoded, identity.publicKey),
        )
        encoded[40] = (encoded[40].toInt() xor 1).toByte()
        assertEquals(
            Messages.VerifyResult.INVALID,
            Messages.verify(Envelope.decode(encoded), encoded, identity.publicKey),
        )
    }

    @Test
    fun dedupeSurvivesAndCountsDuplicates() {
        val e = sos()
        val now = TimeSource.wallMs()
        assertTrue(store.markSeen(e.msgId, now))
        assertFalse(store.markSeen(e.msgId, now))
        assertFalse(store.markSeen(e.msgId, now))
        assertEquals(2, store.duplicateCount(e.msgId))
    }

    @Test
    fun insertAndReadBack() {
        val e = sos()
        assertTrue(store.insert(e, e.encode(), TimeSource.wallMs(), isMine = true))
        val back = store.get(e.msgId)
        assertNotNull(back)
        assertEquals(MsgType.SOS, back!!.type)
        assertEquals(0, back.tier)
        assertTrue(back.isMine)
        assertTrue(back.envelope.contentEquals(e.encode()))
        // A second insert of the same id is ignored, not duplicated.
        assertFalse(store.insert(e, e.encode(), TimeSource.wallMs(), isMine = true))
        assertEquals(1, store.countAll())
    }

    @Test
    fun receiptsRaiseTheStatusLadderAndCountDistinctCarriers() {
        val e = sos()
        store.insert(e, e.encode(), TimeSource.wallMs(), isMine = true)
        assertEquals(Status.HELD, store.get(e.msgId)!!.status)

        val now = TimeSource.wallMs()
        assertTrue(store.addReceipt(e.msgId, ByteArray(8) { 1 }, now, Bodies.RECEIPT_CARRIED))
        assertTrue(store.addReceipt(e.msgId, ByteArray(8) { 2 }, now, Bodies.RECEIPT_CARRIED))
        // The same device reporting twice must not inflate the carrier count.
        assertFalse(store.addReceipt(e.msgId, ByteArray(8) { 2 }, now, Bodies.RECEIPT_CARRIED))
        assertEquals(2, store.carrierCount(e.msgId))

        store.setStatus(e.msgId, Status.CARRIED)
        assertEquals(Status.CARRIED, store.get(e.msgId)!!.status)
        assertFalse(store.hasDeliveryReceipt(e.msgId))

        store.addReceipt(e.msgId, ByteArray(8) { 3 }, now, Bodies.RECEIPT_DELIVERED)
        assertTrue(store.hasDeliveryReceipt(e.msgId))
    }

    @Test
    fun neighbourCountOnlyCountsRecentPeers() {
        val now = TimeSource.wallMs()
        store.touchPeer(ByteArray(8) { 1 }, now)
        store.touchPeer(ByteArray(8) { 2 }, now)
        store.touchPeer(ByteArray(8) { 3 }, now - 120_000)
        assertEquals(2, store.neighbourCount(now, 60_000))
        assertEquals(3, store.neighbourCount(now, 600_000))
    }

    /** docs/09-test-plan.md: never delete a tier-0 message lacking a receipt. */
    @Test
    fun reaperKeepsAnUnreceiptedTierZeroAndDeletesTheRest() {
        val long_ago = TimeSource.wallSeconds() - 48 * 3600
        val orphan = sos(long_ago)
        val acknowledged = sos(long_ago)
        val checkIn = Messages.checkIn(identity, "someone", Bodies.STATUS_SAFE, long_ago - 96 * 3600)

        for (e in listOf(orphan, acknowledged, checkIn)) {
            store.insert(e, e.encode(), TimeSource.wallMs(), isMine = true)
        }
        store.addReceipt(acknowledged.msgId, ByteArray(8) { 7 }, TimeSource.wallMs(), Bodies.RECEIPT_DELIVERED)

        store.reapExpired(TimeSource.wallMs())

        assertNotNull("a tier-0 without a receipt must survive", store.get(orphan.msgId))
        assertEquals(Status.EXPIRED, store.get(orphan.msgId)!!.status)
        assertNull("a delivered tier-0 may be reaped", store.get(acknowledged.msgId))
        assertNull("an expired check-in may be reaped", store.get(checkIn.msgId))
    }

    /** docs/09-test-plan.md: evict highest tier first, then oldest. */
    @Test
    fun evictionDropsHighestTierOldestFirstAndSparesTierZero() {
        val base = TimeSource.wallSeconds()
        val tierZero = sos(base)
        val oldCheckIn = Messages.checkIn(identity, "a", Bodies.STATUS_SAFE, base - 1000)
        val newCheckIn = Messages.checkIn(identity, "b", Bodies.STATUS_SAFE, base)
        val receipt = Messages.receipt(identity, tierZero.msgId, Bodies.RECEIPT_CARRIED, base)

        // Stored as if received from others, since eviction never touches ours.
        for (e in listOf(tierZero, oldCheckIn, newCheckIn, receipt)) {
            store.insert(e, e.encode(), TimeSource.wallMs(), isMine = false)
        }
        assertEquals(4, store.countAll())

        assertEquals(1, store.evictIfFull(cap = 3))
        assertNull("tier 2 goes before tier 1", store.get(receipt.msgId))

        assertEquals(1, store.evictIfFull(cap = 2))
        assertNull("the older tier-1 goes first", store.get(oldCheckIn.msgId))
        assertNotNull(store.get(newCheckIn.msgId))

        // Everything else can go; the unreceipted tier-0 cannot.
        store.evictIfFull(cap = 0)
        assertNotNull(store.get(tierZero.msgId))
    }

    @Test
    fun purgeSeenDropsOnlyOldRows() {
        val now = TimeSource.wallMs()
        val fresh = Messages.newMsgId()
        val stale = Messages.newMsgId()
        store.markSeen(fresh, now)
        store.markSeen(stale, now - 40L * 24 * 3_600_000L)
        assertEquals(1, store.purgeSeen(now))
        assertFalse("a purged id can be seen again", !store.markSeen(stale, now))
        assertFalse("a fresh id is still deduped", store.markSeen(fresh, now))
    }

    @Test
    fun aReceiptBodyRoundTripsThroughTheEnvelope() {
        val target = Messages.newMsgId()
        val r = Messages.receipt(identity, target, Bodies.RECEIPT_DELIVERED, TimeSource.wallSeconds())
        val decoded = Envelope.decode(r.encode())
        assertEquals(MsgType.RECEIPT, decoded.type)
        assertEquals(2, decoded.tier)
        assertTrue(Bodies.receiptRefMsgId(decoded.sealedBody).contentEquals(target))
        assertEquals(Bodies.RECEIPT_DELIVERED, Bodies.receiptKind(decoded.sealedBody))
    }
}
