package `in`.setu.relay.crypto

import `in`.setu.relay.wire.Codec

/**
 * DEBUG SOURCE SET ONLY. The release counterpart in `src/release` returns null,
 * so this private key is never compiled into a shipped APK — verified by
 * scanning the release DEX, not merely assumed.
 *
 * It exists so a demo on the developer's own device can open what that device
 * sealed, proving the SOS body really is encrypted and really does hold the
 * position. In a deployment this key would live only on a rescuer's device or at
 * the backend, and would be rotated through a signed profile update.
 */
object RescuerDemo {
    val privateKey: ByteArray? =
        Codec.unhex("8872a00d82067595c977fd7a3a3024f78336d0102ee27c8f510b73c60bb8057b")
}
