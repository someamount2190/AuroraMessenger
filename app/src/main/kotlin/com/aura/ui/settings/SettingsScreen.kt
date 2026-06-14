package com.aura.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.crypto.toHex
import com.aura.db.AuroraDatabase
import com.aura.identity.IdentityManager
import com.aura.network.RendezvousClient
import com.aura.security.AppLockManager
import com.aura.security.RootDetector
import com.aura.server.RendezvousServerController
import com.aura.settings.AuroraSettings
import com.aura.settings.DisappearingTimer
import com.aura.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val settings: AuroraSettings,
    val serverController: RendezvousServerController,
    private val rendezvousClient: RendezvousClient,
    private val identityManager: IdentityManager,
    private val database: AuroraDatabase,
    val appLockManager: AppLockManager,
    private val secureWipe: com.aura.security.SecureWipe,
    private val backupManager: com.aura.backup.BackupManager
) : ViewModel() {

    /** Build an encrypted backup blob to write to a user-chosen file. */
    suspend fun buildBackup(passphrase: String): ByteArray = backupManager.export(passphrase.toCharArray())

    /** Restore from a backup blob; on success the app hard-exits so the restored identity loads. */
    fun restoreBackup(bytes: ByteArray, passphrase: String) {
        viewModelScope.launch {
            backupManager.import(bytes, passphrase.toCharArray())
                .onSuccess { secureWipe.exitProcess() }
                .onFailure { _testResult.value = "Restore failed: ${it.message}" }
        }
    }

    val meshPeerCount = database.meshPeerDao().observeCount()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 0)

    val isRooted: Boolean by lazy { RootDetector.isLikelyRooted() }

    fun lockEnabled(): Boolean = appLockManager.isLockEnabled()
    fun setPin(pin: String) = appLockManager.setPin(pin)
    fun setDecoyPin(pin: String) = appLockManager.setDecoyPin(pin)
    fun disableLock() = appLockManager.disableLock()

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)
    fun setThemePalette(palette: com.aura.settings.ThemePalette) = settings.setThemePalette(palette)
    fun setDeveloperMode(enabled: Boolean) = settings.setDeveloperMode(enabled)
    fun setShadowMesh(enabled: Boolean) = settings.setShadowMeshEnabled(enabled)

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult

    fun setServerMode(enabled: Boolean) {
        settings.setServerMode(enabled)
        if (enabled) serverController.start() else serverController.stop()
    }

    /** Developer tool: sign a check-in and POST it to the configured server. */
    fun testCheckIn() {
        _testResult.value = "Checking in…"
        viewModelScope.launch {
            val identity = identityManager.getOrCreate()
            val result = rendezvousClient.checkIn(settings.serverAddress.value, identity)
            _testResult.value = result.fold(
                onSuccess = { "Check-in OK: $it" },
                onFailure = { "Check-in failed: ${it.message}" }
            )
        }
    }

    /** Developer tool: look up our own nodeId and verify the real IP among 10 candidates. */
    fun testFindSelf() {
        _testResult.value = "Looking up…"
        viewModelScope.launch {
            val identity = identityManager.getOrCreate()
            val nodeIdHex = identity.nodeId.toHex()
            val result = rendezvousClient.find(settings.serverAddress.value, nodeIdHex)
            _testResult.value = result.fold(
                onSuccess = { candidates ->
                    val real = rendezvousClient.verifyCandidates(
                        nodeIdHex,
                        identity.publicPart.signingPublicKey.ed25519PublicKey,
                        identity.publicPart.signingPublicKey.dilithiumPublicKey,
                        candidates
                    )
                    "Got ${candidates.size} candidates. " +
                        if (real != null) "Verified real IP: ${real.ip}:${real.port}"
                        else "No candidate verified (not checked in yet?)"
                },
                onFailure = { "Find failed: ${it.message}" }
            )
        }
    }

    fun setDuressWipe(enabled: Boolean) = settings.setDuressWipe(enabled)

    /**
     * Cryptographic erase of everything, then hard-exit. Destroying the DB key and the
     * identity/lock key vaults makes the on-disk ciphertext unrecoverable — far stronger
     * than deleting rows. The app closes; relaunch generates a fresh identity.
     */
    fun clearAllData() {
        viewModelScope.launch {
            serverController.stop()
            secureWipe.wipeEverything()
            secureWipe.exitProcess()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLegal: (String) -> Unit = {},
    onOpenShadowMesh: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val themeMode by viewModel.settings.themeMode.collectAsState()
    val themePalette by viewModel.settings.themePalette.collectAsState()
    val developerMode by viewModel.settings.developerMode.collectAsState()
    val disappearing by viewModel.settings.disappearingTimer.collectAsState()
    val shadowMesh by viewModel.settings.shadowMeshEnabled.collectAsState()
    val duressWipe by viewModel.settings.duressWipe.collectAsState()

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
            // ── Appearance ──────────────────────────────────────────────────
            SectionTitle("Appearance")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Choose how Aurora looks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    ThemeMode.entries.forEach { mode ->
                        OptionRow(
                            label = mode.label,
                            selected = themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) }
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Colour palette", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Aurora is the current look. Cherish brings back our classic plum theme.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    com.aura.settings.ThemePalette.entries.forEach { palette ->
                        OptionRow(
                            label = palette.label,
                            selected = themePalette == palette,
                            onClick = { viewModel.setThemePalette(palette) }
                        )
                    }
                }
            }

            // ── Privacy ─────────────────────────────────────────────────────
            SectionTitle("Privacy")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Disappearing messages", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Default self-destruct timer for new conversations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    DisappearingTimer.entries.forEach { timer ->
                        OptionRow(
                            label = timer.label,
                            selected = disappearing == timer,
                            onClick = { viewModel.settings.setDisappearingTimer(timer) }
                        )
                    }
                }
            }

            // ── Security ────────────────────────────────────────────────────
            SectionTitle("Security")
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (viewModel.isRooted) {
                        Text(
                            "⚠ This device appears to be rooted. Your keys may be extractable — use Aurora with caution.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        "App lock (PIN). A separate decoy PIN opens an empty app with no contacts or messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showSetPin = true }, modifier = Modifier.weight(1f)) {
                            Text(if (viewModel.lockEnabled()) "Change PIN" else "Set PIN")
                        }
                        OutlinedButton(onClick = { showSetDecoy = true }, modifier = Modifier.weight(1f)) {
                            Text("Decoy PIN")
                        }
                    }
                    if (viewModel.lockEnabled()) {
                        TextButton(onClick = { viewModel.disableLock() }) { Text("Disable app lock") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Decoy PIN wipes everything (duress)",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = duressWipe, onCheckedChange = viewModel::setDuressWipe)
                    }
                    Text(
                        "When on, entering the decoy PIN permanently erases all data instead of " +
                            "showing an empty app. Screenshots are blocked app-wide.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Network ─────────────────────────────────────────────────────
            SectionTitle("Network")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("ShadowMesh relay", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Help carry other users' encrypted, end-to-end fragments and route " +
                                    "your own through the network for extra metadata privacy. " +
                                    "Off by default — your messages work without it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // Turning ON routes through the opt-in screen (Join there);
                        // turning OFF is immediate, no friction to leave.
                        Switch(
                            checked = shadowMesh,
                            onCheckedChange = { want ->
                                if (want) onOpenShadowMesh() else viewModel.setShadowMesh(false)
                            }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onOpenShadowMesh,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) { Text("Learn more about ShadowMesh") }
                }
            }

            // ── About & Legal ───────────────────────────────────────────────
            SectionTitle("About & Legal")
            Card {
                Column(Modifier.padding(vertical = 4.dp)) {
                    NavRow("Terms & Conditions") { onOpenLegal("terms") }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    NavRow("Privacy Policy") { onOpenLegal("privacy") }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Contact", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            com.aura.legal.LegalDocs.CONTACT_EMAIL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Data ────────────────────────────────────────────────────────
            SectionTitle("Data")
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "An encrypted backup lets you restore your identity and contacts after a " +
                            "reinstall. It's protected by a passphrase only you know — keep it safe, " +
                            "there's no way to recover the backup without it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { showExportDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Export encrypted backup")
                    }
                    OutlinedButton(
                        onClick = { openBackupLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Restore from backup")
                    }
                    HorizontalDivider()
                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear all data", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // ── Developer options (hidden until revealed) ────────────────────
            if (developerMode) {
                DeveloperSection(viewModel)
            }

            // ── Footer (tap 7× to reveal developer options) ─────────────────
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Text(
                text = "Aurora · 0.1.0\nPost-quantum · end-to-end encrypted",
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

/**
 * Network/rendezvous developer tools — not part of the normal user experience.
 * Revealed by tapping the version footer seven times; hidden again from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeveloperSection(viewModel: SettingsViewModel) {
    val serverMode by viewModel.settings.serverMode.collectAsState()
    val serverAddress by viewModel.settings.serverAddress.collectAsState()
    val advertisedAddress by viewModel.settings.advertisedAddress.collectAsState()
    val serverStatus by viewModel.serverController.status.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val meshPeerCount by viewModel.meshPeerCount.collectAsState()

    var addressDraft by remember(serverAddress) { mutableStateOf(serverAddress) }
    var advertisedDraft by remember(advertisedAddress) { mutableStateOf(advertisedAddress) }

    SectionTitle("Developer options")
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Server Mode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Run the rendezvous server on this device (port 8080)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = serverMode, onCheckedChange = viewModel::setServerMode)
            }

            when (val s = serverStatus) {
                is RendezvousServerController.Status.Running -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Running at ${s.localIp}:${s.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                is RendezvousServerController.Status.Error -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Error: ${s.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> Unit
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = addressDraft,
                onValueChange = { addressDraft = it },
                label = { Text("Server address (client mode)") },
                supportingText = { Text("10.0.2.2 reaches the host machine from an emulator") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (addressDraft != serverAddress) {
                TextButton(onClick = { viewModel.settings.setServerAddress(addressDraft) }) {
                    Text("Save address")
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = advertisedDraft,
                onValueChange = { advertisedDraft = it },
                label = { Text("Advertised address (ip:port, optional)") },
                supportingText = { Text("Override what peers are told to connect to (NAT / emulator testing). Mesh peers learned: $meshPeerCount") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (advertisedDraft != advertisedAddress) {
                TextButton(onClick = { viewModel.settings.setAdvertisedAddress(advertisedDraft) }) {
                    Text("Save advertised address")
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::testCheckIn, modifier = Modifier.weight(1f)) {
                    Text("Check in")
                }
                OutlinedButton(onClick = viewModel::testFindSelf, modifier = Modifier.weight(1f)) {
                    Text("Find myself")
                }
            }
            testResult?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { viewModel.setDeveloperMode(false) }) {
                Text("Hide developer options")
            }
        }
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun PinDialog(
    title: String,
    subtitle: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                    label = { Text("PIN (4–8 digits)") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    ),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = pin.length >= 4) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
