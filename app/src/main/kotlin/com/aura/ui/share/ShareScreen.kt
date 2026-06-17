package com.aura.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.media.MediaTransfer
import com.aura.share.ShareIntentBus
import com.aura.transport.MessageSender
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ShareViewModel @Inject constructor(
    contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val mediaTransfer: MediaTransfer,
    private val messageSender: MessageSender,
    private val shareIntentBus: ShareIntentBus
) : ViewModel() {

    val contacts: StateFlow<List<ContactEntity>> = contactDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pending = shareIntentBus.pending

    /** Send the pending shared content to [contactNodeIdHex], then clear the share. */
    fun shareTo(contactNodeIdHex: String, onDone: (String) -> Unit) {
        val share = shareIntentBus.pending.value ?: return
        viewModelScope.launch {
            if (share.isMedia) {
                share.uris.forEach { uri -> mediaTransfer.sendMedia(contactNodeIdHex, uri, share.mediaType) }
            } else if (!share.text.isNullOrBlank()) {
                messageDao.insert(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        contactNodeIdHex = contactNodeIdHex,
                        fromMe = true,
                        body = share.text.trim(),
                        timestampMs = System.currentTimeMillis(),
                        status = "pending"
                    )
                )
                messageSender.flushPending()
            }
            shareIntentBus.consume()
            onDone(contactNodeIdHex)
        }
    }

    fun cancel() = shareIntentBus.consume()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    onShared: (String) -> Unit,
    onCancel: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel()
) {
    val contacts by viewModel.contacts.collectAsState()
    val pending by viewModel.pending.collectAsState()

    val summary = when (val p = pending) {
        null            -> ""
        else            -> if (p.isMedia) "${p.uris.size} ${p.mediaType}(s)" else "\"${p.text?.take(60) ?: ""}\""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share with…") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.cancel(); onCancel() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (summary.isNotEmpty()) {
                Text(
                    "Sharing $summary",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            if (contacts.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("No contacts yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pair with someone first, then you can share to them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    items(contacts) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.shareTo(contact.nodeIdHex, onShared) }
                                .padding(vertical = 12.dp),
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
                            Text(contact.displayName, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
}
