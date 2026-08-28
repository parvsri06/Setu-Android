package `in`.setu.relay.wire

/**
 * Wire constants. See docs/02-wire-protocol.md.
 *
 * Any change to a byte offset, a field width or an enum value here MUST bump
 * [PROTO_VERSION] and update docs/02-wire-protocol.md in the same commit.
 */
object Proto {
    const val PROTO_VERSION = 1

    /** Total size of a beacon envelope on the wire. Fixed. Do not grow. */
    const val ENVELOPE_SIZE = 142

    // Field offsets inside the envelope.
    const val OFF_VERSION = 0
    const val OFF_TYPE_PRIORITY = 1
    const val OFF_MSG_ID = 2
    const val OFF_ORIGIN_KEY_ID = 10
    const val OFF_CREATED_AT = 18
    const val OFF_HOP_COUNT = 22
    const val OFF_TTL_HOURS = 23
    const val OFF_SEALED_BODY = 24
    const val OFF_SIGNATURE = 78

    const val LEN_MSG_ID = 8
    const val LEN_ORIGIN_KEY_ID = 8
    const val LEN_SEALED_BODY = 54
    const val LEN_SIGNATURE = 64

    /** Bytes 0..77 are covered by the signature. */
    const val SIGNED_PREFIX = OFF_SIGNATURE

    const val HOP_LIMIT = 32
}

/** Message types. Value is the high nibble of `type_priority`. */
object MsgType {
    const val SOS = 0x1
    const val CHECK_IN = 0x2
    const val RECEIPT = 0x3
    const val SURVEY_REF = 0x4
    const val PROFILE_REF = 0x5

    /**
     * A rescuer asking a phone to make itself findable — scream and flash.
     * Tier 0: someone is standing over rubble with a handheld scanner.
     */
    const val FIND_PING = 0x6

    /** Points at an announcement record on the bulk plane. Outbound information. */
    const val ANNOUNCE_REF = 0x7

    /** Priority tier for a type, per docs/01-architecture.md. */
    fun tierOf(type: Int): Int = when (type) {
        SOS -> 0
        CHECK_IN -> 1
        RECEIPT -> 2
        SURVEY_REF -> 3
        PROFILE_REF -> 4
        FIND_PING -> 0
        ANNOUNCE_REF -> 1
        else -> 4
    }

    /** Default TTL in hours for a type. */
    fun defaultTtlHours(type: Int): Int = when (type) {
        SOS -> 24
        CHECK_IN -> 72
        RECEIPT -> 24
        SURVEY_REF -> 14 * 24
        PROFILE_REF -> 30 * 24
        // A ping is worthless once the rescuer has walked away; a short life
        // keeps a stale ping from waking a phone hours later and wasting the
        // battery of someone who is already found.
        FIND_PING -> 1
        ANNOUNCE_REF -> 12
        else -> 24
    }

    fun name(type: Int): String = when (type) {
        SOS -> "SOS"
        CHECK_IN -> "CHECK_IN"
        RECEIPT -> "RECEIPT"
        SURVEY_REF -> "SURVEY_REF"
        PROFILE_REF -> "PROFILE_REF"
        FIND_PING -> "FIND_PING"
        ANNOUNCE_REF -> "ANNOUNCE_REF"
        else -> "UNKNOWN($type)"
    }
}

/** Local delivery status of a message. Rendered by the UI status ladder. */
object Status {
    const val HELD = 0
    const val CARRIED = 1
    const val DELIVERED = 2
    const val EXPIRED = 3
}

/** Advertising lifecycle state, persisted so a restart resumes correctly. */
object AdvertState {
    const val IDLE = 0
    const val BACKOFF = 1
    const val ADVERTISING = 2
    const val DONE = 3
}

class WireFormatException(message: String) : IllegalArgumentException(message)
