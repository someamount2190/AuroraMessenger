package com.aura.crypto

import com.aura.crypto.testutil.NativeCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T2 (native liboqs — Dilithium). Skips when the native lib is unavailable. */
class HybridSignerTest {

    private val signer = HybridSigner()
    private val msg = "authenticate me".toByteArray()

    @Before fun requireNative() = assumeTrue("liboqs native unavailable", NativeCrypto.available)

    @Test fun sign_thenVerify_passes() = runTest {
        val kp = signer.generateSigningKeyPair().getOrThrow()
        val sig = signer.sign(msg, kp.privateKey).getOrThrow()
        assertTrue(signer.verify(msg, sig, kp.publicKey).getOrThrow())
        assertTrue(signer.verifyHybridSync(msg, sig, kp.publicKey))
    }

    @Test fun wrongMessage_fails() = runTest {
        val kp = signer.generateSigningKeyPair().getOrThrow()
        val sig = signer.sign(msg, kp.privateKey).getOrThrow()
        assertTrue(signer.verify("different".toByteArray(), sig, kp.publicKey).isFailure)
        assertFalse(signer.verifyHybridSync("different".toByteArray(), sig, kp.publicKey))
    }

    @Test fun wrongKey_fails() = runTest {
        val kp = signer.generateSigningKeyPair().getOrThrow()
        val other = signer.generateSigningKeyPair().getOrThrow()
        val sig = signer.sign(msg, kp.privateKey).getOrThrow()
        assertFalse(signer.verifyHybridSync(msg, sig, other.publicKey))
    }

    /** Anti-downgrade: corrupting EITHER component must fail the hybrid verify. */
    @Test fun tamperedDilithiumPart_fails() = runTest {
        val kp = signer.generateSigningKeyPair().getOrThrow()
        val sig = signer.sign(msg, kp.privateKey).getOrThrow()
        sig[10] = (sig[10].toInt() xor 0x01).toByte()  // inside the Dilithium region
        assertFalse(signer.verifyHybridSync(msg, sig, kp.publicKey))
        assertTrue(signer.verify(msg, sig, kp.publicKey).isFailure)
        // but the Ed25519 part is still intact — proves only the PQ half was broken
        assertTrue(signer.verifyHybridEd25519PartSync(msg, sig, kp.publicKey.ed25519PublicKey))
    }

    @Test fun tamperedEd25519Part_fails() = runTest {
        val kp = signer.generateSigningKeyPair().getOrThrow()
        val sig = signer.sign(msg, kp.privateKey).getOrThrow()
        sig[sig.size - 1] = (sig[sig.size - 1].toInt() xor 0x01).toByte()  // last byte = Ed25519 region
        assertFalse(signer.verifyHybridSync(msg, sig, kp.publicKey))
        assertFalse(signer.verifyHybridEd25519PartSync(msg, sig, kp.publicKey.ed25519PublicKey))
    }

    @Test fun ed25519Only_roundTrip() = runTest {
        val kp = signer.generateSigningKeyPair().getOrThrow()
        val sig = signer.signEd25519Only(msg, kp.privateKey).getOrThrow()
        assertEquals(HybridSigner.ED25519_SIG_BYTES, sig.size)
        assertTrue(signer.verifyEd25519Only(msg, sig, kp.publicKey.ed25519PublicKey).getOrThrow())
        assertTrue(signer.verifyEd25519OnlySync(msg, sig, kp.publicKey.ed25519PublicKey))
        assertFalse(signer.verifyEd25519OnlySync("x".toByteArray(), sig, kp.publicKey.ed25519PublicKey))
    }

    @Test fun verifyKey_bytesRoundTrip() = runTest {
        val kp = signer.generateSigningKeyPair().getOrThrow()
        assertEquals(kp.publicKey, HybridVerifyKey.fromBytes(kp.publicKey.toBytes()))
    }
}
