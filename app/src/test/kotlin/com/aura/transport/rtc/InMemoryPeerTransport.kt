package com.aura.transport.rtc

import com.aura.transport.FrameInbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * In-memory [PeerTransport] for **in-process two-peer simulation**: delivers a peer's
 * [send]/[sendMedia] straight to the *other* peer's [FrameInbox], with no WebRTC, ICE,
 * or sockets. Cross-wire two of these to connect two peers:
 *
 * ```
 * val aliceToBob = InMemoryPeerTransport(bobInbox)   // Alice sends -> Bob's inbox
 * val bobToAlice = InMemoryPeerTransport(aliceInbox)
 * val ack = aliceToBob.send(bobNodeId, msgFrame)     // -> Bob's ack
 * ```
 *
 * Always reports CONNECTED (the in-process channel is never down), so [connectAsync] is a
 * no-op. This is the data-plane twin of `InMemoryRendezvous`: together they let the full
 * pair-then-message flow run between two object graphs in one process.
 */
class InMemoryPeerTransport(private val peerInbox: FrameInbox) : PeerTransport {

    override var mediaHandler: (suspend (meta: JSONObject, sealed: ByteArray) -> JSONObject?)? = null

    private val connected = MutableStateFlow(RtcState.CONNECTED)
    override fun state(peerNodeIdHex: String): StateFlow<RtcState> = connected

    override fun isConnected(peerNodeIdHex: String): Boolean = true

    override fun connectAsync(peerNodeIdHex: String) { /* always connected in-process */ }

    override suspend fun send(peerNodeIdHex: String, frame: JSONObject): JSONObject? =
        peerInbox.processFrame(frame)

    /**
     * Deliver a sealed media blob to the peer's inbound media handler (set on the peer's
     * own transport), mirroring how RTC reassembles a blob and hands it to MediaTransfer.
     */
    override suspend fun sendMedia(peerNodeIdHex: String, startFrame: JSONObject, sealedBytes: ByteArray): JSONObject? =
        peerMediaHandler?.invoke(startFrame, sealedBytes)

    /** The receiving peer's media handler — wire this to the other transport's [mediaHandler]. */
    var peerMediaHandler: (suspend (meta: JSONObject, sealed: ByteArray) -> JSONObject?)? = null
}
