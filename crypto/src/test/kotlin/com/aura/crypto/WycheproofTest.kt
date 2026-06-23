package com.aura.crypto

import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.json.JSONObject
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Project Wycheproof** known-answer / known-bug vectors (C2SP/wycheproof `testvectors_v1`,
 * vendored under `src/test/resources/wycheproof/`). These are *independent-authority*, edge-case
 * corpora — twists, low-order points, signature malleability, AEAD tag/nonce edge cases, and the
 * ML-KEM implicit-rejection ("Strcmp") bug — complementing the happy-path KATs.
 *
 * Coverage maps to Aurora's stack: X25519 (X-Wing's classical half), Ed25519 (Aurora's signing
 * path), XChaCha20-Poly1305 (Tink AEAD), ML-KEM-768 (X-Wing's PQ half). All pure-JVM.
 */
class WycheproofTest {

    @Test fun x25519_xdh() {
        var checked = 0; var skippedThrow = 0
        forEachTest("x25519_test.json") { _, t ->
            val priv = hexToBytes(t.getString("private"))
            val pub = hexToBytes(t.getString("public"))
            val shared = t.getString("shared")
            val result = t.getString("result")
            val out = try {
                val a = X25519Agreement(); a.init(X25519PrivateKeyParameters(priv, 0))
                ByteArray(a.agreementSize).also { a.calculateAgreement(X25519PublicKeyParameters(pub, 0), it, 0) }
            } catch (e: Exception) {
                // BC rejects some low-order / all-zero cases; only "valid" must never throw.
                if (result == "valid") fail("tc${t.getInt("tcId")}: valid X25519 threw: ${e.message}")
                skippedThrow++; return@forEachTest
            }
            // X25519 is a pure function: the `shared` value is the true scalar-mult output even
            // for key-quality-"invalid" cases, so any computed result must match it.
            if (out.toHex() != shared) fail("tc${t.getInt("tcId")} ($result): X25519 mismatch")
            checked++
        }
        assertTrue(checked > 400, "expected most X25519 vectors to compute; got $checked (threw $skippedThrow)")
    }

    @Test fun ed25519_verify() {
        val signer = HybridSigner()
        var valid = 0; var invalid = 0
        forEachGroup("ed25519_test.json") { g ->
            val pk = hexToBytes(g.getJSONObject("publicKey").getString("pk"))
            forEachTestIn(g) { t ->
                val msg = hexToBytes(t.getString("msg"))
                val sig = hexToBytes(t.getString("sig"))
                val result = t.getString("result")
                val ok = signer.verifyEd25519OnlySync(msg, sig, pk)
                when (result) {
                    "valid", "acceptable" -> { if (!ok) fail("tc${t.getInt("tcId")}: valid Ed25519 sig rejected"); valid++ }
                    "invalid" -> { if (ok) fail("tc${t.getInt("tcId")}: invalid Ed25519 sig ACCEPTED"); invalid++ }
                }
            }
        }
        assertTrue(valid > 0 && invalid > 0, "expected both valid+invalid Ed25519 vectors (got $valid/$invalid)")
    }

    @Test fun xchacha20poly1305_aead() = runBlocking {
        val cipher = SymmetricCipher()
        var valid = 0; var invalid = 0
        forEachGroup("xchacha20_poly1305_test.json") { g ->
            if (g.getInt("ivSize") != 192 || g.getInt("tagSize") != 128) return@forEachGroup  // 24-B nonce only
            forEachTestIn(g) { t ->
                val key = hexToBytes(t.getString("key"))
                val iv = hexToBytes(t.getString("iv"))
                val aad = hexToBytes(t.getString("aad"))
                val ct = hexToBytes(t.getString("ct")) + hexToBytes(t.getString("tag"))
                val result = t.getString("result")
                val opened = runBlocking { cipher.decrypt(iv + ct, key, aad) }.getOrNull()
                when (result) {
                    "valid", "acceptable" -> {
                        if (opened == null || opened.toHex() != t.getString("msg"))
                            fail("tc${t.getInt("tcId")}: valid XChaCha frame failed to open")
                        valid++
                    }
                    "invalid" -> { if (opened != null) fail("tc${t.getInt("tcId")}: invalid XChaCha frame ACCEPTED"); invalid++ }
                }
            }
        }
        assertTrue(valid > 0 && invalid > 0, "expected both valid+invalid XChaCha vectors (got $valid/$invalid)")
    }

    @Test fun mlkem768_decap_implicitRejection() {
        val dkCache = HashMap<String, MLKEMPrivateKeyParameters>()
        fun dkFromSeed(seedHex: String) = dkCache.getOrPut(seedHex) {
            val g = MLKEMKeyPairGenerator()
            g.init(MLKEMKeyGenerationParameters(SeededRandom(hexToBytes(seedHex)), MLKEMParameters.ml_kem_768))
            g.generateKeyPair().private as MLKEMPrivateKeyParameters
        }
        var checked = 0
        forEachTest("mlkem_768_test.json") { _, t ->
            val seed = t.optString("seed", "")
            if (seed.isEmpty()) return@forEachTest                 // only seed-keyed decap cases
            val c = hexToBytes(t.getString("c"))
            val k = t.getString("K")
            val result = t.getString("result")
            val ss = try { MLKEMExtractor(dkFromSeed(seed)).extractSecret(c) } catch (e: Exception) {
                if (result == "valid") fail("tc${t.getInt("tcId")}: valid ML-KEM decap threw: ${e.message}")
                return@forEachTest
            }
            // A correct FIPS-203 decaps returns K for valid cases AND the implicit-rejection
            // secret for modified ciphertexts (never an error) — exactly the Strcmp-bug check.
            if (result == "valid" && ss.toHex() != k) fail("tc${t.getInt("tcId")}: ML-KEM decap secret mismatch")
            if (result == "invalid" && ss.toHex() == k) fail("tc${t.getInt("tcId")}: ML-KEM invalid case matched K")
            checked++
        }
        assertTrue(checked > 50, "expected many ML-KEM-768 decap vectors; got $checked")
    }

    // ── JSON helpers ───────────────────────────────────────────────────────────

    private fun load(name: String): JSONObject {
        val stream = javaClass.getResourceAsStream("/wycheproof/$name") ?: error("missing resource $name")
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    private inline fun forEachGroup(name: String, body: (JSONObject) -> Unit) {
        val groups = load(name).getJSONArray("testGroups")
        for (i in 0 until groups.length()) body(groups.getJSONObject(i))
    }

    private inline fun forEachTestIn(group: JSONObject, body: (JSONObject) -> Unit) {
        val tests = group.getJSONArray("tests")
        for (i in 0 until tests.length()) body(tests.getJSONObject(i))
    }

    private inline fun forEachTest(name: String, body: (JSONObject, JSONObject) -> Unit) =
        forEachGroup(name) { g -> forEachTestIn(g) { t -> body(g, t) } }

    /** Returns the provided bytes in order from nextBytes — for replaying KAT keygen seeds. */
    private class SeededRandom(private val data: ByteArray) : SecureRandom() {
        private var pos = 0
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = if (pos < data.size) data[pos++] else 0
        }
    }
}
