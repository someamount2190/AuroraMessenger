package com.aura.call

import android.content.Context
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.identity.IdentityStore
import java.util.UUID
import com.aura.network.SyncEngine
import com.aura.notify.Notifier
import com.aura.service.WakeService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 7: encrypted WebRTC voice/video calls.
 *
 * CallController owns the **call state machine** (idle → outgoing/incoming → connecting →
 * connected → ended), the call log, ringing/notifications, and minimize state. The
 * sealed signaling transport lives in [CallSignalCodec]; the WebRTC media engine
 * (peer connection, tracks, ICE, disposal) lives in [WebRtcSession]. Once ICE connects,
 * media flows peer-to-peer and never touches the server. No call logs on the wire, no
 * recording.
 */
@Singleton
class CallController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val codec: CallSignalCodec,
    private val identityStore: IdentityStore,
    private val syncEngine: SyncEngine,
    private val notifier: Notifier,
    private val ringer: Ringer
) {
    // The call state machine's model types ([CallState], [CallInfo]) live in CallModels.kt.

    // The WebRTC media engine. Its observer callbacks are marshalled back here through
    // [WebRtcSession.Listener], always on the app scope (never WebRTC's signaling thread).
    private val session = WebRtcSession(context, { scope }, object : WebRtcSession.Listener {
        override suspend fun onLocalIceCandidate(candidate: IceCandidate) {
            remoteNodeIdHex?.let {
                codec.send(it, JSONObject()
                    .put("kind", "ice")
                    .put("candidate", candidate.sdp)
                    .put("mid", candidate.sdpMid)
                    .put("mlineindex", candidate.sdpMLineIndex))
            }
        }
        override fun onConnected() {
            android.util.Log.i(TAG, "media CONNECTED (ICE up)")
            reconnectJob?.cancel(); reconnectJob = null   // recovered (or first connect)
            noAnswerJob?.cancel(); noAnswerJob = null
            connectTimeoutJob?.cancel(); connectTimeoutJob = null
            if (callConnectedAtMs == 0L) callConnectedAtMs = System.currentTimeMillis()
            // Replace the incoming-ring notification with the ongoing-call notification
            // (same id): "call in progress" + an End control.
            notifier.notifyOngoingCall()
            _call.value = _call.value.copy(state = CallState.CONNECTED, connectedAtMs = callConnectedAtMs)
        }
        override fun onDisconnected() {
            // Transient — WebRTC may recover on its own via continual gathering. Don't tear
            // the call down here; a genuine failure escalates to onFailed().
            android.util.Log.i(TAG, "ICE disconnected — awaiting recovery")
        }
        override fun onFailed() { onIceFailed() }
    })

    /** Surfaced to the UI as a toast when a call can't connect or is lost (see AuroraApp). */
    private val _callError = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val callError: kotlinx.coroutines.flow.SharedFlow<String> = _callError

    // Media state exposed to the call UI — delegated to the session.
    val eglBase get() = session.eglBase
    val localVideo: StateFlow<org.webrtc.VideoTrack?> get() = session.localVideo
    val remoteVideo: StateFlow<org.webrtc.VideoTrack?> get() = session.remoteVideo
    val videoEnabled: StateFlow<Boolean> get() = session.videoEnabled

    private val _call = MutableStateFlow(CallInfo(CallState.IDLE))
    val call: StateFlow<CallInfo> = _call

    private var scope: CoroutineScope? = null
    private var ringTimeoutJob: kotlinx.coroutines.Job? = null
    private var remoteNodeIdHex: String? = null
    private var pendingOfferSdp: String? = null   // held while INCOMING, applied on accept
    private var pendingOfferIsVideo: Boolean = true
    // ICE-recovery bookkeeping: a watchdog that fails the call if recovery doesn't land,
    // and a one-shot guard so a restart is attempted at most once per call.
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var iceRestartTried: Boolean = false
    // Caller's no-answer backstop (if the callee never rings — e.g. offline — no "bye" ever
    // comes to end us), and a connect watchdog so a call that negotiates but never reaches
    // CONNECTED (ICE stalls) fails honestly instead of hanging on "Connecting…".
    private var noAnswerJob: kotlinx.coroutines.Job? = null
    private var connectTimeoutJob: kotlinx.coroutines.Job? = null

    // Minimized = the call still runs but the full call screen is dismissed (user pressed
    // Back). The app shows a floating bubble + an ongoing notification with an End control.
    // Reset whenever a call starts/arrives or ends, so a new call always opens full-screen.
    private val _minimized = MutableStateFlow(false)
    val minimized: StateFlow<Boolean> = _minimized
    fun minimize() { _minimized.value = true }
    fun expand() { _minimized.value = false }

    // Call-log bookkeeping (each device logs its own view of the call into the chat).
    private var callConnectedAtMs: Long = 0L      // >0 once media connected (answered)
    private var callDeclinedByMe: Boolean = false
    private var callAcceptedByMe: Boolean = false // we tapped Accept on an incoming call

    /** Register the call signal handler with the sync engine (once, at startup). */
    fun init(appScope: CoroutineScope) {
        scope = appScope
        session.ensureFactory()
        syncEngine.registerSignalHandler("call") { json ->
            val inc = codec.receive(json) ?: return@registerSignalHandler
            handleInner(inc.from, inc.inner)
        }
    }

    // ── Outbound call ──────────────────────────────────────────────────────

    fun startCall(peerNodeIdHex: String, video: Boolean = true) {
        val s = scope ?: return
        // One call at a time: ignore a request to start a new call (this contact or any
        // other) while one is already incoming/outgoing/connecting/connected.
        val cur = _call.value.state
        if (cur != CallState.IDLE && cur != CallState.ENDED) return
        remoteNodeIdHex = peerNodeIdHex
        callConnectedAtMs = 0L; callDeclinedByMe = false; callAcceptedByMe = false; iceRestartTried = false
        session.resetForNewCall()
        _minimized.value = false
        session.setVideoEnabled(video)
        // Hold the mic/camera FGS type for the call now (we're foreground), so pressing
        // BACK / leaving the app doesn't let the OS cut the mic or drop the connection.
        WakeService.setCallActive(context, active = true, video = video)
        s.launch {
            val t0 = System.currentTimeMillis()
            val contact = contactDao.byNodeId(peerNodeIdHex)
            _call.value = CallInfo(CallState.OUTGOING, peerNodeIdHex, contact?.displayName, isCaller = true, isVideo = video)
            android.util.Log.i(TAG, "startCall: OUTGOING set, creating PC (video=$video)")
            session.create()
            session.addLocalTracks(video)
            android.util.Log.i(TAG, "startCall: tracks added (+${System.currentTimeMillis() - t0}ms), creating offer")
            val offer = session.createOffer()
            session.setLocalDescription(offer)
            codec.send(peerNodeIdHex, JSONObject()
                .put("kind", "offer")
                .put("video", video)
                .put("sdp", offer.description))
            android.util.Log.i(TAG, "startCall: offer SENT (+${System.currentTimeMillis() - t0}ms)")
        }
        // No-answer backstop: if the callee never picks up — or is offline, so no "bye" ever
        // arrives to end us — stop ringing out after roughly the window the callee rings, so
        // the caller isn't stuck on "Calling…" forever. Cancelled the moment an answer lands.
        noAnswerJob?.cancel()
        noAnswerJob = s.launch {
            kotlinx.coroutines.delay(NO_ANSWER_TIMEOUT_MS)
            if (_call.value.state == CallState.OUTGOING) {
                android.util.Log.i(TAG, "no answer — ending outgoing call")
                _callError.tryEmit("No answer from ${_call.value.peerName ?: "the other device"}")
                endCall(notifyPeer = true)
            }
        }
    }

    // ── Inbound call ───────────────────────────────────────────────────────

    fun acceptCall() {
        val s = scope ?: return
        val peer = remoteNodeIdHex ?: return
        val offerSdp = pendingOfferSdp ?: return
        notifier.cancelCall()
        ringer.stop()
        ringTimeoutJob?.cancel()
        callAcceptedByMe = true
        session.setVideoEnabled(pendingOfferIsVideo)
        // Accepting brings us foreground; hold the mic/camera FGS type for the live call.
        WakeService.setCallActive(context, active = true, video = pendingOfferIsVideo)
        s.launch {
            _call.value = _call.value.copy(state = CallState.CONNECTING)
            session.create()
            session.addLocalTracks(pendingOfferIsVideo)
            // Apply the remote offer + replay ICE the caller trickled before we accepted.
            session.applyRemoteDescription(SessionDescription.Type.OFFER, offerSdp)
            val answer = session.createAnswer()
            session.setLocalDescription(answer)
            codec.send(peer, JSONObject().put("kind", "answer").put("sdp", answer.description))
            armConnectTimeout()   // we've answered — fail honestly if media never connects
        }
    }

    fun declineCall() {
        val peer = remoteNodeIdHex
        callDeclinedByMe = true
        scope?.launch { peer?.let { codec.send(it, JSONObject().put("kind", "bye")) } }
        endCall(notifyPeer = false)
    }

    fun endCall(notifyPeer: Boolean = true) {
        // Log this call into the chat before tearing down (reads live call state).
        logCall()
        val peer = remoteNodeIdHex
        if (notifyPeer && peer != null) {
            scope?.launch { codec.send(peer, JSONObject().put("kind", "bye")) }
        }
        cleanup()
        _call.value = CallInfo(CallState.ENDED)
    }

    /**
     * Record this call as a message in its conversation — each device logs its own view
     * (outgoing/incoming, answered/missed/declined/no-answer). A missed incoming call is
     * left unread so it surfaces on the home screen. No-ops if no call was actually in
     * progress (so a stray endCall can't double-log).
     */
    private fun logCall() {
        val info = _call.value
        if (info.state == CallState.IDLE || info.state == CallState.ENDED) return
        val peer = info.peerNodeIdHex ?: remoteNodeIdHex ?: return
        val connected = callConnectedAtMs > 0L
        val durationMs = if (connected) System.currentTimeMillis() - callConnectedAtMs else null
        val status = CallLog.status(
            isCaller     = info.isCaller,
            connected    = connected,
            declinedByMe = callDeclinedByMe,
            acceptedByMe = callAcceptedByMe
        )
        val entity = MessageEntity(
            id               = UUID.randomUUID().toString(),
            contactNodeIdHex = peer,
            fromMe           = info.isCaller,
            body             = CallLog.label(info.isCaller, status, durationMs),
            timestampMs      = System.currentTimeMillis(),
            status           = "delivered",
            type             = "call",
            callStatus       = status,
            durationMs       = durationMs,
            read             = status != "missed"   // a missed call stays unread
        )
        scope?.launch { messageDao.insert(entity) }
    }

    // ── Camera / mute controls (delegate to the media engine) ───────────────

    fun toggleMute(): Boolean = session.toggleMute()
    fun toggleVideo(): Boolean = session.toggleVideo()
    fun switchCamera() = session.switchCamera()

    // ── Inbound signal state machine ────────────────────────────────────────

    /** Drive the call state machine from a decrypted inbound signal (see [CallSignalCodec]). */
    private suspend fun handleInner(from: String, inner: JSONObject) {
        android.util.Log.i(TAG, "recv '${inner.optString("kind")}' from ${from.take(8)}")
        when (inner.optString("kind")) {
            "offer" -> {
                // An offer arriving from the peer we're already in a call with is an ICE
                // RESTART (the caller recovering a dropped path), not a new call — apply it
                // and answer in place, no re-ring.
                val isRestart = remoteNodeIdHex == from &&
                    (_call.value.state == CallState.CONNECTED || _call.value.state == CallState.CONNECTING)
                if (isRestart) {
                    android.util.Log.i(TAG, "recv ICE-restart offer — answering in place")
                    session.applyRemoteDescription(SessionDescription.Type.OFFER, inner.optString("sdp"))
                    val answer = session.createAnswer()
                    session.setLocalDescription(answer)
                    codec.send(from, JSONObject().put("kind", "answer").put("sdp", answer.description))
                    armReconnectWatchdog()
                    return
                }

                // We're already on (or ringing) a call: guard the inbound offer so a second
                // caller can't clobber it, and resolve a simultaneous mutual dial (glare).
                val cur = _call.value.state
                if (cur != CallState.IDLE && cur != CallState.ENDED) {
                    when {
                        from == remoteNodeIdHex && cur == CallState.OUTGOING -> {
                            // Glare: we dialled each other at once. Deterministic tiebreak (same
                            // rule as the data transport): the lower nodeId stays the caller, the
                            // higher yields to callee, so exactly one call forms — not two crossed
                            // offers. We win → keep our offer, ignore theirs. We lose → drop our
                            // outgoing and ring on theirs (our mic/camera grant already stands).
                            val myNodeId = runCatching { identityStore.getOrCreate().nodeId.toHex() }.getOrNull() ?: return
                            if (myNodeId < from) return
                            android.util.Log.i(TAG, "glare — yielding to ${from.take(8)} as callee")
                            cleanup()   // tear down our outgoing attempt; rebuild as callee below
                        }
                        from == remoteNodeIdHex -> {
                            // A duplicate/late offer from the peer we're already handling — refresh
                            // the pending SDP while still ringing; never re-ring or hang up on them.
                            if (cur == CallState.INCOMING) pendingOfferSdp = inner.optString("sdp")
                            return
                        }
                        else -> {
                            // Busy with a different peer — tell the new caller cleanly and leave
                            // the active call untouched (no clobber).
                            android.util.Log.i(TAG, "busy — declining incoming from ${from.take(8)}")
                            scope?.launch { codec.send(from, JSONObject().put("kind", "bye")) }
                            return
                        }
                    }
                }

                remoteNodeIdHex = from
                pendingOfferSdp = inner.optString("sdp")
                pendingOfferIsVideo = inner.optBoolean("video", true)
                callConnectedAtMs = 0L; callDeclinedByMe = false; callAcceptedByMe = false; iceRestartTried = false
                session.resetForNewCall()
                _minimized.value = false
                val name = contactDao.byNodeId(from)?.displayName
                _call.value = CallInfo(CallState.INCOMING, from, name, isCaller = false, isVideo = pendingOfferIsVideo)
                // Surface the call over whatever is in the foreground (other app, launcher,
                // lock screen). No-op when Aurora is already on screen.
                notifier.notifyIncomingCall()
                // Ring regardless of foreground state; auto-give-up as missed if unanswered.
                ringer.start()
                ringTimeoutJob?.cancel()
                ringTimeoutJob = scope?.launch {
                    kotlinx.coroutines.delay(RING_TIMEOUT_MS)
                    if (_call.value.state == CallState.INCOMING) endCall(notifyPeer = true)
                }
            }
            "answer" -> {
                // Also the answer to an ICE-restart offer — apply it either way; only show
                // CONNECTING for the initial negotiation, not a mid-call restart.
                session.applyRemoteDescription(SessionDescription.Type.ANSWER, inner.optString("sdp"))
                if (_call.value.state != CallState.CONNECTED) {
                    noAnswerJob?.cancel(); noAnswerJob = null   // they picked up — stop the no-answer backstop
                    _call.value = _call.value.copy(state = CallState.CONNECTING)
                    armConnectTimeout()                          // now ensure media actually connects
                }
            }
            "ice" -> {
                session.addOrBufferIce(
                    IceCandidate(inner.optString("mid"), inner.optInt("mlineindex"), inner.optString("candidate"))
                )
            }
            "bye" -> endCall(notifyPeer = false)
        }
    }

    /**
     * ICE failed mid-session (e.g. a network switch killed the path). The caller re-offers
     * with an ICE restart — which re-gathers candidates and re-negotiates a fresh path — and
     * a watchdog ends the call with a clear message if recovery doesn't land in time. The
     * callee waits once for the caller's restart offer. A restart is attempted at most once;
     * a second failure ends the call.
     */
    private fun onIceFailed() {
        val s = scope ?: return
        val info = _call.value
        if (info.state == CallState.IDLE || info.state == CallState.ENDED) return
        val peer = remoteNodeIdHex ?: return
        when {
            info.isCaller && !iceRestartTried -> {
                iceRestartTried = true
                android.util.Log.i(TAG, "ICE failed — caller attempting ICE restart")
                s.launch {
                    runCatching {
                        val offer = session.createRestartOffer()
                        session.setLocalDescription(offer)
                        codec.send(peer, JSONObject()
                            .put("kind", "offer")
                            .put("video", info.isVideo)
                            .put("sdp", offer.description))
                    }
                }
                armReconnectWatchdog()
            }
            !info.isCaller && !iceRestartTried -> {
                // Wait once for the caller's restart offer to arrive and re-negotiate.
                iceRestartTried = true
                android.util.Log.i(TAG, "ICE failed — callee awaiting caller's restart")
                armReconnectWatchdog()
            }
            else -> failCall()
        }
    }

    /**
     * Fail a call that negotiated but never reached CONNECTED (ICE stalled with no clean
     * FAILED callback) so it doesn't hang on "Connecting…". onConnected cancels this;
     * [failCall] picks honest copy (never-connected vs dropped). Covers both the caller
     * (armed when the answer lands) and the callee (armed on accept).
     */
    private fun armConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope?.launch {
            kotlinx.coroutines.delay(CONNECT_TIMEOUT_MS)
            val st = _call.value.state
            if (st != CallState.IDLE && st != CallState.ENDED && st != CallState.CONNECTED) {
                android.util.Log.i(TAG, "connect timeout (state=$st) — failing call")
                failCall()
            }
        }
    }

    /** End the call with a clear message unless it recovers (onConnected cancels this). */
    private fun armReconnectWatchdog() {
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            kotlinx.coroutines.delay(RECONNECT_TIMEOUT_MS)
            if (_call.value.state != CallState.IDLE && _call.value.state != CallState.ENDED) {
                android.util.Log.i(TAG, "reconnect window elapsed — failing call")
                failCall()
            }
        }
    }

    /** Surface an honest reason and end. Distinguishes never-connected from dropped. */
    private fun failCall() {
        val name = _call.value.peerName ?: "the other device"
        val reason = if (callConnectedAtMs > 0L)
            "Call with $name disconnected"
        else
            "Couldn't connect the call to $name — you may both be on mobile data. Try when one of you is on Wi-Fi."
        _callError.tryEmit(reason)
        endCall(notifyPeer = false)
    }

    private fun cleanup() {
        notifier.cancelCall()
        ringer.stop()
        ringTimeoutJob?.cancel()
        reconnectJob?.cancel(); reconnectJob = null
        noAnswerJob?.cancel(); noAnswerJob = null
        connectTimeoutJob?.cancel(); connectTimeoutJob = null
        iceRestartTried = false
        session.dispose()
        // Drop the mic/camera FGS type — back to the low-priority keep-alive service.
        WakeService.setCallActive(context, active = false, video = false)
        remoteNodeIdHex = null
        pendingOfferSdp = null
        pendingOfferIsVideo = true
        callAcceptedByMe = false
        _minimized.value = false
    }

    private companion object {
        const val TAG = "AuroraCall"
        /** Ring this long before giving up an unanswered incoming call (logged missed). */
        const val RING_TIMEOUT_MS = 45_000L
        /** After ICE fails, how long to wait for an ICE-restart to recover before ending. */
        const val RECONNECT_TIMEOUT_MS = 15_000L
        /** Caller's no-answer backstop — slightly longer than the callee's ring, so the
         *  callee's "bye" normally ends us first; this only fires if none ever arrives. */
        const val NO_ANSWER_TIMEOUT_MS = 50_000L
        /** A call must reach CONNECTED within this long after negotiating, or we fail it. */
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
