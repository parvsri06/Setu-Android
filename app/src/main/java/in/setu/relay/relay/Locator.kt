package `in`.setu.relay.relay

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * One-shot position for an SOS, on the platform [LocationManager].
 *
 * No Google Play Services, so no fused provider — see D7 in MEMORY.md. GPS is
 * slow and often unavailable indoors, and an SOS that waits for a fix is worse
 * than an SOS with no fix, so this returns the last known position immediately
 * when it has one and gives a live fix a short window to beat it.
 */
@SuppressLint("MissingPermission")
class Locator(context: Context) {

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * Calls [onResult] exactly once, with null when no position could be had
     * within [timeoutMs]. Always called on the main thread.
     */
    fun fixOnce(timeoutMs: Long = 8_000L, onResult: (Location?) -> Unit) {
        val mgr = manager
        if (mgr == null) {
            onResult(null)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        var done = false
        var best: Location? = lastKnown(mgr)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done) return
                best = location
                done = true
                runCatching { mgr.removeUpdates(this) }
                handler.post { onResult(location) }
            }

            @Deprecated("required by the pre-API-29 interface")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { mgr.isProviderEnabled(it) }.getOrDefault(false) }

        if (providers.isEmpty()) {
            onResult(best)
            return
        }
        for (p in providers) {
            runCatching { mgr.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) }
                .onFailure { Log.w(TAG, "requestLocationUpdates($p) failed: ${it.javaClass.simpleName}") }
        }
        handler.postDelayed({
            if (done) return@postDelayed
            done = true
            runCatching { mgr.removeUpdates(listener) }
            onResult(best)
        }, timeoutMs)
    }

    private fun lastKnown(mgr: LocationManager): Location? = try {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { mgr.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    } catch (_: SecurityException) {
        null
    }

    companion object {
        private const val TAG = "SetuLocator"
    }
}
