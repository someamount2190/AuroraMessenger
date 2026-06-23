package com.aura.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.db.MessageEntity
import com.aura.db.PairState
import com.aura.streak.Streaks
import com.aura.ui.theme.auroraGlass
import com.aura.ui.theme.glassEdgeLight
import kotlinx.coroutines.flow.SharedFlow

@Composable
internal fun ContactList(
    contacts: List<ContactRow>,
    onOpen: (String) -> Unit,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onCancel: (String) -> Unit,
    pulses: SharedFlow<String>,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(contacts.size, key = { contacts[it].contact.nodeIdHex }) { i ->
            val row = contacts[i]
            val contact = row.contact
            val hasUnread = row.unread > 0
            // Only verified ("active") and verifying contacts can be opened; pending
            // requests can't be tapped into a chat.
            val openable = contact.pairState == PairState.ACTIVE || contact.pairState == PairState.VERIFY

            // "Light through glass" sweep around this contact's box when a fresh
            // message arrives from them while the home screen is visible.
            var pulseTrigger by remember(contact.nodeIdHex) { mutableIntStateOf(0) }
            LaunchedEffect(contact.nodeIdHex) {
                pulses.collect { if (it == contact.nodeIdHex) pulseTrigger++ }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .auroraGlass()                       // glossy frosted panel (dark mode)
                    .glassEdgeLight(pulseTrigger)        // edge light on incoming message
                    .then(if (openable) Modifier.clickable { onOpen(contact.nodeIdHex) } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        contact.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            contact.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            // The sender's row changes design when it has unread: name goes bold.
                            fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (row.streak >= Streaks.MIN_DISPLAY_DAYS) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "🔥 ${row.streak}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    val subtitle = when (contact.pairState) {
                        PairState.REQUESTED -> "Awaiting handshake…"
                        PairState.INCOMING  -> "Wants to connect"
                        PairState.VERIFY    -> "Tap to verify codes"
                        else                -> previewOf(row.lastMessage, contact.nodeIdHex)
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        // Unread → emphasized (darker + medium weight); missed call → red.
                        fontWeight = if (hasUnread || contact.pairState != PairState.ACTIVE) FontWeight.Medium else FontWeight.Normal,
                        color = when {
                            contact.pairState == PairState.INCOMING -> MaterialTheme.colorScheme.primary
                            contact.pairState != PairState.ACTIVE   -> MaterialTheme.colorScheme.onSurfaceVariant
                            row.lastMessage?.callStatus == "missed"  -> MaterialTheme.colorScheme.error
                            hasUnread -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                // Trailing area depends on the pairing state.
                when (contact.pairState) {
                    PairState.INCOMING -> {
                        TextButton(onClick = { onReject(contact.nodeIdHex) }) { Text("Reject") }
                        Button(onClick = { onAccept(contact.nodeIdHex) }) { Text("Accept") }
                    }
                    PairState.REQUESTED -> {
                        TextButton(onClick = { onCancel(contact.nodeIdHex) }) { Text("Cancel") }
                    }
                    else -> if (hasUnread) {
                        Spacer(Modifier.width(8.dp))
                        UnreadBadge(row.unread)
                    }
                }
            }
        }
    }
}

/** Preview text for the home conversation row from the last message. */
private fun previewOf(m: MessageEntity?, nodeIdHex: String): String {
    if (m == null) return "Tap to start chatting"
    val mine = if (m.fromMe) "You: " else ""
    return when (m.type) {
        "call"  -> m.body                       // "Missed call", "Incoming call · 2:34", …
        "image" -> "$mine📷 Photo"
        "video" -> "$mine📹 Video"
        "audio" -> "$mine🎤 Voice message"
        else    -> "$mine${m.body}"
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}
