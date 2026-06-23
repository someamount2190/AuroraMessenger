package com.aura.crypto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Functional demonstration of the POST-QUANTUM crypto stacks on a real device (liboqs
 * present): Kyber-1024 KEM, Dilithium-3 signatures, identity, and a full end-to-end
 * KEM → HKDF → ratchet encrypt/decrypt. Each step logs its real values under the
 * "CRYPTODEMO" tag and asserts correctness.
 *
 * Run: `./gradlew :app:connectedReleaseAndroidTest` (unlocked emulator attached).
 */
@RunWith(AndroidJUnit4::class)
class CryptoStackDemoNative {

    private val hkdf = Hkdf()
    private val kem = HybridKem()
    private val signer = HybridSigner()
    private fun show(s: String) = Log.i("CRYPTODEMO", s)
    private fun ByteArray.head(n: Int = 40) = toHex().take(n) + "…"

    /** Minimal in-memory KEM-ratchet session store for the E2E demo. */
    private class MemStore : KemSessionStore {
        val sessions = HashMap<String, ByteArray>()
        override suspend fun load(contactNodeIdHex: String) = sessions[contactNodeIdHex]
        override suspend fun save(contactNodeIdHex: String, session: ByteArray) { sessions[contactNodeIdHex] = session }
        override suspend fun delete(contactNodeIdHex: String) { sessions.remove(contactNodeIdHex) }
        override suspend fun deleteAll() { sessions.clear() }
    }

    @Test fun stack4_kyberX25519_kem() = runBlocking {
        val kp = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(kp.publicKey).getOrThrow()           // sender side
        val ssRecv = kem.decapsulate(enc.ciphertext, kp.privateKey).getOrThrow()  // receiver side
        show("── 4) Hybrid KEM — X-Wing (ML-KEM-768 + X25519) ──")
        show("   xwing pub  : ${kp.publicKey.encoded.size}B")
        show("   ciphertext : ${enc.ciphertext.toBytes().size}B")
        show("   sender   ss: ${enc.sharedSecret.head()}")
        show("   receiver ss: ${ssRecv.head()}")
        show("   secrets match: ${enc.sharedSecret.contentEquals(ssRecv)}")
        assertContentEquals(enc.sharedSecret, ssRecv)
    }

    @Test fun stack5_dilithiumEd25519_signatures() = runBlocking {
        val kp = signer.generateSigningKeyPair().getOrThrow()
        val msg = "Aurora pairing request".toByteArray()
        val sig = signer.sign(msg, kp.privateKey).getOrThrow()
        val ok = signer.verify(msg, sig, kp.publicKey).getOrThrow()
        val tamperedOk = signer.verifyHybridSync("different".toByteArray(), sig, kp.publicKey)
        show("── 5) Hybrid signature (ML-DSA-65 + Ed25519) ──")
        show("   message     : \"${String(msg)}\"")
        show("   signature   : ${sig.size}B (4 + 3309 ML-DSA-65 + 64 Ed25519)")
        show("   verify(good): $ok    verify(tampered msg): $tamperedOk")
        assertTrue(ok); assertTrue(!tamperedOk)
    }

    @Test fun stack6_nodeIdentity() = runBlocking {
        val id = NodeIdentityGenerator(kem, signer, hkdf).generate().getOrThrow()
        val recomputed = hkdf.sha3_256(id.publicPart.kemPublicKey.toBytes() + id.publicPart.signingPublicKey.toBytes())
        show("── 6) Node identity (nodeId = SHA3-256(kemPub ‖ signPub)) ──")
        show("   nodeId      : ${id.nodeId.toHex()}")
        show("   recomputed  : ${recomputed.toHex()}   matches: ${id.nodeId.contentEquals(recomputed)}")
        assertContentEquals(id.nodeId, recomputed)
    }

    /** The whole pipeline: PQ KEM → HKDF root → seed ratchet → AEAD encrypt/decrypt. */
    @Test fun stack7_endToEnd_kemToRatchetMessage() = runBlocking {
        // 1) KEM establishes a shared secret
        val alice = kem.generateKeyPair().getOrThrow()
        val enc = kem.encapsulate(alice.publicKey).getOrThrow()
        val aliceSs = kem.decapsulate(enc.ciphertext, alice.privateKey).getOrThrow()
        // 2) HKDF → 32-byte pairing root (both sides identical)
        val rootBob = hkdf.derive(ikm = enc.sharedSecret, info = "aura-e2e-demo".toByteArray())
        val rootAlice = hkdf.derive(ikm = aliceSs, info = "aura-e2e-demo".toByteArray())
        // 3) seed the KEM double ratchets from the root (Bob initiates / sends first)
        val rmBob = KemRatchetManager(MemStore(), kem, hkdf, SymmetricCipher())
        val rmAlice = KemRatchetManager(MemStore(), kem, hkdf, SymmetricCipher())
        val A = "aaaa"; val B = "bbbb"
        rmBob.seed(A, rootBob.copyOf(), iAmInitiator = true)
        rmAlice.seed(B, rootAlice.copyOf(), iAmInitiator = false)
        // 4) Bob encrypts a message, Alice decrypts
        val pt = "End-to-end: PQ KEM → HKDF → ratchet → XChaCha20".toByteArray()
        val sealed = rmBob.sealNext(A, pt, "aad".toByteArray())!!
        val dec = rmAlice.open(B, sealed.bytes, "aad".toByteArray())

        show("── 7) FULL E2E: Kyber/X25519 → HKDF → ratchet → XChaCha20 ──")
        show("   roots match : ${rootAlice.contentEquals(rootBob)}")
        show("   plaintext   : \"${String(pt)}\"")
        show("   on the wire : ${sealed.bytes.head(56)} (${sealed.bytes.size}B, counter ${sealed.n})")
        show("   decrypted   : \"${dec?.let { String(it) }}\"")
        assertContentEquals(pt, dec)
    }
}
