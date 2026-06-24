package com.aura.crypto

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial suite for the hybrid post-quantum primitives (X-Wing KEM + ML-DSA/Ed25519
 * signatures). The stack is pure-JVM (BouncyCastle), so this runs in the normal JVM CI — it
 * was previously an instrumented `androidTest` (a relic of the retired liboqs JNI tier) and
 * therefore never executed in CI. Each test mounts a known attack vector and asserts it is
 * defeated; a green run means the attack was beaten, a failure is a real finding.
 */
class HybridCryptoAttacksTest {

    private val hkdf = Hkdf()
    private val kem = HybridKem()
    private val signer = HybridSigner()

    // ── Sanity: the primitives agree at all ─────────────────────────────────────
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
        val bad = HybridCiphertext(
            enc.ciphertext.encoded.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        )
        val r = kem.decapsulate(bad, kp.privateKey)
        assertTrue(r.isFailure || !r.getOrThrow().contentEquals(enc.sharedSecret))
    }

    @Test fun kem_partialBreak_x25519Region_divergesSecret() = runBlocking {
        val kp = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(kp.publicKey).getOrThrow()
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
        sig[10] = (sig[10].toInt() xor 0x01).toByte()   // corrupt the ML-DSA region
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
        val parsed = NodePublicIdentity.fromBytes(id.publicPart.toBytes(), hkdf)
        // NodePublicIdentity.equals only compares nodeId, so assert the KEY BYTES survived too —
        // a serialization bug that corrupted a key while leaving the nodeId field intact would
        // otherwise pass.
        assertContentEquals(id.publicPart.kemPublicKey.encoded, parsed.kemPublicKey.encoded, "KEM pub round-trips")
        assertContentEquals(id.publicPart.signingPublicKey.dilithiumPublicKey, parsed.signingPublicKey.dilithiumPublicKey, "ML-DSA pub round-trips")
        assertContentEquals(id.publicPart.signingPublicKey.ed25519PublicKey, parsed.signingPublicKey.ed25519PublicKey, "Ed25519 pub round-trips")

        val tampered = id.publicPart.toBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFalse(runCatching { NodePublicIdentity.fromBytes(tampered, hkdf) }.isSuccess)
    }
}
