package com.aura.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HkdfTest {

    private val hkdf = Hkdf()

    // ── SHA3-256 known-answer vectors (NIST FIPS 202) ────────────────────────
    @Test fun sha3_256_emptyString_matchesNistVector() {
        assertEquals(
            "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
            hkdf.sha3_256(ByteArray(0)).toHex()
        )
    }

    @Test fun sha3_256_abc_matchesNistVector() {
        assertEquals(
            "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532",
            hkdf.sha3_256("abc".toByteArray()).toHex()
        )
    }

    // ── HMAC-SHA3-256 ────────────────────────────────────────────────────────
    @Test fun hmac_outputIs32Bytes_andDeterministic() {
        val k = "key".toByteArray(); val d = "data".toByteArray()
        val a = hkdf.hmacSha3_256(k, d)
        val b = hkdf.hmacSha3_256(k, d)
        assertEquals(32, a.size)
        assertTrue(a.contentEquals(b))
    }

    @Test fun hmac_differentKey_producesDifferentTag() {
        val d = "same data".toByteArray()
        assertFalse(
            hkdf.hmacSha3_256("k1".toByteArray(), d)
                .contentEquals(hkdf.hmacSha3_256("k2".toByteArray(), d))
        )
    }

    /** A key longer than the 136-byte block is pre-hashed: HMAC(K) == HMAC(SHA3(K)). */
    @Test fun hmac_longKey_isPrehashed() {
        val longKey = ByteArray(200) { it.toByte() }
        val d = "payload".toByteArray()
        assertTrue(
            hkdf.hmacSha3_256(longKey, d)
                .contentEquals(hkdf.hmacSha3_256(hkdf.sha3_256(longKey), d))
        )
    }

    // ── derive (HKDF extract+expand) ─────────────────────────────────────────
    @Test fun derive_respectsRequestedLength() {
        val ikm = "ikm".toByteArray(); val info = "info".toByteArray()
        assertEquals(32, hkdf.derive(ikm = ikm, info = info).size)            // default
        assertEquals(64, hkdf.derive(ikm = ikm, info = info, outputLen = 64).size)
        assertEquals(1, hkdf.derive(ikm = ikm, info = info, outputLen = 1).size)
    }

    @Test fun derive_isDeterministic() {
        val ikm = "ikm".toByteArray(); val info = "info".toByteArray()
        assertTrue(
            hkdf.derive(ikm = ikm, info = info).contentEquals(hkdf.derive(ikm = ikm, info = info))
        )
    }

    @Test fun derive_differentInfo_givesDomainSeparation() {
        val ikm = "ikm".toByteArray()
        assertFalse(
            hkdf.derive(ikm = ikm, info = "a".toByteArray())
                .contentEquals(hkdf.derive(ikm = ikm, info = "b".toByteArray()))
        )
    }

    /** RFC 5869 §2.2: a null or empty salt is equivalent to 32 zero bytes. */
    @Test fun derive_nullSalt_equalsEmptySalt_equalsZeroSalt() {
        val ikm = "ikm".toByteArray(); val info = "info".toByteArray()
        val nullSalt = hkdf.derive(ikm = ikm, salt = null, info = info)
        val emptySalt = hkdf.derive(ikm = ikm, salt = ByteArray(0), info = info)
        val zeroSalt = hkdf.derive(ikm = ikm, salt = ByteArray(32), info = info)
        assertTrue(nullSalt.contentEquals(emptySalt))
        assertTrue(nullSalt.contentEquals(zeroSalt))
    }

    @Test fun derive_differentSalt_diverges() {
        val ikm = "ikm".toByteArray(); val info = "info".toByteArray()
        assertFalse(
            hkdf.derive(ikm = ikm, salt = ByteArray(32) { 1 }, info = info)
                .contentEquals(hkdf.derive(ikm = ikm, salt = ByteArray(32) { 2 }, info = info))
        )
    }

    @Test fun derive_rejectsOutOfRangeLengths() {
        val ikm = "ikm".toByteArray(); val info = "info".toByteArray()
        assertFailsWith<IllegalArgumentException> { hkdf.derive(ikm = ikm, info = info, outputLen = 0) }
        assertFailsWith<IllegalArgumentException> { hkdf.derive(ikm = ikm, info = info, outputLen = 255 * 32 + 1) }
    }
}
