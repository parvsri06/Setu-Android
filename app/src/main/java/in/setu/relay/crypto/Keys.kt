package `in`.setu.relay.crypto

import `in`.setu.relay.wire.Codec

/**
 * Keys shipped inside the APK. **Public keys only.**
 *
 * DEMO HONESTY (docs/04-security-model.md): sealing is really implemented — the
 * SOS location is encrypted to [RESCUER_PUBLIC] and relays carry an opaque blob
 * they cannot read. Key *distribution* is not implemented; these are fixed demo
 * keys generated once for this build, not keys held by any real rescue agency.
 *
 * The matching rescuer *private* key lives in [RescuerDemo], which exists only
 * in the debug source set. A release build has no way to open a sealed body, and
 * that is the point: shipping the rescuer private key inside every APK would
 * mean every relay could read every SOS location, which is exactly the
 * "location privacy inversion" that D6 in MEMORY.md exists to prevent.
 */
object Keys {

    /** X25519 public key that SOS and check-in bodies are sealed to. */
    val RESCUER_PUBLIC: ByteArray =
        Codec.unhex("4531e1d83ffd3429e15e18bf8555de44c17724bebd44979790aa4f09ebed0613")

    /** X25519 public key that bulk-plane survey bodies seal to. Phase 5. */
    val BACKEND_PUBLIC: ByteArray =
        Codec.unhex("cdb985aff27f1c701888264060049470f4e147977db988bbb564d0e74541ec73")
}
