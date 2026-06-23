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
        assertFailsWith<IllegalArgumentException> { HybridPublicKey.fromBytes(ByteArray(2)) }
        assertFailsWith<IllegalArgumentException> { HybridCiphertext.fromBytes(ByteArray(2)) }
    }
}
