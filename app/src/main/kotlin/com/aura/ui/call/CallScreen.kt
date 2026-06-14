package com.aura.ui.call

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.aura.call.CallManager
import dagger.hilt.android.lifecycle.HiltViewModel
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import javax.inject.Inject

@HiltViewModel
class CallViewModel @Inject constructor(
    val callManager: CallManager
) : ViewModel()

@Composable
fun CallScreen(
    onCallEnded: () -> Unit,
    viewModel: CallViewModel = hiltViewModel()
) {
    val manager = viewModel.callManager
    val call by manager.call.collectAsState()

    // Tracks are observed as state, so the renderers attach whenever the screen
    // composes regardless of when the tracks were created (caller vs callee).
    val remoteTrack by manager.remoteVideo.collectAsState()
    val localTrack by manager.localVideo.collectAsState()
    val videoEnabled by manager.videoEnabled.collectAsState()
    var muted by remember { mutableStateOf(false) }

    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled best-effort; call proceeds with what's granted */ }

    LaunchedEffect(Unit) {
        audioPermission.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    LaunchedEffect(call.state) {
        if (call.state == CallManager.CallState.ENDED || call.state == CallManager.CallState.IDLE) {
            onCallEnded()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Remote (full screen)
        remoteTrack?.let { track ->
            VideoRenderer(
                track = track,
                eglContext = manager.eglBase.eglBaseContext,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Local preview (picture-in-picture). overlay = true so this SurfaceView
        // composites ABOVE the full-screen remote SurfaceView once the call connects
        // — otherwise the remote feed covers it and the self-view vanishes. Hidden
        // while the camera is off. Draggable anywhere on screen.
        val lt = localTrack
        if (lt != null && videoEnabled) {
            var dragOffset by remember { mutableStateOf(Offset.Zero) }
            VideoRenderer(
                track = lt,
                eglContext = manager.eglBase.eglBaseContext,
                mirror = true,
                overlay = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                    .size(110.dp, 160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                        }
                    }
            )
        }

        // Status / name overlay
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                call.peerName ?: "Call",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                when (call.state) {
                    CallManager.CallState.OUTGOING   -> "Calling…"
                    CallManager.CallState.INCOMING   -> "Incoming call"
                    CallManager.CallState.CONNECTING -> "Connecting…"
                    CallManager.CallState.CONNECTED  -> "Connected"
                    else -> ""
                },
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        // Controls
        if (call.state == CallManager.CallState.INCOMING) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RoundButton(Icons.Default.CallEnd, Color.Red, "Decline") { manager.declineCall() }
                RoundButton(Icons.Default.Call, Color(0xFF4CD97B), "Accept") { manager.acceptCall() }
            }
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RoundButton(
                    if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                    Color.DarkGray, "Mute"
                ) { muted = manager.toggleMute() }
                if (call.isVideo) {
                    RoundButton(
                        if (videoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        Color.DarkGray, "Video"
                    ) { manager.toggleVideo() }
                }
                RoundButton(Icons.Default.CallEnd, Color.Red, "End") { manager.endCall() }
                if (call.isVideo) {
                    RoundButton(Icons.Default.Cameraswitch, Color.DarkGray, "Flip") { manager.switchCamera() }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(color)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.IconButton(onClick = onClick) {
                Icon(icon, contentDescription = label, tint = Color.White)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun VideoRenderer(
    track: VideoTrack,
    eglContext: org.webrtc.EglBase.Context,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    overlay: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            // Must be set before the surface is created (before attach). Lifts this
            // surface above other SurfaceViews in the window, while staying below the
            // regular Compose controls drawn on top.
            if (overlay) setZOrderMediaOverlay(true)
            init(eglContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            setMirror(mirror)
        }
    }
    // Attach/detach the sink with the track, and release the GL renderer when the
    // composable leaves (call ends / PiP hidden) to avoid leaking the surface.
    DisposableEffect(track, renderer) {
        track.addSink(renderer)
        onDispose { track.removeSink(renderer) }
    }
    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}
