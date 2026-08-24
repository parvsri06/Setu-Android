package `in`.setu.relay.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import `in`.setu.relay.wire.Codec
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The device identity key. Ed25519, generated on first run, signs every message.
 *
 * AndroidKeyStore cannot generate or hold Ed25519 keys — it offers EC/ECDSA and,
 * from API 33, X25519 agreement, but not Ed25519 signing. Rather than swap the
 * wire format to ECDSA (64-byte signature but non-deterministic, larger DER
 * handling, and a break from the spec), the 32-byte Ed25519 seed is sealed at
 * rest with a non-exportable, hardware-backed AES-256-GCM key held in
 * AndroidKeyStore. The seed only exists in cleartext inside process memory.
 * Recorded as D12 in MEMORY.md.
 */
class Identity(
    val seed: ByteArray,
    val publicKey: ByteArray,
    val keyId: ByteArray,
    /** False when the Keystore was unavailable and the seed is stored unwrapped. */
    val hardwareBacked: Boolean,
) {
    val keyIdHex: String get() = Codec.hex(keyId)

    fun sign(message: ByteArray): ByteArray = Signer.sign(seed, message)

    companion object {
        private const val TAG = "SetuIdentity"
        private const val PREFS = "setu_identity"
        private const val KEY_SEALED_SEED = "sealed_seed"
        private const val KEY_PLAIN_SEED = "plain_seed"
        private const val KEYSTORE_ALIAS = "setu_identity_wrap_v1"
        private const val GCM_TAG_BITS = 128
        private const val IV_LEN = 12

        @Volatile
        private var cached: Identity? = null

        fun get(context: Context): Identity {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val id = loadOrCreate(context.applicationContext)
                cached = id
                return id
            }
        }

        private fun loadOrCreate(context: Context): Identity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            prefs.getString(KEY_SEALED_SEED, null)?.let { stored ->
                unwrap(stored)?.let { return build(it, hardwareBacked = true) }
                Log.w(TAG, "sealed seed present but could not be unwrapped; regenerating")
            }
            prefs.getString(KEY_PLAIN_SEED, null)?.let { stored ->
                return build(Codec.unhex(stored), hardwareBacked = false)
            }

            val seed = ByteArray(Ed25519.SEED_SIZE).also { SecureRandom().nextBytes(it) }
            val sealed = wrap(seed)
            if (sealed != null) {
                prefs.edit().putString(KEY_SEALED_SEED, sealed).apply()
                return build(seed, hardwareBacked = true)
            }
            // Some OEM builds have a broken or absent Keystore. An app that
            // refuses to start there is worse than one that stores the seed in
            // app-private storage and says so on screen.
            Log.w(TAG, "AndroidKeyStore unavailable; storing identity seed unwrapped")
            prefs.edit().putString(KEY_PLAIN_SEED, Codec.hex(seed)).apply()
            return build(seed, hardwareBacked = false)
        }

        private fun build(seed: ByteArray, hardwareBacked: Boolean): Identity {
            val pub = Signer.publicKeyFromSeed(seed)
            return Identity(seed, pub, Digest.keyId(pub), hardwareBacked)
        }

        // --------------------------------------------------------- keystore

        private fun wrapKey(): SecretKey? = try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey) ?: run {
                val gen = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore",
                )
                gen.init(
                    KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                gen.generateKey()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "keystore wrap key unavailable: ${t.javaClass.simpleName}")
            null
        }

        private fun wrap(seed: ByteArray): String? = try {
            val key = wrapKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(seed)
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "seed wrap failed: ${t.javaClass.simpleName}")
            null
        }

        private fun unwrap(stored: String): ByteArray? = try {
            val key = wrapKey()
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            if (key == null || blob.size <= IV_LEN) {
                null
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_LEN),
                )
                cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "seed unwrap failed: ${t.javaClass.simpleName}")
            null
        }
    }
}
