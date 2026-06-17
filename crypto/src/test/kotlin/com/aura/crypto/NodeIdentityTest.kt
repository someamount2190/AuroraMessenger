package com.aura.crypto

import com.aura.crypto.testutil.NativeCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** T2 (native liboqs). Skips when the native lib is unavailable. */
class NodeIdentityTest {

    private val hkdf = Hkdf()
    private val gen = NodeIdentityGenerator(HybridKem(hkdf), HybridSigner(), hkdf)

    @Before fun requireNative() = assumeTrue("liboqs native unavailable", NativeCrypto.available)

    @Test fun nodeId_isHashOfPublicKeys() = runTest {
        val id = gen.generate().getOrThrow()
        val expected = hkdf.sha3_256(
            id.publicPart.kemPublicKey.toBytes() + id.publicPart.signingPublicKey.toBytes()
        )
        assertTrue(expected.contentEquals(id.nodeId))
    }

    @Test fun publicIdentity_bytesRoundTrip() = runTest {
        val id = gen.generate().getOrThrow()
        val restored = NodePublicIdentity.fromBytes(id.publicPart.toBytes(), hkdf)
        assertEquals(id.publicPart, restored)
        assertTrue(id.nodeId.contentEquals(restored.nodeId))
    }

    @Test fun tamperedBytes_areRejected() = runTest {
        val id = gen.generate().getOrThrow()
        val bytes = id.publicPart.toBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()  // flip a key byte
        assertFailsWith<IllegalArgumentException> { NodePublicIdentity.fromBytes(bytes, hkdf) }
    }

    @Test fun distinctIdentities_haveDistinctNodeIds() = runTest {
        val a = gen.generate().getOrThrow()
        val b = gen.generate().getOrThrow()
        assertTrue(!a.nodeId.contentEquals(b.nodeId))
    }
}
