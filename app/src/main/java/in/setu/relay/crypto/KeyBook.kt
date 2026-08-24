package `in`.setu.relay.crypto

import android.content.Context
import `in`.setu.relay.wire.Codec

/**
 * Known origin public keys, indexed by `origin_key_id`.
 *
 * OPEN GAP, deliberately visible rather than papered over. The 142-byte envelope
 * carries `origin_key_id` (8 bytes of SHA-256 of the origin public key), not the
 * 32-byte key itself. A relay that has never met the origin therefore cannot
 * check the Ed25519 signature, so docs/03-relay-algorithm.md's
 * "if !verifySignature(M): drop" cannot be fully honoured on the beacon plane in
 * phases 1-3.
 *
 * What this build does instead, recorded as D16 in MEMORY.md:
 *  - structural validation before store — exact length, known proto version,
 *    known type, tier matching type, non-zero TTL, hop below the limit. This is
 *    what actually blunts malformed-input and storage-DoS in the MVP;
 *  - full signature verification wherever the key is known, which covers
 *    everything this device originated and everything the bulk plane teaches it;
 *  - the UI states verification status per message and never renders an
 *    unverifiable message as verified.
 *
 * Closing the gap properly is bulk-plane key exchange in phase 5. Two options
 * that would close it earlier are written up in docs/04-security-model.md; both
 * cost beacon bytes, which D5 says to spend very carefully.
 */
class KeyBook(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("setu_keybook", Context.MODE_PRIVATE)

    private val cache = HashMap<String, ByteArray>()

    init {
        synchronized(cache) {
            for ((k, v) in prefs.all) {
                val hex = v as? String ?: continue
                runCatching { cache[k] = Codec.unhex(hex) }
            }
        }
    }

    fun learn(publicKey: ByteArray) {
        if (publicKey.size != Ed25519.PUBLIC_KEY_SIZE) return
        val id = Codec.hex(Digest.keyId(publicKey))
        synchronized(cache) {
            if (cache.containsKey(id)) return
            cache[id] = publicKey
        }
        prefs.edit().putString(id, Codec.hex(publicKey)).apply()
    }

    fun lookup(keyId: ByteArray): ByteArray? =
        synchronized(cache) { cache[Codec.hex(keyId)] }

    fun size(): Int = synchronized(cache) { cache.size }
}
