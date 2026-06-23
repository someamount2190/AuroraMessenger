package com.aura.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * HKDF (RFC 5869) over **HMAC-SHA-256**, backed entirely by BouncyCastle's
 * [HKDFBytesGenerator]. No hand-rolled extract/expand or HMAC — see
 * [docs/CRYPTO_MIGRATION_PLAN.md].
 *
 * The HMAC was deliberately moved from SHA3-256 to SHA-256: it is the RFC 5869
 * standard, FIPS-aligned, and — unlike HKDF-SHA3-256 — has authoritative published
 * known-answer tests (RFC 5869 Appendix A), which `HkdfRfc5869KatTest` now pins.
 *
 * [sha3_256] stays: it is the hash used for `nodeId = SHA3-256(kemPub ‖ signPub)` and
 * for KEM/pairing salts. It is a Bouncy Castle primitive, not a hand-rolled construction.
 */
class Hkdf {

    /**
     * Full HKDF: extract then expand (RFC 5869), HMAC-SHA-256.
     *
     * @param ikm       Input key material
     * @param salt      Optional salt. null or empty → HashLen (32) zero bytes (RFC 5869 §2.2),
     *                  which is exactly how BouncyCastle treats a null/empty salt.
     * @param info      Context/application-specific info bytes
     * @param outputLen Output length in bytes. Max: 255 × 32 = 8160.
     */
    fun derive(
        ikm:       ByteArray,
        salt:      ByteArray? = null,
        info:      ByteArray,
        outputLen: Int = 32
    ): ByteArray {
        require(outputLen > 0)         { "outputLen must be positive" }
        require(outputLen <= 255 * 32) { "outputLen exceeds HKDF maximum (${255 * 32})" }

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        // HKDFParameters maps a null/empty salt to HashLen zero bytes, matching RFC 5869 §2.2.
        hkdf.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(outputLen)
        hkdf.generateBytes(out, 0, outputLen)
        return out
    }

    /** SHA3-256 (FIPS 202). Backs `nodeId` and KEM/pairing salts. */
    fun sha3_256(data: ByteArray): ByteArray {
        val digest = SHA3Digest(256)
        digest.update(data, 0, data.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    companion object {
        /**
         * Shared singleton. Thread-safe because every call allocates a fresh
         * generator/digest and the singleton holds no mutable state.
         */
        val instance = Hkdf()
    }
}
