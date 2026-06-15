package com.aura.call

import android.content.Context
import android.util.Base64
import com.aura.crypto.RatchetManager
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.identity.IdentityManager
import java.util.UUID
import com.aura.network.RendezvousClient
import com.aura.network.SyncEngine
import com.aura.notify.Notifier
import com.aura.settings.AuroraSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 7: encrypted WebRTC video calls.
 *
 * Signaling (SDP offer/answer + ICE candidates) is sealed with the
 * conversation's shared secret (XChaCha20-Poly1305) and relayed through the
 * rendezvous server's /signal queue — the server sees only ciphertext. Once
 * ICE connects, media flows peer-to-peer and never touches the server again.
 * No call logs, no recording.
 */
@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val ratchet: RatchetManager,
    private val rendezvousClient: RendezvousClient,
    private val syncEngine: SyncEngine,
    private val settings: AuroraSettings,
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

    val eglBase: EglBase by lazy { EglBase.create() }

    private val _call = MutableStateFlow(CallInfo(CallState.IDLE))
    val call: StateFlow<CallInfo> = _call

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var scope: CoroutineScope? = null
    private var ringTimeoutJob: kotlinx.coroutines.Job? = null
    private var remoteNodeIdHex: String? = null
    private var pendingOfferSdp: String? = null   // held while INCOMING, applied on accept
    private var pendingOfferIsVideo: Boolean = true
    // True once the remote SDP (offer on the callee, answer on the caller) is applied —
    // ICE candidates may only be added after that. Remote candidates that arrive earlier
    // (very common: the caller trickles candidates before the callee has even accepted)
    // are buffered here and replayed by flushPendingIce().
    @Volatile private var remoteDescSet: Boolean = false
    private val pendingRemoteIce = java.util.Collections.synchronizedList(ArrayList<IceCandidate>())

    // Guards one-time disposal of the WebRTC objects. endCall()/cleanup() can fire from
    // several paths at once — the local End button, the remote "bye", and an ICE
    // FAILED/DISCONNECTED observer callback — and disposing the same native
    // PeerConnection twice aborts the process with "Pure virtual function called!".
    // Reset to false whenever a new peer connection is created.
    private val disposed = java.util.concurrent.atomic.AtomicBoolean(true)

    // Whether the local camera is currently sending. Drives the in-call video
    // toggle button and hides the self-view when the camera is off.
    private val _videoEnabled = MutableStateFlow(true)
    val videoEnabled: StateFlow<Boolean> = _videoEnabled

    // Minimized = the call is still running but the full call screen is dismissed (the
    // user pressed Back to the main menu). The app shows a floating bubble instead, and
    // an ongoing notification carries an End control. Reset to false whenever a call
    // starts/arrives or ends, so a new call always opens full-screen.
    private val _minimized = MutableStateFlow(false)
    val minimized: StateFlow<Boolean> = _minimized
    fun minimize() { _minimized.value = true }
    fun expand() { _minimized.value = false }

    // Call-log bookkeeping (each device logs its own view of the call into the chat).
    private var callConnectedAtMs: Long = 0L      // >0 once media connected (answered)
    private var callDeclinedByMe: Boolean = false
    private var callAcceptedByMe: Boolean = false // we tapped Accept on an incoming call

    // Video tracks are exposed as state (not one-shot callbacks) so the call UI
    // renders them whenever it composes, regardless of whether the track was
    // created before or after the screen appeared. This is what previously made
    // local self-view show on only one side, depending on timing.
    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo
    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo

    /** Register the call signal handler with the sync engine (once, at startup). */
    fun init(appScope: CoroutineScope) {
        scope = appScope
        ensureFactory()
        syncEngine.registerSignalHandler("call") { json ->
            handleSignal(json)
        }
    }

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        // Software-only VP8/VP9 codecs (libvpx, bundled in the webrtc-sdk).
        //
        // NOTE: the call crash this once seemed to address was actually a Java
        // exception escaping a WebRTC observer callback into native code (a
        // disqualified CallStyle notification posted on the signaling thread →
        // RTC_CHECK(!ExceptionCheck()) → abort); that is fixed elsewhere (see
        // onIceConnectionChange and Notifier.safeNotify). Software codecs are kept
        // because they're maximally portable and verified working for 1:1 voice and
        // video, with negligible CPU cost at that scale. Hardware codecs
        // (DefaultVideoEncoderFactory) can be re-enabled later for efficiency.
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()
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
        remoteDescSet = false; pendingRemoteIce.clear()
        _minimized.value = false
        _videoEnabled.value = video
        s.launch {
            val contact = contactDao.byNodeId(peerNodeIdHex)
            _call.value = CallInfo(CallState.OUTGOING, peerNodeIdHex, contact?.displayName, isCaller = true, isVideo = video)
            createPeerConnection(peerNodeIdHex, isCaller = true)
            createLocalTracks(video)
            val offer = createOffer()
            peerConnection?.setLocalDescription(SimpleSdpObserver(), offer)
            sendSignal(peerNodeIdHex, JSONObject()
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
        _videoEnabled.value = pendingOfferIsVideo
        s.launch {
            _call.value = _call.value.copy(state = CallState.CONNECTING)
            createPeerConnection(peer, isCaller = false)
            createLocalTracks(pendingOfferIsVideo)
            peerConnection?.setRemoteDescription(
                SimpleSdpObserver(),
                SessionDescription(SessionDescription.Type.OFFER, offerSdp)
            )
            // Remote offer applied: replay ICE candidates the caller trickled before we
            // accepted (buffered because no peer connection existed then).
            remoteDescSet = true
            flushPendingIce()
            val answer = createAnswer()
            peerConnection?.setLocalDescription(SimpleSdpObserver(), answer)
            sendSignal(peer, JSONObject().put("kind", "answer").put("sdp", answer.description))
        }
    }

    fun declineCall() {
        val peer = remoteNodeIdHex
        callDeclinedByMe = true
        scope?.launch { peer?.let { sendSignal(it, JSONObject().put("kind", "bye")) } }
        endCall(notifyPeer = false)
    }

    fun endCall(notifyPeer: Boolean = true) {
        // Log this call into the chat before tearing down (reads live call state).
        logCall()
        val peer = remoteNodeIdHex
        if (notifyPeer && peer != null) {
            scope?.launch { sendSignal(peer, JSONObject().put("kind", "bye")) }
        }
        cleanup()
        _call.value = CallInfo(CallState.ENDED)
    }

    /**
     * Record this call as a message in its conversation — each device logs its own
     * view (outgoing/incoming, answered/missed/declined/no-answer). A missed
     * incoming call is left unread so it surfaces on the home screen. No-ops if no
     * call was actually in progress (so a stray endCall can't double-log).
     */
    private fun logCall() {
        val info = _call.value
        if (info.state == CallState.IDLE || info.state == CallState.ENDED) return
        val peer = info.peerNodeIdHex ?: remoteNodeIdHex ?: return
        val connected = callConnectedAtMs > 0L
        val durationMs = if (connected) System.currentTimeMillis() - callConnectedAtMs else null
        val status = when {
            connected            -> "answered"
            info.isCaller        -> "no_answer"
            callDeclinedByMe     -> "declined"
            callAcceptedByMe     -> "ended"     // we answered, but media never connected
            else                 -> "missed"
        }
        val entity = MessageEntity(
            id               = UUID.randomUUID().toString(),
            contactNodeIdHex = peer,
            fromMe           = info.isCaller,
            body             = callLabel(info.isCaller, status, durationMs),
            timestampMs      = System.currentTimeMillis(),
            status           = "delivered",
            type             = "call",
            callStatus       = status,
            durationMs       = durationMs,
            read             = status != "missed"   // a missed call stays unread
        )
        scope?.launch { messageDao.insert(entity) }
    }

    private fun callLabel(isCaller: Boolean, status: String, durationMs: Long?): String = when (status) {
        "answered"  -> (if (isCaller) "Outgoing call" else "Incoming call") +
                       (durationMs?.let { " · ${formatDuration(it)}" } ?: "")
        "missed"    -> "Missed call"
        "declined"  -> "Declined call"
        "no_answer" -> "No answer"
        "ended"     -> "Call ended"
        else        -> "Call"
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    // ── Camera / mute controls ─────────────────────────────────────────────

    fun toggleMute(): Boolean {
        val track = localAudioTrack ?: return false
        track.setEnabled(!track.enabled())
        return !track.enabled()  // returns true when now muted
    }

    /** Toggle the local camera. Returns true when video is now OFF. */
    fun toggleVideo(): Boolean {
        val track = localVideoTrack ?: return true
        val nowEnabled = !track.enabled()
        track.setEnabled(nowEnabled)
        _videoEnabled.value = nowEnabled
        return !nowEnabled
    }

    fun switchCamera() {
        (videoCapturer as? org.webrtc.CameraVideoCapturer)?.switchCamera(null)
    }

    // ── Signaling ──────────────────────────────────────────────────────────

    private suspend fun handleSignal(json: JSONObject) {
        val identity = identityManager.getOrCreate()
        val from = json.optString("from")
        if (json.optString("to") != identity.nodeId.toHex()) return
        val contact = contactDao.byNodeId(from) ?: return

        val sealed = Base64.decode(json.optString("sealed"), Base64.NO_WRAP)
        val n = json.optLong("n", -1)
        val aad = "aura-call-v1|$from|${identity.nodeId.toHex()}".toByteArray()
        val plaintext = ratchet.open(from, n, sealed, aad) ?: return
        val inner = try { JSONObject(String(plaintext)) } catch (e: Exception) { return }

        when (inner.optString("kind")) {
            "offer" -> {
                remoteNodeIdHex = from
                pendingOfferSdp = inner.optString("sdp")
                pendingOfferIsVideo = inner.optBoolean("video", true)
                callConnectedAtMs = 0L; callDeclinedByMe = false; callAcceptedByMe = false
                remoteDescSet = false; pendingRemoteIce.clear()
                _minimized.value = false
                _call.value = CallInfo(CallState.INCOMING, from, contact.displayName, isCaller = false, isVideo = pendingOfferIsVideo)
                // Surface the call over whatever is in the foreground (other app,
                // launcher, lock screen). No-op when Aurora is already on screen —
                // the in-app navigation handles that case.
                notifier.notifyIncomingCall()
                // Ring (looping ringtone + vibration) regardless of foreground state,
                // and auto-give-up as a missed call if it's never answered.
                ringer.start()
                ringTimeoutJob?.cancel()
                ringTimeoutJob = scope?.launch {
                    kotlinx.coroutines.delay(RING_TIMEOUT_MS)
                    if (_call.value.state == CallState.INCOMING) endCall(notifyPeer = true)
                }
            }
            "answer" -> {
                peerConnection?.setRemoteDescription(
                    SimpleSdpObserver(),
                    SessionDescription(SessionDescription.Type.ANSWER, inner.optString("sdp"))
                )
                // Remote answer applied: replay any ICE candidates buffered before it.
                remoteDescSet = true
                flushPendingIce()
                _call.value = _call.value.copy(state = CallState.CONNECTING)
            }
            "ice" -> {
                addOrBufferIce(
                    IceCandidate(inner.optString("mid"), inner.optInt("mlineindex"), inner.optString("candidate"))
                )
            }
            "bye" -> endCall(notifyPeer = false)
        }
    }

    private suspend fun sendSignal(peerNodeIdHex: String, inner: JSONObject) {
        val identity = identityManager.getOrCreate()
        contactDao.byNodeId(peerNodeIdHex) ?: return
        val aad = "aura-call-v1|${identity.nodeId.toHex()}|$peerNodeIdHex".toByteArray()
        val sealed = ratchet.sealNext(peerNodeIdHex, inner.toString().toByteArray(), aad) ?: return

        val payload = JSONObject()
            .put("type", "call")
            .put("from", identity.nodeId.toHex())
            .put("to", peerNodeIdHex)
            .put("n", sealed.n)
            .put("sealed", Base64.encodeToString(sealed.bytes, Base64.NO_WRAP))
        rendezvousClient.postSignal(settings.serverAddress.value, peerNodeIdHex, payload.toString())
    }

    /**
     * Add a remote ICE candidate now if the peer connection exists and its remote
     * description is set; otherwise buffer it. Candidates routinely arrive before the
     * callee accepts (no peer connection yet) or before the remote SDP is applied.
     * Dropping them — as the old code did — left ICE with no candidate pairs and the
     * call stuck on "connecting"; [flushPendingIce] replays the buffered ones.
     */
    private fun addOrBufferIce(candidate: IceCandidate) {
        val pc = peerConnection
        if (pc != null && remoteDescSet) pc.addIceCandidate(candidate)
        else pendingRemoteIce.add(candidate)
    }

    private fun flushPendingIce() {
        val pc = peerConnection ?: return
        synchronized(pendingRemoteIce) {
            pendingRemoteIce.forEach { pc.addIceCandidate(it) }
            pendingRemoteIce.clear()
        }
    }

    // ── WebRTC plumbing ────────────────────────────────────────────────────

    private fun createPeerConnection(peerNodeIdHex: String, isCaller: Boolean) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        disposed.set(false)   // a live peer connection now exists to tear down exactly once
        peerConnection = factory?.createPeerConnection(config, object : SimplePcObserver() {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope?.launch {
                    sendSignal(peerNodeIdHex, JSONObject()
                        .put("kind", "ice")
                        .put("candidate", candidate.sdp)
                        .put("mid", candidate.sdpMid)
                        .put("mlineindex", candidate.sdpMLineIndex))
                }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                // CRITICAL: WebRTC delivers observer callbacks on its native signaling
                // thread, marshalled through JNI. If anything we do here lets a Java
                // exception escape (e.g. the system rejecting a notification), WebRTC's
                // JNI bridge aborts the entire process via RTC_CHECK(!ExceptionCheck()).
                // So we never touch app state inline — hop onto our own coroutine scope,
                // where exceptions stay contained and tearing down the peer connection
                // can't deadlock against the thread that's delivering this callback.
                scope?.launch {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            if (callConnectedAtMs == 0L) callConnectedAtMs = System.currentTimeMillis()
                            // Replace the incoming-ring notification with the ongoing-call
                            // notification (same id): "call in progress" + an End control.
                            notifier.notifyOngoingCall()
                            _call.value = _call.value.copy(state = CallState.CONNECTED, connectedAtMs = callConnectedAtMs)
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> endCall(notifyPeer = false)
                        else -> {}
                    }
                }
            }
            // UNIFIED_PLAN delivers the remote media through onAddTrack (the receiver's
            // track), not the Plan-B onAddStream — relying on onAddStream is why remote
            // video never appeared. onAddStream is kept only as a defensive fallback.
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                (receiver?.track() as? VideoTrack)?.let { _remoteVideo.value = it }
            }
            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { _remoteVideo.value = it }
            }
        })
    }

    private fun createLocalTracks(video: Boolean = true) {
        val f = factory ?: return
        // Audio (always)
        localAudioTrack = f.createAudioTrack("audio0", f.createAudioSource(MediaConstraints()))
        peerConnection?.addTrack(localAudioTrack)

        // Video (front camera) — skipped entirely for a voice-only call.
        if (!video) return
        val capturer = createCameraCapturer() ?: return
        videoCapturer = capturer
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = f.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        capturer.startCapture(1280, 720, 30)
        localVideoTrack = f.createVideoTrack("video0", videoSource).apply {
            setEnabled(true)
        }
        _localVideo.value = localVideoTrack
        peerConnection?.addTrack(localVideoTrack)
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return null
        // A real events handler instead of null: camera-open failures (permission, busy,
        // disconnect) used to be swallowed silently, which is exactly what made a broken
        // first call invisible. Log them so they're diagnosable.
        val events = object : org.webrtc.CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(error: String?) { android.util.Log.w("AuroraCall", "camera error: $error") }
            override fun onCameraDisconnected() { android.util.Log.w("AuroraCall", "camera disconnected") }
            override fun onCameraFreezed(error: String?) { android.util.Log.w("AuroraCall", "camera frozen: $error") }
            override fun onCameraOpening(name: String?) {}
            override fun onFirstFrameAvailable() {}
            override fun onCameraClosed() {}
        }
        return enumerator.createCapturer(front, events)
    }

    private suspend fun createOffer(): SessionDescription = suspendSdp { obs ->
        peerConnection?.createOffer(obs, MediaConstraints())
    }

    private suspend fun createAnswer(): SessionDescription = suspendSdp { obs ->
        peerConnection?.createAnswer(obs, MediaConstraints())
    }

    private suspend fun suspendSdp(create: (SdpObserver) -> Unit): SessionDescription =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            create(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    if (cont.isActive) cont.resumeWith(Result.success(sdp))
                }
                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException(error)))
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            })
        }

    private fun cleanup() {
        notifier.cancelCall()
        ringer.stop()
        ringTimeoutJob?.cancel()
        // Dispose the WebRTC objects exactly once, even if cleanup() is reached from two
        // racing paths (local End + remote "bye" + ICE-failed callback). The CAS winner
        // captures the references and disposes; losers skip it. Peer connection is
        // disposed FIRST so its senders release the tracks before we dispose the
        // capturer/source — disposing a source still referenced by a live sender aborts
        // inside libjingle.
        if (disposed.compareAndSet(false, true)) {
            val pc = peerConnection; peerConnection = null
            val capt = videoCapturer; videoCapturer = null
            val src = videoSource; videoSource = null
            val helper = surfaceHelper; surfaceHelper = null
            pc?.dispose()
            try { capt?.stopCapture() } catch (e: Exception) {}
            capt?.dispose()
            src?.dispose()
            helper?.dispose()
        }
        localVideoTrack = null
        localAudioTrack = null
        remoteNodeIdHex = null
        pendingOfferSdp = null
        pendingOfferIsVideo = true
        _localVideo.value = null
        _remoteVideo.value = null
        _videoEnabled.value = true
        remoteDescSet = false
        callAcceptedByMe = false
        pendingRemoteIce.clear()
        _minimized.value = false
    }

    private companion object {
        /** Ring this long before giving up an unanswered incoming call (logged missed). */
        const val RING_TIMEOUT_MS = 45_000L
    }
}
