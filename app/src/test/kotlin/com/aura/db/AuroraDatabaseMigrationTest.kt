package com.aura.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Migration data-preservation tests. The production `AuroraDatabase.build` is SQLCipher-backed
 * (native lib, not loadable under Robolectric), so we exercise the `Migration` objects directly
 * against a plain in-memory SQLite built from the prior schema — proving the SQL each migration
 * runs both transforms the schema AND preserves the rows it must not touch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuroraDatabaseMigrationTest {

    private val open = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext<Context>())
            .name(null)   // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldV: Int, newV: Int) {}
            })
            .build()
    )
    private val db: SupportSQLiteDatabase get() = open.writableDatabase

    @AfterTest fun tearDown() = open.close()

    private fun count(table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private fun tableExists(name: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'").use { it.moveToFirst() }

    // ── v9 → v10: drop the symmetric ratchet tables, clear the incompatible KEM blobs ──────────
    @Test fun migration_9_10_dropsSymmetricTables_clearsKemBlobs_keepsContacts() {
        db.execSQL("CREATE TABLE ratchet_state (contactNodeIdHex TEXT NOT NULL PRIMARY KEY, sendChainKeyB64 TEXT NOT NULL, sendN INTEGER NOT NULL, recvChainKeyB64 TEXT NOT NULL, recvN INTEGER NOT NULL, sasFingerprintB64 TEXT NOT NULL, mediaKeyB64 TEXT NOT NULL)")
        db.execSQL("CREATE TABLE ratchet_skipped (contactNodeIdHex TEXT NOT NULL, n INTEGER NOT NULL, messageKeyB64 TEXT NOT NULL, PRIMARY KEY(contactNodeIdHex, n))")
        db.execSQL("CREATE TABLE kem_ratchet (contactNodeIdHex TEXT NOT NULL PRIMARY KEY, sessionB64 TEXT NOT NULL)")
        db.execSQL("CREATE TABLE contacts (nodeIdHex TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL)")
        db.execSQL("INSERT INTO ratchet_state VALUES ('c1','s',0,'r',0,'fp','mk')")
        db.execSQL("INSERT INTO ratchet_skipped VALUES ('c1',1,'k1')")
        db.execSQL("INSERT INTO kem_ratchet VALUES ('c1','old-unversioned-blob')")
        db.execSQL("INSERT INTO contacts VALUES ('c1','Alice')")

        AuroraDatabase.MIGRATION_9_10.migrate(db)

        assertFalse(tableExists("ratchet_state"), "ratchet_state must be dropped")
        assertFalse(tableExists("ratchet_skipped"), "ratchet_skipped must be dropped")
        assertEquals(0, count("kem_ratchet"), "stale unversioned KEM blobs must be cleared")
        assertEquals(1, count("contacts"), "contacts must be preserved (migration must not be destructive)")
    }

    // ── v8 → v9: add the kem_ratchet table (additive; existing data untouched) ──────────────────
    @Test fun migration_8_9_addsKemRatchet_keepsContacts() {
        db.execSQL("CREATE TABLE contacts (nodeIdHex TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL)")
        db.execSQL("INSERT INTO contacts VALUES ('c1','Alice')")
        assertFalse(tableExists("kem_ratchet"))

        AuroraDatabase.MIGRATION_8_9.migrate(db)

        assertTrue(tableExists("kem_ratchet"), "kem_ratchet must be created")
        db.execSQL("INSERT INTO kem_ratchet VALUES ('c1','blob')")   // usable after migration
        assertEquals(1, count("kem_ratchet"))
        assertEquals(1, count("contacts"), "additive migration must preserve contacts")
    }

    // ── v7 → v8: rebuild prekeys with X-Wing columns (prekeys are ephemeral → drop is lossless) ─
    @Test fun migration_7_8_rebuildsPrekeysWithXWingColumns() {
        // old prekeys schema (kyber/x25519 column pairs)
        db.execSQL("CREATE TABLE prekeys (prekeyId TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, kyberPubB64 TEXT NOT NULL, x25519PubB64 TEXT NOT NULL, createdAtMs INTEGER NOT NULL)")
        db.execSQL("INSERT INTO prekeys VALUES ('p1','spk','k','x',1)")

        AuroraDatabase.MIGRATION_7_8.migrate(db)

        // The rebuilt table accepts the new X-Wing columns and starts empty.
        assertEquals(0, count("prekeys"), "ephemeral prekeys are dropped on the X-Wing rebuild")
        db.execSQL("INSERT INTO prekeys (prekeyId, kind, kemPubB64, kemPrivB64, createdAtMs) VALUES ('p2','spk','pub','priv',2)")
        assertEquals(1, count("prekeys"))
    }
}
