package com.aura.crypto

import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Cross-implementation known-answer test for the hand-rolled XChaCha20-Poly1305 in
 * [SymmetricCipher] (HChaCha20 subkey + RFC-8439 ChaCha20-Poly1305, 24-byte nonce).
 *
 * BouncyCastle 1.78.1 has **no** XChaCha engine (that's *why* the project hand-rolls it),
 * and transcribing the draft-irtf-cfrg-xchacha §A.1 vector by hand proved unreliable, so
 * this builds an **independent reference** of the same construction:
 *   - a clean-room [hChaCha20] written straight from the algorithm (independent of the
 *     project's private HChaCha20), and
 *   - BouncyCastle's **own** RFC-8439 [ChaCha20Poly1305] (12-byte nonce) as the trusted
 *     inner AEAD, keyed by the HChaCha20 subkey with nonce `00000000 ‖ nonce[16:24]`.
 *
 * The two implementations must **interoperate both directions** across varied inputs.
 * Two independently-written XChaCha constructions agreeing byte-for-byte (incl. empty and
 * 1 KiB messages, with and without AAD) is the known-answer check — it would catch a real
 * bug (wrong HChaCha20 rounds, wrong subkey, wrong inner-nonce layout, AAD not bound).
 */
class SymmetricCipherKatTest {

    private val cipher = SymmetricCipher()

    // ── Independent reference: clean-room HChaCha20 + BC's RFC-8439 ChaCha20-Poly1305 ──

    private fun refSeal(key: ByteArray, nonce24: ByteArray, pt: ByteArray, aad: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(subkey(key, nonce24)), 128, innerNonce(nonce24), aad))
        val out = ByteArray(c.getOutputSize(pt.size))
        val n = c.processBytes(pt, 0, pt.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    private fun refOpen(key: ByteArray, nonce24: ByteArray, ctTag: ByteArray, aad: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(subkey(key, nonce24)), 128, innerNonce(nonce24), aad))
        val out = ByteArray(c.getOutputSize(ctTag.size))
        var n = c.processBytes(ctTag, 0, ctTag.size, out, 0)
        n += c.doFinal(out, n)
        return out.copyOf(n)
    }

    private fun subkey(key: ByteArray, nonce24: ByteArray) = hChaCha20(key, nonce24.copyOfRange(0, 16))
    private fun innerNonce(nonce24: ByteArray) = ByteArray(12).also { System.arraycopy(nonce24, 16, it, 4, 8) }

    /** HChaCha20 (draft-irtf-cfrg-xchacha §2.2): 20 rounds, emit rows 0–3 and 12–15. */
    private fun hChaCha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = leWord(key, i * 4)
        for (i in 0 until 4) s[12 + i] = leWord(nonce16, i * 4)
        repeat(10) {
            qr(s, 0, 4, 8, 12); qr(s, 1, 5, 9, 13); qr(s, 2, 6, 10, 14); qr(s, 3, 7, 11, 15)
            qr(s, 0, 5, 10, 15); qr(s, 1, 6, 11, 12); qr(s, 2, 7, 8, 13); qr(s, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        intArrayOf(s[0], s[1], s[2], s[3], s[12], s[13], s[14], s[15]).forEachIndexed { i, w -> leBytes(w, out, i * 4) }
        return out
    }

    private fun qr(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }

    private fun rotl(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))
    private fun leWord(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
        ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    private fun leBytes(w: Int, b: ByteArray, o: Int) {
        b[o] = w.toByte(); b[o + 1] = (w ushr 8).toByte(); b[o + 2] = (w ushr 16).toByte(); b[o + 3] = (w ushr 24).toByte()
    }

    // ── Inputs (draft §A.1; key/nonce are simple ramps, plaintext from canonical ASCII) ──

    private val key = ByteArray(32) { (0x80 + it).toByte() }
    private val nonce = ByteArray(24) { (0x40 + it).toByte() }
    private val aad = ByteArray(12) { i -> if (i < 4) (0x50 + i).toByte() else (0xc0 + (i - 4)).toByte() }
    private val plaintext =
        "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
            .toByteArray(Charsets.US_ASCII)

    @Test
    fun project_decrypts_reference_xchacha() = runBlocking {
        val ctTag = refSeal(key, nonce, plaintext, aad)
        val wire = nonce + ctTag   // SymmetricCipher wire = [24B nonce][ciphertext‖tag]
        assertContentEquals(plaintext, cipher.decrypt(wire, key, aad).getOrThrow())
    }

    @Test
    fun reference_decrypts_project_xchacha() = runBlocking {
        val wire = cipher.encrypt(plaintext, key, aad).getOrThrow()   // random 24B nonce + ct‖tag
        assertContentEquals(plaintext, refOpen(key, wire.copyOfRange(0, 24), wire.copyOfRange(24, wire.size), aad))
    }

    @Test
    fun interop_across_varied_inputs() = runBlocking {
        val plaintexts = listOf(ByteArray(0), "x".toByteArray(), ByteArray(1024) { it.toByte() })
        val aads = listOf(ByteArray(0), "context".toByteArray())
        for (pt in plaintexts) for (ad in aads) {
            val w = cipher.encrypt(pt, key, ad).getOrThrow()
            assertContentEquals(pt, refOpen(key, w.copyOfRange(0, 24), w.copyOfRange(24, w.size), ad))
            assertContentEquals(pt, cipher.decrypt(nonce + refSeal(key, nonce, pt, ad), key, ad).getOrThrow())
        }
    }

    @Test
    fun tampered_ciphertext_is_rejected() = runBlocking {
        val ctTag = refSeal(key, nonce, plaintext, aad)
        ctTag[0] = (ctTag[0].toInt() xor 0x01).toByte()
        assertTrue(cipher.decrypt(nonce + ctTag, key, aad).isFailure)
    }

    @Test
    fun wrong_aad_is_rejected() = runBlocking {
        val ctTag = refSeal(key, nonce, plaintext, aad)
        assertTrue(cipher.decrypt(nonce + ctTag, key, "different-aad".toByteArray()).isFailure)
    }
}
