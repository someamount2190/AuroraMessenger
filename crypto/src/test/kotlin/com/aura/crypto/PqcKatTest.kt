package com.aura.crypto

import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import org.bouncycastle.pqc.crypto.xwing.XWingKEMExtractor
import org.bouncycastle.pqc.crypto.xwing.XWingKEMGenerator
import org.bouncycastle.pqc.crypto.xwing.XWingKeyGenerationParameters
import org.bouncycastle.pqc.crypto.xwing.XWingKeyPairGenerator
import org.bouncycastle.pqc.crypto.xwing.XWingPrivateKeyParameters
import org.bouncycastle.pqc.crypto.xwing.XWingPublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic known-answer / regression tests for the FIPS post-quantum primitives Aurora
 * now uses through BouncyCastle: **ML-KEM-768** (FIPS 203), **ML-DSA-65** (FIPS 204), and
 * **X-Wing** (ML-KEM-768 + X25519). All pure-JVM — no liboqs, no device tier.
 *
 * Rather than hand-transcribe multi-kilobyte NIST/ACVP vectors (error-prone — the same
 * transcription hazard the XChaCha work hit), each primitive is driven by a **deterministic
 * RNG** ([DeterministicRandom]) so keygen/encaps are reproducible, and we assert:
 *   - exact **FIPS sizes** (the round-3-vs-FIPS discriminator, e.g. ML-DSA-65 sig = 3309 B);
 *   - **round-trip correctness** (decaps recovers the encapsulated secret; sign verifies);
 *   - **run-to-run determinism** (same seed ⇒ identical key bytes); and
 *   - a **pinned SHA-256 digest** of the deterministic public output — a cross-version
 *     tripwire that fails loudly if a BouncyCastle bump (or an X-Wing draft revision) silently
 *     changes the wire/output format. The digests are computed from the library itself, so
 *     they are *regression* vectors, not an independent authority; they still catch the
 *     change that matters most here (X-Wing tracks an in-progress draft).
 *
 * For **independent-authority** KATs of ML-KEM-768 keygen/encaps/decaps and ML-DSA-65 keygen
 * (NIST ACVP vectors), see [AcvpKatTest]. X-Wing has no independent vector here (it tracks a
 * moving draft), but its ML-KEM-768 half, X25519 half, and decaps direction are each
 * independently pinned (ACVP, RFC 7748 in [ClassicalKatTest], Wycheproof in [WycheproofTest]).
 */
class PqcKatTest {

    // ── ML-KEM-768 (FIPS 203) ──────────────────────────────────────────────────

    @Test fun mlkem768_sizes_roundTrip_determinism_andPinnedDigest() {
        fun keypair() = MLKEMKeyPairGenerator().run {
            init(MLKEMKeyGenerationParameters(DeterministicRandom("mlkem-768-keygen"), MLKEMParameters.ml_kem_768))
            generateKeyPair()
        }
        val kp = keypair()
        val ek = (kp.public as MLKEMPublicKeyParameters).encoded
        val dk = kp.private as MLKEMPrivateKeyParameters

        assertEquals(1184, ek.size, "ML-KEM-768 encapsulation key = 1184 B (FIPS 203)")

        val enc = MLKEMGenerator(DeterministicRandom("mlkem-768-encaps")).generateEncapsulated(kp.public)
        assertEquals(1088, enc.encapsulation.size, "ML-KEM-768 ciphertext = 1088 B")
        assertEquals(32, enc.secret.size, "ML-KEM-768 shared secret = 32 B")

        val recovered = MLKEMExtractor(dk).extractSecret(enc.encapsulation)
        assertContentEquals(enc.secret, recovered, "decaps must recover the encapsulated secret")

        // Determinism: same seed ⇒ identical key bytes.
        assertContentEquals(ek, (keypair().public as MLKEMPublicKeyParameters).encoded)

        // Cross-version tripwire.
        assertEquals(PIN_MLKEM_EK, sha256Hex(ek), "ML-KEM-768 keygen output changed — investigate the BC bump")
    }

    // ── ML-DSA-65 (FIPS 204) ───────────────────────────────────────────────────

    @Test fun mldsa65_sizes_signVerify_determinism_andPinnedDigest() {
        fun keypair() = MLDSAKeyPairGenerator().run {
            init(MLDSAKeyGenerationParameters(DeterministicRandom("mldsa-65-keygen"), MLDSAParameters.ml_dsa_65))
            generateKeyPair()
        }
        val kp = keypair()
        val pk = (kp.public as MLDSAPublicKeyParameters).encoded
        assertEquals(1952, pk.size, "ML-DSA-65 public key = 1952 B (FIPS 204)")

        val msg = "aura ml-dsa-65 kat".toByteArray()
        val signer = MLDSASigner().apply { init(true, kp.private as MLDSAPrivateKeyParameters); update(msg, 0, msg.size) }
        val sig = signer.generateSignature()
        assertEquals(3309, sig.size, "ML-DSA-65 signature = 3309 B (the round-3 vs FIPS discriminator)")

        val verifier = MLDSASigner().apply { init(false, kp.public as MLDSAPublicKeyParameters); update(msg, 0, msg.size) }
        assertTrue(verifier.verifySignature(sig), "valid signature must verify")

        // Tamper → reject.
        val bad = sig.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val v2 = MLDSASigner().apply { init(false, kp.public as MLDSAPublicKeyParameters); update(msg, 0, msg.size) }
        assertFalse(v2.verifySignature(bad), "tampered signature must be rejected")

        // Determinism of keygen + pinned digest.
        assertContentEquals(pk, (keypair().public as MLDSAPublicKeyParameters).encoded)
        assertEquals(PIN_MLDSA_PK, sha256Hex(pk), "ML-DSA-65 keygen output changed — investigate the BC bump")
    }

    // ── X-Wing (ML-KEM-768 + X25519) ────────────────────────────────────────────

    @Test fun xwing_sizes_roundTrip_determinism_andPinnedDigest() {
        fun keypair() = XWingKeyPairGenerator().run {
            init(XWingKeyGenerationParameters(DeterministicRandom("xwing-keygen")))
            generateKeyPair()
        }
        val kp = keypair()
        val pk = (kp.public as XWingPublicKeyParameters).encoded
        val sk = kp.private as XWingPrivateKeyParameters

        assertEquals(1216, pk.size, "X-Wing public key = 1184 (ML-KEM-768) + 32 (X25519)")

        val enc = XWingKEMGenerator(DeterministicRandom("xwing-encaps")).generateEncapsulated(kp.public)
        assertEquals(1120, enc.encapsulation.size, "X-Wing ciphertext = 1088 (ML-KEM-768) + 32 (X25519)")
        assertEquals(32, enc.secret.size, "X-Wing shared secret = 32 B")

        val recovered = XWingKEMExtractor(sk).extractSecret(enc.encapsulation)
        assertContentEquals(enc.secret, recovered, "decaps must recover the encapsulated secret")

        assertContentEquals(pk, (keypair().public as XWingPublicKeyParameters).encoded)
        // Draft-change tripwire: BC X-Wing tracks draft-connolly-cfrg-xwing-kem (currently -07).
        assertEquals(PIN_XWING_PK, sha256Hex(pk), "X-Wing keygen output changed — likely a draft revision in the BC bump")
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).toHex()

    /** A reproducible, inexhaustible byte stream (SHA-256 counter mode over a fixed label) so
     *  BC's keygen/encaps become deterministic for KATs. NOT for production use. */
    private class DeterministicRandom(label: String) : SecureRandom() {
        private val seed = label.toByteArray()
        private var counter = 0L
        private var buf = ByteArray(0)
        private var off = 0
        override fun nextBytes(bytes: ByteArray) {
            var i = 0
            while (i < bytes.size) {
                if (off >= buf.size) {
                    val md = MessageDigest.getInstance("SHA-256")
                    md.update(seed)
                    md.update(ByteArray(8) { ((counter ushr (56 - it * 8)) and 0xff).toByte() })
                    counter++
                    buf = md.digest(); off = 0
                }
                bytes[i++] = buf[off++]
            }
        }
        override fun generateSeed(numBytes: Int): ByteArray = ByteArray(numBytes).also { nextBytes(it) }
    }

    private companion object {
        // Pinned SHA-256 of the deterministic public output (computed from BC 1.84; see KDoc).
        const val PIN_MLKEM_EK = "bfa2483f14820f08d0470222dbb8fb6ce40e999c81ac16d746dc361dbbd6b71d"
        const val PIN_MLDSA_PK = "177a11b8b7f20749e5b569a00703a3d2236024f4e44ba68ccd7cf1b42ec746ac"
        const val PIN_XWING_PK = "100da36569f4fdbf2441654f3ceb58aed792f01f3506db833897191dd63db466"
    }
}
