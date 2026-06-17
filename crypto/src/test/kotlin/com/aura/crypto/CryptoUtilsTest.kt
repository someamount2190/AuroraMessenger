package com.aura.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CryptoUtilsTest {

    @Test fun toHex_lowercaseTwoCharsPerByte() {
        assertEquals("00", byteArrayOf(0).toHex())
        assertEquals("ff", byteArrayOf(0xFF.toByte()).toHex())
        assertEquals("deadbeef", byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()).toHex())
        assertEquals("", ByteArray(0).toHex())
    }

    @Test fun hexToBytes_roundTrips() {
        val bytes = ByteArray(256) { it.toByte() }
        assertContentEquals(bytes, hexToBytes(bytes.toHex()))
    }

    @Test fun hexToBytes_acceptsUppercase() {
        assertContentEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), hexToBytes("ABCD"))
    }

    @Test fun hexToBytes_rejectsOddLength() {
        assertFailsWith<IllegalArgumentException> { hexToBytes("abc") }
    }

    @Test fun intTo4Bytes_isBigEndian() {
        assertContentEquals(byteArrayOf(0, 0, 0, 1), intTo4Bytes(1))
        assertContentEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), intTo4Bytes(0x12345678))
        assertContentEquals(byteArrayOf(-1, -1, -1, -1), intTo4Bytes(-1))
    }

    @Test fun intRoundTrip_acrossBoundaries() {
        for (v in intArrayOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, 0x7FABCDEF, -123456789)) {
            assertEquals(v, readInt4(intTo4Bytes(v), 0))
        }
    }

    @Test fun readInt4_respectsOffset() {
        val b = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 5)
        assertEquals(5, readInt4(b, 4))
    }
}
