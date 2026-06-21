@file:OptIn(ExperimentalMaterial3Api::class)

package com.aura.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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

/** Group info + admin management (rename, add/remove members, leave). Same VM as the conversation. */
@Composable
fun GroupInfoScreen(
    onBack: () -> Unit,
    viewModel: GroupConversationViewModel = hiltViewModel()
) {
    val group by viewModel.group.collectAsState()
    val members by viewModel.members.collectAsState()
    val names by viewModel.memberNames.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState()
    val myNodeId by viewModel.myNodeId.collectAsState()
    val addable by viewModel.addableContacts.collectAsState()

    var showRename by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffectExit(group?.active, onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group info") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text(group?.name ?: "Group", style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${members.size} members",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isAdmin) {
                Spacer(Modifier.width(8.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showRename = true }) { Text("Rename") }
                    OutlinedButton(onClick = { showAdd = true }) { Text("Add people") }
                }
            } else {
                Spacer(Modifier.padding(vertical = 8.dp))
            }

            members.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(names[m.nodeId] ?: m.nodeId.take(8), modifier = Modifier.weight(1f))
                    if (viewModel.isAdminRole(m.role)) {
                        Text("Admin", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (isAdmin && m.nodeId != myNodeId && !viewModel.isAdminRole(m.role)) {
                        IconButton(onClick = { viewModel.removeMember(m.nodeId) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove")
                        }
                    }
                }
            }

            Spacer(Modifier.padding(vertical = 12.dp))
            TextButton(onClick = { viewModel.leave(); onBack() }) {
                Text("Leave group", color = MaterialTheme.colorScheme.error)
            }
            if (isAdmin) {
                Text(
                    "You're the admin — leaving isn't available yet.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showRename) {
        var newName by remember { mutableStateOf(group?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRename = false },
            confirmButton = {
                TextButton(onClick = { viewModel.rename(newName); showRename = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
            title = { Text("Rename group") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true) }
        )
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            confirmButton = { TextButton(onClick = { showAdd = false }) { Text("Done") } },
            title = { Text("Add people") },
            text = {
                Column {
                    if (addable.isEmpty()) Text("No more contacts to add.")
                    addable.forEach { c ->
                        Text(
                            c.displayName,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { viewModel.addMember(c.nodeIdHex) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        )
    }
}

/** Pop back when the group goes inactive (we left or were removed). */
@Composable
private fun LaunchedEffectExit(active: Boolean?, onBack: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(active) { if (active == false) onBack() }
}
