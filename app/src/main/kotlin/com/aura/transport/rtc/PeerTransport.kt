package com.aura.transport.rtc

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * The outbound side of the direct peer-to-peer **data plane**: a per-peer, nodeId-keyed
 * frame round-trip (`send(frame) → ack`) plus connection state. The production
 * [RtcTransport] (WebRTC data channels) implements it; an in-memory implementation can
 * route a peer's [send] straight to the other peer's [com.aura.transport.FrameInbox],
 * enabling in-process two-peer message-delivery simulation with no network.
 *
 * Lifecycle (`init`) stays on the concrete [RtcTransport] — only the delivery surface is
 * abstracted, which is all the message/media senders and the chat header depend on.
 */
interface PeerTransport {
    /** Inbound reassembled-media handler (set by the media layer; transport-agnostic). */
    var mediaHandler: (suspend (meta: JSONObject, sealed: ByteArray) -> JSONObject?)?

    /** Per-peer connection state for the UI (pairing result + chat header). */
    fun state(peerNodeIdHex: String): StateFlow<RtcState>

    /** True when a channel to [peerNodeIdHex] is open right now. */
    fun isConnected(peerNodeIdHex: String): Boolean

    /** Best-effort, non-blocking: ensure a channel to [peerNodeIdHex] is being established. */
    fun connectAsync(peerNodeIdHex: String)

    /** Round-trip a request [frame] to [peerNodeIdHex]; returns its ack, or null if not ready. */
    suspend fun send(peerNodeIdHex: String, frame: JSONObject): JSONObject?

    /** Stream a sealed media blob to [peerNodeIdHex] and await the final ack; null if not ready. */
    suspend fun sendMedia(peerNodeIdHex: String, startFrame: JSONObject, sealedBytes: ByteArray): JSONObject?
}
