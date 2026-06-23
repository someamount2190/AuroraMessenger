package com.aura.sim

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.crypto.Hkdf
import com.aura.crypto.HybridKem
import com.aura.crypto.KemRatchetManager
import com.aura.crypto.SymmetricCipher
import com.aura.crypto.toHex
import com.aura.db.AuroraDatabase
import com.aura.db.ContactEntity
import com.aura.db.MessageEntity
import com.aura.db.PairState
import com.aura.db.RoomKemSessionStore
import com.aura.network.InMemoryRendezvous
import com.aura.settings.AuroraSettings
import com.aura.testutil.FakeIdentityProvider
import com.aura.testutil.testIdentity
import com.aura.transport.MessageSender
import com.aura.transport.TcpMessageServer
import com.aura.transport.rtc.InMemoryPeerTransport
import com.aura.ux.MessagePulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end **two-peer simulation** in one JVM: two full peer graphs (each a real
 * [MessageSender] + [TcpMessageServer] + ratchet + Room DB) exchange real
 * double-ratchet-sealed messages over the in-memory data plane
 * ([InMemoryPeerTransport] → the peer's [TcpMessageServer] as a `FrameInbox`).
 *
 * The KEM pairing **handshake** (`HybridKem` encapsulate/decapsulate) needs liboqs
 * native, which isn't on the JVM unit-test path — so pairing's *outcome* is reproduced
 * directly: both sides seed the ratchet from one shared root (exactly what
 * `acceptIncoming`/`handlePairAccept` do) and store each other as ACTIVE contacts. The
 * handshake itself is covered by the native instrumented tests; this test covers
 * everything downstream of it — sealing, the wire frame, delivery, decrypt, store, ack —
 * which is all pure-JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EndToEndPairMessageSimTest {

    private val cipher = SymmetricCipher()

    private lateinit var alice: Peer
    private lateinit var bob: Peer

    /** One simulated peer: identity + DB + ratchet + an inbound server + an outbound sender. */
    private class Peer(
        val hex: String,
        val db: AuroraDatabase,
        val kem: KemRatchetManager,
        val server: TcpMessageServer,
        val sender: MessageSender
    )

    @Before
    fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val settings = AuroraSettings(ctx)
        val pulse = MessagePulse()
        val rendezvous = InMemoryRendezvous()   // unused on the connected RTC path, but required

        val aliceId = FakeIdentityProvider(testIdentity(0xA1))
        val bobId = FakeIdentityProvider(testIdentity(0xB2))

        val aliceDb = inMemoryDb(ctx)
        val bobDb = inMemoryDb(ctx)
        val aliceKem = KemRatchetManager(RoomKemSessionStore(aliceDb.ratchetDao()), HybridKem(), Hkdf(), cipher)
        val bobKem = KemRatchetManager(RoomKemSessionStore(bobDb.ratchetDao()), HybridKem(), Hkdf(), cipher)

        // ── "Pairing" outcome: seed the KEM ratchet from one shared root (Alice is the
        // initiator and sends first / auto-bootstraps; Bob the responder) + store contacts.
        val root = ByteArray(32) { 0x5A }
        aliceKem.seed(bobId.hex, root.copyOf(), iAmInitiator = true)
        bobKem.seed(aliceId.hex, root.copyOf(), iAmInitiator = false)
        aliceDb.contactDao().upsert(activeContact(bobId.hex, "Bob"))
        bobDb.contactDao().upsert(activeContact(aliceId.hex, "Alice"))

        // ── Each peer's inbound server (FrameInbox) over its own DB/ratchet.
        val aliceServer = TcpMessageServer(aliceId, aliceKem, aliceDb.contactDao(), aliceDb.messageDao(), settings, pulse, Dispatchers.Unconfined)
        val bobServer = TcpMessageServer(bobId, bobKem, bobDb.contactDao(), bobDb.messageDao(), settings, pulse, Dispatchers.Unconfined)

        // ── Cross-wire the data plane: each peer's sender delivers into the other's server.
        val aliceSender = MessageSender(aliceId, aliceKem, aliceDb.contactDao(), aliceDb.messageDao(), aliceDb.meshPeerDao(), rendezvous, settings, InMemoryPeerTransport(bobServer), Dispatchers.Unconfined)
        val bobSender = MessageSender(bobId, bobKem, bobDb.contactDao(), bobDb.messageDao(), bobDb.meshPeerDao(), rendezvous, settings, InMemoryPeerTransport(aliceServer), Dispatchers.Unconfined)

        alice = Peer(aliceId.hex, aliceDb, aliceKem, aliceServer, aliceSender)
        bob = Peer(bobId.hex, bobDb, bobKem, bobServer, bobSender)
    }

    @Test
    fun message_seals_delivers_decrypts_and_acks_across_two_peers() = runBlocking {
        // Alice composes a message to Bob (pending outbound).
        alice.db.messageDao().insert(
            MessageEntity("m1", bob.hex, fromMe = true, body = "hello bob", timestampMs = 100, status = "pending", type = "text")
        )

        alice.sender.flushPending()

        // Bob received it — decrypted, stored, inbound.
        val onBob = bob.db.messageDao().byId("m1")
        assertNotNull("Bob should have received the message", onBob)
        assertEquals("hello bob", onBob!!.body)
        assertFalse("inbound on Bob's side", onBob.fromMe)
        assertEquals("delivered", onBob.status)

        // Alice's copy is marked delivered (ack round-tripped).
        assertEquals("delivered", alice.db.messageDao().byId("m1")!!.status)
    }

    @Test
    fun full_duplex_reply_uses_the_reverse_ratchet_chain() = runBlocking {
        // Alice → Bob, then Bob → Alice (exercises both ratchet directions over the sim).
        alice.db.messageDao().insert(
            MessageEntity("a1", bob.hex, fromMe = true, body = "hi from alice", timestampMs = 100, status = "pending", type = "text")
        )
        alice.sender.flushPending()
        assertEquals("hi from alice", bob.db.messageDao().byId("a1")?.body)

        bob.db.messageDao().insert(
            MessageEntity("b1", alice.hex, fromMe = true, body = "hi back from bob", timestampMs = 200, status = "pending", type = "text")
        )
        bob.sender.flushPending()

        val onAlice = alice.db.messageDao().byId("b1")
        assertNotNull(onAlice)
        assertEquals("hi back from bob", onAlice!!.body)
        assertFalse(onAlice.fromMe)
        assertEquals("delivered", bob.db.messageDao().byId("b1")!!.status)
    }

    private fun inMemoryDb(ctx: Context): AuroraDatabase =
        Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java).allowMainThreadQueries().build()

    private fun activeContact(nodeIdHex: String, name: String) = ContactEntity(
        nodeIdHex = nodeIdHex,
        displayName = name,
        kemPubB64 = "k",
        ed25519PubB64 = "e",
        createdAtMs = 1,
        pairingSent = true,
        pairState = PairState.ACTIVE
    )
}
