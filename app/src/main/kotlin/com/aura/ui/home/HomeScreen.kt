package com.aura.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.db.MessageEntity
import com.aura.db.PairState
import com.aura.server.RendezvousServerController
import com.aura.streak.Streaks
import com.aura.ui.theme.StatusGreen
import com.aura.ui.theme.auroraGlass
import com.aura.ui.theme.glassEdgeLight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddContact: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConversation: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val serverStatus by viewModel.serverController.status.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filteredContacts by viewModel.filteredContacts.collectAsState()
    val messageMatches by viewModel.messageMatches.collectAsState()
    val searching = searchQuery.isNotBlank()

    // Tick the server status card (node count / uptime) while it's visible.
    LaunchedEffect(serverStatus is RendezvousServerController.Status.Running) {
        while (serverStatus is RendezvousServerController.Status.Running) {
            viewModel.serverController.refreshStatus()
            delay(1000)
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("Aurora", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            // First-run hint: while there are no contacts, the QR button is labelled
            // so the user knows it's how you add someone. Icon-only once they have one.
            if (contacts.isEmpty() && !searching) {
                ExtendedFloatingActionButton(
                    onClick = onAddContact,
                    icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                    text = { Text("Add a contact") }
                )
            } else {
                FloatingActionButton(onClick = onAddContact) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Add contact")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            SearchField(query = searchQuery, onChange = viewModel::setSearchQuery)
            Spacer(Modifier.height(8.dp))

            if (searching) {
                SearchResults(
                    contacts = filteredContacts,
                    messages = messageMatches,
                    onOpen = onOpenConversation,
                    modifier = Modifier.weight(1f)
                )
            } else {
                (serverStatus as? RendezvousServerController.Status.Running)?.let { running ->
                    ServerStatusCard(running)
                    Spacer(Modifier.height(8.dp))
                }
                if (contacts.isEmpty()) {
                    EmptyState(modifier = Modifier.weight(1f))
                } else {
                    ContactList(
                        contacts = contacts,
                        onOpen = onOpenConversation,
                        onAccept = viewModel::acceptPair,
                        onReject = viewModel::rejectPair,
                        onCancel = viewModel::cancelPair,
                        pulses = viewModel.pulses,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search contacts and messages") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        shape = CircleShape
    )
}

@Composable
private fun SearchResults(
    contacts: List<ContactRow>,
    messages: List<MessageMatch>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (contacts.isEmpty() && messages.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "No matches",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        if (contacts.isNotEmpty()) {
            item { SectionHeader("Contacts") }
            items(contacts.size, key = { "c" + contacts[it].contact.nodeIdHex }) { i ->
                val c = contacts[i].contact
                SearchRow(
                    initial = c.displayName.take(1).uppercase(),
                    title = c.displayName,
                    subtitle = "Contact",
                    onClick = { onOpen(c.nodeIdHex) }
                )
            }
        }
        if (messages.isNotEmpty()) {
            item { SectionHeader("Messages") }
            items(messages.size, key = { "m" + messages[it].message.id }) { i ->
                val mm = messages[i]
                SearchRow(
                    initial = mm.contactName.take(1).uppercase(),
                    title = mm.contactName,
                    subtitle = mm.message.body,
                    onClick = { onOpen(mm.message.contactNodeIdHex) }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Column {
        Spacer(Modifier.height(8.dp))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

@Composable
private fun SearchRow(initial: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                initial,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ServerStatusCard(status: RendezvousServerController.Status.Running) {
    val uptimeSec = (System.currentTimeMillis() - status.startedAtMs) / 1000
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(StatusGreen)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Rendezvous server running",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "${status.localIp}:${status.port}  •  ${status.nodeCount} node(s)  •  up ${formatUptime(uptimeSec)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatUptime(seconds: Long): String = when {
    seconds < 60   -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else           -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

@Composable
private fun ContactList(
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

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "No conversations yet",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Add a contact by scanning their QR code.\nNo phone number needed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
