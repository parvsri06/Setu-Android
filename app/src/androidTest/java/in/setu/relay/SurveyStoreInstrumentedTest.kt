package `in`.setu.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.setu.relay.crypto.AadhaarId
import `in`.setu.relay.store.Person
import `in`.setu.relay.store.PersonStatus
import `in`.setu.relay.store.SetuDb
import `in`.setu.relay.store.Survey
import `in`.setu.relay.store.SurveyStatus
import `in`.setu.relay.store.SurveyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Survey persistence needs real SQLite, so it cannot live in the JVM suite.
 *
 *     ./gradlew :app:connectedDebugAndroidTest
 *
 * The duplicate-Aadhaar tests here are a regression suite for a bug that reached
 * a running build: `insertWithOnConflict(CONFLICT_REPLACE)` deletes every row
 * conflicting on *any* unique index, so entering an Aadhaar that already existed
 * silently destroyed the earlier survey and reported success. A surveyor would
 * have lost a completed record with no message and no way to notice.
 */
@RunWith(AndroidJUnit4::class)
class SurveyStoreInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var helper: SetuDb
    private lateinit var store: SurveyStore

    private val keyId = ByteArray(8) { it.toByte() }

    @Before
    fun setUp() {
        context.deleteDatabase(SetuDb.NAME)
        helper = SetuDb(context)
        store = SurveyStore(helper.writableDatabase)
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(SetuDb.NAME)
    }

    private fun survey(
        name: String,
        id: String = UUID.randomUUID().toString(),
        people: List<Person> = emptyList(),
    ) = Survey(
        surveyId = id,
        createdAt = 1_770_000_000_000L,
        updatedAt = 1_770_000_000_000L,
        status = SurveyStatus.COMPLETE,
        isProxy = false,
        proxyConsent = false,
        name = name,
        village = "Majuli",
        district = "Jorhat",
        aadhaarLast4 = "9087",
        people = people,
    )

    // ------------------------------------------------- the regression itself

    @Test
    fun duplicateAadhaarIsRefusedAndLeavesTheOriginalIntact() {
        val hash = AadhaarId.duplicateKey("123456789087")

        assertTrue(store.save(survey("Anita Baruah"), keyId, hash))
        assertEquals(1, store.count())

        // Same number, different survey. Must be refused, not "replace".
        assertFalse(store.save(survey("Duplicate Test"), keyId, hash))

        assertEquals(1, store.count())
        assertEquals("Anita Baruah", store.all().single().name)
    }

    @Test
    fun refusedSaveDoesNotStrandChildRows() {
        val hash = AadhaarId.duplicateKey("123456789087")
        val first = survey(
            "Anita",
            people = listOf(Person("p1", "x", 0, "Ramesh", 54, 0, PersonStatus.MISSING, "Majuli")),
        )
        assertTrue(store.save(first.copy(people = first.people.map { it.copy(surveyId = first.surveyId) }), keyId, hash))

        val second = survey("Duplicate", people = emptyList())
        assertFalse(store.save(second, keyId, hash))

        // The original survey keeps its person, and nothing is orphaned.
        assertEquals(1, store.get(first.surveyId)!!.people.size)
        assertEquals(0, store.purgeOrphanPeople())
    }

    @Test
    fun resavingTheSameSurveyUpdatesRatherThanDuplicating() {
        val hash = AadhaarId.duplicateKey("123456789087")
        val id = UUID.randomUUID().toString()

        assertTrue(store.save(survey("First name", id), keyId, hash))
        assertTrue(store.save(survey("Corrected name", id), keyId, hash))

        assertEquals(1, store.count())
        assertEquals("Corrected name", store.get(id)!!.name)
    }

    @Test
    fun draftsWithoutAnAadhaarDoNotCollide() {
        // Several drafts exist before anyone types a number. NULL must not
        // collide with NULL, or a surveyor could only ever hold one draft.
        repeat(4) { assertTrue(store.save(survey("Draft $it"), keyId, null)) }
        assertEquals(4, store.count())
    }

    // ------------------------------------------------------ ordinary storage

    @Test
    fun surveyRoundTripsThroughSqlite() {
        val id = UUID.randomUUID().toString()
        val people = listOf(
            Person("p1", id, 0, "অনিতা", 20, 1, PersonStatus.ALIVE, "Delhi"),
            Person("p2", id, 1, "ৰমেশ", 54, 0, PersonStatus.MISSING, "Majuli"),
        )
        val original = survey("অনিতা বৰুৱা", id, people).copy(
            mobile = "7836734808",
            aadhaarSealed = AadhaarId.seal("123456789087"),
            postOffice = "Kamalabari",
            policeStation = "Garamur",
            pin = "785104",
            damageAreas = 5,
            inCamp = true,
            campName = "Kamalabari HS School",
        )
        assertTrue(store.save(original, keyId, AadhaarId.duplicateKey("123456789087")))

        val read = store.get(id)!!
        assertEquals("অনিতা বৰুৱা", read.name)
        assertEquals("7836734808", read.mobile)
        assertEquals("785104", read.pin)
        assertEquals(5, read.damageAreas)
        assertTrue(read.inCamp)
        assertEquals("Kamalabari HS School", read.campName)
        assertNotNull(read.aadhaarSealed)
        assertEquals(2, read.people.size)
        assertEquals("অনিতা", read.people[0].name)
        assertEquals(PersonStatus.MISSING, read.people[1].status)
    }

    @Test
    fun hasAadhaarFindsTheBlobDespiteTextBinding() {
        // rawQuery binds arguments as TEXT and a BLOB never equals TEXT in
        // SQLite, so this asserts the hex() comparison actually matches.
        val hash = AadhaarId.duplicateKey("123456789087")
        val id = UUID.randomUUID().toString()
        store.save(survey("Anita", id), keyId, hash)

        assertTrue(store.hasAadhaar(hash))
        assertFalse(store.hasAadhaar(hash, exceptSurveyId = id))
        assertFalse(store.hasAadhaar(AadhaarId.duplicateKey("123456789088")))
    }

    @Test
    fun deleteRemovesThePeopleToo() {
        val id = UUID.randomUUID().toString()
        store.save(
            survey("Anita", id, listOf(Person("p1", id, 0, "R", 1, 0, PersonStatus.ALIVE, "x"))),
            keyId,
            null,
        )
        store.delete(id)
        assertEquals(0, store.count())
        assertEquals(0, store.purgeOrphanPeople())
    }

    /** Schema v1 data must survive the v2 upgrade — see SetuDb.onUpgrade. */
    @Test
    fun upgradeFromV1KeepsExistingMessages() {
        helper.close()
        context.deleteDatabase(SetuDb.NAME)

        // Build a v1-shaped database by hand, with one row in it.
        val path = context.getDatabasePath(SetuDb.NAME)
        path.parentFile?.mkdirs()
        val v1 = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null)
        v1.execSQL(
            "CREATE TABLE message (msg_id BLOB PRIMARY KEY, type INTEGER NOT NULL, " +
                "tier INTEGER NOT NULL, origin_key_id BLOB NOT NULL, created_at INTEGER NOT NULL, " +
                "received_at INTEGER NOT NULL, ttl_hours INTEGER NOT NULL, hop_count INTEGER NOT NULL, " +
                "envelope BLOB NOT NULL, is_mine INTEGER NOT NULL, status INTEGER NOT NULL, " +
                "advert_state INTEGER NOT NULL)",
        )
        v1.execSQL(
            "INSERT INTO message VALUES (x'0102030405060708', 1, 0, x'1112131415161718', " +
                "1, 1, 24, 0, x'00', 1, 0, 0)",
        )
        v1.version = 1
        v1.close()

        // Opening with the current helper runs onUpgrade.
        val upgraded = SetuDb(context)
        val db = upgraded.writableDatabase
        db.rawQuery("SELECT COUNT(*) FROM message", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("the v1 message was destroyed by the upgrade", 1, it.getInt(0))
        }
        // And the v2 tables now exist.
        assertEquals(0, SurveyStore(db).count())
        upgraded.close()
    }
}
