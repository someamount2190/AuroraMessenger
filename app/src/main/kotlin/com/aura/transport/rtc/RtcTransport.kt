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
 * (both peers symmetric-CGNAT, no IPv6) surfaces as [RtcState.UNREACHABLE] for the UI to
 * report honestly (distinct from [RtcState.FAILED] = peer never answered / offline); a
 * future ShadowMesh relay candidate slots into the ICE config here.
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
     * If a session hasn't reached CONNECTED within the window, surface the truth and
     * **tear the dead session down**. Tearing down (rather than leaving it alive) matters:
     *  - it stops a stuck PeerConnection from gathering forever in the background, and
     *  - it lets [connectAsync] actually re-offer later — its guard reads the *session's*
     *    own ICE state, which would otherwise still read GATHERING/CHECKING and bail.
     * The terminal state distinguishes the two real failure modes (patch §G): the peer
     * answered but no direct path formed (UNREACHABLE) vs. it never answered (FAILED ⇒
     * likely offline). A fresh attempt on the next flush (after the cooldown) can still
     * connect if conditions change.
     */
    private fun armConnectTimeout(peerNodeIdHex: String) {
        val s = scope ?: return
        s.launch {
            kotlinx.coroutines.delay(CONNECT_TIMEOUT_MS)
            val session = sessions[peerNodeIdHex] ?: return@launch
            if (session.state != RtcState.CONNECTED) {
                val terminal = if (session.remoteDescriptionApplied) RtcState.UNREACHABLE else RtcState.FAILED
                android.util.Log.i(TAG, "timeout ${peerNodeIdHex.take(8)} (was ${session.state}) -> $terminal")
                teardown(peerNodeIdHex)                  // dispose the dead PC + drop from the map
                stateFlow(peerNodeIdHex).value = terminal // publish honest state (teardown set IDLE)
            }
        }
    }

    private fun newSession(peerNodeIdHex: String): RtcDataSession {
        teardown(peerNodeIdHex)   // never leak a previous session for this peer
        // The listener captures `created` so it can ignore callbacks from a session
        // we've already replaced/torn down — otherwise a disposing PeerConnection could
        // publish a stale state (e.g. clobber a terminal UNREACHABLE/FAILED) or signal
        // ICE for a peer we're no longer talking to. (Nullable var, not lateinit: lateinit
        // isn't allowed on locals; the closure only reads it after we assign below.)
        var created: RtcDataSession? = null
        val isCurrent: () -> Boolean = { sessions[peerNodeIdHex] === created }
        val session = RtcDataSession(
            factory = ensureFactory(),
            iceServers = iceServers,
            scopeProvider = { scope },
            listener = object : RtcDataSession.Listener {
                override suspend fun onLocalIceCandidate(candidate: IceCandidate) {
                    if (!isCurrent()) return
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
                    if (!isCurrent()) return
                    android.util.Log.i(TAG, "state ${peerNodeIdHex.take(8)} -> $state")
                    stateFlow(peerNodeIdHex).value = state
                }
                override suspend fun onInboundFrame(frame: JSONObject): JSONObject? = tcpServer.processFrame(frame)
            }
        )
        created = session
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
