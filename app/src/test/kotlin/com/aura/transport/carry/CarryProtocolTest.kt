package com.aura.transport.carry

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Framing + validation + TTL policy for the degraded carry relay. Pure JVM (real org.json is on
 * the test classpath per the SOP) — no Robolectric needed.
 */
class CarryProtocolTest {

    private fun innerMsg(from: String, to: String, id: String) = JSONObject()
        .put("t", "msg").put("from", from).put("to", to).put("id", id)
        .put("ts", 123L).put("n", 5L).put("sealed", "BASE64BLOB").put("groupId", "g1")

    @Test fun buildParse_roundTrips() {
        val frame = CarryProtocol.build("g1", "B", 60_000L, innerMsg("A", "B", "m1"))
        val c = CarryProtocol.parse(frame)
        assertNotNull(c)
        assertEquals("g1", c.groupId)
        assertEquals("B", c.target)
        assertEquals("A", c.originNodeId)
        assertEquals("m1", c.msgId)
        assertEquals(60_000L, c.ttlMs)
        assertEquals("m1", c.inner.getString("id"))
    }

    @Test fun parse_rejectsMalformed() {
        val inner = innerMsg("A", "B", "m1")
        assertNull(CarryProtocol.parse(JSONObject().put("t", "nope").put("groupId", "g1").put("target", "B").put("inner", inner)))
        assertNull(CarryProtocol.parse(JSONObject().put("t", "carry").put("target", "B").put("inner", inner)))   // no groupId
        assertNull(CarryProtocol.parse(JSONObject().put("t", "carry").put("groupId", "g1").put("target", "B")))  // no inner
    }

    @Test fun parse_rejectsInnerNotAddressedToTarget() {
        val frame = CarryProtocol.build("g1", "B", 60_000L, innerMsg("A", "C", "m1"))  // inner is for C, not B
        assertNull(CarryProtocol.parse(frame))
    }

    @Test fun parse_rejectsInnerNotMsg() {
        val inner = JSONObject().put("t", "ctl").put("from", "A").put("to", "B").put("id", "m1")
        assertNull(CarryProtocol.parse(CarryProtocol.build("g1", "B", 60_000L, inner)))
    }

    @Test fun ttl_isClamped() {
        val inner = innerMsg("A", "B", "m1")
        assertEquals(CarryProtocol.DEFAULT_TTL_MS, CarryProtocol.parse(CarryProtocol.build("g1", "B", 0L, inner))!!.ttlMs)
        assertEquals(CarryProtocol.MAX_TTL_MS, CarryProtocol.parse(CarryProtocol.build("g1", "B", Long.MAX_VALUE, inner))!!.ttlMs)
    }

    @Test fun isExpired_boundary() {
        assertFalse(CarryProtocol.isExpired(createdMs = 1_000, ttlMs = 100, nowMs = 1_099))
        assertTrue(CarryProtocol.isExpired(createdMs = 1_000, ttlMs = 100, nowMs = 1_100))
        assertTrue(CarryProtocol.isExpired(createdMs = 1_000, ttlMs = 100, nowMs = 5_000))
    }
}
