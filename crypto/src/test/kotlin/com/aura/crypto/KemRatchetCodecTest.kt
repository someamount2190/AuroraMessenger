package com.aura.crypto

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the [KemRatchetCodec] substrate the transport integration needs:
 *  - [KemRatchetCodec.messageToBytes]/[messageFromBytes] is a faithful self-contained wire
 *    frame (header ‖ ciphertext), for both step and non-step messages;
 *  - a [KemDoubleRatchet.Session] survives serialize→deserialize **between every operation**
 *    (simulating store-backed persistence) with correctness, out-of-order handling, and
 *    post-compromise healing all intact.
 */
class KemRatchetCodecTest {

    private val dr = KemDoubleRatchet(HybridKem(), Hkdf(), SymmetricCipher())

    private fun pair(): Pair<KemDoubleRatchet.Session, KemDoubleRatchet.Session> = runBlocking {
        val root = ByteArray(32) { 0x5A }
        val bobInitial = HybridKem().generateKeyPair().getOrThrow()
        dr.initSender(root.copyOf(), bobInitial.publicKey) to dr.initReceiver(root.copyOf(), bobInitial)
    }

    /** Round-trip a session through its serialized form (what a RatchetStore would hold). */
    private fun persist(s: KemDoubleRatchet.Session): KemDoubleRatchet.Session =
        KemRatchetCodec.sessionFromBytes(KemRatchetCodec.sessionToBytes(s))

    /** Round-trip a message through its wire form. */
    private fun wire(m: KemDoubleRatchet.Message): KemDoubleRatchet.Message =
        KemRatchetCodec.messageFromBytes(KemRatchetCodec.messageToBytes(m))

    @Test fun frame_roundTrips_forStepAndNonStepMessages() = runBlocking {
        val (alice, bob) = pair()
        val step = dr.encrypt(alice, "first".toByteArray())   // carries the KEM ciphertext
        assertTrue(step.header.kemCt != null, "first message should be a ratchet step")
        val nonStep = dr.encrypt(alice, "second".toByteArray())
        assertNull(nonStep.header.kemCt)
        assertContentEquals("first".toByteArray(), dr.decrypt(bob, wire(step)))
        assertContentEquals("second".toByteArray(), dr.decrypt(bob, wire(nonStep)))
    }

    @Test fun session_survivesPersistence_acrossManySteps() = runBlocking {
        val root = ByteArray(32) { 0x33 }
        val bobInitial = HybridKem().generateKeyPair().getOrThrow()
        var alice = dr.initSender(root.copyOf(), bobInitial.publicKey)
        var bob = dr.initReceiver(root.copyOf(), bobInitial)

        repeat(6) { i ->
            alice = persist(alice)
            val m = wire(dr.encrypt(alice, "a$i".toByteArray()))
            bob = persist(bob)
            assertContentEquals("a$i".toByteArray(), dr.decrypt(bob, m))

            bob = persist(bob)
            val r = wire(dr.encrypt(bob, "b$i".toByteArray()))
            alice = persist(alice)
            assertContentEquals("b$i".toByteArray(), dr.decrypt(alice, r))
        }
    }

    @Test fun skippedKeys_survivePersistence() = runBlocking {
        val root = ByteArray(32) { 0x44 }
        val bobInitial = HybridKem().generateKeyPair().getOrThrow()
        val alice = dr.initSender(root.copyOf(), bobInitial.publicKey)
        var bob = dr.initReceiver(root.copyOf(), bobInitial)

        val m0 = wire(dr.encrypt(alice, "m0".toByteArray()))
        val m1 = wire(dr.encrypt(alice, "m1".toByteArray()))
        val m2 = wire(dr.encrypt(alice, "m2".toByteArray()))
        assertContentEquals("m0".toByteArray(), dr.decrypt(bob, m0))
        assertContentEquals("m2".toByteArray(), dr.decrypt(bob, m2))   // caches m1's key
        bob = persist(bob)                                             // persist WITH the skipped key
        assertContentEquals("m1".toByteArray(), dr.decrypt(bob, m1))   // recovered from restored cache
    }

    @Test fun healing_survivesPersistence() = runBlocking {
        val root = ByteArray(32) { 0x77 }
        val bobInitial = HybridKem().generateKeyPair().getOrThrow()
        var alice = dr.initSender(root.copyOf(), bobInitial.publicKey)
        var bob = dr.initReceiver(root.copyOf(), bobInitial)

        repeat(2) {
            alice = persist(alice); dr.decrypt(bob, wire(dr.encrypt(alice, "a".toByteArray())))
            bob = persist(bob); dr.decrypt(alice, wire(dr.encrypt(bob, "b".toByteArray())))
        }
        val stolen = persist(alice)   // attacker captures the serialized session

        repeat(3) {
            dr.decrypt(bob, wire(dr.encrypt(alice, "x".toByteArray())))
            dr.decrypt(alice, wire(dr.encrypt(bob, "y".toByteArray())))
        }
        dr.decrypt(bob, wire(dr.encrypt(alice, "ping".toByteArray())))
        val secret = wire(dr.encrypt(bob, "after healing".toByteArray()))

        assertContentEquals("after healing".toByteArray(), dr.decrypt(alice, secret))
        assertNull(dr.decrypt(stolen, secret))   // restored-from-bytes snapshot still can't follow
    }
}
