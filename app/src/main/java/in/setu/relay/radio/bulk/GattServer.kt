package `in`.setu.relay.radio.bulk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import `in`.setu.relay.wire.Codec

/**
 * The receiving half of the bulk plane: publishes what this device holds, and
 * accepts records pushed to it.
 *
 * Runs for as long as the relay runs. It is cheap when idle — a GATT server with
 * no connections costs nothing beyond the connectable advertisement that
 * [`in`.setu.relay.radio.beacon.PresenceAdvertiser] is already sending.
 *
 * Everything arriving here is hostile input from a stranger's phone. Sizes are
 * bounded before allocation, reassembly buffers are capped and expire, and a
 * record that does not decode is dropped without touching the store.
 */
@SuppressLint("MissingPermission")
class GattServer(
    private val context: Context,
    private val heldRecordIds: () -> List<ByteArray>,
    private val onRecord: (recordId: ByteArray, sealed: ByteArray) -> Boolean,
) {

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var recordsReceived: Long = 0L
        private set

    @Volatile
    var digestsServed: Long = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var server: BluetoothGattServer? = null

    /** Partial records, keyed by record id hex. Bounded; see [reap]. */
    private val inbox = HashMap<String, Partial>()

    private class Partial(val total: Int, val startedAt: Long) {
        val buf = ByteArray(total)
        var have = 0
    }

    fun start(): Boolean {
        stop()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: run {
                lastError = "no bluetooth manager"
                return false
            }

        val digest = BluetoothGattCharacteristic(
            BulkProto.DIGEST_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val push = BluetoothGattCharacteristic(
            BulkProto.PUSH_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val service = BluetoothGattService(
            BulkProto.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).apply {
            addCharacteristic(digest)
            addCharacteristic(push)
        }

        return try {
            val s = manager.openGattServer(context, callback) ?: run {
                lastError = "openGattServer returned null"
                return false
            }
            s.addService(service)
            server = s
            running = true
            Log.i(TAG, "GATT server listening on ${BulkProto.SERVICE_UUID}")
            true
        } catch (t: Throwable) {
            lastError = "server start: ${t.javaClass.simpleName}"
            Log.w(TAG, "GATT server failed to start", t)
            false
        }
    }

    fun stop() {
        running = false
        val s = server ?: return
        server = null
        synchronized(inbox) { inbox.clear() }
        runCatching { s.close() }
    }

    private val callback = object : BluetoothGattServerCallback() {

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            val s = server ?: return
            val peer = device ?: return
            if (characteristic?.uuid != BulkProto.DIGEST_UUID) {
                s.sendResponse(peer, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            // The digest is 259 bytes, so it exceeds a default 23-byte MTU and
            // the client stack fetches it as a series of blob reads. Honouring
            // `offset` is what makes that work; ignoring it returns the head of
            // the value over and over, which looks like a corrupt filter.
            val full = BulkProto.encodeDigest(heldRecordIds())
            val slice = if (offset in 0..full.size) full.copyOfRange(offset, full.size) else ByteArray(0)
            if (offset == 0) digestsServed++
            s.sendResponse(peer, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val s = server ?: return
            val peer = device ?: return
            val ok = characteristic?.uuid == BulkProto.PUSH_UUID && value != null &&
                acceptChunk(value)
            if (responseNeeded) {
                s.sendResponse(
                    peer,
                    requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null,
                )
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            Log.i(TAG, "server connection state=$newState status=$status")
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            Log.i(TAG, "server MTU now $mtu")
        }
    }

    /** Returns false on anything malformed, which the client sees as a failed write. */
    private fun acceptChunk(value: ByteArray): Boolean {
        val push = BulkProto.decodePush(value) ?: return false
        val key = Codec.hex(push.recordId)

        val complete: ByteArray = synchronized(inbox) {
            reap()
            var partial = inbox[key]
            if (partial == null || partial.total != push.totalLen) {
                if (inbox.size >= MAX_PARTIALS) return@synchronized null
                partial = Partial(push.totalLen, System.currentTimeMillis())
                inbox[key] = partial
            }
            // decodePush already bounds offset + size against total, so this
            // cannot run off the end of the buffer.
            push.data.copyInto(partial.buf, push.offset)
            partial.have = maxOf(partial.have, push.offset + push.data.size)
            if (partial.have >= partial.total) {
                inbox.remove(key)
                partial.buf
            } else {
                null
            }
        } ?: return true    // chunk accepted, record not finished yet

        val stored = onRecord(push.recordId, complete)
        if (stored) {
            recordsReceived++
            Log.i(TAG, "record ${key.take(8)} received, ${complete.size} B")
        }
        return true
    }

    /** Drops half-finished records from peers that walked away. */
    private fun reap() {
        val now = System.currentTimeMillis()
        val dead = inbox.entries.filter { now - it.value.startedAt > PARTIAL_TTL_MS }
        for (e in dead) inbox.remove(e.key)
    }

    private companion object {
        const val TAG = "SetuBulkServer"
        const val MAX_PARTIALS = 8
        const val PARTIAL_TTL_MS = 60_000L
    }
}
