package com.aura.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pure-JVM now: X-Wing (ML-KEM-768 + X25519) via BouncyCastle, no liboqs. */
class HybridKemTest {

    private val kem = HybridKem()

    @Test fun encapsulate_thenDecapsulate_agree() = runTest {
        val kp = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(kp.publicKey).getOrThrow()
        val dec = kem.decapsulate(enc.ciphertext, kp.privateKey).getOrThrow()
        assertEquals(32, enc.sharedSecret.size)
        assertContentEquals(enc.sharedSecret, dec)
    }

    @Test fun wrongPrivateKey_doesNotRecoverSecret() = runTest {
        val kp = kem.generateKeyPair().getOrThrow()
        val other = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(kp.publicKey).getOrThrow()
        val dec = kem.decapsulate(enc.ciphertext, other.privateKey)
        assertTrue(dec.isFailure || !dec.getOrThrow().contentEquals(enc.sharedSecret))
    }

    /** Corrupting the ML-KEM region of the X-Wing ciphertext must change the shared secret. */
    @Test fun tamperingMlkemRegion_breaksAgreement() = runTest {
        val kp = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(kp.publicKey).getOrThrow()
        val bad = enc.ciphertext.encoded.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val dec = kem.decapsulate(HybridCiphertext(bad), kp.privateKey)
        assertTrue(dec.isFailure || !dec.getOrThrow().contentEquals(enc.sharedSecret))
    }

    /** Corrupting the trailing X25519 ephemeral key region must change the shared secret. */
    @Test fun tamperingX25519Region_breaksAgreement() = runTest {
        val kp = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(kp.publicKey).getOrThrow()
        val ct = enc.ciphertext.encoded
        val bad = ct.copyOf().also { it[ct.size - 1] = (it[ct.size - 1].toInt() xor 0x01).toByte() }
        val dec = kem.decapsulate(HybridCiphertext(bad), kp.privateKey)
        assertTrue(dec.isFailure || !dec.getOrThrow().contentEquals(enc.sharedSecret))
    }

    @Test fun publicKey_bytesRoundTrip() = runTest {
        val kp = kem.generateKeyPair().getOrThrow()
        assertEquals(kp.publicKey, HybridPublicKey.fromBytes(kp.publicKey.toBytes()))
    }

    @Test fun ciphertext_bytesRoundTrip() = runTest {
        val kp = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(kp.publicKey).getOrThrow()
        assertEquals(enc.ciphertext, HybridCiphertext.fromBytes(enc.ciphertext.toBytes()))
    }

    @Test fun fromBytes_rejectsTruncated() {
        assertFailsWith<IllegalArgumentException> { HybridPublicKey.fromBytes(ByteArray(2)) }       // < 4-byte prefix
        assertFailsWith<IllegalArgumentException> { HybridCiphertext.fromBytes(ByteArray(2)) }
        // Valid 4-byte length prefix declaring more bytes than follow — exercises the
        // `size == 4 + len` clause specifically (a separate check from the >= 4 guard above).
        val lyingPrefix = intTo4Bytes(1000) + ByteArray(3)
        assertFailsWith<IllegalArgumentException> { HybridPublicKey.fromBytes(lyingPrefix) }
        assertFailsWith<IllegalArgumentException> { HybridCiphertext.fromBytes(lyingPrefix) }
    }

    // ── deterministicKeyPair: the pairing-bootstrap determinism guarantee ────────
    // Both peers derive the responder's bootstrap key from the shared root via this;
    // if it weren't reproducible across calls/instances, real two-process pairing breaks.

    @Test fun deterministicKeyPair_sameSeed_sameKeyPair_acrossInstances() = runTest {
        val seed = ByteArray(32) { (it * 7 + 1).toByte() }
        val a = HybridKem().deterministicKeyPair(seed.copyOf()).getOrThrow()
        val b = HybridKem().deterministicKeyPair(seed.copyOf()).getOrThrow()   // separate instance
        assertContentEquals(a.publicKey.encoded, b.publicKey.encoded, "same seed ⇒ same public key")
        assertContentEquals(a.privateKey.encoded, b.privateKey.encoded, "same seed ⇒ same private key")
    }

    @Test fun deterministicKeyPair_differentSeed_differentKeyPair() = runTest {
        val k1 = kem.deterministicKeyPair(ByteArray(32) { 1 }).getOrThrow()
        val k2 = kem.deterministicKeyPair(ByteArray(32) { 2 }).getOrThrow()
        assertTrue(!k1.publicKey.encoded.contentEquals(k2.publicKey.encoded), "distinct seeds ⇒ distinct keys")
    }

    /** The real bootstrap shape: initiator encapsulates to the deterministic PUBLIC half, the
     *  responder (who re-derives the keypair on the other device) decapsulates with the PRIVATE
     *  half — both must recover the same secret. */
    @Test fun deterministicKeyPair_bootstrapEncapDecap_agrees() = runTest {
        val seed = ByteArray(32) { 0x42 }
        val initiatorView = HybridKem().deterministicKeyPair(seed.copyOf()).getOrThrow()  // public half
        val responderView = HybridKem().deterministicKeyPair(seed.copyOf()).getOrThrow()  // private half
        val enc = kem.encapsulate(initiatorView.publicKey).getOrThrow()
        val dec = kem.decapsulate(enc.ciphertext, responderView.privateKey).getOrThrow()
        assertContentEquals(enc.sharedSecret, dec)
    }
}
