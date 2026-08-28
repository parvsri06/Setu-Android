package `in`.setu.relay.radio.bulk

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides *when* to open a connection. [GattClient] decides what happens on one.
 *
 * Three rules, and each exists because of a specific way this goes wrong:
 *
 * 1. **One session at a time.** A chipset supports 4–7 concurrent GATT
 *    connections (docs/05) and a scan plus several connections is where cheap
 *    radios start dropping advertisements. Serialising costs nothing at demo
 *    scale — a session is under a second — and keeps the beacon plane, which
 *    carries SOS, unaffected.
 *
 * 2. **A cooldown per peer.** Presence beacons arrive roughly once a second from
 *    every neighbour. Without a cooldown, two idle phones would reconnect
 *    continuously, burning both batteries to re-exchange a digest that has not
 *    changed.
 *
 * 3. **Nothing to say, no connection.** With no records, a session can only
 *    learn that the peer also has nothing. The digest read is not free, so skip
 *    it entirely.
 */
class BulkSync(
    private val context: Context,
    private val heldRecordIds: () -> List<ByteArray>,
    private val sealedFor: (ByteArray) -> ByteArray?,
    private val onSessionEnd: () -> Unit = {},
) {

    @Volatile
    var sessions: Long = 0L
        private set

    @Volatile
    var recordsPushed: Long = 0L
        private set

    @Volatile
    var lastResult: String? = null
        private set

    private val busy = AtomicBoolean(false)

    /** Peer MAC -> when it was last synced. */
    private val lastSync = ConcurrentHashMap<String, Long>()

    /**
     * Called for every presence beacon that advertises a bulk server. Returns
     * quickly and does nothing at all in the common case; when it does decide to
     * sync it blocks, so the caller must already be off the main thread.
     */
    fun onPeerSeen(device: BluetoothDevice, nowMs: Long) {
        val address = runCatching { device.address }.getOrNull() ?: return

        val since = nowMs - (lastSync[address] ?: 0L)
        if (since < COOLDOWN_MS) return

        val mine = heldRecordIds()
        if (mine.isEmpty()) return

        // Claim the slot before marking the peer, so a failed session still
        // takes its cooldown and a broken peer cannot be retried in a tight loop.
        if (!busy.compareAndSet(false, true)) return
        lastSync[address] = nowMs

        try {
            sessions++
            val result = GattClient(context).sync(device, mine, sealedFor)
            recordsPushed += result.pushed
            lastResult = when {
                result.failure != null -> result.failure
                result.pushed > 0 -> "pushed ${result.pushed}"
                else -> "peer already had all ${mine.size}"
            }
            Log.i(
                TAG,
                "sync with $address: connected=${result.connected} " +
                    "peerHeld=${result.peerRecordCount} pushed=${result.pushed} " +
                    "failure=${result.failure}",
            )
        } finally {
            busy.set(false)
            onSessionEnd()
        }
    }

    fun forget() {
        lastSync.clear()
    }

    private companion object {
        const val TAG = "SetuBulkSync"

        /**
         * Was 60 s, which *was* the reported "takes a whole minute to share":
         * a survey saved just after a sync sat until the next window opened.
         *
         * 8 s is short enough to feel immediate when two phones are together and
         * still long enough that idle phones are not reconnecting constantly —
         * a session with nothing to push costs one digest read, and BulkSync
         * skips the connection entirely when this phone holds no records.
         */
        const val COOLDOWN_MS = 8_000L
    }
}
