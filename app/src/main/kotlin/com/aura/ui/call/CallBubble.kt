package com.aura.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aura.call.CallController
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

/**
 * A small floating window shown over the app while a call runs minimized (the user
 * pressed Back to the main menu). It renders the remote camera (the other phone's
 * video) when present, falls back to a labelled tile for a voice call, and is
 * draggable. Tapping it returns to the full call screen via [onExpand].
 */
@Composable
fun CallBubble(callManager: CallController, onExpand: () -> Unit) {
    val remote by callManager.remoteVideo.collectAsState()
    val call by callManager.call.collectAsState()
    var drag by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                .size(120.dp, 168.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectDragGestures { change, amount -> change.consume(); drag += amount }
                }
                .clickable { onExpand() },
            contentAlignment = Alignment.Center
        ) {
            val track = remote
            if (track != null) {
                BubbleVideo(track, callManager.eglBase.eglBaseContext, Modifier.fillMaxSize())
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = Color(0xFF4CD97B))
                    Text(
                        call.peerName ?: "Call",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        "Tap to return",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun BubbleVideo(
    track: VideoTrack,
    eglContext: org.webrtc.EglBase.Context,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            setZOrderMediaOverlay(true)
            init(eglContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
        }
    }
    // release() must run after removeSink() (see VideoRenderer in CallScreen): declare
    // the release effect first so it is disposed last, avoiding a frame being delivered
    // to a released renderer (native abort).
    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }
    DisposableEffect(track, renderer) {
        track.addSink(renderer)
        onDispose { track.removeSink(renderer) }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}
