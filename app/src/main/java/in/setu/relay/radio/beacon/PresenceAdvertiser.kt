package `in`.setu.relay.radio.beacon

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.util.Log

/**
 * Says "a Setu phone is here" continuously, whether or not this device has
 * anything to relay.
 *
 * WHY THIS EXISTS. Until this class, `touchPeer` was reached from exactly one
 * place — the ingest path in RelayEngine — so a peer only counted when a valid
 * envelope arrived. An envelope only goes on air when the scheduler has
 * something due, and an idle phone has nothing due. Two freshly installed
 * phones sitting next to each other were therefore completely silent, and the
 * home screen showed "0 phones nearby" until somebody fired an SOS. That is the
 * "sometimes it does not detect other devices" report, and it was not
 * intermittent at all — it was every idle pair, every time.
 *
 * The fix has to be independent of the store, so this is a separate advertising
 * set that runs for as long as the relay runs. It is deliberately tiny: 11 bytes
 * of payload in a legacy advertisement, which every handset can send without
 * fragmentation, and which the beacon plane's existing manufacturer-data scan
 * filter already picks up.
 *
 * It is **connectable**, unlike the beacon plane. Nothing connects yet, but the
 * bulk plane in phase 5 needs a connectable advertisement to find peers, and one
 * advertising set serving both purposes is cheaper than two.
 *
 * Cost: an 18-byte advertisement every second is roughly 0.02% of one
 * advertising channel. Detection is essentially certain within a few seconds
 * against a scanner listening ~1024 ms in every 4096 ms.
 */
@SuppressLint("MissingPermission")
class PresenceAdvertiser(private val adapter: BluetoothAdapter?) {

    /**
     * Whether this chipset can hold more than one advertising set at once.
     *
     * This gate is not cosmetic. Cheap handsets exist that support exactly one
     * advertising instance, and on those a permanently-running presence set
     * would take the only slot and stop SOS beacons going out entirely — trading
     * a cosmetic "0 nearby" bug for a safety-critical one. Where it is false,
     * RelayEngine time-shares the single slot instead, sending presence only
     * while no message is due.
     */
    val multipleSetsSupported: Boolean =
        runCatching { adapter?.isMultipleAdvertisementSupported == true }.getOrDefault(false)

    @Volatile
    var advertising: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val advertiser: BluetoothLeAdvertiser?
        get() = runCatching { adapter?.bluetoothLeAdvertiser }.getOrNull()

    private var callback: AdvertiseCallback? = null

    fun start(originKeyId: ByteArray, bulkAvailable: Boolean = false): Boolean {
        stop()
        val adv = advertiser ?: run {
            lastError = "no BLE advertiser"
            return false
        }

        val settings = AdvertiseSettings.Builder()
            // LOW_POWER is a ~1 s interval. The scanner's duty cycle, not this,
            // is what decides how fast a peer is noticed, so there is nothing to
            // buy by advertising faster.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)     // 31 bytes is not much; a name eats it
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(
                BeaconFormat.COMPANY_ID,
                BeaconFormat.wrapPresence(originKeyId, bulkAvailable),
            )
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                advertising = true
                Log.i(TAG, "presence advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                advertising = false
                lastError = "presence advertise error=$errorCode"
                Log.w(TAG, "presence advertising failed, error=$errorCode")
            }
        }

        return try {
            adv.startAdvertising(settings, data, cb)
            callback = cb
            true
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName
            Log.w(TAG, "startAdvertising threw ${t.javaClass.simpleName}")
            false
        }
    }

    fun stop() {
        advertising = false
        val cb = callback ?: return
        callback = null
        runCatching { advertiser?.stopAdvertising(cb) }
    }

    private companion object {
        const val TAG = "SetuPresence"
    }
}
