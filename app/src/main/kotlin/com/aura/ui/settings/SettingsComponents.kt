package com.aura.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.server.RendezvousServerController
import com.aura.settings.DisappearingTimer
import com.aura.settings.ThemeMode
import com.aura.settings.ThemePalette

// Section composables for SettingsScreen. Each owns its own state subscription
// (collectAsState), mirroring DeveloperSection; the parent screen wires the
// dialogs/launchers via the callbacks below.

@Composable
internal fun AppearanceSection(viewModel: SettingsViewModel) {
    val themeMode by viewModel.settings.themeMode.collectAsState()
    val themePalette by viewModel.settings.themePalette.collectAsState()
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
            ThemePalette.entries.forEach { palette ->
                OptionRow(
                    label = palette.label,
                    selected = themePalette == palette,
                    onClick = { viewModel.setThemePalette(palette) }
                )
            }
        }
    }
}

@Composable
internal fun PrivacySection(viewModel: SettingsViewModel) {
    val disappearing by viewModel.settings.disappearingTimer.collectAsState()
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
}

@Composable
internal fun SecuritySection(
    viewModel: SettingsViewModel,
    onSetPin: () -> Unit,
    onSetDecoy: () -> Unit
) {
    val duressWipe by viewModel.settings.duressWipe.collectAsState()
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
                Button(onClick = onSetPin, modifier = Modifier.weight(1f)) {
                    Text(if (viewModel.lockEnabled()) "Change PIN" else "Set PIN")
                }
                OutlinedButton(onClick = onSetDecoy, modifier = Modifier.weight(1f)) {
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
}

@Composable
internal fun NetworkSection(viewModel: SettingsViewModel, onOpenShadowMesh: () -> Unit) {
    val shadowMesh by viewModel.settings.shadowMeshEnabled.collectAsState()
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
}

@Composable
internal fun AboutLegalSection(onOpenLegal: (String) -> Unit) {
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
}

@Composable
internal fun DataSection(
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onClear: () -> Unit
) {
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
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                Text("Export encrypted backup")
            }
            OutlinedButton(
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Restore from backup")
            }
            HorizontalDivider()
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear all data", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Network/rendezvous developer tools — not part of the normal user experience.
 * Revealed by tapping the version footer seven times; hidden again from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeveloperSection(viewModel: SettingsViewModel) {
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
internal fun PinDialog(
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
