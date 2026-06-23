package com.aura.crypto

import com.aura.crypto.testutil.FakePrekeyStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-JVM now: X-Wing PQXDH prekeys, no liboqs. */
class PrekeyManagerTest {

    private val hkdf = Hkdf()
    private val kem = HybridKem()
    private val signer = HybridSigner()
    private val gen = NodeIdentityGenerator(kem, signer, hkdf)

    @Test fun publicBundle_isSignedAndPopulated() = runTest {
        val store = FakePrekeyStore()
        val pm = PrekeyManager(store, kem, signer)
        val id = gen.generate().getOrThrow()

        val bundle = pm.publicBundle(id)
        val spk = bundle.getJSONObject("spk")
        val opks = bundle.getJSONArray("opks")
        assertEquals(PrekeyManager.OPK_PUBLISH_COUNT, opks.length())

        val nodeIdHex = id.nodeId.toHex()
        val edPub = id.publicPart.signingPublicKey.ed25519PublicKey
        // SPK signature verifies under the identity's Ed25519 key over the canonical message
        val spkMsg = PrekeyManager.prekeyMessage(nodeIdHex, PrekeyManager.KIND_SPK,
            spk.getString("id"), spk.getString("pub"))
        assertTrue(signer.verifyEd25519OnlySync(spkMsg, B64.decode(spk.getString("sig")), edPub))
        // and the first OPK too
        val o0 = opks.getJSONObject(0)
        val opkMsg = PrekeyManager.prekeyMessage(nodeIdHex, PrekeyManager.KIND_OPK,
            o0.getString("id"), o0.getString("pub"))
        assertTrue(signer.verifyEd25519OnlySync(opkMsg, B64.decode(o0.getString("sig")), edPub))
    }

    @Test fun consume_opkIsSingleUse() = runTest {
        val store = FakePrekeyStore()
        val pm = PrekeyManager(store, kem, signer)
        pm.publicBundle(gen.generate().getOrThrow())

        val spkId = store.currentSpk()!!.prekeyId
        val opkId = store.unusedOpks(1).first().prekeyId

        val first = pm.consume(spkId, opkId)
        assertNotNull(first)
        assertFalse(first.opkMissing)
        assertNotNull(first.opkPriv)

        val second = pm.consume(spkId, opkId)         // same OPK again
        assertNotNull(second)
        assertTrue(second.opkMissing, "a consumed OPK must report missing on reuse")
    }

    @Test fun consume_spkOnly_andUnknownSpk() = runTest {
        val store = FakePrekeyStore()
        val pm = PrekeyManager(store, kem, signer)
        pm.publicBundle(gen.generate().getOrThrow())
        val spkId = store.currentSpk()!!.prekeyId

        val spkOnly = pm.consume(spkId, null)
        assertNotNull(spkOnly)
        assertNull(spkOnly.opkPriv)
        assertFalse(spkOnly.opkMissing)

        assertNull(pm.consume("does-not-exist", null), "unknown SPK → null")
    }

    @Test fun wipeAll_clearsStore() = runTest {
        val store = FakePrekeyStore()
        val pm = PrekeyManager(store, kem, signer)
        pm.publicBundle(gen.generate().getOrThrow())
        assertTrue(store.records.isNotEmpty())
        pm.wipeAll()
        assertTrue(store.records.isEmpty())
    }
}
