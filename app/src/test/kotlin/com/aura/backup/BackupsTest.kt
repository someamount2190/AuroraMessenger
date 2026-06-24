package com.aura.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.crypto.Hkdf
import com.aura.crypto.HybridKem
import com.aura.crypto.HybridSigner
import com.aura.crypto.NodeIdentity
import com.aura.crypto.NodeIdentityGenerator
import com.aura.crypto.SymmetricCipher
import com.aura.crypto.toHex
import com.aura.db.AuroraDatabase
import com.aura.db.ContactEntity
import com.aura.db.KemRatchetEntity
import com.aura.db.MessageEntity
import com.aura.identity.IdentityStore
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Encrypted backup round-trip. Restore overwrites the identity and reseeds conversations, so a
 * serialization bug silently breaks every chat, and a wrong passphrase that didn't fail closed
 * would be a confidentiality break. Identity persistence is Keystore-backed in production, so
 * [IdentityStore] is mocked; everything else (DAOs, cipher, Argon2) is real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupsTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val cipher = SymmetricCipher()
    private val genId: NodeIdentity = runBlocking {
        NodeIdentityGenerator(HybridKem(), HybridSigner(), Hkdf()).generate().getOrThrow()
    }

    private fun newDb() = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java)
        .allowMainThreadQueries().build()

    private val srcDb = newDb()
    private val tgtDb = newDb()

    @AfterTest fun tearDown() { srcDb.close(); tgtDb.close() }

    private val node = "ab".repeat(32)

    private suspend fun seedSource() {
        srcDb.contactDao().upsert(ContactEntity(node, "Alice", "kpub", "edpub", createdAtMs = 1, pairingSent = true))
        srcDb.messageDao().insert(MessageEntity("m1", node, fromMe = true, body = "hi", timestampMs = 2, status = "sent"))
        srcDb.ratchetDao().kemUpsert(KemRatchetEntity(node, "kem-session-blob"))
    }

    @Test fun export_thenImport_restoresIdentityContactsMessagesAndKemRatchet() = runBlocking {
        seedSource()
        val exporter = mockk<IdentityStore>()
        coEvery { exporter.getOrCreate() } returns genId
        val blob = Backups(exporter, srcDb.contactDao(), srcDb.messageDao(), srcDb.ratchetDao(),
            cipher, Dispatchers.Unconfined).export("correct horse".toCharArray())

        val restored = slot<NodeIdentity>()
        val importer = mockk<IdentityStore>()
        coJustRun { importer.restore(capture(restored)) }
        val result = Backups(importer, tgtDb.contactDao(), tgtDb.messageDao(), tgtDb.ratchetDao(),
            cipher, Dispatchers.Unconfined).import(blob, "correct horse".toCharArray())

        assertTrue(result.isSuccess, "round-trip import must succeed")
        assertEquals(genId.nodeId.toHex(), restored.captured.nodeId.toHex(), "identity restored intact")
        assertNotNull(tgtDb.contactDao().byNodeId(node), "contact restored")
        assertNotNull(tgtDb.messageDao().byId("m1"), "message restored")
        val kem = tgtDb.ratchetDao().allKemForBackup()
        assertEquals(1, kem.size); assertEquals("kem-session-blob", kem.first().sessionB64)
    }

    @Test fun import_wrongPassphrase_failsClosed() = runBlocking {
        seedSource()
        val exporter = mockk<IdentityStore>()
        coEvery { exporter.getOrCreate() } returns genId
        val blob = Backups(exporter, srcDb.contactDao(), srcDb.messageDao(), srcDb.ratchetDao(),
            cipher, Dispatchers.Unconfined).export("right".toCharArray())

        val importer = mockk<IdentityStore>(relaxed = true)
        val result = Backups(importer, tgtDb.contactDao(), tgtDb.messageDao(), tgtDb.ratchetDao(),
            cipher, Dispatchers.Unconfined).import(blob, "WRONG".toCharArray())
        assertTrue(result.isFailure, "a wrong passphrase must not decrypt")
    }

    @Test fun import_foreignBlob_fails() = runBlocking {
        val importer = mockk<IdentityStore>(relaxed = true)
        val result = Backups(importer, tgtDb.contactDao(), tgtDb.messageDao(), tgtDb.ratchetDao(),
            cipher, Dispatchers.Unconfined).import("not an aurora backup".toByteArray(), "x".toCharArray())
        assertTrue(result.isFailure, "a non-Aurora file must be rejected")
    }
}
