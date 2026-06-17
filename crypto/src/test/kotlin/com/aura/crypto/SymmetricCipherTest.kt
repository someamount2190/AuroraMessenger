package com.aura.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SymmetricCipherTest {

    private val cipher = SymmetricCipher()
    private fun key() = cipher.generateKey()

    @Test fun roundTrip_acrossSizes() = runTest {
        val k = key()
        for (size in intArrayOf(0, 1, 15, 16, 17, 64, 1024, 65_537)) {
            val pt = ByteArray(size) { (it * 31).toByte() }
            val ct = cipher.encrypt(pt, k).getOrThrow()
            assertContentEquals(pt, cipher.decrypt(ct, k).getOrThrow())
        }
    }

    @Test fun ciphertext_hasNonceAndTagOverhead() = runTest {
        val pt = "hello".toByteArray()
        val ct = cipher.encrypt(pt, key()).getOrThrow()
        // [24 nonce][ciphertext == pt.size][16 tag]
        assertEquals(SymmetricCipher.NONCE_BYTES + pt.size + SymmetricCipher.MAC_BYTES, ct.size)
    }

    @Test fun tamperedTag_failsToDecrypt() = runTest {
        val k = key()
        val ct = cipher.encrypt("secret".toByteArray(), k).getOrThrow()
        ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 0x01).toByte()  // flip a tag byte
        assertTrue(cipher.decrypt(ct, k).isFailure)
    }

    @Test fun tamperedNonce_failsToDecrypt() = runTest {
        val k = key()
        val ct = cipher.encrypt("secret".toByteArray(), k).getOrThrow()
        ct[0] = (ct[0].toInt() xor 0x01).toByte()  // flip a nonce byte
        assertTrue(cipher.decrypt(ct, k).isFailure)
    }

    @Test fun wrongKey_failsToDecrypt() = runTest {
        val ct = cipher.encrypt("secret".toByteArray(), key()).getOrThrow()
        assertTrue(cipher.decrypt(ct, key()).isFailure)
    }

    @Test fun aad_mustMatch() = runTest {
        val k = key()
        val ct = cipher.encrypt("m".toByteArray(), k, aad = "ctx-A".toByteArray()).getOrThrow()
        assertContentEquals("m".toByteArray(), cipher.decrypt(ct, k, aad = "ctx-A".toByteArray()).getOrThrow())
        assertTrue(cipher.decrypt(ct, k, aad = "ctx-B".toByteArray()).isFailure)  // wrong aad
        assertTrue(cipher.decrypt(ct, k, aad = null).isFailure)                   // missing aad
    }

    @Test fun nonceIsRandom_perEncryption() = runTest {
        val k = key(); val pt = "same".toByteArray()
        val a = cipher.encrypt(pt, k).getOrThrow()
        val b = cipher.encrypt(pt, k).getOrThrow()
        assertTrue(!a.contentEquals(b), "two encryptions of the same plaintext must differ (random nonce)")
        assertContentEquals(pt, cipher.decrypt(a, k).getOrThrow())
        assertContentEquals(pt, cipher.decrypt(b, k).getOrThrow())
    }

    @Test fun generateKey_isCorrectLength_andHighEntropy() {
        assertEquals(SymmetricCipher.KEY_BYTES, cipher.generateKey().size)
        val seen = HashSet<String>()
        repeat(100) { seen.add(cipher.generateKey().toHex()) }
        assertEquals(100, seen.size, "generated keys must not repeat")
    }

    @Test fun wrongKeySize_isFailureNotCrash() = runTest {
        assertTrue(cipher.encrypt("x".toByteArray(), ByteArray(16)).isFailure)
        assertTrue(cipher.decrypt(ByteArray(64), ByteArray(16)).isFailure)
    }

    @Test fun tooShortInput_isFailure() = runTest {
        assertTrue(cipher.decrypt(ByteArray(10), key()).isFailure)  // < nonce+tag
    }
}
