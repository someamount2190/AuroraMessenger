package com.aura.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.server.RendezvousServerController
import kotlinx.coroutines.delay

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
