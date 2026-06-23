package com.aura.ui.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.aura.db.MessageEntity
import com.aura.media.ByteArrayMediaDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val REACTION_EMOJI = listOf("❤️", "😂", "👍", "😮", "😢", "🔥")

/** A centered call-log entry in the conversation (missed calls in red). */
@Composable
internal fun CallLogRow(message: MessageEntity) {
    val missed = message.callStatus == "missed"
    val container = if (missed) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (missed) MaterialTheme.colorScheme.onErrorContainer
                  else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        androidx.compose.material3.Surface(shape = RoundedCornerShape(16.dp), color = container) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(message.body, style = MaterialTheme.typography.bodySmall, color = content)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    message: MessageEntity,
    progress: Float?,
    isPlaying: Boolean,
    loadMedia: suspend (String) -> ByteArray?,
    onOpenViewer: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onTogglePlay: () -> Unit
) {
    val onColor = if (message.fromMe) MaterialTheme.colorScheme.onPrimary
                  else MaterialTheme.colorScheme.onSurface
    var menuOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.fromMe) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start) {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = if (message.fromMe) 16.dp else 4.dp,
                            bottomEnd = if (message.fromMe) 4.dp else 16.dp
                        )
                    )
                    .background(
                        if (message.fromMe) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .combinedClickable(
                        onClick = { if (message.type == "image" || message.type == "video") onOpenViewer() },
                        onLongClick = { menuOpen = true }
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // Quoted reply block.
                message.replyPreview?.let { quote ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(onColor.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(quote, style = MaterialTheme.typography.bodySmall,
                            color = onColor.copy(alpha = 0.8f), maxLines = 1)
                    }
                    Spacer(Modifier.height(4.dp))
                }

                when (message.type) {
                    "image", "video" -> MediaContent(message, progress, loadMedia, onOpenViewer, onColor)
                    "audio"          -> AudioContent(message, isPlaying, onTogglePlay, onColor)
                    else             -> Text(message.body, color = onColor,
                        style = MaterialTheme.typography.bodyMedium)
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.End)) {
                    message.expiresAtMs?.let { expiry ->
                        val remaining = expiry - System.currentTimeMillis()
                        if (remaining > 0) {
                            Text("⏱ ${formatRemaining(remaining)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = onColor.copy(alpha = 0.7f))
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    if (message.fromMe) {
                        Text(
                            text = when (message.status) {
                                "delivered" -> "✓✓"; "sent" -> "✓"; else -> "…"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = onColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Reaction chips below the bubble.
            val reactions = listOfNotNull(message.theirReaction, message.myReaction)
            if (reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(reactions.joinToString(" "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Long-press menu: reaction row + Reply.
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                REACTION_EMOJI.forEach { emoji ->
                    Text(
                        emoji,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clip(CircleShape)
                            .clickable { menuOpen = false; onReact(emoji) }
                            .padding(4.dp)
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Reply") },
                onClick = { menuOpen = false; onReply() }
            )
        }
    }
}

@Composable
private fun AudioContent(
    message: MessageEntity,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onColor: androidx.compose.ui.graphics.Color
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        IconButton(onClick = onTogglePlay, modifier = Modifier.size(36.dp)) {
            Icon(
                if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Stop" else "Play",
                tint = onColor
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = onColor.copy(alpha = 0.7f))
        Spacer(Modifier.width(8.dp))
        Text(
            formatDuration(message.durationMs ?: 0L),
            style = MaterialTheme.typography.bodyMedium,
            color = onColor
        )
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

@Composable
private fun MediaContent(
    message: MessageEntity,
    progress: Float?,
    loadMedia: suspend (String) -> ByteArray?,
    onOpenViewer: () -> Unit,
    onColor: androidx.compose.ui.graphics.Color
) {
    val path = message.mediaPath
    val bytes by produceState<ByteArray?>(initialValue = null, path) {
        value = if (path != null) loadMedia(path) else null
    }

    Box(contentAlignment = Alignment.Center) {
        // Images decode directly; videos render their first frame as the thumbnail.
        // Both are off the main thread (video frame extraction in particular is heavy).
        val bmpState by produceState<Bitmap?>(initialValue = null, bytes, message.type) {
            val b = bytes
            value = when {
                b == null -> null
                message.type == "video" -> withContext(Dispatchers.IO) { videoFrame(b) }
                else -> withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(b, 0, b.size) }
            }
        }
        val bmp = bmpState   // local capture so the null check smart-casts
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = message.body,
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenViewer() },
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.size(200.dp).clip(RoundedCornerShape(8.dp))
                    .background(onColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (progress != null && progress < 1f) {
                    CircularProgressIndicator(progress = { progress }, color = onColor)
                } else {
                    Text(message.body, color = onColor)
                }
            }
        }
        if (message.type == "video" && bmp != null) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "Video",
                tint = onColor,
                modifier = Modifier.size(48.dp)
            )
        }
    }
    Text(
        "Tap to view",
        style = MaterialTheme.typography.labelSmall,
        color = onColor.copy(alpha = 0.6f)
    )
}

/** First decodable frame of a video blob, for the conversation thumbnail. */
private fun videoFrame(bytes: ByteArray): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(ByteArrayMediaDataSource(bytes))
        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun formatRemaining(ms: Long): String {
    val totalSec = ms / 1000
    return when {
        totalSec < 60      -> "${totalSec}s"
        totalSec < 3600    -> "${totalSec / 60}m"
        totalSec < 86400   -> "${totalSec / 3600}h"
        else               -> "${totalSec / 86400}d"
    }
}
