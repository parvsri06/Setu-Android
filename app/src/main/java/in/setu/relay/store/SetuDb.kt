package `in`.setu.relay.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Schema v1, verbatim from docs/06-data-model.md.
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema v1 is the first release. There is nothing in the field to
        // migrate from, so a downgrade-safe rebuild is the honest behaviour.
        db.execSQL("DROP TABLE IF EXISTS message")
        db.execSQL("DROP TABLE IF EXISTS seen")
        db.execSQL("DROP TABLE IF EXISTS receipt")
        db.execSQL("DROP TABLE IF EXISTS peer")
        db.execSQL("DROP TABLE IF EXISTS record")
        db.execSQL("DROP TABLE IF EXISTS profile")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        onUpgrade(db, oldVersion, newVersion)

    companion object {
        const val NAME = "setu.db"
        const val VERSION = 1
    }
}
