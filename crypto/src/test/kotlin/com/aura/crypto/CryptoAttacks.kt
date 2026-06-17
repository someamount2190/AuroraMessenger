package com.aura.crypto

import com.aura.crypto.testutil.FakeRatchetStore
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial test suite: each test mounts a KNOWN attack vector and asserts the stack
 * resists it. A green run means the attack was defeated; a failure is a real finding.
 *
 * Covers the pure-JVM parts (AEAD, ratchet, Ed25519, HKDF). Kyber/Dilithium-specific
 * vectors (hybrid downgrade, KEM partial-break) are in the native-gated T2 suites.
 */
class CryptoAttacks {

    private val hkdf = Hkdf()
    private val cipher = SymmetricCipher()

    // ════════════════════════ AEAD (XChaCha20-Poly1305) ════════════════════════

    @Test fun aead_bitFlip_inEveryRegion_isRejected() = runTest {
        val k = cipher.generateKey()
        val ct = cipher.encrypt("attack me".toByteArray(), k, aad = "ctx".toByteArray()).getOrThrow()
        // nonce(0..23), ciphertext body(24..n-16), Poly1305 tag(last 16) — flip one byte in each
        for (pos in intArrayOf(0, 12, 24, ct.size - 20, ct.size - 1)) {
            val t = ct.copyOf(); t[pos] = (t[pos].toInt() xor 0x01).toByte()
            assertTrue(cipher.decrypt(t, k, aad = "ctx".toByteArray()).isFailure, "flip at $pos must reject")
        }
    }

    @Test fun aead_truncation_isRejected() = runTest {
        val k = cipher.generateKey()
        val ct = cipher.encrypt("payload".toByteArray(), k).getOrThrow()
        assertTrue(cipher.decrypt(ct.copyOf(ct.size - 1), k).isFailure, "dropped tag byte must reject")
        assertTrue(cipher.decrypt(ct.copyOf(ct.size - 8), k).isFailure)
    }

    /** Cross-context replay: a frame sealed under one AAD must not open under another. */
    @Test fun aead_aadConfusion_isRejected() = runTest {
        val k = cipher.generateKey()
        val ct = cipher.encrypt("msg".toByteArray(), k, aad = "aura-call-v1|A|B".toByteArray()).getOrThrow()
        assertTrue(cipher.decrypt(ct, k, aad = "aura-call-v1|B|A".toByteArray()).isFailure, "swapped direction")
        assertTrue(cipher.decrypt(ct, k, aad = "aura-media-v1".toByteArray()).isFailure, "swapped purpose")
        assertTrue(cipher.decrypt(ct, k, aad = null).isFailure, "stripped aad")
    }

    @Test fun aead_wrongKey_isRejected() = runTest {
        val ct = cipher.encrypt("x".toByteArray(), cipher.generateKey()).getOrThrow()
        assertTrue(cipher.decrypt(ct, cipher.generateKey()).isFailure)
    }

    @Test fun aead_noKeystreamReuse_acrossEncryptions() = runTest {
        val k = cipher.generateKey(); val pt = ByteArray(64) { 0 }   // all-zero plaintext
        val a = cipher.encrypt(pt, k).getOrThrow()
        val b = cipher.encrypt(pt, k).getOrThrow()
        // distinct random nonces ⇒ distinct keystreams ⇒ distinct ciphertext bodies
        assertFalse(a.copyOfRange(24, a.size).contentEquals(b.copyOfRange(24, b.size)))
    }

    // ════════════════════════════ Ratchet ══════════════════════════════════════

    private val ALICE = "aaaa"; private val BOB = "bbbb"; private val aad = "f".toByteArray()
    private suspend fun pair(): Pair<RatchetManager, RatchetManager> {
        val a = RatchetManager(FakeRatchetStore(), hkdf, cipher)
        val b = RatchetManager(FakeRatchetStore(), hkdf, cipher)
        a.seedFromSharedSecret(BOB, ALICE, BOB, ByteArray(32) { 9 })
        b.seedFromSharedSecret(ALICE, BOB, ALICE, ByteArray(32) { 9 })
        return a to b
    }

    @Test fun ratchet_replay_isRejected() = runTest {
        val (a, b) = pair()
        val m = a.sealNext(BOB, "hi".toByteArray(), aad)!!
        assertTrue(b.open(ALICE, m.n, m.bytes, aad) != null)
        assertNull(b.open(ALICE, m.n, m.bytes, aad), "replay of consumed counter")
    }

    /** Reflection: a frame I sent, bounced back to me, must not decrypt (send≠recv chain). */
    @Test fun ratchet_reflection_isRejected() = runTest {
        val (a, _) = pair()
        val m = a.sealNext(BOB, "hi".toByteArray(), aad)!!   // Alice's SEND chain
        assertNull(a.open(BOB, m.n, m.bytes, aad), "own frame on own recv chain must fail")
    }

    @Test fun ratchet_crossContactKey_isRejected() = runTest {
        val (a, b) = pair()
        val m = a.sealNext(BOB, "hi".toByteArray(), aad)!!
        // open under a different, unrelated contact id
        assertNull(b.open("cccc", m.n, m.bytes, aad))
    }

    @Test fun ratchet_skipFloodDoS_isBounded() = runTest {
        val (a, b) = pair()
        val m = a.sealNext(BOB, "hi".toByteArray(), aad)!!
        assertNull(b.open(ALICE, RatchetManager.MAX_SKIP_AHEAD + 1, m.bytes, aad), "skip-flood refused")
        assertTrue(b.open(ALICE, m.n, m.bytes, aad) != null, "state intact after refused flood")
    }

    @Test fun ratchet_forgedFrame_doesNotAdvanceChain() = runTest {
        val (a, b) = pair()
        val m = a.sealNext(BOB, "hi".toByteArray(), aad)!!
        val forged = m.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(b.open(ALICE, m.n, forged, aad))
        assertTrue(b.open(ALICE, m.n, m.bytes, aad) != null, "genuine frame still opens (chain not burned)")
    }

    @Test fun ratchet_garbageInput_failsClosed() = runTest {
        val (_, b) = pair()
        assertNull(b.open(ALICE, 0, ByteArray(8), aad))      // too short
        assertNull(b.open(ALICE, 0, ByteArray(200) { 0xAB.toByte() }, aad))  // junk
    }

    // ════════════════════════════ HKDF ═════════════════════════════════════════

    @Test fun hkdf_domainSeparation_holds() {
        val ikm = "k".toByteArray()
        assertFalse(hkdf.derive(ikm = ikm, info = "A".toByteArray())
            .contentEquals(hkdf.derive(ikm = ikm, info = "B".toByteArray())))
        assertFalse(hkdf.derive(ikm = ikm, salt = ByteArray(32) { 1 }, info = "A".toByteArray())
            .contentEquals(hkdf.derive(ikm = ikm, salt = ByteArray(32) { 2 }, info = "A".toByteArray())))
    }

    // ════════════════════════ Ed25519 (BouncyCastle) ═══════════════════════════

    private val signer = HybridSigner()
    private fun edKeypair(): Triple<HybridSigningKey, ByteArray, ByteArray> {
        val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
        val kp = gen.generateKeyPair()
        val seed = (kp.private as Ed25519PrivateKeyParameters).encoded
        val pub = (kp.public as Ed25519PublicKeyParameters).encoded
        return Triple(HybridSigningKey(ByteArray(0), seed + pub), pub, seed)
    }

    @Test fun ed25519_tamperedMessageOrSig_isRejected() = runTest {
        val (key, pub, _) = edKeypair()
        val msg = "transfer 100".toByteArray()
        val sig = signer.signEd25519Only(msg, key).getOrThrow()
        assertTrue(signer.verifyEd25519OnlySync(msg, sig, pub))
        assertFalse(signer.verifyEd25519OnlySync("transfer 999".toByteArray(), sig, pub))
        val badSig = sig.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(signer.verifyEd25519OnlySync(msg, badSig, pub))
    }

    @Test fun ed25519_wrongKey_isRejected() = runTest {
        val (key, _, _) = edKeypair(); val (_, otherPub, _) = edKeypair()
        val msg = "m".toByteArray()
        val sig = signer.signEd25519Only(msg, key).getOrThrow()
        assertFalse(signer.verifyEd25519OnlySync(msg, sig, otherPub))
    }

    /** Signature malleability: S' = S + L (group order) must be REJECTED (canonical S enforced). */
    @Test fun ed25519_sMalleability_isRejected() = runTest {
        val (key, pub, _) = edKeypair()
        val msg = "malleable?".toByteArray()
        val sig = signer.signEd25519Only(msg, key).getOrThrow()  // R(32) || S(32) little-endian
        val L = BigInteger("1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed", 16)
        val sLE = sig.copyOfRange(32, 64)
        val s = BigInteger(1, sLE.reversedArray())
        val sPlusL = s.add(L)
        // encode S+L back to 32-byte little-endian
        val be = sPlusL.toByteArray()                  // big-endian, possibly with sign/leading byte
        val mag = if (be.size > 32) be.copyOfRange(be.size - 32, be.size) else ByteArray(32 - be.size) + be
        val malleable = sig.copyOf()
        mag.reversedArray().copyInto(malleable, 32)
        assertFalse(signer.verifyEd25519OnlySync(msg, malleable, pub),
            "non-canonical S (S+L) must be rejected — Ed25519 malleability")
    }

    @Test fun ed25519_malformedInputs_failClosed() = runTest {
        val (_, pub, _) = edKeypair()
        assertFalse(signer.verifyEd25519OnlySync("m".toByteArray(), ByteArray(63), pub))   // short sig
        assertFalse(signer.verifyEd25519OnlySync("m".toByteArray(), ByteArray(64), ByteArray(31)))  // short key
    }
}
