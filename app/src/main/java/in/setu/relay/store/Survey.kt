package `in`.setu.relay.store

/**
 * The survey record from `Setu-docs/Basic UI workflow/`.
 *
 * One row per affected person or family, with casualties in a child table. The
 * whole thing is captured offline; what leaves the phone, and by which
 * transport, is decided elsewhere:
 *
 * - identity, location and casualty status ride the **bulk plane** over BLE
 *   (`wire/SurveyRecord.kt`) — small, no photos
 * - damage detail, relief camp and photos are **internet only**, because photo
 *   bytes have no business on a radio that carries SOS traffic
 *
 * That split is the reason this class keeps damage fields it never relays.
 */
data class Survey(
    val surveyId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: Int,
    val isProxy: Boolean,
    val proxyConsent: Boolean,

    // ------------------------------------------------------------- personal
    val name: String = "",
    val fatherName: String = "",
    val mobile: String = "",
    /** Sealed to the backend key. The plaintext never rests on the device. */
    val aadhaarSealed: ByteArray? = null,
    val aadhaarLast4: String = "",
    val familyId: String = "",

    // ------------------------------------------------------------- location
    val village: String = "",
    val district: String = "",
    val postOffice: String = "",
    val policeStation: String = "",
    val pin: String = "",

    // --------------------------------------------------- damage (not relayed)
    val disasterType: Int = DisasterType.FLOOD,
    val disasterOther: String = "",
    val damageDate: String = "",
    /** Bitmask of [DamageArea]. Multi-select, as in the mockup. */
    val damageAreas: Int = 0,
    val damageOther: String = "",
    val damageDescription: String = "",

    // ----------------------------------------------- relief camp (not relayed)
    val inCamp: Boolean = false,
    val campName: String = "",
    val campLocation: String = "",
    val needs: String = "",

    /**
     * Where and when the survey was taken, captured automatically at save time
     * exactly as an SOS captures its fix. A surveyor under pressure will not
     * type coordinates, and "which village was this?" is the first question an
     * officer asks of a record that arrived over the mesh.
     *
     * Unlike an SOS location this is **not sealed**, because the whole point is
     * that the phones carrying it can show where it came from. NaN means no fix
     * was available — indoors, or location switched off.
     */
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    /** Wall clock at capture, millis. Untrusted like every offline clock. */
    val capturedAt: Long = 0L,

    val people: List<Person> = emptyList(),
) {
    // Generated equals/hashCode would compare aadhaarSealed by reference. It is
    // never used as a map key, so identity comparison is the honest default —
    // but spell it out rather than leaving a lint warning that looks like a bug.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = surveyId.hashCode()
}

data class Person(
    val personId: String,
    val surveyId: String,
    val ordinal: Int,
    val name: String = "",
    val age: Int = -1,
    val gender: Int = Gender.UNSET,
    val status: Int = PersonStatus.UNSET,
    val location: String = "",
)

/** Draft is on this phone only. Nothing above `COMPLETE` has left it yet either. */
object SurveyStatus {
    const val DRAFT = 0
    const val COMPLETE = 1
    const val QUEUED = 2
    const val RELAYED = 3
    const val UPLOADED = 4
}

object Gender {
    const val UNSET = -1
    const val MALE = 0
    const val FEMALE = 1
    const val OTHER = 2
}

object PersonStatus {
    const val UNSET = -1
    const val ALIVE = 0
    const val MISSING = 1
    const val NOT_ALIVE = 2
}

object DisasterType {
    const val FLOOD = 0
    const val EARTHQUAKE = 1
    const val CYCLONE = 2
    const val LANDSLIDE = 3
    const val FIRE = 4
    const val OTHER = 5
    val ALL = listOf(FLOOD, EARTHQUAKE, CYCLONE, LANDSLIDE, FIRE, OTHER)
}

/** Multi-select, so these are bit positions rather than values. */
object DamageArea {
    const val HOUSE = 1 shl 0
    const val SHOP = 1 shl 1
    const val AGRICULTURAL_LAND = 1 shl 2
    const val ROAD = 1 shl 3
    const val VEHICLE = 1 shl 4
    const val LIVESTOCK = 1 shl 5
    const val OTHER = 1 shl 6
    val ALL = listOf(HOUSE, SHOP, AGRICULTURAL_LAND, ROAD, VEHICLE, LIVESTOCK, OTHER)
}
