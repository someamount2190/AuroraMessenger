package com.aura.crypto

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Correctness + security tests for the [KemDoubleRatchet] (Phase 5, X-Wing KEM Double
 * Ratchet). Pure-JVM, two in-memory sessions. The headline test is [healing_postCompromise]
 * which demonstrates the property the symmetric [RatchetManager] lacks: **post-compromise
 * security** — a stolen session snapshot stops being able to read traffic once the ratchet
 * has stepped with fresh X-Wing entropy the attacker can't reproduce.
 */
class KemDoubleRatchetTest {

    private val dr = KemDoubleRatchet(HybridKem(), Hkdf(), SymmetricCipher())

    /** Pair two fresh sessions from a shared root; Bob's initial ratchet key bootstraps it. */
    private fun pair(): Pair<KemDoubleRatchet.Session, KemDoubleRatchet.Session> = runBlocking {
        val root = ByteArray(32) { 0x5A }
        val bobInitial = HybridKem().generateKeyPair().getOrThrow()
        val alice = dr.initSender(root.copyOf(), bobInitial.publicKey)
        val bob = dr.initReceiver(root.copyOf(), bobInitial)
        alice to bob
    }

    @Test fun roundTrip_bothDirections() = runBlocking {
        val (alice, bob) = pair()
        val m1 = dr.encrypt(alice, "hi bob".toByteArray())
        assertContentEquals("hi bob".toByteArray(), dr.decrypt(bob, m1))
        val m2 = dr.encrypt(bob, "hi alice".toByteArray())
        assertContentEquals("hi alice".toByteArray(), dr.decrypt(alice, m2))
    }

    @Test fun manyAlternating_eachDirectionChangeSteps() = runBlocking {
        val (alice, bob) = pair()
        repeat(8) { i ->
            val ab = dr.encrypt(alice, "a$i".toByteArray())
            assertContentEquals("a$i".toByteArray(), dr.decrypt(bob, ab))
            val ba = dr.encrypt(bob, "b$i".toByteArray())
            assertContentEquals("b$i".toByteArray(), dr.decrypt(alice, ba))
        }
    }

    @Test fun multipleInSameChain_thenReply() = runBlocking {
        val (alice, bob) = pair()
        val msgs = (0 until 4).map { dr.encrypt(alice, "m$it".toByteArray()) }
        msgs.forEachIndexed { i, m -> assertContentEquals("m$i".toByteArray(), dr.decrypt(bob, m)) }
        val reply = dr.encrypt(bob, "got them".toByteArray())
        assertContentEquals("got them".toByteArray(), dr.decrypt(alice, reply))
    }

    @Test fun outOfOrder_withinChain_usesSkippedKeys() = runBlocking {
        val (alice, bob) = pair()
        val m0 = dr.encrypt(alice, "zero".toByteArray())   // step message (establishes the epoch)
        val m1 = dr.encrypt(alice, "one".toByteArray())
        val m2 = dr.encrypt(alice, "two".toByteArray())
        // Deliver the epoch's first (step) message, then out of order: m2, then m1, then... m0 was first.
        assertContentEquals("zero".toByteArray(), dr.decrypt(bob, m0))
        assertContentEquals("two".toByteArray(), dr.decrypt(bob, m2))   // skips+caches m1
        assertContentEquals("one".toByteArray(), dr.decrypt(bob, m1))   // from the skipped cache
    }

    @Test fun tamperedFrame_isRejected_andStateSurvives() = runBlocking {
        val (alice, bob) = pair()
        val m = dr.encrypt(alice, "authentic".toByteArray())
        val bad = KemDoubleRatchet.Message(
            m.header,
            m.ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        )
        assertNull(dr.decrypt(bob, bad))                                   // tamper rejected
        assertContentEquals("authentic".toByteArray(), dr.decrypt(bob, m)) // state intact → real frame still opens
    }

    @Test fun replayedFrame_isRejected() = runBlocking {
        val (alice, bob) = pair()
        val m = dr.encrypt(alice, "once".toByteArray())
        assertContentEquals("once".toByteArray(), dr.decrypt(bob, m))
        assertNull(dr.decrypt(bob, m))   // replay: key already consumed
    }

    /**
     * Post-compromise security ("healing"): an attacker who snapshots Alice's full session
     * state can read the immediately-following traffic, but once the ratchet has stepped with
     * fresh X-Wing entropy (Alice rotates her keypair on a receiving step, then Bob
     * encapsulates to the new key), the frozen snapshot can no longer decrypt — while the real
     * Alice can. This is exactly what the symmetric-only ratchet cannot provide.
     */
    @Test fun healing_postCompromise() = runBlocking {
        val (alice, bob) = pair()
        // Warm up the session with a couple of round trips.
        repeat(2) { i ->
            dr.decrypt(bob, dr.encrypt(alice, "a$i".toByteArray()))
            dr.decrypt(alice, dr.encrypt(bob, "b$i".toByteArray()))
        }

        // ── Attacker compromises Alice's entire session state here. ──
        val stolen = alice.deepCopy()

        // Drive several more fresh ratchet steps on the REAL sessions (each direction change
        // forces an X-Wing step; Alice rotates her keypair on each receiving step).
        repeat(3) { i ->
            dr.decrypt(bob, dr.encrypt(alice, "x$i".toByteArray()))
            dr.decrypt(alice, dr.encrypt(bob, "y$i".toByteArray()))
        }

        // Force a final direction change so the captured message is a fresh KEM *step* that
        // encapsulates to Alice's current (post-compromise-rotated) ratchet key.
        dr.decrypt(bob, dr.encrypt(alice, "ping".toByteArray()))
        val secret = dr.encrypt(bob, "after healing".toByteArray())

        // Real Alice still reads it; the frozen snapshot cannot (its private keys predate the
        // rotations, so X-Wing decapsulation of the new step yields the wrong root).
        assertContentEquals("after healing".toByteArray(), dr.decrypt(alice, secret))
        assertNull(dr.decrypt(stolen, secret), "stolen snapshot must NOT decrypt post-healing traffic")
    }

    /** A message left over from a previous epoch is still decryptable after the ratchet has
     *  stepped, via keys cached when we skipped past it (the `pn` count drives that skip). */
    @Test fun outOfOrder_acrossEpochs_usesSkippedKeys() = runBlocking {
        val (alice, bob) = pair()
        val m0 = dr.encrypt(alice, "m0".toByteArray())   // epoch A, step
        val m1 = dr.encrypt(alice, "m1".toByteArray())   // epoch A, n=1 — delayed in flight
        assertContentEquals("m0".toByteArray(), dr.decrypt(bob, m0))
        dr.decrypt(alice, dr.encrypt(bob, "r0".toByteArray()))   // Bob→Alice; Alice now owes a step
        val m2 = dr.encrypt(alice, "m2".toByteArray())   // epoch C step, pn=2 (epoch A had 2 msgs)
        assertContentEquals("m2".toByteArray(), dr.decrypt(bob, m2))   // skips+caches A.m1 while stepping
        assertContentEquals("m1".toByteArray(), dr.decrypt(bob, m1))   // delayed A.m1 from the cache
    }

    /** Both peers step at the same time (each sends before receiving the other's step); after
     *  the crossed step messages are delivered the session still converges both directions. */
    @Test fun simultaneousSteps_converge() = runBlocking {
        val (alice, bob) = pair()
        dr.decrypt(bob, dr.encrypt(alice, "warm".toByteArray()))    // both learn each other's keys
        dr.decrypt(alice, dr.encrypt(bob, "warm2".toByteArray()))
        // Both now owe a sending step; both send before receiving the other's.
        val a1 = dr.encrypt(alice, "a1".toByteArray())
        val b1 = dr.encrypt(bob, "b1".toByteArray())
        assertContentEquals("a1".toByteArray(), dr.decrypt(bob, a1))
        assertContentEquals("b1".toByteArray(), dr.decrypt(alice, b1))
        // Still able to talk both ways afterwards.
        assertContentEquals("a2".toByteArray(), dr.decrypt(bob, dr.encrypt(alice, "a2".toByteArray())))
        assertContentEquals("b2".toByteArray(), dr.decrypt(alice, dr.encrypt(bob, "b2".toByteArray())))
    }

    /** KEM-DR delivery property: an epoch's later messages can't be opened until that epoch's
     *  first (step, ciphertext-bearing) message arrives — then both decrypt. */
    @Test fun droppedStepMessage_blocksEpochUntilItArrives() = runBlocking {
        val (alice, bob) = pair()
        dr.decrypt(bob, dr.encrypt(alice, "m0".toByteArray()))
        dr.decrypt(alice, dr.encrypt(bob, "r0".toByteArray()))   // Alice owes a step
        val step = dr.encrypt(alice, "step".toByteArray())       // epoch C, carries the KEM ct
        val followup = dr.encrypt(alice, "followup".toByteArray()) // epoch C, n=1, no ct
        assertNull(dr.decrypt(bob, followup))                    // can't establish the epoch yet
        assertContentEquals("step".toByteArray(), dr.decrypt(bob, step))
        assertContentEquals("followup".toByteArray(), dr.decrypt(bob, followup))
    }

    /** The caller-supplied associated data is bound into the AEAD: a mismatch is rejected. */
    @Test fun aadBinding_mismatchRejected() = runBlocking {
        val (alice, bob) = pair()
        val m = dr.encrypt(alice, "secret".toByteArray(), aad = "context-A".toByteArray())
        assertNull(dr.decrypt(bob, m, aad = "context-B".toByteArray()))
        assertContentEquals("secret".toByteArray(), dr.decrypt(bob, m, aad = "context-A".toByteArray()))
    }

    /** The ratchet header (here the advertised ratchet public key) is AEAD-AAD-bound, so
     *  tampering it fails authentication — and leaves the session intact for the real frame. */
    @Test fun tamperedHeader_rejected_stateSurvives() = runBlocking {
        val (alice, bob) = pair()
        val m = dr.encrypt(alice, "hi".toByteArray())
        val badPub = HybridPublicKey(
            m.header.ratchetPub.encoded.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        )
        val bad = m.copy(header = m.header.copy(ratchetPub = badPub))
        assertNull(dr.decrypt(bob, bad))
        assertContentEquals("hi".toByteArray(), dr.decrypt(bob, m))
    }

    /** Skip-flood guard: a frame more than MAX_SKIP message numbers ahead is refused rather
     *  than forcing the receiver to derive an unbounded run of chain keys. */
    @Test fun skipFloodBeyondBound_rejected() = runBlocking {
        val (alice, bob) = pair()
        val first = dr.encrypt(alice, "first".toByteArray())   // n=0, establishes the epoch
        var far = first
        repeat(KemDoubleRatchet.MAX_SKIP + 2) { far = dr.encrypt(alice, "m".toByteArray()) }
        assertContentEquals("first".toByteArray(), dr.decrypt(bob, first))   // nr → 1
        assertNull(dr.decrypt(bob, far))   // gap (>512) exceeds MAX_SKIP → refused
    }

    @Test fun initialState_isConsistent() = runBlocking {
        val (alice, bob) = pair()
        // Alice owes a sending step; Bob waits to receive.
        assertTrue(alice.sendStepNeeded)
        assertEquals(false, bob.sendStepNeeded)
        assertNotNull(alice.peerPub)
        assertNull(bob.peerPub)
    }
}
