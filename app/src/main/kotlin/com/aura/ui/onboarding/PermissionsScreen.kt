package com.aura.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import com.aura.settings.AuroraSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val settings: AuroraSettings
) : ViewModel() {
    /** Finishing the permission gate is what actually completes onboarding. */
    fun complete() { settings.onboardingDone = true }
}

private enum class PermKind {
    RUNTIME,   // an Android runtime permission we can request
    BATTERY,   // the Doze/background-execution exemption (a settings intent, not a runtime perm)
    INFO       // no permission needed — purely explanatory (e.g. the system media picker)
}

private data class PermSpec(
    val id: String,
    val icon: ImageVector,
    val title: String,
    val why: String,
    val kind: PermKind,
    /** Required entries gate entry into the app; optional ones can be enabled later. */
    val required: Boolean = true,
    /** Manifest permission string for [PermKind.RUNTIME]. */
    val permission: String? = null,
    /** Only shown/enforced at or above this SDK (notifications are runtime-prompted on 33+). */
    val minSdk: Int = 0
)

private val PERMISSION_SPECS: List<PermSpec> = listOf(
    PermSpec(
        id = "notifications",
        icon = Icons.Filled.Notifications,
        title = "Notifications",
        why = "So you actually find out when something happens — a new message, an incoming " +
            "call, or someone asking to connect. Without this, requests and messages arrive " +
            "silently and you can miss them entirely (including a contact's pairing request).",
        kind = PermKind.RUNTIME,
        permission = android.Manifest.permission.POST_NOTIFICATIONS,
        minSdk = Build.VERSION_CODES.TIRAMISU
    ),
    PermSpec(
        id = "battery",
        icon = Icons.Filled.BatteryChargingFull,
        title = "Run in the background",
        why = "So messages and calls reach you even when Aurora is closed or your screen is off. " +
            "Aurora holds one lightweight connection that lets the other person's device wake " +
            "yours. If the system is allowed to sleep it, you simply won't receive anything " +
            "until you reopen the app.",
        kind = PermKind.BATTERY
    ),
    PermSpec(
        id = "microphone",
        icon = Icons.Filled.Mic,
        title = "Microphone",
        why = "For voice messages and for voice and video calls. Audio is captured only while " +
            "you're recording or on a call, and it's end-to-end encrypted — it never touches a server. " +
            "You can skip this now; Aurora will ask the first time you record or call.",
        kind = PermKind.RUNTIME,
        required = false,
        permission = android.Manifest.permission.RECORD_AUDIO
    ),
    PermSpec(
        id = "camera",
        icon = Icons.Filled.PhotoCamera,
        title = "Camera",
        why = "To add a contact by scanning their QR code, and for video calls. The camera is " +
            "used only while scanning or during a video call. You can skip this now; Aurora will " +
            "ask the first time you scan a code or start a video call.",
        kind = PermKind.RUNTIME,
        required = false,
        permission = android.Manifest.permission.CAMERA
    ),
    PermSpec(
        id = "gallery",
        icon = Icons.Filled.PhotoLibrary,
        title = "Photos & videos",
        why = "Sharing a photo or video opens Android's built-in picker, where you choose exactly " +
            "what to send. Aurora never asks for access to your whole gallery, so there's nothing " +
            "to grant here.",
        kind = PermKind.INFO,
        required = false
    )
)

private fun isGranted(context: Context, spec: PermSpec): Boolean = when (spec.kind) {
    PermKind.RUNTIME -> {
        if (Build.VERSION.SDK_INT < spec.minSdk) true
        else ContextCompat.checkSelfPermission(context, spec.permission!!) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    PermKind.BATTERY -> {
        val pm = context.getSystemService(PowerManager::class.java)
        pm != null && pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    PermKind.INFO -> true   // nothing to grant
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }
}

private fun requestBatteryExemption(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }.onFailure {
        // Some OEMs don't expose the direct request — fall back to the battery settings list.
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

@Composable
fun PermissionsScreen(
    onComplete: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-read permission state whenever we come back to the foreground (returning from a
    // system permission dialog, the battery prompt, or the app's settings page).
    var refreshKey by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Which runtime permissions we've already prompted for, so a second tap (after a
    // denial — possibly permanent) sends the user to app settings instead of a silent no-op.
    val requested = remember { mutableStateMapOf<String, Boolean>() }
    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshKey++ }

    // Only the rows relevant to this OS version (notifications isn't runtime-prompted < 33).
    val specs = remember {
        PERMISSION_SPECS.filter { Build.VERSION.SDK_INT >= it.minSdk }
    }
    val requiredSpecs = remember(specs) { specs.filter { it.required } }
    val optionalSpecs = remember(specs) { specs.filter { !it.required } }
    // Only the required rows gate entry. Keying on refreshKey re-reads permission state
    // (and triggers recomposition) after every grant dialog / settings return.
    val allRequiredGranted = remember(refreshKey) { requiredSpecs.all { isGranted(context, it) } }

    // Shared request handler: runtime perms launch the system dialog (then app settings on a
    // repeat tap after denial); the battery row opens the Doze-exemption prompt; INFO does nothing.
    val handleAllow: (PermSpec) -> Unit = handler@{ spec ->
        when (spec.kind) {
            PermKind.RUNTIME -> {
                val perm = spec.permission ?: return@handler
                if (requested[perm] == true) openAppSettings(context)
                else {
                    requested[perm] = true
                    runtimeLauncher.launch(perm)
                }
            }
            PermKind.BATTERY -> requestBatteryExemption(context)
            PermKind.INFO -> { /* nothing to request */ }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Before you start",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Aurora needs two permissions to deliver your messages and calls reliably. " +
                "None of them change what Aurora can see — your conversations stay end-to-end " +
                "encrypted and never pass through a server. These only let the app reach you. " +
                "The rest are optional and you can enable them anytime.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionLabel("Required")
            requiredSpecs.forEach { spec ->
                val granted = remember(refreshKey, spec.id) { isGranted(context, spec) }
                PermissionCard(spec = spec, granted = granted, onAllow = { handleAllow(spec) })
            }
            if (optionalSpecs.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SectionLabel("Optional · enable now or anytime later")
                optionalSpecs.forEach { spec ->
                    val granted = remember(refreshKey, spec.id) { isGranted(context, spec) }
                    PermissionCard(spec = spec, granted = granted, onAllow = { handleAllow(spec) })
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (!allRequiredGranted) {
            Text(
                "Enable the required permissions above to continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { viewModel.complete(); onComplete() },
            enabled = allRequiredGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (allRequiredGranted) "Enter Aurora" else "Grant required permissions")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun PermissionCard(
    spec: PermSpec,
    granted: Boolean,
    onAllow: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                spec.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    spec.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    spec.why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            when {
                // Informational rows (e.g. the media picker) need nothing granted.
                spec.kind == PermKind.INFO -> Text(
                    "No setup\nneeded",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End
                )
                granted -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Granted",
                    tint = Color(0xFF4CD97B),
                    modifier = Modifier.size(26.dp)
                )
                else -> OutlinedButton(onClick = onAllow) { Text("Allow") }
            }
        }
    }
}
