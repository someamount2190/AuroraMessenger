@file:OptIn(ExperimentalMaterial3Api::class)

package com.aura.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.db.MessageEntity

/**
 * Group conversation: a text thread with per-sender labels on incoming messages. v1 is text only
 * (media/voice/reactions/calls are deliberately absent — the engine doesn't do them for groups yet).
 */
@Composable
fun GroupConversationScreen(
    onBack: () -> Unit,
    onOpenInfo: (String) -> Unit,
    viewModel: GroupConversationViewModel = hiltViewModel()
) {
    val group by viewModel.group.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val names by viewModel.memberNames.collectAsState()
    val members by viewModel.members.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        viewModel.markRead()
    }
    // A group we've left / been removed from disappears — exit when its row goes inactive.
    LaunchedEffect(group) { if (group?.active == false) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable { onOpenInfo(viewModel.groupId) }) {
                        Text(group?.name ?: "Group", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${members.size} members",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { onOpenInfo(viewModel.groupId) }) {
                        Icon(Icons.Filled.Info, contentDescription = "Group info")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 4
                )
                IconButton(
                    onClick = { viewModel.send(input); input = "" },
                    enabled = input.isNotBlank()
                ) { Icon(Icons.Filled.Send, contentDescription = "Send") }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp)
        ) {
            items(messages, key = { it.id }) { m ->
                GroupBubble(
                    message = m,
                    isMine = m.fromMe,
                    senderName = if (!m.fromMe) (names[m.senderNodeId] ?: viewModel.nameOf(m.senderNodeId.orEmpty())) else null
                )
            }
        }
    }
}

@Composable
private fun GroupBubble(message: MessageEntity, isMine: Boolean, senderName: String?) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        if (!isMine && !senderName.isNullOrBlank()) {
            Text(
                senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 1.dp)
            )
        }
        Surface(
            color = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                message.body,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
