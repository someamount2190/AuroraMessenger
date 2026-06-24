package com.aura.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the store-backed [KemRatchetManager] (Phase 5 transport-integration component) against
 * an in-memory [KemSessionStore]: deterministic-from-root bootstrap, the initiator-sends-first
 * auto-bootstrap exchange, persistence across every call, healing, and the responder being
 * unable to send before it has received.
 */
class KemRatchetManagerTest {

    private class MemStore : KemSessionStore {
        private val m = HashMap<String, ByteArray>()
        override suspend fun load(contactNodeIdHex: String) = m[contactNodeIdHex]
        override suspend fun save(contactNodeIdHex: String, session: ByteArray) { m[contactNodeIdHex] = session }
        override suspend fun delete(contactNodeIdHex: String) { m.remove(contactNodeIdHex) }
        override suspend fun deleteAll() { m.clear() }
    }

    private fun manager() = KemRatchetManager(MemStore(), HybridKem(), Hkdf(), SymmetricCipher())

    private val aad = "aura-msg-v1".toByteArray()
    private val ROOT = ByteArray(32) { 0x5A }
    private val BOB = "bob"; private val ALICE = "alice"
    // Frozen SAS code for root=0x5A×32, target="peer-identity" (this implementation's value).
    private val PINNED_SAS = "386501"

    @Test fun seed_thenInitiatorFirst_roundTrips() = runBlocking {
        val a = manager(); val b = manager()
        a.seed(BOB, ROOT.copyOf(), iAmInitiator = true)
        b.seed(ALICE, ROOT.copyOf(), iAmInitiator = false)
        assertTrue(a.isSeeded(BOB)); assertTrue(b.isSeeded(ALICE))

        val s1 = a.sealNext(BOB, "hello bob".toByteArray(), aad)!!
        assertContentEquals("hello bob".toByteArray(), b.open(ALICE, s1.bytes, aad))
        val s2 = b.sealNext(ALICE, "hi alice".toByteArray(), aad)!!
        assertContentEquals("hi alice".toByteArray(), a.open(BOB, s2.bytes, aad))
    }

    @Test fun manyAlternating_throughPersistence() = runBlocking {
        val a = manager(); val b = manager()
        a.seed(BOB, ROOT.copyOf(), true); b.seed(ALICE, ROOT.copyOf(), false)
        repeat(8) { i ->
            val ab = a.sealNext(BOB, "a$i".toByteArray(), aad)!!
            assertContentEquals("a$i".toByteArray(), b.open(ALICE, ab.bytes, aad))
            val ba = b.sealNext(ALICE, "b$i".toByteArray(), aad)!!
            assertContentEquals("b$i".toByteArray(), a.open(BOB, ba.bytes, aad))
        }
    }

    @Test fun responderCannotSendBeforeReceiving() = runBlocking {
        val b = manager()
        b.seed(ALICE, ROOT.copyOf(), iAmInitiator = false)
        assertNull(b.sealNext(ALICE, "too early".toByteArray(), aad))   // no peer ratchet key yet
    }

    @Test fun outOfOrder_andReplay() = runBlocking {
        val a = manager(); val b = manager()
        a.seed(BOB, ROOT.copyOf(), true); b.seed(ALICE, ROOT.copyOf(), false)
        val m0 = a.sealNext(BOB, "m0".toByteArray(), aad)!!
        val m1 = a.sealNext(BOB, "m1".toByteArray(), aad)!!
        val m2 = a.sealNext(BOB, "m2".toByteArray(), aad)!!
        assertContentEquals("m0".toByteArray(), b.open(ALICE, m0.bytes, aad))
        assertContentEquals("m2".toByteArray(), b.open(ALICE, m2.bytes, aad))
        assertContentEquals("m1".toByteArray(), b.open(ALICE, m1.bytes, aad))
        assertNull(b.open(ALICE, m0.bytes, aad))   // replay rejected
    }

    @Test fun tamper_rejected_stateSurvives() = runBlocking {
        val a = manager(); val b = manager()
        a.seed(BOB, ROOT.copyOf(), true); b.seed(ALICE, ROOT.copyOf(), false)
        val m = a.sealNext(BOB, "authentic".toByteArray(), aad)!!
        val bad = m.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertNull(b.open(ALICE, bad, aad))
        assertContentEquals("authentic".toByteArray(), b.open(ALICE, m.bytes, aad))
    }

    @Test fun healing_postCompromise() = runBlocking {
        val aStore = MemStore(); val bStore = MemStore()
        val a = KemRatchetManager(aStore, HybridKem(), Hkdf(), SymmetricCipher())
        val b = KemRatchetManager(bStore, HybridKem(), Hkdf(), SymmetricCipher())
        a.seed(BOB, ROOT.copyOf(), true); b.seed(ALICE, ROOT.copyOf(), false)
        repeat(2) { i ->
            b.open(ALICE, a.sealNext(BOB, "a$i".toByteArray(), aad)!!.bytes, aad)
            a.open(BOB, b.sealNext(ALICE, "b$i".toByteArray(), aad)!!.bytes, aad)
        }

        // Attacker snapshots Alice's persisted session blob.
        val stolenStore = MemStore().also { it.save(BOB, aStore.load(BOB)!!.copyOf()) }
        val stolen = KemRatchetManager(stolenStore, HybridKem(), Hkdf(), SymmetricCipher())

        repeat(3) { i ->
            b.open(ALICE, a.sealNext(BOB, "x$i".toByteArray(), aad)!!.bytes, aad)
            a.open(BOB, b.sealNext(ALICE, "y$i".toByteArray(), aad)!!.bytes, aad)
        }
        b.open(ALICE, a.sealNext(BOB, "ping".toByteArray(), aad)!!.bytes, aad)
        val secret = b.sealNext(ALICE, "after healing".toByteArray(), aad)!!

        assertNotNull(a.open(BOB, secret.bytes, aad))            // real Alice still reads it
        assertNull(stolen.open(BOB, secret.bytes, aad))          // frozen snapshot cannot
    }

    // ── SAS + media-at-rest key (folded in from the retired symmetric ratchet) ──────────────

    @Test fun sas_bothPeersAgree_forSameTarget_andSurvivesRatcheting() = runBlocking {
        val a = manager(); val b = manager()
        a.seed(BOB, ROOT.copyOf(), iAmInitiator = true)
        b.seed(ALICE, ROOT.copyOf(), iAmInitiator = false)

        // Each shows the code bound to a given identity; both compute the SAME value from the
        // shared root fingerprint, so the mutual cross-check matches.
        val target = "peer-identity"
        val codeFromA = a.sasCodeFor(BOB, target)
        val codeFromB = b.sasCodeFor(ALICE, target)
        assertNotNull(codeFromA)
        assertEquals(codeFromA, codeFromB)
        assertTrue(Regex("\\d{6}").matches(codeFromA!!))

        // Advancing the wire ratchet must not disturb the static SAS fingerprint.
        a.sealNext(BOB, "msg".toByteArray(), aad)
        assertEquals(codeFromA, a.sasCodeFor(BOB, target))
    }

    @Test fun sas_differsWhenRootsDiffer() = runBlocking {
        val a = manager(); val b = manager()
        a.seed(BOB, ByteArray(32) { 1 }, iAmInitiator = true)
        b.seed(ALICE, ByteArray(32) { 2 }, iAmInitiator = false)  // a MITM ⇒ different root
        assertNotEquals(a.sasCodeFor(BOB, "x"), b.sasCodeFor(ALICE, "x"))
    }

    /**
     * Pin the SAS code for a fixed root + target. The A==B / differs-on-MITM checks only prove the
     * derivation is *symmetric*; any change that stays symmetric (different HKDF info, bit-packing,
     * output length) survives them. This freezes the actual protocol value — an Aurora-internal
     * authority — so a derivation change that would silently break cross-version/peer interop fails.
     */
    @Test fun sasCode_isPinned_forFixedRootAndTarget() = runBlocking {
        val a = manager()
        a.seed(BOB, ByteArray(32) { 0x5A }, iAmInitiator = true)
        assertEquals(PINNED_SAS, a.sasCodeFor(BOB, "peer-identity"))
    }

    @Test fun mediaKey_presentAfterSeed_stableAcrossRatcheting_andLocalOnly() = runBlocking {
        val a = manager(); val b = manager()
        a.seed(BOB, ROOT.copyOf(), iAmInitiator = true)
        b.seed(ALICE, ROOT.copyOf(), iAmInitiator = false)

        val mk = a.mediaKey(BOB)
        assertNotNull(mk); assertEquals(32, mk!!.size)
        a.sealNext(BOB, "msg".toByteArray(), aad)
        assertContentEquals(mk, a.mediaKey(BOB))                 // survives ratchet steps

        // Local-only: never transported, so the two peers hold DIFFERENT media keys.
        assertFalse(mk.contentEquals(b.mediaKey(ALICE)!!))
    }

    @Test fun sasAndMediaKey_nullForUnseededContact() = runBlocking {
        val a = manager()
        assertNull(a.sasCodeFor("nobody", "x"))
        assertNull(a.mediaKey("nobody"))
    }

    /**
     * The per-contact mutex must serialize concurrent seals: 50 seals fired in parallel on the
     * same contact must each get a distinct message number (no lost ratchet step / counter reuse)
     * and every frame must still open exactly once on the peer. Removing `withLock` fails this.
     */
    @Test fun concurrentSeals_sameContact_noLostUpdates() = runBlocking {
        val a = manager(); val b = manager()
        a.seed(BOB, ROOT.copyOf(), iAmInitiator = true)
        b.seed(ALICE, ROOT.copyOf(), iAmInitiator = false)
        val n = 50

        val sealed = coroutineScope {
            (0 until n).map { i ->
                async(Dispatchers.Default) { a.sealNext(BOB, "m$i".toByteArray(), aad)!! }
            }.awaitAll()
        }
        assertEquals(n, sealed.map { it.n }.toSet().size, "every concurrent seal needs a distinct counter")

        // Open in counter order: the property under test is that the mutex serialized the seals so
        // none was lost (distinct counters, all 50 decrypt). Out-of-order receipt is covered
        // separately by outOfOrder_andReplay; replaying that here just stresses the skip cache by a
        // thread-count-dependent amount, which isn't what this test is about.
        val seen = HashSet<String>()
        for (s in sealed.sortedBy { it.n }) {
            val pt = b.open(ALICE, s.bytes, aad)
            assertNotNull(pt, "every concurrently-sealed frame must open on the peer")
            assertTrue(seen.add(String(pt)), "no two frames decrypt to the same plaintext")
        }
        assertEquals(n, seen.size)
    }

    /**
     * A legacy / malformed stored blob (e.g. a pre-header v9 session row that survived an upgrade)
     * must fail CLOSED — read as not-seeded, every accessor null — never throw out of the manager.
     */
    @Test fun legacyOrMalformedBlob_failsClosed() = runBlocking {
        // Each blob exercises a DISTINCT rejection path so no single check carries all of them:
        //  (a) old bare-session format: short AND wrong version byte (0x00 != REC_VERSION);
        //  (b) right length but WRONG version byte — isolates the version-discriminator alone;
        //  (c) right version + full 41-byte header but a GARBAGE session tail — the realistic
        //      "survived an upgrade / corrupt row" case, which only fails inside sessionFromBytes.
        val legacyShort = byteArrayOf(0x00, 0x00, 0x00, 0x20, 1, 2, 3, 4)
        val wrongVersion = ByteArray(64).also { it[0] = 0x02 }                       // len 64 ≥ header, ver 2
        val corruptSession = ByteArray(41 + 32).also { it[0] = 0x01 }                // ver ok, header ok, junk tail

        for ((label, blob) in listOf("legacyShort" to legacyShort, "wrongVersion" to wrongVersion, "corruptSession" to corruptSession)) {
            val store = MemStore().also { it.save(BOB, blob) }
            val a = KemRatchetManager(store, HybridKem(), Hkdf(), SymmetricCipher())
            assertFalse(a.isSeeded(BOB), "$label: must read as not-seeded")
            assertNull(a.sealNext(BOB, "x".toByteArray(), aad), "$label: sealNext null")
            assertNull(a.open(BOB, ByteArray(32), aad), "$label: open null")
            assertNull(a.mediaKey(BOB), "$label: mediaKey null")
            assertNull(a.sasCodeFor(BOB, "x"), "$label: sasCodeFor null")
        }
    }
}
