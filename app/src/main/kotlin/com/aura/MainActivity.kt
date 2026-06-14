package com.aura

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aura.security.AppLockManager
import com.aura.settings.AuroraSettings
import com.aura.settings.ThemeMode
import com.aura.share.PendingShare
import com.aura.share.ShareIntentBus
import com.aura.ui.AuroraApp
import com.aura.ui.theme.AuroraTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appLockManager: AppLockManager
    @Inject lateinit var shareIntentBus: ShareIntentBus
    @Inject lateinit var auroraSettings: AuroraSettings
    @Inject lateinit var callManager: com.aura.call.CallManager

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phase 8: screenshot prevention + no content in the recents preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        maybeRequestNotificationPermission()
        maybeStartWakeService()
        observeCallWindowFlags()
        handleShareIntent(intent)
        handleCallAnswer(intent)
        setContent {
            val themeMode by auroraSettings.themeMode.collectAsState()
            val palette by auroraSettings.themePalette.collectAsState()
            val dark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK  -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            AuroraTheme(darkTheme = dark, palette = palette) {
                AuroraApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleCallAnswer(intent)
    }

    /** The notification's Answer button launches us with this action — accept the call. */
    private fun handleCallAnswer(intent: Intent?) {
        if (intent?.action == com.aura.notify.Notifier.ACTION_ANSWER_CALL) {
            callManager.acceptCall()
        }
    }

    /**
     * While a call is incoming or live, let this Activity show over the device
     * keyguard and turn the screen on, so an incoming call surfaces on a locked phone
     * like any phone call. Cleared when the call ends so the app stays behind the
     * keyguard normally.
     */
    private fun observeCallWindowFlags() {
        lifecycleScope.launch {
            callManager.call.collect { info ->
                val active = info.state == com.aura.call.CallManager.CallState.INCOMING ||
                    info.state == com.aura.call.CallManager.CallState.OUTGOING ||
                    info.state == com.aura.call.CallManager.CallState.CONNECTING ||
                    info.state == com.aura.call.CallManager.CallState.CONNECTED
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(active)
                    setTurnScreenOn(active)
                }
            }
        }
    }

    /**
     * Once the app is set up, start the background wake service (keeps the parked
     * long-poll alive so peers can rouse us) and ask once to be exempt from Doze so
     * that parked socket survives deep sleep. Observes onboarding so it also fires the
     * instant a fresh install finishes setup (no relaunch needed), not only on a
     * launch of an already-onboarded app. Foreground-service starts are only permitted
     * from a foreground context, which the Activity is whenever this collector emits.
     */
    private fun maybeStartWakeService() {
        lifecycleScope.launch {
            auroraSettings.onboardingDoneFlow.collect { done ->
                if (done) {
                    com.aura.service.WakeService.start(this@MainActivity)
                    maybeRequestBatteryExemption()
                }
            }
        }
    }

    private fun maybeRequestBatteryExemption() {
        if (auroraSettings.batteryPromptShown) return
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
            auroraSettings.batteryPromptShown = true
            return
        }
        auroraSettings.batteryPromptShown = true
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    /** Ask for notification posting on Android 13+ (message + call alerts). */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-lock when the app leaves the foreground (app-lock enabled only).
        appLockManager.lock()
    }

    /** Parse an inbound system-share (ACTION_SEND/SEND_MULTIPLE) into a pending share. */
    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        // A contact shortcut (direct-share row / launcher long-press) was tapped.
        if (intent.action == Intent.ACTION_VIEW) {
            intent.getStringExtra(com.aura.share.ShareShortcutManager.EXTRA_CONTACT_NODE_ID)
                ?.let { shareIntentBus.offerOpenContact(it); return }
        }
        val share = when (intent.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type
                if (type == "text/plain" && !intent.hasExtra(Intent.EXTRA_STREAM)) {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { PendingShare(it, emptyList(), type) }
                } else {
                    streamExtra(intent)?.let { PendingShare(null, listOf(it), type) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                streamListExtra(intent)?.takeIf { it.isNotEmpty() }
                    ?.let { PendingShare(null, it, intent.type) }
            }
            else -> null
        }
        if (share != null) shareIntentBus.offer(share)
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
}
