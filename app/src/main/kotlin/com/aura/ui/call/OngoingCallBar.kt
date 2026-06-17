package com.aura.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.call.CallController
import kotlinx.coroutines.delay

/**
 * A docked "ongoing call" bar shown at the top of the app while a call runs minimized
 * (the user pressed Back) — the Messenger / Viber style green bar. Works for both voice
 * and video. Shows the contact and a live timer (or call status before it connects);
 * tapping the bar returns to the full call screen, and the End button hangs up.
 */
@Composable
fun OngoingCallBar(callManager: CallController, onExpand: () -> Unit) {
    val call by callManager.call.collectAsState()

    // Tick once a second so the timer stays current.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedTick { nowMs = System.currentTimeMillis() }

    val label = when {
        call.connectedAtMs > 0L -> formatDuration(nowMs - call.connectedAtMs)
        call.state == CallController.CallState.OUTGOING   -> "Calling…"
        call.state == CallController.CallState.CONNECTING -> "Connecting…"
        call.state == CallController.CallState.INCOMING   -> "Incoming call"
        else -> "On call"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1B7F4B))   // call green, matches the ongoing-call notification feel
            .statusBarsPadding()
            .clickable { onExpand() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (call.isVideo) Icons.Default.Videocam else Icons.Default.Call,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                (call.peerName ?: "Call") + "  ·  " + label,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Tap to return",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE53935)),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = { callManager.endCall() }) {
                    Icon(Icons.Default.CallEnd, contentDescription = "End call", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Runs [onTick] roughly once a second while composed. */
@Composable
private fun LaunchedTick(onTick: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { onTick(); delay(1000) }
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
