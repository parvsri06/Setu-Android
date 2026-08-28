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

    /**
     * Ed25519 public key that announcements are verified against.
     *
     * This is the one key in the app whose job is **authenticity rather than
     * secrecy**, and it is the reason announcements are usable at all. Without
     * it any install could broadcast "the bridge is safe" and the feature would
     * be a rumour amplifier with better reach than word of mouth.
     *
     * Only the public half is here. The private seed lives with whoever is
     * authorised to speak — a district control room, not a handset — and is
     * entered on a rescuer phone to compose. An announcement that fails this
     * check is still shown, marked unverified: in a blackout an unverified
     * message may well be true, and hiding it is its own kind of lie. It simply
     * never gets to look official.
     */
    val AUTHORITY_PUBLIC: ByteArray =
        Codec.unhex("6fa0a3ada6c430e050789afcc27df62ca0928ce5f740a3d7ff7f08d84a5f7291")
}
