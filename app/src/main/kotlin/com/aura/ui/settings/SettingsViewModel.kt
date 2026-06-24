package com.aura.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.backup.Backups
import com.aura.crypto.toHex
import com.aura.db.AuroraDatabase
import com.aura.identity.IdentityStore
import com.aura.network.Rendezvous
import com.aura.security.AppLock
import com.aura.security.RootDetector
import com.aura.security.SecureWipe
import com.aura.server.RendezvousServerController
import com.aura.settings.AuroraSettings
import com.aura.settings.ThemeMode
import com.aura.settings.ThemePalette
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
    private val rendezvousClient: Rendezvous,
    private val identityManager: IdentityStore,
    private val database: AuroraDatabase,
    val appLockManager: AppLock,
    private val secureWipe: SecureWipe,
    private val backupManager: Backups
) : ViewModel() {

    /** Build an encrypted backup blob to write to a user-chosen file. */
    suspend fun buildBackup(passphrase: String): ByteArray = backupManager.export(passphrase.toCharArray())

    /** Restore from a backup blob; on success the app hard-exits so the restored identity loads. */
    fun restoreBackup(bytes: ByteArray, passphrase: String) {
        if (appLockManager.decoyActive.value) return   // decoy: no identity-altering actions
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
    // PIN/lock changes are blocked under decoy so a coercer can't lock out the real owner
    // (change the real PIN) or tear down the lock while holding only the decoy PIN.
    fun setPin(pin: String) { if (!appLockManager.decoyActive.value) appLockManager.setPin(pin) }
    fun setDecoyPin(pin: String) { if (!appLockManager.decoyActive.value) appLockManager.setDecoyPin(pin) }
    fun disableLock() { if (!appLockManager.decoyActive.value) appLockManager.disableLock() }

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)
    fun setThemePalette(palette: ThemePalette) = settings.setThemePalette(palette)
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
        if (appLockManager.decoyActive.value) return   // decoy: don't let a coercer wipe the real data
        viewModelScope.launch {
            serverController.stop()
            secureWipe.wipeEverything()
            secureWipe.exitProcess()
        }
    }
}
