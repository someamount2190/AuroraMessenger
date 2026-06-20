package com.aura.transport.rtc

import com.aura.call.SimplePcObserver
import com.aura.call.SimpleSdpObserver
import com.aura.media.EncryptedMediaStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One peer's WebRTC **data-channel** connection — the message-transport sibling of
 * [com.aura.call.WebRtcSession], stripped of media. It owns a single PeerConnection,
 * a reliable/ordered DataChannel, ICE candidate buffering, and one-time disposal.
 *
 * The threading/disposal invariants are copied verbatim from WebRtcSession because
 * they were paid for in process aborts:
 *  - observer callbacks hop onto the app scope (never touch state on WebRTC's native
 *    signaling thread → no JNI RTC_CHECK(!ExceptionCheck()) abort);
 *  - the native objects are disposed exactly once via an atomic CAS guard.
 *
 * Frames are JSON (the same wire shape `MessageSender`/`TcpMessageServer` use). A
 * request frame carries an `id`; the receiver processes it and sends an `ack` frame
 * back over the same channel. [send] correlates the ack to the request by `id`.
 */
class RtcDataSession(
    private val factory: PeerConnectionFactory,
    private val iceServers: List<PeerConnection.IceServer>,
    private val scopeProvider: () -> CoroutineScope?,
    private val listener: Listener
) {
    /** Events the transport reacts to. State/IO callbacks land on the app scope. */
    interface Listener {
        suspend fun onLocalIceCandidate(candidate: IceCandidate)
        fun onStateChanged(state: RtcState)
        /** Process an inbound request frame (msg/ctl); return an ack frame to send back, or null. */
        suspend fun onInboundFrame(frame: JSONObject): JSONObject?
        /** Store a fully-reassembled inbound media blob; return the ack frame, or null. */
        suspend fun onInboundMedia(meta: JSONObject, sealed: ByteArray): JSONObject?
    }

    @Volatile var state: RtcState = RtcState.IDLE
        private set

    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null

    @Volatile private var remoteDescSet = false
    /** True once the peer's offer/answer was applied — i.e. the peer was alive and
     *  responded. Lets the transport tell "offline" (FAILED) from "answered but no
     *  direct path" (UNREACHABLE) on a connect timeout. */
    val remoteDescriptionApplied: Boolean get() = remoteDescSet
    private val pendingRemoteIce = java.util.Collections.synchronizedList(ArrayList<IceCandidate>())
    private val disposed = AtomicBoolean(true)

    // Outstanding sends awaiting an ack, keyed by frame "id".
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    // Inbound media reassembly (id-keyed); fed in order from onMessage.
    private val mediaReassembler = RtcMediaReassembler(EncryptedMediaStore.MAX_MEDIA_BYTES)

    private fun setState(s: RtcState) {
        if (state == s) return
        state = s
        listener.onStateChanged(s)
    }

    /** Build the peer connection (and arm the one-time disposal guard). */
    fun create(asCaller: Boolean) {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Gather continually so a late IPv6 / network-change candidate still surfaces.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // IPv6 candidate gathering is on by default in this WebRTC build; ICE ranks
            // IPv6 pairs above IPv4 by candidate priority, so IPv6 is tried first and the
            // connection degrades to IPv4 automatically (RFC 8445). No "prefer IPv6" flag.
        }
        disposed.set(false)
        setState(RtcState.GATHERING)
        pc = factory.createPeerConnection(config, object : SimplePcObserver() {
            override fun onIceCandidate(candidate: IceCandidate) {
                scopeProvider()?.launch { listener.onLocalIceCandidate(candidate) }
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                // Marshal off WebRTC's native signaling thread (see class doc).
                scopeProvider()?.launch {
                    when (s) {
                        PeerConnection.IceConnectionState.CHECKING -> setState(RtcState.CHECKING)
                        // ICE connectivity is necessary but NOT sufficient to send — the SCTP
                        // data channel opens slightly later. So CONNECTED is driven *only* by the
                        // channel reaching OPEN (see attachChannel), keeping "🔒 Connected" honest:
                        // it means you can actually send right now. Until then we stay CHECKING.
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> setState(RtcState.CHECKING)
                        // DISCONNECTED is usually a transient blip; with continual gathering
                        // WebRTC keeps trying and can recover, so show "Connecting…" (CHECKING)
                        // rather than flashing a failure banner mid-conversation.
                        PeerConnection.IceConnectionState.DISCONNECTED -> setState(RtcState.CHECKING)
                        // A hard ICE failure means checks ran but no candidate pair worked —
                        // that's UNREACHABLE (direct path impossible), not "offline" (FAILED).
                        // CLOSED is driven by our own dispose(), so it's not surfaced here.
                        PeerConnection.IceConnectionState.FAILED -> setState(RtcState.UNREACHABLE)
                        else -> {}
                    }
                }
            }
            // Responder receives the channel the caller created.
            override fun onDataChannel(dc: DataChannel?) { dc?.let { attachChannel(it) } }
        })
        if (asCaller) {
            pc?.createDataChannel("aura", DataChannel.Init())?.let { attachChannel(it) }
        }
    }

    private fun attachChannel(dc: DataChannel) {
        channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (dc.state() == DataChannel.State.OPEN) scopeProvider()?.launch { setState(RtcState.CONNECTED) }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                // The buffer is reused by WebRTC after this returns — copy synchronously.
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                handleInbound(data)
            }
        })
        // A channel handed to the responder via onDataChannel may already be OPEN, in which
        // case onStateChange won't fire again — so reflect the live state immediately. This
        // is what makes it safe to drive CONNECTED solely from the channel (not from ICE).
        if (dc.state() == DataChannel.State.OPEN) scopeProvider()?.launch { setState(RtcState.CONNECTED) }
    }

    private fun handleInbound(data: ByteArray) {
        val frame = try { JSONObject(String(data, StandardCharsets.UTF_8)) } catch (e: Exception) { return }
        when (frame.optString("t")) {
            // Delivery receipt for one of our outstanding sends (msg or media).
            "ack" -> pendingAcks.remove(frame.optString("id"))?.complete(frame)
            // Streamed media: accumulate cheaply in arrival order on this (network) thread;
            // defer the heavy Base64 decode + store to the app scope once the last chunk lands.
            "media" -> mediaReassembler.begin(frame)
            "mediachunk" -> {
                val done = mediaReassembler.chunk(frame) ?: return
                scopeProvider()?.launch {
                    val sealed = RtcMediaReassembler.assemble(done.parts)
                    val ack = listener.onInboundMedia(done.meta, sealed) ?: return@launch
                    writeRaw(ack)
                }
            }
            // A request from the peer (msg/ctl): process and send the ack back over the channel.
            else -> scopeProvider()?.launch {
                val ack = listener.onInboundFrame(frame) ?: return@launch
                writeRaw(ack)
            }
        }
    }

    /**
     * Send a request [frame] and await its ack (correlated by `id`). Returns the ack
     * frame, or null if the channel isn't open, the write fails, or the ack times out.
     */
    suspend fun send(frame: JSONObject, timeoutMs: Long): JSONObject? {
        val ch = channel ?: return null
        if (ch.state() != DataChannel.State.OPEN) return null
        val id = frame.optString("id")
        if (id.isEmpty()) { return if (writeRaw(frame)) JSONObject().put("t", "sent") else null }
        val deferred = CompletableDeferred<JSONObject>()
        pendingAcks[id] = deferred
        return try {
            if (!writeRaw(frame)) null
            else withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingAcks.remove(id)
        }
    }

    private fun writeRaw(frame: JSONObject): Boolean {
        val ch = channel ?: return false
        return try {
            val bytes = frame.toString().toByteArray(StandardCharsets.UTF_8)
            ch.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        } catch (e: Exception) { false }
    }

    /**
     * Stream a sealed media blob: a "media" start frame followed by id-tagged "mediachunk"
     * frames, then await the single final ack (correlated by [startFrame]'s `id`, the message
     * id). Splitting into small frames keeps each data-channel message under the SCTP
     * max-message-size; [bufferedAmount] backpressure stops a large blob from outrunning the
     * send buffer (which would drop messages or close the channel). Returns the ack frame, or
     * null if the channel isn't open / a write fails / the ack times out.
     */
    suspend fun sendMedia(startFrame: JSONObject, sealedBytes: ByteArray, chunkBytes: Int, timeoutMs: Long): JSONObject? {
        val ch = channel ?: return null
        if (ch.state() != DataChannel.State.OPEN) return null
        val id = startFrame.optString("id")
        if (id.isEmpty()) return null
        startFrame.put("chunks", RtcMediaChunking.chunkCount(sealedBytes.size, chunkBytes))
        val deferred = CompletableDeferred<JSONObject>()
        pendingAcks[id] = deferred
        return try {
            if (!writeRaw(startFrame)) return null
            var off = 0
            var i = 0
            while (off < sealedBytes.size) {
                // Flow control: wait out the SCTP send buffer before queueing more.
                while ((channel?.bufferedAmount() ?: return null) > MAX_BUFFERED_BYTES) {
                    if (channel?.state() != DataChannel.State.OPEN) return null
                    delay(BACKPRESSURE_POLL_MS)
                }
                val end = minOf(off + chunkBytes, sealedBytes.size)
                if (!writeRaw(RtcMediaChunking.chunkFrame(id, i, sealedBytes, off, end))) return null
                off = end; i++
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingAcks.remove(id)
        }
    }

    // ── SDP / ICE (same shape as WebRtcSession) ──────────────────────────────

    suspend fun createOffer(): SessionDescription = suspendSdp { obs -> pc?.createOffer(obs, MediaConstraints()) }
    suspend fun createAnswer(): SessionDescription = suspendSdp { obs -> pc?.createAnswer(obs, MediaConstraints()) }

    fun setLocalDescription(sdp: SessionDescription) {
        pc?.setLocalDescription(SimpleSdpObserver(), sdp)
    }

    fun applyRemoteDescription(type: SessionDescription.Type, sdp: String) {
        pc?.setRemoteDescription(SimpleSdpObserver(), SessionDescription(type, sdp))
        remoteDescSet = true
        flushPendingIce()
    }

    fun addOrBufferIce(candidate: IceCandidate) {
        val p = pc
        if (p != null && remoteDescSet) p.addIceCandidate(candidate) else pendingRemoteIce.add(candidate)
    }

    private fun flushPendingIce() {
        val p = pc ?: return
        synchronized(pendingRemoteIce) {
            pendingRemoteIce.forEach { p.addIceCandidate(it) }
            pendingRemoteIce.clear()
        }
    }

    private suspend fun suspendSdp(create: (SdpObserver) -> Unit): SessionDescription =
        suspendCancellableCoroutine { cont ->
            create(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resumeWith(Result.success(sdp)) }
                override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException(error))) }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            })
        }

    /** Dispose the native objects exactly once (CAS guard), failing any pending acks. */
    fun dispose() {
        if (disposed.compareAndSet(false, true)) {
            val c = channel; channel = null
            val p = pc; pc = null
            try { c?.close() } catch (e: Exception) {}
            try { c?.dispose() } catch (e: Exception) {}
            p?.dispose()
        }
        pendingAcks.values.forEach { it.cancel() }
        pendingAcks.clear()
        mediaReassembler.clear()
        remoteDescSet = false
        pendingRemoteIce.clear()
    }

    private companion object {
        /** Pause media streaming while the SCTP send buffer holds more than this (bytes). */
        const val MAX_BUFFERED_BYTES = 1L * 1024 * 1024
        /** Re-check the send buffer this often while backpressured. */
        const val BACKPRESSURE_POLL_MS = 16L
    }
}
