package com.aura.crypto

import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **Independent-authority** known-answer tests for the FIPS post-quantum primitives Aurora's
 * `HybridKem` / `HybridSigner` wrap through BouncyCastle: **ML-KEM-768** (FIPS 203) key
 * generation, encapsulation and decapsulation, and **ML-DSA-65** (FIPS 204) key generation.
 *
 * Unlike [PqcKatTest] (which pins a digest of BC's *own* output — a regression tripwire, not an
 * authority) these vectors come from the **NIST ACVP** generation/validation suite
 * (usnistgov/ACVP-Server), vendored under `src/test/resources/acvp/`. BC's keygen/encaps draw
 * their randomness from a `SecureRandom`; feeding the exact ACVP seed bytes (`d‖z` for ML-KEM
 * keygen, `m` for encaps, `ξ` for ML-DSA keygen) makes the operation the standard's deterministic
 * KAT, so the produced encapsulation/keys/ciphertext/shared-secret must equal the published
 * expected values. This closes the keygen/encaps gap that Wycheproof (decaps/verify only) left.
 */
class AcvpKatTest {

    /** Returns exactly the supplied bytes, in order — the standard's KAT randomness. */
    private class FixedRandom(private val data: ByteArray) : SecureRandom() {
        private var off = 0
        override fun nextBytes(bytes: ByteArray) {
            require(off + bytes.size <= data.size) {
                "ACVP KAT RNG over-drawn: wanted ${bytes.size} at offset $off of ${data.size}"
            }
            System.arraycopy(data, off, bytes, 0, bytes.size); off += bytes.size
        }
        override fun generateSeed(numBytes: Int): ByteArray = ByteArray(numBytes).also { nextBytes(it) }
    }

    // ── ML-KEM-768 (FIPS 203) ──────────────────────────────────────────────────

    @Test fun mlkem768_keyGen_matchesAcvpVectors() {
        forEach(mlkem().getJSONArray("keyGen")) { t ->
            val kp = MLKEMKeyPairGenerator().run {
                init(MLKEMKeyGenerationParameters(
                    FixedRandom(hexToBytes(t.getString("d")) + hexToBytes(t.getString("z"))),
                    MLKEMParameters.ml_kem_768))
                generateKeyPair()
            }
            val tc = t.getInt("tcId")
            assertEquals(t.getString("ek").lowercase(), (kp.public as MLKEMPublicKeyParameters).encoded.toHex(), "ek tcId=$tc")
            assertEquals(t.getString("dk").lowercase(), (kp.private as MLKEMPrivateKeyParameters).encoded.toHex(), "dk tcId=$tc")
        }
    }

    @Test fun mlkem768_encapsulation_matchesAcvpVectors() {
        forEach(mlkem().getJSONArray("encap")) { t ->
            val pub = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, hexToBytes(t.getString("ek").lowercase()))
            val enc = MLKEMGenerator(FixedRandom(hexToBytes(t.getString("m")))).generateEncapsulated(pub)
            val tc = t.getInt("tcId")
            assertEquals(t.getString("c").lowercase(), enc.encapsulation.toHex(), "ciphertext tcId=$tc")
            assertEquals(t.getString("k").lowercase(), enc.secret.toHex(), "shared secret tcId=$tc")
        }
    }

    @Test fun mlkem768_decapsulation_matchesAcvpVectors() {
        forEach(mlkem().getJSONArray("decap")) { t ->
            val priv = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, hexToBytes(t.getString("dk").lowercase()))
            val k = MLKEMExtractor(priv).extractSecret(hexToBytes(t.getString("c").lowercase()))
            assertEquals(t.getString("k").lowercase(), k.toHex(), "recovered secret tcId=${t.getInt("tcId")}")
        }
    }

    // ── ML-DSA-65 (FIPS 204) keygen ─────────────────────────────────────────────

    @Test fun mldsa65_keyGen_matchesAcvpVectors() {
        forEach(mldsa().getJSONArray("keyGen")) { t ->
            val kp = MLDSAKeyPairGenerator().run {
                init(MLDSAKeyGenerationParameters(FixedRandom(hexToBytes(t.getString("seed"))), MLDSAParameters.ml_dsa_65))
                generateKeyPair()
            }
            val tc = t.getInt("tcId")
            assertEquals(t.getString("pk").lowercase(), (kp.public as MLDSAPublicKeyParameters).encoded.toHex(), "pk tcId=$tc")
            assertEquals(t.getString("sk").lowercase(), (kp.private as MLDSAPrivateKeyParameters).encoded.toHex(), "sk tcId=$tc")
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun mlkem() = load("mlkem768_kat.json")
    private fun mldsa() = load("mldsa65_kat.json")

    private inline fun forEach(arr: JSONArray, body: (JSONObject) -> Unit) {
        for (i in 0 until arr.length()) body(arr.getJSONObject(i))
    }

    private fun load(name: String): JSONObject {
        val stream = javaClass.getResourceAsStream("/acvp/$name") ?: error("missing resource /acvp/$name")
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }
}
