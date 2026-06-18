package com.aura.transport.rtc

import android.content.Context
import com.aura.crypto.toHex
import com.aura.identity.IdentityStore
import com.aura.network.SyncEngine
import com.aura.transport.TcpMessageServer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Peer-to-peer **message transport** over WebRTC data channels — the NAT-traversing
 * path that lets two devices behind carrier NAT exchange messages directly. It is the
 * messaging twin of [com.aura.call.CallController], but **multi-peer** (a session per
 * contact) instead of one-call-at-a-time.
 *
 * Flow per peer: signal an offer/answer through the rendezvous /signal queue
 * ([RtcSignalCodec]); ICE gathers (IPv6 first) and punches a direct path; once the
 * data channel opens the server falls off and frames flow peer-to-peer. Inbound
 * request frames are processed by [TcpMessageServer.processFrame] (the same
 * decrypt→store→ack pipeline the TCP path uses), so RTC and TCP are interchangeable
 * transports for an identical wire frame.
 *
 * STUN is self-hosted (no TURN — the droplet is never in the data path). The residual
 * (both peers symmetric-CGNAT, no IPv6) surfaces as [RtcState.FAILED] for the UI to
 * report honestly; a future ShadowMesh relay candidate slots into the ICE config here.
 */
@Singleton
class RtcTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val codec: RtcSignalCodec,
    private val identityManager: IdentityStore,
    private val tcpServer: TcpMessageServer
) {
    private var scope: CoroutineScope? = null
    private var factory: PeerConnectionFactory? = null

    private val sessions = ConcurrentHashMap<String, RtcDataSession>()
    private val states = ConcurrentHashMap<String, MutableStateFlow<RtcState>>()
    private val lastAttemptMs = ConcurrentHashMap<String, Long>()

    private val iceServers = listOf(
        // Self-hosted STUN (coturn on the rendezvous droplet, STUN-only, dual-stack).
        // Resolves directly to the droplet, so it bypasses any HTTP proxy. No TURN.
        PeerConnection.IceServer.builder("stun:api.auroramessenger.com:3478").createIceServer()
    )

    /** Per-peer connection state for the UI (pairing result + chat header). */
    fun state(peerNodeIdHex: String): StateFlow<RtcState> = stateFlow(peerNodeIdHex)

    /** Registered as the "rtc" signal handler. Call once at startup (AppWiring). */
    fun init(appScope: CoroutineScope, syncEngine: SyncEngine) {
        scope = appScope
        ensureFactory()
        syncEngine.registerSignalHandler("rtc") { json -> handleSignal(json) }
        android.util.Log.i(TAG, "init: WebRTC data transport ready (STUN ${iceServers.firstOrNull()?.urls})")
    }

    /** True when a data channel to [peerNodeIdHex] is open right now. */
    fun isConnected(peerNodeIdHex: String): Boolean =
        sessions[peerNodeIdHex]?.state == RtcState.CONNECTED

    /**
     * Best-effort: ensure a connection to [peerNodeIdHex] is being established (or is
     * up). Non-blocking — kicks off signaling in the background and returns. Callers
     * use [isConnected]/[send]; a warm session is used on the next attempt.
     */
    fun connectAsync(peerNodeIdHex: String) {
        val s = scope ?: return
        val existing = sessions[peerNodeIdHex]
        if (existing != null && (existing.state == RtcState.CONNECTED || existing.state.isActive)) return
        // Cooldown: don't re-offer an offline/unreachable peer on every flush retry.
        val now = System.currentTimeMillis()
        if (now - (lastAttemptMs[peerNodeIdHex] ?: 0L) < RETRY_COOLDOWN_MS) return
        lastAttemptMs[peerNodeIdHex] = now
        s.launch {
            android.util.Log.i(TAG, "connectAsync: offering ${peerNodeIdHex.take(8)}")
            val session = newSession(peerNodeIdHex)
            session.create(asCaller = true)
            val offer = session.createOffer()
            session.setLocalDescription(offer)
            val sent = codec.send(peerNodeIdHex, JSONObject().put("kind", "offer").put("sdp", offer.description))
            android.util.Log.i(TAG, "connectAsync: offer sent=$sent to ${peerNodeIdHex.take(8)}")
            if (!sent) teardown(peerNodeIdHex) else armConnectTimeout(peerNodeIdHex)
        }
    }

    /** Send a request [frame] over the open data channel and await its ack; null if not ready. */
    suspend fun send(peerNodeIdHex: String, frame: JSONObject): JSONObject? {
        val session = sessions[peerNodeIdHex] ?: return null
        if (session.state != RtcState.CONNECTED) return null
        return session.send(frame, SEND_ACK_TIMEOUT_MS)
    }

    // ── Signaling ────────────────────────────────────────────────────────────

    private suspend fun handleSignal(json: JSONObject) {
        val inc = codec.receive(json) ?: return
        val from = inc.from
        val inner = inc.inner
        android.util.Log.i(TAG, "signal: ${inner.optString("kind")} from ${from.take(8)}")
        when (inner.optString("kind")) {
            "offer" -> handleOffer(from, inner.optString("sdp"))
            "answer" -> sessions[from]?.applyRemoteDescription(SessionDescription.Type.ANSWER, inner.optString("sdp"))
            "ice" -> sessions[from]?.addOrBufferIce(
                IceCandidate(inner.optString("mid"), inner.optInt("mlineindex"), inner.optString("candidate"))
            )
            "bye" -> teardown(from)
        }
    }

    private suspend fun handleOffer(from: String, sdp: String) {
        val existing = sessions[from]
        if (existing != null && existing.state.isActive) {
            // Glare: both sides offered. The lower nodeId stays the caller; the higher
            // one yields and becomes the responder so exactly one connection forms.
            val myNodeId = runCatching { identityManager.getOrCreate().nodeId.toHex() }.getOrNull() ?: return
            if (myNodeId < from) return   // keep our outgoing offer; ignore theirs
            teardown(from)                // we yield → rebuild as responder
        }
        val session = newSession(from)
        session.create(asCaller = false)
        session.applyRemoteDescription(SessionDescription.Type.OFFER, sdp)
        val answer = session.createAnswer()
        session.setLocalDescription(answer)
        codec.send(from, JSONObject().put("kind", "answer").put("sdp", answer.description))
        armConnectTimeout(from)
    }

    /**
     * If a session hasn't reached CONNECTED within the window, surface FAILED so the UI
     * stops saying "Connecting…" forever and tells the user the truth (a peer that's
     * offline, or unreachable directly — both symmetric-CGNAT with no IPv6). The session
     * is left alive: a late successful connect still flips the state back to CONNECTED.
     */
    private fun armConnectTimeout(peerNodeIdHex: String) {
        val s = scope ?: return
        s.launch {
            kotlinx.coroutines.delay(CONNECT_TIMEOUT_MS)
            val session = sessions[peerNodeIdHex] ?: return@launch
            if (session.state != RtcState.CONNECTED) {
                android.util.Log.i(TAG, "timeout ${peerNodeIdHex.take(8)} (was ${session.state}) -> FAILED")
                stateFlow(peerNodeIdHex).value = RtcState.FAILED
            }
        }
    }

    private fun newSession(peerNodeIdHex: String): RtcDataSession {
        teardown(peerNodeIdHex)   // never leak a previous session for this peer
        val session = RtcDataSession(
            factory = ensureFactory(),
            iceServers = iceServers,
            scopeProvider = { scope },
            listener = object : RtcDataSession.Listener {
                override suspend fun onLocalIceCandidate(candidate: IceCandidate) {
                    codec.send(
                        peerNodeIdHex,
                        JSONObject()
                            .put("kind", "ice")
                            .put("candidate", candidate.sdp)
                            .put("mid", candidate.sdpMid)
                            .put("mlineindex", candidate.sdpMLineIndex)
                    )
                }
                override fun onStateChanged(state: RtcState) {
                    android.util.Log.i(TAG, "state ${peerNodeIdHex.take(8)} -> $state")
                    stateFlow(peerNodeIdHex).value = state
                }
                override suspend fun onInboundFrame(frame: JSONObject): JSONObject? = tcpServer.processFrame(frame)
            }
        )
        sessions[peerNodeIdHex] = session
        return session
    }

    private fun teardown(peerNodeIdHex: String) {
        sessions.remove(peerNodeIdHex)?.dispose()
        stateFlow(peerNodeIdHex).value = RtcState.IDLE
    }

    private fun stateFlow(peerNodeIdHex: String): MutableStateFlow<RtcState> =
        states.getOrPut(peerNodeIdHex) { MutableStateFlow(RtcState.IDLE) }

    @Synchronized
    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        // Idempotent across the app (WebRtcSession may also initialize for calls).
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        return PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
    }

    private companion object {
        const val TAG = "AuroraRtc"
        const val SEND_ACK_TIMEOUT_MS = 10_000L
        /** Flip a stalled connection to FAILED after this long so the UI reports honestly. */
        const val CONNECT_TIMEOUT_MS = 25_000L
        /** Don't re-offer the same peer more often than this (avoids flush-retry churn). */
        const val RETRY_COOLDOWN_MS = 30_000L
    }
}
