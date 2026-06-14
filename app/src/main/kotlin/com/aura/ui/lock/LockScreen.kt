package com.aura.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.security.AppLockManager
import com.aura.security.SecureWipe
import com.aura.settings.AuroraSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LockViewModel @Inject constructor(
    val appLockManager: AppLockManager,
    private val settings: AuroraSettings,
    private val secureWipe: SecureWipe
) : ViewModel() {
    fun unlock(pin: String): AppLockManager.UnlockResult {
        val result = appLockManager.tryUnlock(pin)
        // Duress mode: the decoy PIN was configured to wipe, not just hide. Erase
        // everything and hard-exit — to an onlooker the app simply opened and closed.
        if (result == AppLockManager.UnlockResult.DECOY && settings.duressWipe.value) {
            viewModelScope.launch {
                secureWipe.wipeEverything()
                secureWipe.exitProcess()
            }
        }
        return result
    }
}

@Composable
fun LockScreen(viewModel: LockViewModel = hiltViewModel()) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    val lockoutUntil by viewModel.appLockManager.lockoutUntil.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockoutUntil) {
        while (System.currentTimeMillis() < lockoutUntil) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }
    val lockedOut = now < lockoutUntil
    val remainingSec = ((lockoutUntil - now) / 1000 + 1).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.height(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text("Aurora is locked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter your PIN",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { c -> c.isDigit() }; error = false },
            label = { Text("PIN") },
            isError = error,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (lockedOut) {
            Text(
                "Too many attempts. Try again in ${remainingSec}s",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        } else if (error) {
            Text(
                "Incorrect PIN",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val result = viewModel.unlock(pin)
                if (result == AppLockManager.UnlockResult.WRONG ||
                    result == AppLockManager.UnlockResult.LOCKED_OUT) {
                    error = true
                    pin = ""
                }
                // REAL / DECOY both clear `locked`; the host recomposes away from the lock screen.
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = pin.length >= 4 && !lockedOut
        ) {
            Text("Unlock")
        }
    }
}
