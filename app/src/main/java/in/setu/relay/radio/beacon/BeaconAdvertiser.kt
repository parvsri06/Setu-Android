package `in`.setu.relay.radio.beacon

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Handler
import android.os.Looper
import android.util.Log
import `in`.setu.relay.relay.RelayParams
import `in`.setu.relay.wire.Frag

/**
 * Sends one envelope at a time as a short burst of BLE advertisements.
 *
 * A chipset holds only a handful of advertising sets, and the store can hold
 * thousands of messages, so one radio slot is time-shared: [RelayEngine] decides
 * which message is due and this class puts it on air for [RelayParams.BURST_MS].
 * The per-message advertising interval in the spec's schedule is realised as the
 * gap between a message's bursts, not as a permanently allocated set.
 *
 * Which path a handset takes is logged on the first burst. That log line is
 * field-test data — docs/02-wire-protocol.md asks for it explicitly.
 */
@SuppressLint("MissingPermission")
class BeaconAdvertiser(
    private val adapter: BluetoothAdapter?,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {

    /** True when the handset can put all 144 bytes into one advertisement. */
    val extendedSupported: Boolean =
        runCatching { adapter?.isLeExtendedAdvertisingSupported == true }.getOrDefault(false)

    val maxAdvertisingDataLength: Int =
        runCatching { adapter?.leMaximumAdvertisingDataLength ?: 31 }.getOrDefault(31)

    @Volatile
    var burstsSent: Long = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val advertiser: BluetoothLeAdvertiser?
        get() = runCatching { adapter?.bluetoothLeAdvertiser }.getOrNull()

    private var loggedPath = false

    private var activeSet: AdvertisingSetCallback? = null
    private var activeLegacy: AdvertiseCallback? = null
    private var legacyQueue: List<ByteArray> = emptyList()
    private var legacyIndex = 0

    /**
     * Puts [envelope] on air for one burst and calls [onDone] when the burst is
     * over, whether it succeeded or not. Never throws.
     */
    fun burst(envelope: ByteArray, onDone: () -> Unit) {
        val adv = advertiser
        if (adv == null) {
            lastError = "no BLE advertiser"
            onDone()
            return
        }
        if (!loggedPath) {
            loggedPath = true
            Log.i(
                TAG,
                "advertising path = ${if (extendedSupported) "extended" else "legacy-fragmented"}, " +
                    "maxAdvertisingDataLength=$maxAdvertisingDataLength",
            )
        }
        stop()
        try {
            if (extendedSupported) burstExtended(adv, envelope, onDone)
            else burstLegacy(adv, envelope, onDone)
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName
            Log.w(TAG, "burst failed: ${t.javaClass.simpleName}: ${t.message}")
            onDone()
        }
    }

    // -------------------------------------------------------------- extended

    private fun burstExtended(adv: BluetoothLeAdvertiser, envelope: ByteArray, onDone: () -> Unit) {
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)   // 100 ms
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .setPrimaryPhy(android.bluetooth.BluetoothDevice.PHY_LE_1M)
            .setSecondaryPhy(android.bluetooth.BluetoothDevice.PHY_LE_1M)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(BeaconFormat.COMPANY_ID, BeaconFormat.wrapExtended(envelope))
            .build()

        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                stop()
                onDone()
            }
        }

        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status != ADVERTISE_SUCCESS) {
                    lastError = "extended start status=$status"
                    Log.w(TAG, "extended advertising failed, status=$status")
                    handler.post { finish() }
                } else {
                    burstsSent++
                }
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                handler.post { finish() }
            }
        }
        activeSet = cb
        // duration is in 10 ms units; the controller stops the set for us.
        adv.startAdvertisingSet(
            params, data, null, null, null,
            (RelayParams.BURST_MS / 10).toInt(), 0, cb,
        )
        // Belt and braces: some controllers do not deliver onAdvertisingSetStopped.
        handler.postDelayed({ finish() }, RelayParams.BURST_MS + GRACE_MS)
    }

    // ---------------------------------------------------------------- legacy

    private fun burstLegacy(adv: BluetoothLeAdvertiser, envelope: ByteArray, onDone: () -> Unit) {
        legacyQueue = Frag.split(envelope)
        legacyIndex = 0
        val perFragmentMs = (RelayParams.BURST_MS + LEGACY_EXTRA_MS) / legacyQueue.size
        var finished = false

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        lateinit var sendNext: () -> Unit
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                lastError = "legacy start error=$errorCode"
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    Log.e(
                        TAG,
                        "legacy fragment of ${BeaconFormat.LEGACY_SIZE} bytes did not fit in a " +
                            "31-byte advertisement on this handset. This is field-test data: the " +
                            "spec's 27-byte fragment assumes the flags AD is omitted for " +
                            "non-connectable advertising.",
                    )
                } else {
                    Log.w(TAG, "legacy advertising failed, error=$errorCode")
                }
                if (!finished) {
                    finished = true
                    handler.post { stop(); onDone() }
                }
            }

            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                burstsSent++
            }
        }
        activeLegacy = cb

        sendNext = {
            if (finished) Unit
            else if (legacyIndex >= legacyQueue.size) {
                finished = true
                stop()
                onDone()
            } else {
                runCatching { adv.stopAdvertising(cb) }
                val fragment = legacyQueue[legacyIndex++]
                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addManufacturerData(BeaconFormat.COMPANY_ID, fragment)
                    .build()
                runCatching { adv.startAdvertising(settings, data, cb) }
                    .onFailure {
                        lastError = it.javaClass.simpleName
                        finished = true
                        stop()
                        onDone()
                    }
                handler.postDelayed({ sendNext() }, perFragmentMs)
            }
        }
        sendNext()
    }

    // ------------------------------------------------------------------ stop

    fun stop() {
        val adv = advertiser ?: return
        activeSet?.let { runCatching { adv.stopAdvertisingSet(it) } }
        activeLegacy?.let { runCatching { adv.stopAdvertising(it) } }
        activeSet = null
        activeLegacy = null
    }

    companion object {
        private const val TAG = "SetuAdvertiser"
        private const val GRACE_MS = 250L

        /** Legacy needs 9 sequential advertisements, so it gets a longer burst. */
        private const val LEGACY_EXTRA_MS = 300L
    }
}
