package com.aura.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.db.AuroraDatabase
import com.aura.db.ContactEntity
import com.aura.db.DbKeyStore
import com.aura.db.KemRatchetEntity
import com.aura.db.MeshPeerEntity
import com.aura.db.MessageEntity
import com.aura.db.PrekeyEntity
import com.aura.identity.IdentityStore
import com.aura.media.EncryptedMediaStore
import com.aura.settings.AuroraSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T4 security — full erase. The strong claim is **data is actually gone**, so this drives the
 * destructive DAO/settings paths against a REAL (file-backed) Room DB + real [AuroraSettings] and
 * re-opens the DB after the wipe to assert every table is empty — a relaxed-mock-only test would
 * pass even if a delete SQL erased nothing. Only the genuinely un-fakeable seams (Keystore identity,
 * SQLCipher key, media files, Context file deletes) are mocked, and we still assert each was invoked
 * and that one failing step does not abort the rest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecureWipeTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "securewipe-test.db"
    private val node = "ab".repeat(32)

    private val identity = mockk<IdentityStore>(relaxed = true)
    private val dbKey = mockk<DbKeyStore>(relaxed = true)
    private val media = mockk<EncryptedMediaStore>(relaxed = true)
    private val appLock = mockk<AppLock>(relaxed = true)
    // Context is mocked so deleteDatabase() is a no-op — the real file survives close() and we
    // reopen it to inspect the rows. We still assert the production delete calls fired.
    private val context = mockk<Context>(relaxed = true)

    private fun openDb() = Room.databaseBuilder(ctx, AuroraDatabase::class.java, dbName)
        .allowMainThreadQueries().build()

    @AfterTest fun tearDown() { ctx.deleteDatabase(dbName) }

    private suspend fun seed(db: AuroraDatabase) {
        db.contactDao().upsert(ContactEntity(node, "Alice", "k", "e", createdAtMs = 1, pairingSent = true))
        db.messageDao().insert(MessageEntity("m1", node, fromMe = false, body = "hi", timestampMs = 1, status = "sent"))
        db.ratchetDao().kemUpsert(KemRatchetEntity(node, "kem-session-blob"))   // the key material that MUST die
        db.prekeyDao().insert(PrekeyEntity("p1", "opk", "pub", "priv", createdAtMs = 1))
        db.meshPeerDao().upsertAll(listOf(MeshPeerEntity("1.2.3.4:5", "1.2.3.4", 5, 1, 1)))
    }

    @Test fun wipeEverything_actuallyEmptiesEveryTable_andClearsSettings_andInvokesKeyDestruction() = runBlocking {
        ctx.deleteDatabase(dbName)
        val db = openDb()
        seed(db)
        val settings = AuroraSettings(ctx).apply { blockNode(node) }   // real prefs

        SecureWipe(context, db, identity, dbKey, media, settings, appLock, Dispatchers.Unconfined).wipeEverything()

        // The DB was closed by the wipe; reopen the same file and prove the rows are GONE.
        val reopened = openDb()
        try {
            assertNull(reopened.contactDao().byNodeId(node), "contacts erased")
            assertTrue(reopened.messageDao().allForBackup().isEmpty(), "messages erased")
            assertTrue(reopened.ratchetDao().allKemForBackup().isEmpty(), "KEM ratchet sessions erased")
            assertEquals(0, reopened.prekeyDao().unusedOpkCount(), "prekeys erased")
            assertTrue(reopened.meshPeerDao().all().isEmpty(), "mesh peers erased")
        } finally {
            reopened.close()
        }
        assertFalse(settings.isBlocked(node), "settings (blocklist) cleared")

        // Un-fakeable key-destruction + file-removal seams still fire.
        coVerify { media.wipeAll() }
        coVerify { appLock.disableLock() }
        coVerify { identity.clear() }
        coVerify { dbKey.clear() }
        coVerify { context.deleteDatabase("aura.db") }
        coVerify { context.deleteSharedPreferences("aura_identity") }
        coVerify { context.deleteSharedPreferences("aura_lock") }
        coVerify { context.deleteSharedPreferences("aura_dbkey") }
    }

    @Test fun oneFailingStep_doesNotAbortKeyDestruction() = runBlocking {
        // A failure in an early step (media files) must not stop the later key-vault destruction —
        // that key destruction is what makes the wipe a real cryptographic erase.
        ctx.deleteDatabase(dbName)
        val db = openDb()
        seed(db)
        coEvery { media.wipeAll() } throws RuntimeException("media volume unavailable")

        SecureWipe(context, db, identity, dbKey, media, AuroraSettings(ctx), appLock, Dispatchers.Unconfined)
            .wipeEverything()   // must not throw

        // DB rows (dropped before the failing media step) are still gone, and key destruction ran.
        val reopened = openDb()
        try {
            assertTrue(reopened.ratchetDao().allKemForBackup().isEmpty(), "ratchet rows still erased")
            assertNull(reopened.contactDao().byNodeId(node))
        } finally { reopened.close() }
        coVerify { identity.clear() }
        coVerify { dbKey.clear() }
        coVerify { context.deleteSharedPreferences("aura_identity") }
    }
}
