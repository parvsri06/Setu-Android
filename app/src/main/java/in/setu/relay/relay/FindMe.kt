package `in`.setu.relay.relay

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Makes a phone findable: maximum-volume alarm, flashing torch, continuous
 * vibration.
 *
 * ### The problem this solves
 *
 * A phone under a metre of mud is invisible. Machinery cannot dig speculatively
 * and a rescuer walking the surface has nothing to go on. But the phone is still
 * powered, and still on the radio. A rescuer with a handheld running Setu can
 * ask it to announce itself — and then the search stops being "somewhere in this
 * field" and becomes "under this square metre".
 *
 * This is the avalanche-transceiver principle built out of hardware everybody is
 * already carrying. The torch matters as much as the siren: mud absorbs sound
 * quickly, and a light visible through a few centimetres of slurry at night is
 * often the thing that gets seen first.
 *
 * ### Deliberately hard to ignore
 *
 * Alarm stream at full volume, ignoring silent mode, looping. A phone that
 * politely respects Do Not Disturb while its owner is buried has failed at the
 * only job that mattered. It stops on its own after the requested window so a
 * ping cannot flatten a battery that someone still needs.
 */
class FindMe(private val context: Context) {

    @Volatile
    var screaming: Boolean = false
        private set

    /** When the current burst ends, for the UI to count down against. */
    @Volatile
    var endsAtMs: Long = 0L
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var torchOn = false
    private var previousVolume = -1

    private val cameraManager: CameraManager?
        get() = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val torchCameraId: String? by lazy {
        runCatching {
            cameraManager?.cameraIdList?.firstOrNull { id ->
                cameraManager?.getCameraCharacteristics(id)
                    ?.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }

    /** Starts screaming for [seconds]. Calling again while active extends it. */
    fun start(seconds: Int) {
        // Clamped here as well as at the wire layer: this class is the thing
        // that actually spends the battery, so it does not trust a caller to
        // have bounded the value for it.
        val window = seconds.coerceIn(1, `in`.setu.relay.wire.Bodies.FIND_MAX_SECONDS)
        endsAtMs = System.currentTimeMillis() + window * 1000L
        if (screaming) {
            // Already going: just push the deadline out and re-arm the stop.
            handler.removeCallbacksAndMessages(STOP_TOKEN)
            scheduleStop(window)
            return
        }
        screaming = true
        Log.i(TAG, "find-me: screaming for ${window}s")

        raiseVolume()
        startSiren()
        startTorchFlashing()
        startVibrating()
        scheduleStop(window)
    }

    fun stop() {
        if (!screaming) return
        screaming = false
        endsAtMs = 0L
        handler.removeCallbacksAndMessages(STOP_TOKEN)
        handler.removeCallbacksAndMessages(FLASH_TOKEN)

        runCatching { player?.stop(); player?.release() }
        player = null
        setTorch(false)
        runCatching { vibrator()?.cancel() }
        restoreVolume()
        Log.i(TAG, "find-me: stopped")
    }

    // ------------------------------------------------------------------ bits

    private fun scheduleStop(seconds: Int) {
        handler.postAtTime({ stop() }, STOP_TOKEN, android.os.SystemClock.uptimeMillis() + seconds * 1000L)
    }

    private fun raiseVolume() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
        }
    }

    private fun restoreVolume() {
        if (previousVolume < 0) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0) }
        previousVolume = -1
    }

    private fun startSiren() {
        // The default alarm tone rather than a bundled sound file: it is already
        // on the device, it costs no APK bytes, and every alarm tone Android
        // ships is designed to cut through noise.
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "siren failed: ${it.javaClass.simpleName}") }
    }

    private fun startTorchFlashing() {
        val id = torchCameraId ?: return
        fun tick() {
            if (!screaming) return
            torchOn = !torchOn
            setTorch(torchOn)
            handler.postAtTime(
                ::tick,
                FLASH_TOKEN,
                android.os.SystemClock.uptimeMillis() + FLASH_INTERVAL_MS,
            )
        }
        Log.i(TAG, "find-me: flashing camera $id")
        tick()
    }

    private fun setTorch(on: Boolean) {
        val id = torchCameraId ?: return
        runCatching { cameraManager?.setTorchMode(id, on) }
        if (!on) torchOn = false
    }

    private fun startVibrating() {
        val v = vibrator() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Long-short-long, repeating: a deliberately unnatural rhythm so
                // it is not mistaken for an ordinary notification.
                v.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 800, 200, 300, 200, 800, 600), 0,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 800, 200, 300, 200, 800, 600), 0)
            }
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private companion object {
        const val TAG = "SetuFindMe"
        const val FLASH_INTERVAL_MS = 400L
        val STOP_TOKEN = Any()
        val FLASH_TOKEN = Any()
    }
}
