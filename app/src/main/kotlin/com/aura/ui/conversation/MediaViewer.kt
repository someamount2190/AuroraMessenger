package com.aura.ui.conversation

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.view.Surface
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aura.db.MessageEntity
import com.aura.media.ByteArrayMediaDataSource
import kotlinx.coroutines.delay

/**
 * Fullscreen image viewer rendered as an in-window overlay (NOT a Dialog) so it
 * stays inside the Activity's FLAG_SECURE window — a separate Dialog window
 * would not inherit the screenshot block. Basic buttons: close, save, share.
 */
@Composable
internal fun FullscreenImageViewer(
    message: MessageEntity,
    loadMedia: suspend (String) -> ByteArray?,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    val path = message.mediaPath
    val bytes by produceState<ByteArray?>(initialValue = null, path) {
        value = if (path != null) loadMedia(path) else null
    }
    val isVideo = message.type == "video"
    val bmp = remember(bytes, isVideo) {
        if (!isVideo) bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onClose() }
    ) {
        val b = bytes
        when {
            isVideo && b != null -> VideoPlayer(
                bytes = b,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth()
            )
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = message.body,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            else -> CircularProgressIndicator(
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Top bar: close (left), share + save (right).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
            IconButton(onClick = onSave) {
                Icon(Icons.Default.Save, contentDescription = "Save to gallery",
                    tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

/**
 * In-window video player for the fullscreen viewer. Plays the decrypted blob straight
 * from memory (no plaintext temp file) onto a [TextureView], which stays inside the
 * Activity's FLAG_SECURE window so frames are screenshot-blocked. Auto-plays once the
 * surface is ready; tapping the video toggles play/pause (and consumes the tap so it
 * doesn't dismiss the viewer). The [MediaPlayer] is released when the viewer closes.
 */
@Composable
private fun VideoPlayer(bytes: ByteArray, modifier: Modifier = Modifier) {
    val player = remember { MediaPlayer() }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var ratio by remember { mutableStateOf(0f) }     // 0 = unknown until prepared
    var durationMs by remember { mutableStateOf(0) }
    var positionMs by remember { mutableStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause(); playing = false
        } else {
            // Replay from the start if we're sitting at the end.
            if (durationMs > 0 && positionMs >= durationMs) { player.seekTo(0); positionMs = 0 }
            runCatching { player.start(); playing = true }
        }
    }

    DisposableEffect(bytes) {
        runCatching {
            player.reset()
            player.setDataSource(ByteArrayMediaDataSource(bytes))
            player.isLooping = false
            player.setOnPreparedListener { mp ->
                ratio = if (mp.videoHeight > 0) mp.videoWidth.toFloat() / mp.videoHeight else 16f / 9f
                durationMs = mp.duration.coerceAtLeast(0)
                prepared = true
            }
            player.setOnCompletionListener {
                playing = false
                positionMs = durationMs
            }
            player.prepareAsync()
        }
        onDispose { runCatching { player.release() } }
    }

    // Attach the surface and auto-start once both the player and surface are ready.
    LaunchedEffect(surface, prepared) {
        val s = surface
        if (prepared && s != null) {
            runCatching {
                player.setSurface(s)
                if (!player.isPlaying && !playing) { player.start(); playing = true }
            }
        }
    }

    // Advance the seek bar while playing (paused/scrubbing freezes it).
    LaunchedEffect(playing, scrubbing) {
        while (playing && !scrubbing) {
            runCatching { positionMs = player.currentPosition }
            delay(200)
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = if (ratio > 0f) Modifier.fillMaxWidth().aspectRatio(ratio)
                       else Modifier.fillMaxWidth(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            surface = Surface(st)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            surface = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            }
        )

        // Tapping the frame toggles play/pause and consumes the tap so it doesn't
        // dismiss the viewer; the bottom bar gives explicit transport controls.
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { togglePlay() }
        )

        // Big centre play affordance while paused.
        if (!playing) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "Play",
                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(72.dp)
            )
        }

        // Transport bar: play/pause + scrubbable seek slider + elapsed / total time.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { togglePlay() }) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = androidx.compose.ui.graphics.Color.White
                )
            }
            Text(
                formatDuration(positionMs.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White
            )
            Slider(
                value = positionMs.coerceIn(0, durationMs).toFloat(),
                onValueChange = { v -> scrubbing = true; positionMs = v.toInt() },
                onValueChangeFinished = {
                    runCatching { player.seekTo(positionMs) }
                    scrubbing = false
                },
                valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text(
                formatDuration(durationMs.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White
            )
        }
    }
}
