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
data class RelayState(
    val identityKeyId: String = "",
    /** Distinct devices heard in the last 60 s. The single most reassuring number. */
    val neighbours: Int = 0,
    val carrying: Int = 0,
    val totalStored: Int = 0,
    val myMessages: List<MyMessage> = emptyList(),
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
    val radioError: String? = null,
    val serviceRunning: Boolean = false,
)
