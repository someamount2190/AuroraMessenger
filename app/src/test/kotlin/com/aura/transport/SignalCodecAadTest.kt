package com.aura.transport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.call.CallSignalCodec
import com.aura.crypto.Hkdf
import com.aura.crypto.HybridKem
import com.aura.crypto.KemRatchetManager
import com.aura.crypto.SymmetricCipher
import com.aura.crypto.toHex
import com.aura.crypto.testutil.FakeKemSessionStore
import com.aura.db.AuroraDatabase
import com.aura.db.ContactEntity
import com.aura.identity.IdentityStore
import com.aura.settings.AuroraSettings
import com.aura.testutil.testIdentity
import com.aura.transport.rtc.RtcSignalCodec
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Call and WebRTC signaling now share ONE per-contact KEM ratchet, kept distinct only by their
 * AEAD labels (`aura-call-v1` vs `aura-rtc-v1`). This proves a frame sealed by the call codec
 * cannot be opened by the RTC codec (no cross-purpose replay), and that the rejected attempt does
 * not burn the frame — the correct codec still opens it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SignalCodecAadTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AuroraSettings(ctx)

    private val aliceHex = testIdentity(1).nodeId.toHex()
    private val bobHex = testIdentity(2).nodeId.toHex()

    private val kemAlice = KemRatchetManager(FakeKemSessionStore(), HybridKem(), Hkdf(), SymmetricCipher())
    private val kemBob = KemRatchetManager(FakeKemSessionStore(), HybridKem(), Hkdf(), SymmetricCipher())

    private val idAlice = mockk<IdentityStore> { coEvery { getOrCreate() } returns testIdentity(1) }
    private val idBob = mockk<IdentityStore> { coEvery { getOrCreate() } returns testIdentity(2) }
    private val rendezvous = mockk<com.aura.network.Rendezvous>()

    @AfterTest fun tearDown() = db.close()

    private suspend fun setup() {
        db.contactDao().upsert(ContactEntity(aliceHex, "Alice", "k", "e", createdAtMs = 1, pairingSent = true))
        db.contactDao().upsert(ContactEntity(bobHex, "Bob", "k", "e", createdAtMs = 1, pairingSent = true))
        val root = ByteArray(32) { 9 }
        kemAlice.seed(bobHex, root.copyOf(), iAmInitiator = true)    // Alice sends first
        kemBob.seed(aliceHex, root.copyOf(), iAmInitiator = false)
    }

    @Test fun callFrame_cannotBeOpenedAsRtc_butCorrectCodecStillOpensIt() = runBlocking {
        setup()
        val payload = slot<String>()
        coEvery { rendezvous.postSignal(any(), any(), capture(payload)) } returns Result.success(Unit)

        val aliceCall = CallSignalCodec(idAlice, db.contactDao(), kemAlice, rendezvous, settings)
        val bobCall = CallSignalCodec(idBob, db.contactDao(), kemBob, rendezvous, settings)
        val bobRtc = RtcSignalCodec(idBob, db.contactDao(), kemBob, rendezvous, settings)

        // Alice seals a CALL offer to Bob; capture what hit the rendezvous queue.
        assertEquals(true, aliceCall.send(bobHex, JSONObject().put("kind", "offer")))
        val envelope = JSONObject(payload.captured)
        assertEquals("call", envelope.getString("type"))

        // Wrong purpose: the RTC codec uses the aura-rtc-v1 AAD → AEAD auth fails → null,
        // and (fail-closed) the ratchet does not advance.
        assertNull(bobRtc.receive(envelope), "a call frame must not open under the RTC label")

        // Right purpose: the call codec opens the very same frame.
        val incoming = bobCall.receive(envelope)
        assertNotNull(incoming, "the correct codec still opens the frame (not burned by the failed attempt)")
        assertEquals("offer", incoming.inner.getString("kind"))
    }
}
