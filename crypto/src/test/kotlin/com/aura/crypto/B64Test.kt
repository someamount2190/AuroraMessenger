package com.aura.crypto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class B64Test {

    @Test fun roundTrips_arbitraryBytes() {
        val bytes = ByteArray(257) { (it * 7).toByte() }
        assertContentEquals(bytes, B64.decode(B64.encode(bytes)))
    }

    @Test fun emptyRoundTrips() {
        assertEquals("", B64.encode(ByteArray(0)))
        assertContentEquals(ByteArray(0), B64.decode(""))
    }

    /** Must match the standard (non-URL-safe), no-wrap alphabet == Android Base64.NO_WRAP. */
    @Test fun matchesStandardBase64Alphabet() {
        val bytes = ByteArray(64) { it.toByte() }
        assertEquals(Base64.getEncoder().encodeToString(bytes), B64.encode(bytes))
    }

    @Test fun knownVector() {
        assertEquals("aGVsbG8=", B64.encode("hello".toByteArray()))
        assertContentEquals("hello".toByteArray(), B64.decode("aGVsbG8="))
    }
}
