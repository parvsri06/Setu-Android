package `in`.setu.relay.radio.beacon

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import `in`.setu.relay.wire.Frag
import `in`.setu.relay.wire.Proto

/**
 * Always-on scanner at [ScanSettings.SCAN_MODE_BALANCED] (~10-15% duty).
 *
 * Two things here are load-bearing on Android and easy to get silently wrong:
 *  - a scan started from a background service **must** carry a [ScanFilter] or
 *    results are withheld with no error (docs/05-platform-constraints.md);
 *  - `setLegacy(false)` is required to be told about extended advertisements at
 *    all. The default is `true`, which silently hides every 144-byte beacon.
 *
 * Listening dominates battery — roughly 18 seconds scanning per second
 * transmitting — so the scan mode, not the advertising interval, is the lever.
 */
@SuppressLint("MissingPermission")
class BeaconScanner(
    private val adapter: BluetoothAdapter?,
    private val onEnvelope: (envelope: ByteArray, rssi: Int) -> Unit,
    // The device is needed to open a GATT connection: the bulk plane can only
    // dial a peer it has an address for, and a scan result is where that comes from.
    private val onPresence: (
        originKeyId: ByteArray,
        device: BluetoothDevice?,
        bulk: Boolean,
        rssi: Int,
    ) -> Unit = { _, _, _, _ -> },
) {

    @Volatile
    var scanning: Boolean = false
        private set

    @Volatile
    var packetsSeen: Long = 0L
        private set

    @Volatile
    var fragmentsSeen: Long = 0L
        private set

    @Volatile
    var reassembled: Long = 0L
        private set

    @Volatile
    var presenceSeen: Long = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val handler = Handler(Looper.getMainLooper())

    /** True between start() and shutdown(); gates the retry loop. */
    @Volatile
    private var wanted = false

    private var retryIndex = 0

    private val reassembly = HashMap<String, Group>()

    private class Group(val startedAt: Long) {
        val parts = arrayOfNulls<ByteArray>(Frag.TOTAL_FRAGS)
        var count = 0
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val record = result?.scanRecord ?: return
            val data = record.getManufacturerSpecificData(BeaconFormat.COMPANY_ID) ?: return
            packetsSeen++
            handlePayload(data, result.rssi, result.device)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            lastError = "scan failed error=$errorCode"
            Log.w(TAG, "scan failed, error=$errorCode")
            // Previously this was the end of the road: scanning stayed false and
            // nothing ever tried again, so one transient failure silenced the
            // device until the service was restarted. SCAN_FAILED_ALREADY_STARTED
            // in particular is recoverable and used to be fatal.
            scheduleRetry()
        }
    }

    private fun handlePayload(data: ByteArray, rssi: Int, device: BluetoothDevice? = null) {
        // Presence first: it is the cheapest check and by far the most common
        // packet, since every running relay sends one every second.
        BeaconFormat.unwrapPresence(data)?.let {
            presenceSeen++
            onPresence(it, device, BeaconFormat.presenceHasBulk(data), rssi)
            return
        }
        BeaconFormat.unwrapExtended(data)?.let {
            onEnvelope(it, rssi)
            return
        }
        if (BeaconFormat.isLegacyFragment(data)) {
            fragmentsSeen++
            acceptFragment(data)?.let {
                reassembled++
                onEnvelope(it, rssi)
            }
        }
    }

    private fun acceptFragment(fragment: ByteArray): ByteArray? {
        val header = Frag.parseHeader(fragment) ?: return null
        val key = header.groupKey.joinToString("") { "%02x".format(it) }
        val now = SystemClock.elapsedRealtime()
        synchronized(reassembly) {
            reassembly.entries.removeAll { now - it.value.startedAt > REASSEMBLY_TIMEOUT_MS }
            val group = reassembly.getOrPut(key) { Group(now) }
            if (group.parts[header.index] == null) {
                group.parts[header.index] = fragment
                group.count++
            }
            if (group.count < Frag.DATA_FRAGS) return null
            val envelope = Frag.reassemble(group.parts.copyOf())
            if (envelope != null) reassembly.remove(key)
            return envelope?.takeIf { it.size == Proto.ENVELOPE_SIZE }
        }
    }

    /**
     * Restarts the scan from scratch. Called when the Bluetooth adapter comes
     * back, and periodically, because some stacks quietly stop delivering
     * results on a very long-lived scan.
     */
    fun restart() {
        stop()
        start()
    }

    private fun scheduleRetry() {
        if (!wanted) return
        val delay = RETRY_DELAYS_MS[retryIndex.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        retryIndex++
        Log.i(TAG, "retrying scan in ${delay}ms (attempt $retryIndex)")
        // Android blocks an app that calls startScan more than 5 times in 30 s,
        // so the backoff is not politeness — a tight retry loop would get this
        // process banned from scanning entirely.
        handler.postDelayed({ if (wanted) start() }, delay)
    }

    fun start(): Boolean {
        wanted = true
        val scanner = runCatching { adapter?.bluetoothLeScanner }.getOrNull()
        if (scanner == null) {
            lastError = "no BLE scanner"
            return false
        }
        val extended = runCatching { adapter?.isLeExtendedAdvertisingSupported == true }
            .getOrDefault(false)

        // The filter is mandatory for background scans. Matching the company ID
        // with an empty pattern accepts both the 144-byte extended beacon and
        // the 27-byte legacy fragments; the shape check happens in handlePayload.
        val filters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(BeaconFormat.COMPANY_ID, ByteArray(0), ByteArray(0))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .apply { if (extended) setLegacy(false) }
            .build()

        return try {
            scanner.startScan(filters, settings, callback)
            scanning = true
            retryIndex = 0
            Log.i(TAG, "scanning started, legacyOnly=${!extended}")
            true
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName
            Log.w(TAG, "startScan threw ${t.javaClass.simpleName}")
            scheduleRetry()
            false
        }
    }

    fun stop() {
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    /** Stops for good; cancels any pending retry. */
    fun shutdown() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        stop()
    }

    companion object {
        private const val TAG = "SetuScanner"

        /** A partial fragment group past this age is abandoned. */
        private const val REASSEMBLY_TIMEOUT_MS = 30_000L

        /**
         * Backoff between scan retries. Deliberately never faster than ~6 s:
         * Android bans an app that calls startScan more than 5 times in 30 s,
         * and being banned is a far worse failure than waiting.
         */
        private val RETRY_DELAYS_MS = longArrayOf(6_000, 10_000, 20_000, 60_000)
    }
}
