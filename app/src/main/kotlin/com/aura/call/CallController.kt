package com.aura.call

import android.content.Context
import com.aura.db.ContactDao
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import java.util.UUID
import com.aura.network.SyncEngine
import com.aura.notify.Notifier
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
    private val syncEngine: SyncEngine,
    private val notifier: Notifier,
    private val ringer: Ringer
) {
    enum class CallState { IDLE, OUTGOING, INCOMING, CONNECTING, CONNECTED, ENDED }

    data class CallInfo(
        val state: CallState,
        val peerNodeIdHex: String? = null,
        val peerName: String? = null,
        val isCaller: Boolean = false,
        val isVideo: Boolean = true,
        /** Wall-clock millis the media connected (0 until CONNECTED). Drives call timers. */
        val connectedAtMs: Long = 0L
    )

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
            if (callConnectedAtMs == 0L) callConnectedAtMs = System.currentTimeMillis()
            // Replace the incoming-ring notification with the ongoing-call notification
            // (same id): "call in progress" + an End control.
            notifier.notifyOngoingCall()
            _call.value = _call.value.copy(state = CallState.CONNECTED, connectedAtMs = callConnectedAtMs)
        }
        override fun onClosed() = endCall(notifyPeer = false)
    })

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
        callConnectedAtMs = 0L; callDeclinedByMe = false; callAcceptedByMe = false
        session.resetForNewCall()
        _minimized.value = false
        session.setVideoEnabled(video)
        s.launch {
            val contact = contactDao.byNodeId(peerNodeIdHex)
            _call.value = CallInfo(CallState.OUTGOING, peerNodeIdHex, contact?.displayName, isCaller = true, isVideo = video)
            session.create()
            session.addLocalTracks(video)
            val offer = session.createOffer()
            session.setLocalDescription(offer)
            codec.send(peerNodeIdHex, JSONObject()
                .put("kind", "offer")
                .put("video", video)
                .put("sdp", offer.description))
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
        s.launch {
            _call.value = _call.value.copy(state = CallState.CONNECTING)
            session.create()
            session.addLocalTracks(pendingOfferIsVideo)
            // Apply the remote offer + replay ICE the caller trickled before we accepted.
            session.applyRemoteDescription(SessionDescription.Type.OFFER, offerSdp)
            val answer = session.createAnswer()
            session.setLocalDescription(answer)
            codec.send(peer, JSONObject().put("kind", "answer").put("sdp", answer.description))
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
        when (inner.optString("kind")) {
            "offer" -> {
                remoteNodeIdHex = from
                pendingOfferSdp = inner.optString("sdp")
                pendingOfferIsVideo = inner.optBoolean("video", true)
                callConnectedAtMs = 0L; callDeclinedByMe = false; callAcceptedByMe = false
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
                session.applyRemoteDescription(SessionDescription.Type.ANSWER, inner.optString("sdp"))
                _call.value = _call.value.copy(state = CallState.CONNECTING)
            }
            "ice" -> {
                session.addOrBufferIce(
                    IceCandidate(inner.optString("mid"), inner.optInt("mlineindex"), inner.optString("candidate"))
                )
            }
            "bye" -> endCall(notifyPeer = false)
        }
    }

    private fun cleanup() {
        notifier.cancelCall()
        ringer.stop()
        ringTimeoutJob?.cancel()
        session.dispose()
        remoteNodeIdHex = null
        pendingOfferSdp = null
        pendingOfferIsVideo = true
        callAcceptedByMe = false
        _minimized.value = false
    }

    private companion object {
        /** Ring this long before giving up an unanswered incoming call (logged missed). */
        const val RING_TIMEOUT_MS = 45_000L
    }
}
