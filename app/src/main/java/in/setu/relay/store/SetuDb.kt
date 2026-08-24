package `in`.setu.relay.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Schema v2. v1 is verbatim from docs/06-data-model.md; v2 adds survey capture.
 *
 * Raw SQLite via [SQLiteOpenHelper]. No Room — it costs ~1 MB and an annotation
 * processor for schema mapping this app does not need.
 */
class SetuDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE message (
              msg_id        BLOB PRIMARY KEY,
              type          INTEGER NOT NULL,
              tier          INTEGER NOT NULL,
              origin_key_id BLOB NOT NULL,
              created_at    INTEGER NOT NULL,
              received_at   INTEGER NOT NULL,
              ttl_hours     INTEGER NOT NULL,
              hop_count     INTEGER NOT NULL,
              envelope      BLOB NOT NULL,
              is_mine       INTEGER NOT NULL,
              status        INTEGER NOT NULL,
              advert_state  INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_msg_tier_created ON message(tier, created_at)")
        db.execSQL("CREATE INDEX idx_msg_status ON message(status)")

        db.execSQL(
            """
            CREATE TABLE seen (
              msg_id     BLOB PRIMARY KEY,
              first_seen INTEGER NOT NULL,
              dup_count  INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE receipt (
              msg_id     BLOB NOT NULL,
              key_id     BLOB NOT NULL,
              seen_at    INTEGER NOT NULL,
              kind       INTEGER NOT NULL,
              PRIMARY KEY (msg_id, key_id, kind)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE peer (
              key_id     BLOB PRIMARY KEY,
              last_seen  INTEGER NOT NULL,
              last_sync  INTEGER,
              platform   INTEGER
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE record (
              record_id  BLOB PRIMARY KEY,
              profile_id TEXT NOT NULL,
              sealed     BLOB NOT NULL,
              created_at INTEGER NOT NULL,
              status     INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE profile (
              profile_id TEXT PRIMARY KEY,
              version    INTEGER NOT NULL,
              json       TEXT NOT NULL,
              signature  BLOB NOT NULL
            )
            """.trimIndent(),
        )

        createSurveyTables(db)
    }

    /**
     * Schema v2 — survey capture. Called from both [onCreate] and [onUpgrade] so
     * a fresh install and an upgraded install cannot drift apart.
     *
     * `aadhaar_sealed` holds the number encrypted to the backend key; there is
     * deliberately no plaintext column. `aadhaar_hash` exists only to catch the
     * same person being surveyed twice and is **not** a privacy control — see
     * `crypto/AadhaarId.kt` for why a hash of a 12-digit number protects nobody.
     */
    private fun createSurveyTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE survey (
              survey_id      TEXT PRIMARY KEY,
              created_at     INTEGER NOT NULL,
              updated_at     INTEGER NOT NULL,
              status         INTEGER NOT NULL,
              is_proxy       INTEGER NOT NULL,
              proxy_consent  INTEGER NOT NULL,
              surveyor_key   BLOB NOT NULL,

              name           TEXT,
              father_name    TEXT,
              mobile         TEXT,
              aadhaar_sealed BLOB,
              aadhaar_hash   BLOB,
              aadhaar_last4  TEXT,
              family_id      TEXT,

              village        TEXT,
              district       TEXT,
              post_office    TEXT,
              police_station TEXT,
              pin            TEXT,

              disaster_type  INTEGER NOT NULL DEFAULT 0,
              disaster_other TEXT,
              damage_date    TEXT,
              damage_areas   INTEGER NOT NULL DEFAULT 0,
              damage_other   TEXT,
              damage_desc    TEXT,

              in_camp        INTEGER NOT NULL DEFAULT 0,
              camp_name      TEXT,
              camp_location  TEXT,
              needs          TEXT
            )
            """.trimIndent(),
        )
        // Partial index: drafts have no Aadhaar yet, and SQLite would otherwise
        // treat every NULL as distinct anyway. Being explicit documents the intent.
        db.execSQL(
            "CREATE UNIQUE INDEX idx_survey_aadhaar ON survey(aadhaar_hash) " +
                "WHERE aadhaar_hash IS NOT NULL",
        )
        db.execSQL("CREATE INDEX idx_survey_status ON survey(status)")

        db.execSQL(
            """
            CREATE TABLE person (
              person_id  TEXT PRIMARY KEY,
              survey_id  TEXT NOT NULL,
              ordinal    INTEGER NOT NULL,
              name       TEXT,
              age        INTEGER,
              gender     INTEGER,
              status     INTEGER,
              location   TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_person_survey ON person(survey_id)")
    }

    /**
     * **Additive only.** The v1 implementation dropped every table and rebuilt,
     * which was defensible when nothing had shipped and is not defensible now:
     * 1.0.1 is in people's hands, and an upgrade that silently destroys a
     * half-finished survey — or a tier-0 SOS still waiting for a receipt — is a
     * data-loss bug wearing a migration costume.
     *
     * Each step migrates one version forward and falls through, so 1 -> 3 later
     * runs 1 -> 2 then 2 -> 3 without a special case.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSurveyTables(db)
    }

    /**
     * A downgrade means the user installed an older APK over a newer one. The
     * old code cannot understand the newer schema, but the newer tables are
     * additive and the older ones are untouched, so leaving the database alone
     * is safer than rebuilding it and losing everything.
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val NAME = "setu.db"
        const val VERSION = 2
    }
}
