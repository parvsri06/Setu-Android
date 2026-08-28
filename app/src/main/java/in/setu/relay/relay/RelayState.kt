package `in`.setu.relay.relay

/** One of this device's own messages, as the UI needs it. */
data class MyMessage(
    val idHex: String,
    val type: Int,
    val status: Int,
    /** Distinct devices that reported carrying it. Not a delivery count. */
    val carriers: Int,
    val hopCount: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val sealedBody: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is MyMessage && other.idHex == idHex && other.status == status &&
            other.carriers == carriers && other.hopCount == hopCount

    override fun hashCode(): Int = idHex.hashCode() * 31 + status * 7 + carriers
}

/** One message this device is carrying for someone else. Never its content. */
data class CarriedItem(
    val idHex: String,
    val type: Int,
    val tier: Int,
    val hopCount: Int,
    val sizeBytes: Int,
    val receivedAt: Long,
    val expiresAt: Long,
)

/** Everything the UI is allowed to know about the relay. */
/**
 * An SOS as a rescuer needs to see it, from anyone — this device or a stranger
 * four hops away. [MyMessage] deliberately only covers what this phone sent, so
 * it cannot answer "who is calling for help nearby".
 *
 * The body stays sealed here. Only [`in`.setu.relay.ui.RescueScreen], holding
 * the rescuer key, can open it; the state object carries the same opaque bytes
 * every relay carries.
 */
data class SosCall(
    val idHex: String,
    val originKeyIdHex: String,
    val hopCount: Int,
    /** Sender's device clock. Untrusted — docs/04-security-model.md. */
    val createdAt: Long,
    /** When this phone actually heard it. Trustworthy. */
    val receivedAt: Long,
    val isMine: Boolean,
    val sealedBody: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = idHex.hashCode()
}

data class RelayState(
    val identityKeyId: String = "",
    /** Distinct devices heard in the last 60 s. The single most reassuring number. */
    val neighbours: Int = 0,
    val carrying: Int = 0,
    val totalStored: Int = 0,
    val myMessages: List<MyMessage> = emptyList(),

    /** Every SOS heard, from anyone. Populated only in rescue mode. */
    val sosCalls: List<SosCall> = emptyList(),
    /** True when a rescuer key has been entered on this phone. */
    val rescueMode: Boolean = false,

    // -------------------------------------------------------- rescue tooling
    /** This phone is screaming and flashing because a rescuer pinged it. */
    val findMeActive: Boolean = false,
    /** Wall clock at which the current scream stops. */
    val findMeEndsAt: Long = 0L,
    /** Battery is low enough that only a position beacon runs. */
    val lastBreath: Boolean = false,
    /** Phones nobody has heard from recently. A search list. */
    val goneQuiet: Int = 0,
    /** Announcements held, whether or not they verified. */
    val announcements: Int = 0,
    /** Observations recorded — the raw material for trails and search boxes. */
    val observations: Int = 0,
    val scanning: Boolean = false,
    val bluetoothOn: Boolean = false,
    /** The system Location toggle, not the permission. BLE scans return
     *  nothing at all when it is off, silently and with no error. */
    val locationServicesOn: Boolean = true,
    val extendedAdvertising: Boolean = false,
    val advertisingId: String? = null,
    val packetsSeen: Long = 0,
    val fragmentsSeen: Long = 0,
    val reassembled: Long = 0,
    val presenceSeen: Long = 0,
    val burstsSent: Long = 0,
    val duplicatesHeard: Long = 0,
    val malformedDropped: Long = 0,
    val signatureDropped: Long = 0,
    val knownKeys: Int = 0,
    val batteryPct: Int = 100,
    val wallClockJumped: Boolean = false,
    val hardwareBackedKey: Boolean = true,

    // ------------------------------------------------------------ bulk plane
    /** Records held, including ones sealed to someone else and unreadable here. */
    val recordsHeld: Int = 0,
    /** Records being carried for other people. Opaque to this device. */
    val recordsForOthers: Int = 0,
    val recordsReceived: Long = 0,
    val recordsPushed: Long = 0,
    val bulkSessions: Long = 0,
    val bulkServerUp: Boolean = false,
    val bulkLastResult: String? = null,
    val radioError: String? = null,
    val serviceRunning: Boolean = false,
)
