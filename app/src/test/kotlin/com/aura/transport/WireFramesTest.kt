package com.aura.transport

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T1-style pure-JVM test (real org.json on the test classpath). */
class WireFramesTest {

    @Test fun roundTrip_preservesJson() {
        val json = JSONObject().put("t", "msg").put("id", "abc").put("n", 7)
        val out = ByteArrayOutputStream()
        WireFrames.write(out, json)
        val read = WireFrames.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("msg", read!!.getString("t"))
        assertEquals("abc", read.getString("id"))
        assertEquals(7, read.getInt("n"))
    }

    @Test fun read_rejectsZeroLength() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).writeInt(0)
        assertNull(WireFrames.read(ByteArrayInputStream(out.toByteArray())))
    }

    @Test fun read_rejectsNegativeLength() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).writeInt(-5)
        assertNull(WireFrames.read(ByteArrayInputStream(out.toByteArray())))
    }

    @Test fun read_rejectsOversizedLength() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).writeInt(WireFrames.MAX_FRAME_BYTES + 1)
        assertNull(WireFrames.read(ByteArrayInputStream(out.toByteArray())))
    }

    @Test fun read_returnsNullOnEmptyStream() {
        assertNull(WireFrames.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test fun read_returnsNullOnInvalidJsonBody() {
        val body = "not json".toByteArray()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeInt(body.size); write(body); flush() }
        assertNull(WireFrames.read(ByteArrayInputStream(out.toByteArray())))
    }
}
