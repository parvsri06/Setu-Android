package `in`.setu.relay.crypto

/**
 * Aadhaar handling. Three separate representations, because they answer three
 * different questions and conflating them is how this kind of data leaks.
 *
 * | Form | Answers | Reversible |
 * |---|---|---|
 * | [seal] | "what does the backend need?" | only with the backend private key |
 * | [duplicateKey] | "have we surveyed this person already?" | **yes, trivially** |
 * | [mask] | "what may a surveyor see on screen?" | no — 8 digits are gone |
 *
 * ### The hash is not a privacy control, and must never be described as one
 *
 * An Aadhaar number is 12 digits. That is 10^12 possibilities — a few minutes of
 * brute force against a known salt on ordinary hardware. Salting stops a
 * precomputed rainbow table and nothing else. [duplicateKey] exists so the
 * device can notice the same person twice **without keeping the number**, and it
 * is stored on the phone knowing full well that anyone who extracts the database
 * and reads this file can recover the numbers.
 *
 * The control that actually protects the number is [seal]: X25519 to the backend
 * key, opened by nobody else, including this app. If that distinction is ever
 * blurred in a demo or a pitch, the honest claim collapses — see the demo
 * honesty section of `docs/04-security-model.md`.
 *
 * The salt is a fixed app-wide constant rather than per-device, because
 * duplicate detection has to work when two surveyors' records meet at the
 * backend. A per-device salt would make every device's hashes mutually
 * unrecognisable, which is the opposite of the point.
 */
object AadhaarId {

    private val SALT = "setu-aadhaar-v1".toByteArray(Charsets.US_ASCII)

    const val LENGTH = 12

    /**
     * Digits only, exactly 12.
     *
     * The Verhoeff checksum that real Aadhaar numbers carry is deliberately
     * **not** enforced. Every number used in testing and in the supplied
     * mockups is invented, and invented numbers fail Verhoeff, so enforcing it
     * would block the demo without protecting anyone. A deployment that ingests
     * real numbers should check it at the backend, where a rejection can be
     * handled rather than silently blocking a field worker.
     */
    fun isWellFormed(digits: String): Boolean =
        digits.length == LENGTH && digits.all { it in '0'..'9' }

    /** Sealed to the backend key. This is the only form that protects the number. */
    fun seal(digits: String): ByteArray {
        require(isWellFormed(digits)) { "aadhaar must be $LENGTH digits" }
        return SealedBox.seal(Keys.BACKEND_PUBLIC, digits.toByteArray(Charsets.US_ASCII))
    }

    /** Salted hash, for the unique index only. See the class note above. */
    fun duplicateKey(digits: String): ByteArray {
        require(isWellFormed(digits)) { "aadhaar must be $LENGTH digits" }
        return Digest.sha256(SALT, digits.toByteArray(Charsets.US_ASCII))
    }

    /** The last four digits — the most that may ever appear on screen. */
    fun last4(digits: String): String {
        require(isWellFormed(digits)) { "aadhaar must be $LENGTH digits" }
        return digits.substring(LENGTH - 4)
    }

    /** `XXXX-XXXX-9087`, as the review screen in the workflow mockups shows it. */
    fun mask(last4: String): String =
        if (last4.length == 4) "XXXX-XXXX-$last4" else "XXXX-XXXX-XXXX"
}
