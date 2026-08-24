package `in`.setu.relay.crypto

import `in`.setu.relay.wire.Codec

/**
 * Keys shipped inside the APK.
 *
 * DEMO HONESTY (docs/04-security-model.md): sealing is really implemented — the
 * SOS location is encrypted to [RESCUER_PUBLIC] and relays carry an opaque blob
 * they cannot read. Key *distribution* is not implemented. These are fixed demo
 * keys generated once for this build, not keys held by any real rescue agency.
 *
 * [RESCUER_PRIVATE_DEMO_ONLY] exists so the app can show the rescuer side of the
 * exchange on a single device during a demo. In a deployment it would live only
 * on a rescuer's device or at the backend, and would be rotated through a signed
 * profile update. The app states this on the Rescuer screen — do not present the
 * sealed body as protected from the demo device itself.
 */
object Keys {

    /** X25519 public key that SOS and check-in bodies are sealed to. */
    val RESCUER_PUBLIC: ByteArray =
        Codec.unhex("4531e1d83ffd3429e15e18bf8555de44c17724bebd44979790aa4f09ebed0613")

    /** Present only so the demo can open what it sealed. Never ship in a real build. */
    val RESCUER_PRIVATE_DEMO_ONLY: ByteArray =
        Codec.unhex("8872a00d82067595c977fd7a3a3024f78336d0102ee27c8f510b73c60bb8057b")

    /** X25519 public key that bulk-plane survey bodies seal to. Phase 5. */
    val BACKEND_PUBLIC: ByteArray =
        Codec.unhex("cdb985aff27f1c701888264060049470f4e147977db988bbb564d0e74541ec73")
}
