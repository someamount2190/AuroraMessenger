package com.aura.call

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

/**
 * The WebRTC engine for a single call, extracted from [CallController]: the
 * PeerConnectionFactory, the peer connection + its observer, local audio/video
 * tracks, the camera capturer, SDP create/apply, ICE buffering, and one-time
 * disposal. [CallController] owns the call *state machine*; this owns the *media*.
 *
 * The threading and disposal invariants that previously caused process aborts are
 * preserved verbatim:
 *  - observer callbacks hop onto the app coroutine scope (never touch state inline on
 *    WebRTC's native signaling thread → no JNI `RTC_CHECK(!ExceptionCheck())` abort);
 *  - the WebRTC objects are disposed exactly once via an atomic CAS guard, peer
 *    connection first.
 */
class WebRtcSession(
    private val context: Context,
    private val scopeProvider: () -> CoroutineScope?,
    private val listener: Listener
) {
    /** Events the call state machine reacts to. Invoked on the app scope. */
    interface Listener {
        suspend fun onLocalIceCandidate(candidate: IceCandidate)
        fun onConnected()
        fun onClosed()
    }

    val eglBase: EglBase by lazy { EglBase.create() }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    // True once the remote SDP is applied — ICE candidates may only be added after that.
    // Earlier-arriving remote candidates are buffered here and replayed by flushPendingIce().
    @Volatile private var remoteDescSet: Boolean = false
    private val pendingRemoteIce = java.util.Collections.synchronizedList(ArrayList<IceCandidate>())

    // Guards one-time disposal — endCall()/dispose() can fire from several racing paths
    // (local End, remote "bye", ICE FAILED/DISCONNECTED) and disposing the same native
    // PeerConnection twice aborts with "Pure virtual function called!". Reset on create().
    private val disposed = java.util.concurrent.atomic.AtomicBoolean(true)

    private val _videoEnabled = MutableStateFlow(true)
    val videoEnabled: StateFlow<Boolean> = _videoEnabled

    // Tracks exposed as state (not one-shot callbacks) so the UI renders them whenever it
    // composes, regardless of whether the track existed before or after the screen appeared.
    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo
    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo

    fun setVideoEnabled(enabled: Boolean) { _videoEnabled.value = enabled }

    /** Clear ICE-buffering state before a new call's signaling begins. */
    fun resetForNewCall() {
        remoteDescSet = false
        pendingRemoteIce.clear()
    }

    fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        // Software-only VP8/VP9 codecs (libvpx, bundled in the webrtc-sdk): maximally
        // portable, verified for 1:1 voice/video, negligible CPU at that scale. Hardware
        // codecs can be re-enabled later. (The historical "codec" crash was actually a
        // notification exception escaping an observer callback — fixed via the scope hop
        // below and Notifier.safeNotify, not by the codec choice.)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()
    }

    /** Create the peer connection (and arm the one-time disposal guard). */
    fun create() {
        ensureFactory()
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        disposed.set(false)   // a live peer connection now exists to tear down exactly once
        peerConnection = factory?.createPeerConnection(config, object : SimplePcObserver() {
            override fun onIceCandidate(candidate: IceCandidate) {
                scopeProvider()?.launch { listener.onLocalIceCandidate(candidate) }
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                // CRITICAL: WebRTC delivers callbacks on its native signaling thread via JNI.
                // A Java exception escaping here aborts the process (RTC_CHECK(!ExceptionCheck())).
                // Never touch app state inline — hop onto the app scope where exceptions stay
                // contained and teardown can't deadlock the delivering thread.
                scopeProvider()?.launch {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> listener.onConnected()
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> listener.onClosed()
                        else -> {}
                    }
                }
            }
            // UNIFIED_PLAN delivers remote media through onAddTrack (the receiver's track),
            // not Plan-B onAddStream — onAddStream is kept only as a defensive fallback.
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                (receiver?.track() as? VideoTrack)?.let { _remoteVideo.value = it }
            }
            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { _remoteVideo.value = it }
            }
        })
    }

    fun addLocalTracks(video: Boolean) {
        val f = factory ?: return
        // Audio (always)
        localAudioTrack = f.createAudioTrack("audio0", f.createAudioSource(MediaConstraints()))
        peerConnection?.addTrack(localAudioTrack)

        // Video (front camera) — skipped entirely for a voice-only call.
        if (!video) return
        val capturer = createCameraCapturer() ?: return
        videoCapturer = capturer
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val src = f.createVideoSource(capturer.isScreencast).also { videoSource = it }
        capturer.initialize(surfaceHelper, context, src.capturerObserver)
        capturer.startCapture(1280, 720, 30)
        localVideoTrack = f.createVideoTrack("video0", src).apply { setEnabled(true) }
        _localVideo.value = localVideoTrack
        peerConnection?.addTrack(localVideoTrack)
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return null
        // A real events handler instead of null: camera-open failures used to be swallowed
        // silently, which is what made a broken first call invisible. Log them.
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

    suspend fun createOffer(): SessionDescription = suspendSdp { obs ->
        peerConnection?.createOffer(obs, MediaConstraints())
    }

    suspend fun createAnswer(): SessionDescription = suspendSdp { obs ->
        peerConnection?.createAnswer(obs, MediaConstraints())
    }

    fun setLocalDescription(sdp: SessionDescription) {
        peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
    }

    /** Apply the remote SDP, mark the description set, and replay any buffered ICE. */
    fun applyRemoteDescription(type: SessionDescription.Type, sdp: String) {
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), SessionDescription(type, sdp))
        remoteDescSet = true
        flushPendingIce()
    }

    /**
     * Add a remote ICE candidate now if the peer connection exists and its remote
     * description is set; otherwise buffer it (candidates routinely arrive before the
     * callee accepts or before the remote SDP is applied). [flushPendingIce] replays them.
     */
    fun addOrBufferIce(candidate: IceCandidate) {
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

    /** @return true when now muted. */
    fun toggleMute(): Boolean {
        val track = localAudioTrack ?: return false
        track.setEnabled(!track.enabled())
        return !track.enabled()
    }

    /** @return true when video is now OFF. */
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

    /** Dispose the WebRTC objects exactly once (CAS guard) and reset media state. */
    fun dispose() {
        // Peer connection is disposed FIRST so its senders release the tracks before we
        // dispose the capturer/source — disposing a source still referenced by a live
        // sender aborts inside libjingle.
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
        _localVideo.value = null
        _remoteVideo.value = null
        _videoEnabled.value = true
        remoteDescSet = false
        pendingRemoteIce.clear()
    }
}
