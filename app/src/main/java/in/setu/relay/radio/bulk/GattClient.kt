package `in`.setu.relay.radio.bulk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import `in`.setu.relay.wire.Codec
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One sync session against one peer, driven as a blocking sequence.
 *
 * Android's GATT API is callback-per-operation with **one operation in flight
 * per connection**, which is the single most common source of bugs in BLE code:
 * fire a write before the previous callback lands and it is silently dropped.
 * Rather than build a state machine, each step parks on a queue until its
 * callback arrives, so the protocol reads top to bottom in [sync] and the
 * one-at-a-time rule is structural instead of remembered.
 *
 * [sync] therefore blocks and must never be called from the main thread.
 */
@SuppressLint("MissingPermission")
class GattClient(private val context: Context) {

    /** Handed back to the caller so a session can be logged and counted. */
    class Result(
        val connected: Boolean,
        val peerRecordCount: Int,
        val pushed: Int,
        val failure: String? = null,
    )

    private val connected = ArrayBlockingQueue<Boolean>(1)
    private val servicesFound = ArrayBlockingQueue<Boolean>(1)
    private val digestRead = ArrayBlockingQueue<ByteArray>(1)
    private val writeDone = ArrayBlockingQueue<Boolean>(1)
    private val mtuDone = ArrayBlockingQueue<Int>(1)

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var mtu = DEFAULT_MTU

    /**
     * Reads the peer's digest and pushes every record it is missing.
     *
     * @param mine every record id this device holds
     * @param sealedFor fetches the body for an id, at the moment it is needed
     */
    fun sync(
        device: BluetoothDevice,
        mine: List<ByteArray>,
        sealedFor: (ByteArray) -> ByteArray?,
    ): Result {
        try {
            val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: return Result(false, 0, 0, "connectGatt returned null")
            gatt = g

            if (await(connected) != true) return Result(false, 0, 0, "connect timed out")

            // A bigger MTU is the difference between 20-byte and 400-byte chunks.
            // Failure is survivable, so the result is used but never insisted on.
            g.requestMtu(MAX_MTU)
            mtu = await(mtuDone, MTU_TIMEOUT_MS) ?: DEFAULT_MTU

            if (!g.discoverServices()) return Result(true, 0, 0, "discoverServices refused")
            if (await(servicesFound) != true) return Result(true, 0, 0, "no Setu service")

            val service = g.getService(BulkProto.SERVICE_UUID)
                ?: return Result(true, 0, 0, "service missing")
            val digestChar = service.getCharacteristic(BulkProto.DIGEST_UUID)
                ?: return Result(true, 0, 0, "digest characteristic missing")
            val pushChar = service.getCharacteristic(BulkProto.PUSH_UUID)
                ?: return Result(true, 0, 0, "push characteristic missing")

            if (!g.readCharacteristic(digestChar)) return Result(true, 0, 0, "read refused")
            val raw = await(digestRead) ?: return Result(true, 0, 0, "digest timed out")
            val digest = BulkProto.decodeDigest(raw)
                ?: return Result(true, 0, 0, "digest malformed (${raw.size} B)")

            // The Bloom answers "do you have this?", so the gap is computed from
            // our own ids. It can never be enumerated from the peer's side.
            val missing = mine.filterNot { digest.bloom.mightContain(it) }
            Log.i(TAG, "peer holds ${digest.count}; ${missing.size} of ${mine.size} to push")

            var pushed = 0
            for (id in missing) {
                val sealed = sealedFor(id) ?: continue
                if (pushRecord(g, pushChar, id, sealed)) pushed++ else break
            }
            return Result(true, digest.count, pushed)
        } catch (t: Throwable) {
            Log.w(TAG, "sync threw", t)
            return Result(false, 0, 0, t.javaClass.simpleName)
        } finally {
            close()
        }
    }

    private fun pushRecord(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        id: ByteArray,
        sealed: ByteArray,
    ): Boolean {
        val chunk = (mtu - ATT_WRITE_OVERHEAD - BulkProto.PUSH_HEADER_SIZE)
            .coerceIn(MIN_CHUNK, BulkProto.MAX_CHUNK)
        var offset = 0
        while (offset < sealed.size) {
            val end = minOf(offset + chunk, sealed.size)
            val frame = BulkProto.encodePush(id, sealed.size, offset, sealed.copyOfRange(offset, end))
            if (!writeChunk(g, characteristic, frame)) {
                Log.w(TAG, "push of ${Codec.hex(id).take(8)} failed at offset $offset")
                return false
            }
            offset = end
        }
        return true
    }

    private fun writeChunk(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        writeDone.clear()
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Returns a BluetoothStatusCodes value, not a GATT_* one. Both
            // spell success as 0, so the wrong constant compared equal and the
            // mistake would never have shown up at runtime.
            g.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = value
                g.writeCharacteristic(characteristic)
            }
        }
        // WRITE_TYPE_DEFAULT means write-with-response, so the peer acknowledges
        // every chunk. Write-without-response would be faster and would also let
        // a record arrive with a hole in the middle and no way to know.
        return started && await(writeDone) == true
    }

    private fun close() {
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private fun <T> await(q: ArrayBlockingQueue<T>, timeoutMs: Long = TIMEOUT_MS): T? =
        runCatching { q.poll(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connected.offer(true)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected.offer(false)
                    // Unblock anything still parked, or the session waits out
                    // every remaining timeout on a connection that is gone.
                    servicesFound.offer(false)
                    writeDone.offer(false)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt?, mtu: Int, status: Int) {
            mtuDone.offer(if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU)
        }

        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            servicesFound.offer(
                status == BluetoothGatt.GATT_SUCCESS && g?.getService(BulkProto.SERVICE_UUID) != null,
            )
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) digestRead.offer(value)
        }

        @Deprecated("Pre-API 33 form; the platform calls exactly one of the two.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                characteristic?.value?.let { digestRead.offer(it) }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            writeDone.offer(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private companion object {
        const val TAG = "SetuBulkClient"
        const val TIMEOUT_MS = 12_000L
        const val MTU_TIMEOUT_MS = 4_000L
        const val DEFAULT_MTU = 23
        const val MAX_MTU = 517

        /** ATT opcode + handle. A write's payload is MTU minus this. */
        const val ATT_WRITE_OVERHEAD = 3
        const val MIN_CHUNK = 16
    }
}
