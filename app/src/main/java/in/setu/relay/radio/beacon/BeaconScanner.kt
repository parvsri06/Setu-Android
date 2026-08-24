package `in`.setu.relay.radio.beacon

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
    var lastError: String? = null
        private set

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
            handlePayload(data, result.rssi)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            lastError = "scan failed error=$errorCode"
            Log.w(TAG, "scan failed, error=$errorCode")
        }
    }

    private fun handlePayload(data: ByteArray, rssi: Int) {
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

    fun start(): Boolean {
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
            Log.i(TAG, "scanning started, legacyOnly=${!extended}")
            true
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName
            Log.w(TAG, "startScan threw ${t.javaClass.simpleName}")
            false
        }
    }

    fun stop() {
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    companion object {
        private const val TAG = "SetuScanner"

        /** A partial fragment group past this age is abandoned. */
        private const val REASSEMBLY_TIMEOUT_MS = 30_000L
    }
}
