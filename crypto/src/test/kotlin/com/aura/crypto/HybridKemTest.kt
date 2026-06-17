package com.aura.crypto

import com.aura.crypto.testutil.NativeCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** T2 (native liboqs). Skips when the native lib is unavailable. */
class HybridKemTest {

    private val kem = HybridKem(Hkdf())

    @Before fun requireNative() = assumeTrue("liboqs native unavailable", NativeCrypto.available)

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

    @Test fun tamperingKyberPart_breaksAgreement() = runTest {
        val kp = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(kp.publicKey).getOrThrow()
        val badKyber = enc.ciphertext.kyberCiphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val ct = HybridCiphertext(badKyber, enc.ciphertext.x25519EphPublicKey)
        val dec = kem.decapsulate(ct, kp.privateKey)
        assertTrue(dec.isFailure || !dec.getOrThrow().contentEquals(enc.sharedSecret))
    }

    @Test fun tamperingX25519Part_breaksAgreement() = runTest {
        val kp = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(kp.publicKey).getOrThrow()
        val badEph = enc.ciphertext.x25519EphPublicKey.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val ct = HybridCiphertext(enc.ciphertext.kyberCiphertext, badEph)
        val dec = kem.decapsulate(ct, kp.privateKey)
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
        // pure validation — no native needed, but lives with its type
        assertFailsWith<IllegalArgumentException> { HybridPublicKey.fromBytes(ByteArray(2)) }
        assertFailsWith<IllegalArgumentException> { HybridCiphertext.fromBytes(ByteArray(2)) }
    }
}
