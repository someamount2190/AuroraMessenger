package com.aura.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Authoritative known-answer tests for [Hkdf.derive] against **RFC 5869 Appendix A**
 * (the HMAC-SHA-256 cases). HKDF-over-SHA3-256 — the old hand-rolled construction — had
 * no published vectors; moving the HMAC to SHA-256 is what buys this external KAT coverage.
 *
 * Vectors: <https://www.rfc-editor.org/rfc/rfc5869.html#appendix-A> (Test Cases 1–3).
 */
class HkdfRfc5869KatTest {

    private val hkdf = Hkdf()

    /** RFC 5869 A.1 — basic test case with SHA-256. */
    @Test fun testCase1_basic() {
        val ikm  = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }            // 0x000102…0c
        val info = ByteArray(10) { (0xf0 + it).toByte() }   // 0xf0f1…f9
        val okm = hkdf.derive(ikm = ikm, salt = salt, info = info, outputLen = 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
            "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
            "34007208d5b887185865",
            okm.toHex()
        )
    }

    /** RFC 5869 A.2 — longer inputs and output with SHA-256. */
    @Test fun testCase2_longInputsAndOutput() {
        val ikm  = ByteArray(80) { it.toByte() }            // 0x0001…4f
        val salt = ByteArray(80) { (0x60 + it).toByte() }   // 0x6061…af
        val info = ByteArray(80) { (0xb0 + it).toByte() }   // 0xb0b1…ff
        val okm = hkdf.derive(ikm = ikm, salt = salt, info = info, outputLen = 82)
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a4934" +
            "4f012eda2d4efad8a050cc4c19afa97c" +
            "59045a99cac7827271cb41c65e590e09" +
            "da3275600c2f09b8367793a9aca3db71" +
            "cc30c58179ec3e87c14c01d5c1f3434f" +
            "1d87",
            okm.toHex()
        )
    }

    /** RFC 5869 A.3 — zero-length salt and info with SHA-256. */
    @Test fun testCase3_emptySaltAndInfo() {
        val ikm = ByteArray(22) { 0x0b }
        val okm = hkdf.derive(ikm = ikm, salt = ByteArray(0), info = ByteArray(0), outputLen = 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31" +
            "b8a11f5c5ee1879ec3454e5f3c738d2d" +
            "9d201395faa4b61a96c8",
            okm.toHex()
        )
    }

    /** RFC 5869 §2.2: a null salt is also HashLen zeros — must equal the empty-salt case. */
    @Test fun nullSalt_equalsEmptySalt() {
        val ikm = ByteArray(22) { 0x0b }
        val viaNull  = hkdf.derive(ikm = ikm, salt = null, info = ByteArray(0), outputLen = 42)
        val viaEmpty = hkdf.derive(ikm = ikm, salt = ByteArray(0), info = ByteArray(0), outputLen = 42)
        assertEquals(viaEmpty.toHex(), viaNull.toHex())
    }
}
