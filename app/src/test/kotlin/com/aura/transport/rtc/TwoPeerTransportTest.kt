package com.aura.transport.rtc

import com.aura.transport.FrameInbox
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demonstrates that the [PeerTransport] / [FrameInbox] seam enables **in-process
 * two-peer message-delivery simulation**: a frame sent by one peer is delivered to the
 * other peer's inbox and the ack round-trips back — the exact contract MessageSender
 * relies on (`send(frame) -> ack`), with no WebRTC or sockets.
 */
class TwoPeerTransportTest {

    /** A stub inbox that records inbound frames and acks "msg" frames by id. */
    private class RecordingInbox : FrameInbox {
        val received = mutableListOf<JSONObject>()
        override suspend fun processFrame(frame: JSONObject): JSONObject? {
            received += frame
            return if (frame.optString("t") == "msg")
                JSONObject().put("t", "ack").put("id", frame.optString("id"))
            else null
        }
    }

    private fun msg(id: String) =
        JSONObject().put("t", "msg").put("from", "alice").put("to", "bob").put("id", id)

    @Test
    fun frame_is_delivered_to_peer_inbox_and_acked() = runBlocking {
        val bobInbox = RecordingInbox()
        val aliceInbox = RecordingInbox()
        val aliceToBob = InMemoryPeerTransport(bobInbox)   // Alice's transport -> Bob's inbox
        val bobToAlice = InMemoryPeerTransport(aliceInbox)

        // Alice → Bob: frame lands in Bob's inbox; ack comes back to Alice.
        val ack = aliceToBob.send("bobNode", msg("m1"))
        assertEquals("ack", ack?.optString("t"))
        assertEquals("m1", ack?.optString("id"))
        assertEquals(1, bobInbox.received.size)
        assertEquals("m1", bobInbox.received[0].optString("id"))
        assertTrue(aliceInbox.received.isEmpty())   // not delivered to the sender

        // Bob → Alice: bidirectional.
        val ack2 = bobToAlice.send("aliceNode", msg("m2"))
        assertEquals("m2", ack2?.optString("id"))
        assertEquals(1, aliceInbox.received.size)

        // In-process channel is always up.
        assertTrue(aliceToBob.isConnected("bobNode"))
        assertEquals(RtcState.CONNECTED, aliceToBob.state("bobNode").value)
    }

    @Test
    fun non_msg_frames_get_no_ack() = runBlocking {
        val inbox = RecordingInbox()
        val transport = InMemoryPeerTransport(inbox)
        assertNull(transport.send("peer", JSONObject().put("t", "unknown")))
        assertEquals(1, inbox.received.size)   // still delivered, just not acked
    }

    @Test
    fun media_routes_to_peer_media_handler() = runBlocking {
        var got: ByteArray? = null
        val aliceToBob = InMemoryPeerTransport(RecordingInbox())
        aliceToBob.peerMediaHandler = { _, sealed -> got = sealed; JSONObject().put("t", "ack") }

        val ack = aliceToBob.sendMedia("bobNode", JSONObject().put("id", "blob1"), byteArrayOf(1, 2, 3))
        assertEquals("ack", ack?.optString("t"))
        assertTrue(got!!.contentEquals(byteArrayOf(1, 2, 3)))
    }
}
