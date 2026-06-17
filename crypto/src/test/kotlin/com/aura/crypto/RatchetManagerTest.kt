package com.aura.crypto

import com.aura.crypto.testutil.FakeRatchetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Forward-secret symmetric ratchet. Two managers (Alice, Bob) are each seeded from
 * the SAME 32-byte root with swapped my/peer ids — mirroring two paired devices —
 * so Alice's send chain == Bob's receive chain.
 */
class RatchetManagerTest {

    private val hkdf = Hkdf()
    private val cipher = SymmetricCipher()
    private val aad = "frame-aad".toByteArray()

    // node ids chosen so ALICE < BOB lexicographically (drives chain role assignment)
    private val ALICE = "aaaa"
    private val BOB = "bbbb"

    private class TwoParty(hkdf: Hkdf, cipher: SymmetricCipher) {
        val alice = RatchetManager(FakeRatchetStore(), hkdf, cipher)
        val bob = RatchetManager(FakeRatchetStore(), hkdf, cipher)
    }

    private suspend fun paired(aliceRoot: Byte = 7, bobRoot: Byte = 7): TwoParty {
        val tp = TwoParty(hkdf, cipher)
        tp.alice.seedFromSharedSecret(BOB, ALICE, BOB, ByteArray(32) { aliceRoot })
        tp.bob.seedFromSharedSecret(ALICE, BOB, ALICE, ByteArray(32) { bobRoot })
        return tp
    }

    @Test fun seed_marksSeeded() = runTest {
        val tp = paired()
        assertTrue(tp.alice.isSeeded(BOB))
        assertTrue(tp.bob.isSeeded(ALICE))
        assertFalse(tp.alice.isSeeded("unknown"))
    }

    @Test fun roundTrip_aliceToBob() = runTest {
        val tp = paired()
        val pt = "hello bob".toByteArray()
        val sealed = tp.alice.sealNext(BOB, pt, aad)!!
        assertEquals(0L, sealed.n)
        assertContentEquals(pt, tp.bob.open(ALICE, sealed.n, sealed.bytes, aad))
    }

    @Test fun roundTrip_bothDirections() = runTest {
        val tp = paired()
        val a = tp.alice.sealNext(BOB, "from alice".toByteArray(), aad)!!
        assertContentEquals("from alice".toByteArray(), tp.bob.open(ALICE, a.n, a.bytes, aad))
        val b = tp.bob.sealNext(ALICE, "from bob".toByteArray(), aad)!!
        assertContentEquals("from bob".toByteArray(), tp.alice.open(BOB, b.n, b.bytes, aad))
    }

    @Test fun counter_incrementsPerSeal() = runTest {
        val tp = paired()
        assertEquals(0L, tp.alice.sealNext(BOB, "a".toByteArray(), aad)!!.n)
        assertEquals(1L, tp.alice.sealNext(BOB, "b".toByteArray(), aad)!!.n)
        assertEquals(2L, tp.alice.sealNext(BOB, "c".toByteArray(), aad)!!.n)
    }

    @Test fun sameMessageKey_neverReused() = runTest {
        val tp = paired()
        val c0 = tp.alice.sealNext(BOB, "x".toByteArray(), aad)!!.bytes
        val c1 = tp.alice.sealNext(BOB, "x".toByteArray(), aad)!!.bytes
        assertFalse(c0.contentEquals(c1), "successive seals must produce different ciphertext")
    }

    @Test fun outOfOrder_delivery() = runTest {
        val tp = paired()
        val m0 = tp.alice.sealNext(BOB, "m0".toByteArray(), aad)!!
        val m1 = tp.alice.sealNext(BOB, "m1".toByteArray(), aad)!!
        val m2 = tp.alice.sealNext(BOB, "m2".toByteArray(), aad)!!
        // deliver 2, 0, 1
        assertContentEquals("m2".toByteArray(), tp.bob.open(ALICE, m2.n, m2.bytes, aad))
        assertContentEquals("m0".toByteArray(), tp.bob.open(ALICE, m0.n, m0.bytes, aad))
        assertContentEquals("m1".toByteArray(), tp.bob.open(ALICE, m1.n, m1.bytes, aad))
    }

    @Test fun skippedKey_isSingleUse() = runTest {
        val tp = paired()
        val m0 = tp.alice.sealNext(BOB, "m0".toByteArray(), aad)!!
        val m1 = tp.alice.sealNext(BOB, "m1".toByteArray(), aad)!!
        // open m1 first (caches the key for skipped m0)
        assertContentEquals("m1".toByteArray(), tp.bob.open(ALICE, m1.n, m1.bytes, aad))
        assertContentEquals("m0".toByteArray(), tp.bob.open(ALICE, m0.n, m0.bytes, aad))
        assertNull(tp.bob.open(ALICE, m0.n, m0.bytes, aad), "skipped key must not work twice")
    }

    @Test fun inOrderReplay_isRejected() = runTest {
        val tp = paired()
        val m0 = tp.alice.sealNext(BOB, "m0".toByteArray(), aad)!!
        assertContentEquals("m0".toByteArray(), tp.bob.open(ALICE, m0.n, m0.bytes, aad))
        assertNull(tp.bob.open(ALICE, m0.n, m0.bytes, aad), "replay of a consumed counter must fail")
    }

    @Test fun forgedFrame_doesNotBurnChain() = runTest {
        val tp = paired()
        val m0 = tp.alice.sealNext(BOB, "m0".toByteArray(), aad)!!
        val corrupt = m0.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertNull(tp.bob.open(ALICE, m0.n, corrupt, aad), "tampered frame must not decrypt")
        // chain must NOT have advanced — the genuine frame still opens
        assertContentEquals("m0".toByteArray(), tp.bob.open(ALICE, m0.n, m0.bytes, aad))
    }

    @Test fun wrongAad_isRejected_andChainIntact() = runTest {
        val tp = paired()
        val m0 = tp.alice.sealNext(BOB, "m0".toByteArray(), aad)!!
        assertNull(tp.bob.open(ALICE, m0.n, m0.bytes, "other-aad".toByteArray()))
        assertContentEquals("m0".toByteArray(), tp.bob.open(ALICE, m0.n, m0.bytes, aad))
    }

    @Test fun skipFlood_beyondMax_isRejected() = runTest {
        val tp = paired()
        val m0 = tp.alice.sealNext(BOB, "m0".toByteArray(), aad)!!
        val tooFar = RatchetManager.MAX_SKIP_AHEAD + 1
        assertNull(tp.bob.open(ALICE, tooFar, m0.bytes, aad), "gap beyond MAX_SKIP_AHEAD must be refused")
        // state untouched: the in-order frame still opens
        assertContentEquals("m0".toByteArray(), tp.bob.open(ALICE, m0.n, m0.bytes, aad))
    }

    @Test fun unseededContact_returnsNull() = runTest {
        val tp = TwoParty(hkdf, cipher)
        assertNull(tp.bob.open(ALICE, 0, ByteArray(64), aad))
        assertNull(tp.alice.sealNext(BOB, "x".toByteArray(), aad))
    }

    // ── SAS ──────────────────────────────────────────────────────────────────
    @Test fun sas_bothPeersAgree_andAreSixDigits() = runTest {
        val tp = paired()
        val a = tp.alice.sasCode(BOB)!!
        val b = tp.bob.sasCode(ALICE)!!
        assertEquals(a, b)
        assertTrue(Regex("\\d{6}").matches(a), "SAS must be 6 digits: $a")
    }

    @Test fun sasCodeFor_bothPeersAgreeForSameTarget() = runTest {
        val tp = paired()
        val target = "ccccdddd"
        assertEquals(tp.alice.sasCodeFor(BOB, target), tp.bob.sasCodeFor(ALICE, target))
        assertTrue(Regex("\\d{6}").matches(tp.alice.sasCodeFor(BOB, target)!!))
    }

    /** MITM: different roots on the two sides ⇒ different SAS ⇒ verification fails. */
    @Test fun sas_differsWhenRootsDiffer() = runTest {
        val tp = paired(aliceRoot = 1, bobRoot = 2)
        assertFalse(tp.alice.sasCode(BOB) == tp.bob.sasCode(ALICE),
            "a man-in-the-middle (divergent roots) must yield mismatching SAS codes")
    }

    // ── media key & wipe ───────────────────────────────────────────────────────
    @Test fun mediaKey_presentAfterSeed() = runTest {
        val tp = paired()
        val mk = tp.alice.mediaKey(BOB)
        assertNotNull(mk)
        assertEquals(SymmetricCipher.KEY_BYTES, mk.size)
    }

    @Test fun wipe_removesContact() = runTest {
        val tp = paired()
        val m0 = tp.alice.sealNext(BOB, "m0".toByteArray(), aad)!!
        tp.bob.wipe(ALICE)
        assertFalse(tp.bob.isSeeded(ALICE))
        assertNull(tp.bob.open(ALICE, m0.n, m0.bytes, aad), "wiped contact can't decrypt")
        assertNull(tp.bob.mediaKey(ALICE))
    }

    @Test fun clear_removesAllContacts() = runTest {
        val store = FakeRatchetStore()
        val mgr = RatchetManager(store, hkdf, cipher)
        mgr.seedFromSharedSecret("c1", ALICE, "c1", ByteArray(32) { 3 })
        mgr.seedFromSharedSecret("c2", ALICE, "c2", ByteArray(32) { 4 })
        mgr.clear()
        assertFalse(mgr.isSeeded("c1"))
        assertFalse(mgr.isSeeded("c2"))
    }

    @Test fun skippedKeys_areCappedAtMaxStored() = runTest {
        // A single open can only skip up to MAX_SKIP_AHEAD (512), so we accumulate
        // past MAX_SKIPPED_STORED (1024) across several in-bounds opens, then assert
        // the cache is pruned to the cap.
        val store = FakeRatchetStore()
        val mgr = RatchetManager(store, hkdf, cipher)
        val sender = RatchetManager(FakeRatchetStore(), hkdf, cipher)
        mgr.seedFromSharedSecret(BOB, ALICE, BOB, ByteArray(32) { 9 })
        sender.seedFromSharedSecret(ALICE, BOB, ALICE, ByteArray(32) { 9 })

        val frames = ArrayList<RatchetManager.Sealed>()
        repeat(1501) { frames.add(sender.sealNext(ALICE, "m".toByteArray(), aad)!!) }  // frames[i].n == i
        // open at 500, 1000, 1500 — each gap ≤ 512, cumulatively caching ~1498 skipped keys
        for (idx in intArrayOf(500, 1000, 1500)) {
            assertContentEquals("m".toByteArray(), mgr.open(BOB, frames[idx].n, frames[idx].bytes, aad))
        }
        assertTrue(
            store.skippedCountFor(BOB) <= RatchetManager.MAX_SKIPPED_STORED,
            "skipped cache must be pruned to MAX_SKIPPED_STORED (was ${store.skippedCountFor(BOB)})"
        )
    }

    // ── concurrency: per-contact mutex must serialize seals ────────────────────
    @Test fun concurrentSeals_produceUniqueCounters() = runTest {
        val tp = paired()
        val n = 200
        val counters = ConcurrentHashMap.newKeySet<Long>()
        withContext(Dispatchers.Default) {
            (0 until n).map {
                async { tp.alice.sealNext(BOB, "m$it".toByteArray(), aad)?.n }
            }.awaitAll().forEach { it?.let(counters::add) }
        }
        assertEquals(n, counters.size, "every concurrent seal must get a distinct counter")
        assertEquals((n - 1).toLong(), counters.max())
    }
}
