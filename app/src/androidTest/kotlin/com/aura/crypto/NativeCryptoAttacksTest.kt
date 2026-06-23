package com.aura.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Instrumented adversarial suite for the NATIVE post-quantum primitives. Runs on a
 * device/emulator where the liboqs `.so` is present, so Kyber-1024 and Dilithium-3
 * actually execute. Each test mounts a known attack vector and asserts it is defeated.
 *
 * Run: `./gradlew :app:connectedDebugAndroidTest` (an unlocked emulator must be attached).
 */
@RunWith(AndroidJUnit4::class)
class NativeCryptoAttacksTest {

    private val hkdf = Hkdf()
    private val kem = HybridKem()
    private val signer = HybridSigner()

    // ── Sanity: the native primitives work at all ───────────────────────────────
    @Test fun kem_roundTrip_agrees() = runBlocking {
        val kp = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(kp.publicKey).getOrThrow()
        assertEquals(32, enc.sharedSecret.size)
        assertContentEquals(enc.sharedSecret, kem.decapsulate(enc.ciphertext, kp.privateKey).getOrThrow())
    }

    // ── Hybrid KEM partial-break: defeating ONE primitive is not enough ─────────
    @Test fun kem_partialBreak_mlkemRegion_divergesSecret() = runBlocking {
        val kp = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(kp.publicKey).getOrThrow()
        // Corrupt the leading ML-KEM-768 region of the X-Wing ciphertext.
        val bad = HybridCiphertext(
            enc.ciphertext.encoded.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        )
        val r = kem.decapsulate(bad, kp.privateKey)
        assertTrue(r.isFailure || !r.getOrThrow().contentEquals(enc.sharedSecret))
    }

    @Test fun kem_partialBreak_x25519Region_divergesSecret() = runBlocking {
        val kp = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(kp.publicKey).getOrThrow()
        // Corrupt the trailing X25519 ephemeral-key region of the X-Wing ciphertext.
        val ct = enc.ciphertext.encoded
        val bad = HybridCiphertext(
            ct.copyOf().also { it[ct.size - 1] = (it[ct.size - 1].toInt() xor 0x01).toByte() }
        )
        val r = kem.decapsulate(bad, kp.privateKey)
        assertTrue(r.isFailure || !r.getOrThrow().contentEquals(enc.sharedSecret))
    }

    @Test fun kem_wrongPrivateKey_failsToRecover() = runBlocking {
        val kp = kem.generateKeyPair().getOrThrow()
        val other = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(kp.publicKey).getOrThrow()
        val r = kem.decapsulate(enc.ciphertext, other.privateKey)
        assertTrue(r.isFailure || !r.getOrThrow().contentEquals(enc.sharedSecret))
    }

    // ── Hybrid signature downgrade: BOTH halves must verify ─────────────────────
    @Test fun sign_verify_roundTrip() = runBlocking {
        val kp = signer.generateSigningKeyPair().getOrThrow()
        val sig = signer.sign("hi".toByteArray(), kp.privateKey).getOrThrow()
        assertTrue(signer.verify("hi".toByteArray(), sig, kp.publicKey).getOrThrow())
    }

    @Test fun signature_downgrade_forgedDilithium_isRejected() = runBlocking {
        val kp = signer.generateSigningKeyPair().getOrThrow()
        val msg = "authorize".toByteArray()
        val sig = signer.sign(msg, kp.privateKey).getOrThrow()
        sig[10] = (sig[10].toInt() xor 0x01).toByte()   // corrupt the Dilithium region
        assertFalse(signer.verifyHybridSync(msg, sig, kp.publicKey))
        assertTrue(signer.verify(msg, sig, kp.publicKey).isFailure)
        // the Ed25519 half is still valid → proves only the PQ half was broken yet verify fails
        assertTrue(signer.verifyHybridEd25519PartSync(msg, sig, kp.publicKey.ed25519PublicKey))
    }

    @Test fun signature_downgrade_forgedEd25519_isRejected() = runBlocking {
        val kp = signer.generateSigningKeyPair().getOrThrow()
        val msg = "authorize".toByteArray()
        val sig = signer.sign(msg, kp.privateKey).getOrThrow()
        sig[sig.size - 1] = (sig[sig.size - 1].toInt() xor 0x01).toByte()   // corrupt the Ed25519 region
        assertFalse(signer.verifyHybridSync(msg, sig, kp.publicKey))
    }

    @Test fun signature_wrongKey_isRejected() = runBlocking {
        val kp = signer.generateSigningKeyPair().getOrThrow()
        val other = signer.generateSigningKeyPair().getOrThrow()
        val sig = signer.sign("m".toByteArray(), kp.privateKey).getOrThrow()
        assertFalse(signer.verifyHybridSync("m".toByteArray(), sig, other.publicKey))
    }

    // ── Identity binding with real keys ─────────────────────────────────────────
    @Test fun nodeIdentity_bytesRoundTrip_andTamperRejected() = runBlocking {
        val gen = NodeIdentityGenerator(kem, signer, hkdf)
        val id = gen.generate().getOrThrow()
        assertEquals(id.publicPart, NodePublicIdentity.fromBytes(id.publicPart.toBytes(), hkdf))
        val tampered = id.publicPart.toBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFalse(runCatching { NodePublicIdentity.fromBytes(tampered, hkdf) }.isSuccess)
    }
}
