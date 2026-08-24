package `in`.setu.relay.crypto

/**
 * RELEASE SOURCE SET. No rescuer private key is compiled into a shipped APK.
 *
 * A release build can seal an SOS location to the rescuer public key but has no
 * way to open one. That asymmetry is the whole security claim in D6: relays
 * learn that an SOS exists, and only the holder of the rescuer key learns who
 * and where. Putting the private key in the APK would hand that key to every
 * relay and to anyone who downloads the file.
 *
 * The debug counterpart in `src/debug` carries the key so the demo still works
 * on a development device.
 */
object RescuerDemo {
    val privateKey: ByteArray? = null
}
