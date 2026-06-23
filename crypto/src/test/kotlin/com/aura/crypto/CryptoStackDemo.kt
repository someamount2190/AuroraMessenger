package com.aura.crypto

import com.aura.crypto.testutil.FakeRatchetStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Functional demonstration of the pure-JVM crypto stacks: each test performs a real
 * encrypt → decrypt (or derive) round-trip and PRINTS the actual values so the
 * encryption/decryption is visible, then asserts correctness. (The post-quantum
 * stacks — Kyber KEM, Dilithium signatures, identity, PQXDH — are demonstrated in the
 * instrumented CryptoStackDemoNative on a device with liboqs.)
 */
class CryptoStackDemo {

    private fun show(s: String) = println("DEMO| $s")

    @Test fun stack1_symmetricCipher_xchacha20poly1305() = runTest {
        val cipher = SymmetricCipher()
        val key = cipher.generateKey()
        val pt = "Hello Aurora — this is a secret".toByteArray()
        val ct = cipher.encrypt(pt, key, aad = "aura-msg-v1".toByteArray()).getOrThrow()
        val dec = cipher.decrypt(ct, key, aad = "aura-msg-v1".toByteArray()).getOrThrow()
        show("── 1) XChaCha20-Poly1305 (message/media cipher) ──")
        show("   plaintext : \"${String(pt)}\"")
        show("   key (32B) : ${key.toHex().take(32)}…")
        show("   ciphertext: ${ct.toHex().take(56)}…  (${ct.size}B = 24 nonce + ${pt.size} body + 16 tag)")
        show("   decrypted : \"${String(dec)}\"")
        assertContentEquals(pt, dec)
    }

    @Test fun stack2_forwardSecretRatchet() = runTest {
        val hkdf = Hkdf(); val cipher = SymmetricCipher()
        val alice = RatchetManager(FakeRatchetStore(), hkdf, cipher)
        val bob = RatchetManager(FakeRatchetStore(), hkdf, cipher)
        val A = "aaaa"; val B = "bbbb"; val root = ByteArray(32) { 42 }
        alice.seedFromSharedSecret(B, A, B, root.copyOf())
        bob.seedFromSharedSecret(A, B, A, root.copyOf())

        val m1 = "Hi Bob".toByteArray()
        val s1 = alice.sealNext(B, m1, "aad".toByteArray())!!
        val d1 = bob.open(A, s1.n, s1.bytes, "aad".toByteArray())!!
        val m2 = "Hey Alice".toByteArray()
        val s2 = bob.sealNext(A, m2, "aad".toByteArray())!!
        val d2 = alice.open(B, s2.n, s2.bytes, "aad".toByteArray())!!

        show("── 2) Forward-secret double ratchet (per-message keys) ──")
        show("   Alice→Bob  : \"${String(m1)}\" → sealed ${s1.bytes.toHex().take(40)}… (n=${s1.n}) → \"${String(d1)}\"")
        show("   Bob→Alice  : \"${String(m2)}\" → sealed ${s2.bytes.toHex().take(40)}… (n=${s2.n}) → \"${String(d2)}\"")
        assertContentEquals(m1, d1); assertContentEquals(m2, d2)
    }

    @Test fun stack3_hkdf_kdf() {
        val hkdf = Hkdf()
        val key = hkdf.derive(ikm = "shared-root".toByteArray(), info = "aura-demo".toByteArray())
        val digest = hkdf.sha3_256("data".toByteArray())
        show("── 3) HKDF-SHA-256 (key derivation) + SHA3-256 (nodeId hash) ──")
        show("   derived 32B key  : ${key.toHex()}")
        show("   SHA3-256 digest  : ${digest.toHex().take(40)}…")
        assertEquals(32, key.size); assertEquals(32, digest.size)
    }
}
