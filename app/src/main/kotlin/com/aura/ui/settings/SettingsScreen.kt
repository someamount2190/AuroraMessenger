package com.aura.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLegal: (String) -> Unit = {},
    onOpenShadowMesh: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val developerMode by viewModel.settings.developerMode.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }
    var showSetPin by remember { mutableStateOf(false) }
    var showSetDecoy by remember { mutableStateOf(false) }
    var versionTaps by remember { mutableStateOf(0) }

    // Encrypted backup / restore.
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    var exportPass by remember { mutableStateOf("") }
    var pendingImportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importPass by remember { mutableStateOf("") }
    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pass = exportPass; exportPass = ""
        if (uri != null) scope.launch {
            runCatching {
                val bytes = viewModel.buildBackup(pass)
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }.onSuccess {
                android.widget.Toast.makeText(ctx, "Backup saved", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(ctx, "Backup failed", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val openBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull()?.let { pendingImportBytes = it }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppearanceSection(viewModel)
            PrivacySection(viewModel)
            SecuritySection(
                viewModel,
                onSetPin = { showSetPin = true },
                onSetDecoy = { showSetDecoy = true }
            )
            NetworkSection(viewModel, onOpenShadowMesh)
            AboutLegalSection(onOpenLegal)
            DataSection(
                onExport = { showExportDialog = true },
                onRestore = { openBackupLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                onClear = { showClearDialog = true }
            )

            // Developer options (hidden until revealed by the footer tap-7×).
            if (developerMode) {
                DeveloperSection(viewModel)
            }

            // ── Footer (tap 7× to reveal developer options) ─────────────────
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.HorizontalDivider()
            Text(
                text = "Aurora · ${com.aura.BuildConfig.VERSION_NAME}\nPost-quantum · end-to-end encrypted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!developerMode) {
                            versionTaps++
                            if (versionTaps >= 7) viewModel.setDeveloperMode(true)
                        }
                    }
                    .padding(vertical = 12.dp)
            )
        }
    }

    if (showSetPin) {
        PinDialog(
            title = "Set app-lock PIN",
            onConfirm = { viewModel.setPin(it); showSetPin = false },
            onDismiss = { showSetPin = false }
        )
    }
    if (showSetDecoy) {
        PinDialog(
            title = "Set decoy PIN",
            subtitle = "Entering this PIN opens an empty app — no contacts, no messages.",
            onConfirm = { viewModel.setDecoyPin(it); showSetDecoy = false },
            onDismiss = { showSetDecoy = false }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all data?") },
            text = { Text("This wipes your identity keys, contacts, and settings. A new identity is generated on next launch. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    viewModel.clearAllData()
                }) { Text("Wipe everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showExportDialog) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Backup passphrase") },
            text = {
                Column {
                    Text(
                        "Choose a strong passphrase. You'll need it to restore — it can't be recovered if you lose it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = p1, onValueChange = { p1 = it }, label = { Text("Passphrase") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = p2, onValueChange = { p2 = it }, label = { Text("Confirm") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        isError = p2.isNotEmpty() && p1 != p2, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        exportPass = p1; showExportDialog = false
                        createBackupLauncher.launch("aurora-backup.aurabk")
                    },
                    enabled = p1.length >= 8 && p1 == p2
                ) { Text("Export") }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("Cancel") } }
        )
    }

    pendingImportBytes?.let { bytes ->
        AlertDialog(
            onDismissRequest = { pendingImportBytes = null; importPass = "" },
            title = { Text("Restore backup") },
            text = {
                Column {
                    Text(
                        "This replaces your current identity and contacts, then restarts the app. " +
                            "Enter the backup's passphrase.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importPass, onValueChange = { importPass = it }, label = { Text("Passphrase") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pass = importPass; pendingImportBytes = null; importPass = ""
                        viewModel.restoreBackup(bytes, pass)
                    },
                    enabled = importPass.isNotEmpty()
                ) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingImportBytes = null; importPass = "" }) { Text("Cancel") } }
        )
    }
}
