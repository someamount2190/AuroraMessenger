package com.aura.backup

import android.content.Context
import android.util.Base64
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
import com.aura.security.AppLock
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            cipher, freshAppLock(), Dispatchers.Unconfined).export("correct horse".toCharArray())

        val restored = slot<NodeIdentity>()
        val importer = mockk<IdentityStore>()
        coJustRun { importer.restore(capture(restored)) }
        val result = Backups(importer, tgtDb.contactDao(), tgtDb.messageDao(), tgtDb.ratchetDao(),
            cipher, freshAppLock(), Dispatchers.Unconfined).import(blob, "correct horse".toCharArray())

        assertTrue(result.isSuccess, "round-trip import must succeed")
        // Deep identity check — nodeId alone is unaffected by a swapped/dropped key field, so the
        // restored identity must be unusable-for-decrypt/sign unless every key survives the JSON.
        val r = restored.captured
        assertEquals(genId.nodeId.toHex(), r.nodeId.toHex())
        assertContentEquals(genId.privatePart.kemPrivateKey.encoded, r.privatePart.kemPrivateKey.encoded, "KEM priv")
        assertContentEquals(genId.privatePart.signingPrivateKey.dilithiumPrivateKey, r.privatePart.signingPrivateKey.dilithiumPrivateKey, "ML-DSA priv")
        assertContentEquals(genId.privatePart.signingPrivateKey.ed25519PrivateKey, r.privatePart.signingPrivateKey.ed25519PrivateKey, "Ed25519 priv")
        assertContentEquals(genId.publicPart.kemPublicKey.encoded, r.publicPart.kemPublicKey.encoded, "KEM pub")
        assertContentEquals(genId.publicPart.signingPublicKey.dilithiumPublicKey, r.publicPart.signingPublicKey.dilithiumPublicKey, "ML-DSA pub")
        assertContentEquals(genId.publicPart.signingPublicKey.ed25519PublicKey, r.publicPart.signingPublicKey.ed25519PublicKey, "Ed25519 pub")

        assertNotNull(tgtDb.contactDao().byNodeId(node), "contact restored")
        assertNotNull(tgtDb.messageDao().byId("m1"), "message restored")
        val kem = tgtDb.ratchetDao().allKemForBackup()
        assertEquals(1, kem.size); assertEquals("kem-session-blob", kem.first().sessionB64)
    }

    @Test fun import_v2Backup_skipsKemRatchetRestore_butKeepsContactsAndMessages() = runBlocking {
        seedSource()
        val exporter = mockk<IdentityStore>(); coEvery { exporter.getOrCreate() } returns genId
        val v3 = Backups(exporter, srcDb.contactDao(), srcDb.messageDao(), srcDb.ratchetDao(),
            cipher, freshAppLock(), Dispatchers.Unconfined).export("pw".toCharArray())
        val v2 = rewriteInnerVersion(v3, "pw", to = 2)   // the old format whose KEM blobs are incompatible

        val importer = mockk<IdentityStore>(relaxed = true)
        val result = Backups(importer, tgtDb.contactDao(), tgtDb.messageDao(), tgtDb.ratchetDao(),
            cipher, freshAppLock(), Dispatchers.Unconfined).import(v2, "pw".toCharArray())

        assertTrue(result.isSuccess)
        assertNotNull(tgtDb.contactDao().byNodeId(node), "v2 still restores contacts")
        assertTrue(tgtDb.ratchetDao().allKemForBackup().isEmpty(),
            "the v<3 gate must SKIP KEM ratchet restore (stale-format blobs ⇒ re-pair)")
    }

    @Test fun import_wrongPassphrase_failsClosed() = runBlocking {
        seedSource()
        val exporter = mockk<IdentityStore>()
        coEvery { exporter.getOrCreate() } returns genId
        val blob = Backups(exporter, srcDb.contactDao(), srcDb.messageDao(), srcDb.ratchetDao(),
            cipher, freshAppLock(), Dispatchers.Unconfined).export("right".toCharArray())

        val importer = mockk<IdentityStore>(relaxed = true)
        val result = Backups(importer, tgtDb.contactDao(), tgtDb.messageDao(), tgtDb.ratchetDao(),
            cipher, freshAppLock(), Dispatchers.Unconfined).import(blob, "WRONG".toCharArray())

        assertTrue(result.isFailure, "a wrong passphrase must not decrypt")
        // Fail CLOSED: nothing decrypted ⇒ nothing written. (Guards against a future reorder that
        // touched the DB or restored the identity before the AEAD check.)
        assertNull(tgtDb.contactDao().byNodeId(node), "no rows written on a failed import")
        coVerify(exactly = 0) { importer.restore(any()) }
    }

    @Test fun export_underDecoy_refuses() = runBlocking {
        // Decoy mode must expose no real data: a backup reads the raw DAOs + identity, so it must
        // refuse while the app was unlocked with the decoy PIN (else it exfiltrates everything).
        seedSource()
        val exporter = mockk<IdentityStore>(relaxed = true)
        val backups = Backups(exporter, srcDb.contactDao(), srcDb.messageDao(), srcDb.ratchetDao(),
            cipher, decoyActiveLock(), Dispatchers.Unconfined)
        assertFailsWith<IllegalStateException> { backups.export("pw".toCharArray()) }
        assertTrue(true, "export must throw under decoy, not return real data")  // keep test method Unit-typed
    }

    @Test fun import_foreignBlob_fails() = runBlocking {
        val importer = mockk<IdentityStore>(relaxed = true)
        val result = Backups(importer, tgtDb.contactDao(), tgtDb.messageDao(), tgtDb.ratchetDao(),
            cipher, freshAppLock(), Dispatchers.Unconfined).import("not an aurora backup".toByteArray(), "x".toCharArray())
        assertTrue(result.isFailure, "a non-Aurora file must be rejected")
    }

    // ── helpers: AppLock instances (real internal seam, no Keystore) ────────────────────────────

    private var lockCounter = 0
    private fun lockPrefs() = ctx.getSharedPreferences("backup_lock_${lockCounter++}", Context.MODE_PRIVATE)

    /** A lock with decoy NOT active (default) — exports allowed. */
    private fun freshAppLock(): AppLock = AppLock(lockPrefs()) { 1_000L }

    /** A lock currently unlocked with the DECOY pin — exports must be refused. */
    private fun decoyActiveLock(): AppLock = AppLock(lockPrefs()) { 1_000L }.apply {
        setPin("1234"); setDecoyPin("9999")
        check(tryUnlock("9999") == AppLock.UnlockResult.DECOY) { "decoy unlock should have flagged decoyActive" }
    }

    // ── helpers: reconstruct the backup container to forge an old-version (v2) backup ───────────

    private val backupAad = "aura-backup-v1".toByteArray()

    /** Decrypt a real export, set the inner data version, and re-seal under the same key/salt. */
    private suspend fun rewriteInnerVersion(blob: ByteArray, passphrase: String, to: Int): ByteArray {
        val container = JSONObject(String(blob, Charsets.UTF_8))
        val salt = Base64.decode(container.getString("salt"), Base64.NO_WRAP)
        val data = Base64.decode(container.getString("data"), Base64.NO_WRAP)
        val key = argon2id(passphrase.toCharArray(), salt)
        val inner = JSONObject(String(cipher.decrypt(data, key, backupAad).getOrThrow(), Charsets.UTF_8))
        inner.put("v", to)
        val sealed = cipher.encrypt(inner.toString().toByteArray(Charsets.UTF_8), key, backupAad).getOrThrow()
        return JSONObject()
            .put("magic", "AURABK").put("v", 1).put("kdf", "argon2id")
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(sealed, Base64.NO_WRAP))
            .toString().toByteArray(Charsets.UTF_8)
    }

    /** Same Argon2id parameters Backups uses (3 passes, 64 MB, parallelism 2). */
    private fun argon2id(passphrase: CharArray, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(3).withMemoryAsKB(64 * 1024).withParallelism(2).withSalt(salt).build()
        return ByteArray(32).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(passphrase, it) }
    }
}
