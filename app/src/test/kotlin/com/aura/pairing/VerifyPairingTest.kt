package com.aura.pairing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.crypto.Hkdf
import com.aura.crypto.HybridKem
import com.aura.crypto.KemRatchetManager
import com.aura.crypto.SymmetricCipher
import com.aura.crypto.toHex
import com.aura.crypto.testutil.FakeKemSessionStore
import com.aura.db.AuroraDatabase
import com.aura.db.ContactEntity
import com.aura.db.ContactEraser
import com.aura.db.PairState
import com.aura.identity.IdentityStore
import com.aura.settings.AuroraSettings
import com.aura.testutil.testIdentity
import com.aura.transport.MessageSender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The SAS verification gate — the man-in-the-middle defense. A wrong-code brute force must be
 * attempt-limited and end in blocklist + cryptographic wipe; a correct code with the peer already
 * verified must activate the contact. Real Room + real KEM-ratchet SAS; the network/Keystore
 * collaborators are mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VerifyPairingTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java).allowMainThreadQueries().build()
    private val kemRatchet = KemRatchetManager(FakeKemSessionStore(), HybridKem(), Hkdf(), SymmetricCipher())

    private val identity = mockk<IdentityStore>()
    private val pairingSignal = mockk<PairingSignal>(relaxed = true)
    private val eraser = mockk<ContactEraser>(relaxed = true)
    private val settings = mockk<AuroraSettings>(relaxed = true)
    private val messageSender = mockk<MessageSender>(relaxed = true)
    private val events = PairingEvents()

    private val node = "ff".repeat(32)

    private fun verifier() = VerifyPairing(
        identity, kemRatchet, PairingCrypto(Hkdf()), pairingSignal,
        db.contactDao(), eraser, settings, events, messageSender
    )

    private suspend fun seedContact(theyVerified: Boolean) {
        db.contactDao().upsert(
            ContactEntity(node, "Alice", "kpub", "ed", createdAtMs = 1, pairingSent = true,
                pairState = PairState.VERIFY, isInitiator = false, theyVerified = theyVerified)
        )
        kemRatchet.seed(node, ByteArray(32) { 7 }, iAmInitiator = true)
    }

    @AfterTest fun tearDown() = db.close()

    @Test fun correctCode_whenPeerAlreadyVerified_activatesContact() = runBlocking {
        coEvery { identity.getOrCreate() } returns testIdentity(1)
        seedContact(theyVerified = true)
        val expected = kemRatchet.sasCodeFor(node, node)!!

        val ok = verifier().submitVerifyCode(node, expected).getOrThrow()

        assertTrue(ok, "the correct SAS code must verify")
        assertEquals(PairState.ACTIVE, db.contactDao().byNodeId(node)!!.pairState)
        coVerify { pairingSignal.sendSimple("pairverify", any(), node, any()) }
    }

    @Test fun fiveWrongCodes_blocklistAndCryptographicWipe() = runBlocking {
        coEvery { identity.getOrCreate() } returns testIdentity(1)
        seedContact(theyVerified = false)
        val expected = kemRatchet.sasCodeFor(node, node)!!
        val wrong = if (expected == "000000") "111111" else "000000"

        val v = verifier()
        repeat(5) { assertFalse(v.submitVerifyCode(node, wrong).getOrThrow(), "wrong code must fail") }

        coVerify { settings.blockNode(node) }       // brute-force defense fired
        coVerify { eraser.wipe(node) }              // cryptographic erase of the contact
    }

    @Test fun submitVerifyCode_ignoredOutsideVerifyState() = runBlocking {
        coEvery { identity.getOrCreate() } returns testIdentity(1)
        db.contactDao().upsert(
            ContactEntity(node, "Alice", "kpub", "ed", createdAtMs = 1, pairingSent = true,
                pairState = PairState.ACTIVE)
        )
        assertFalse(verifier().submitVerifyCode(node, "000000").getOrThrow(), "no verification outside VERIFY state")
    }
}
