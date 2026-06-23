package com.aura.transport

import org.json.JSONObject

/**
 * The inbound side of the data plane: process a request frame (decrypt → store → ack)
 * and return the ack to send back over whatever transport delivered it, or null when no
 * response is warranted (not for us / unknown peer / decrypt fails).
 *
 * [com.aura.transport.TcpMessageServer] implements this; [com.aura.transport.rtc.RtcTransport]
 * depends on it (not the concrete server), so an in-memory transport can deliver straight
 * to a peer's inbox for two-peer simulation.
 */
interface FrameInbox {
    suspend fun processFrame(frame: JSONObject): JSONObject?
}
