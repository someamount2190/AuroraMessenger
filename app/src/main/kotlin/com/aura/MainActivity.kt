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
import com.aura.call.CallController
import com.aura.call.CallController.CallState
import com.aura.call.CallOverlay
import com.aura.notify.Notifier
import com.aura.security.AppLock
import com.aura.settings.AuroraSettings
import com.aura.settings.ThemeMode
import com.aura.share.PendingShare
import com.aura.share.ShareIntentBus
import com.aura.share.ShareShortcuts
import com.aura.ui.AuroraApp
import com.aura.ui.theme.AuroraTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appLockManager: AppLock
    @Inject lateinit var shareIntentBus: ShareIntentBus
    @Inject lateinit var auroraSettings: AuroraSettings
    @Inject lateinit var callManager: CallController
    @Inject lateinit var overlayManager: CallOverlay

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phase 8: screenshot prevention + no content in the recents preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Fresh installs are walked through permissions by the onboarding gate
        // (com.aura.ui.onboarding.PermissionsScreen), which owns the notification prompt.
        // Only nudge already-onboarded upgrades that predate that gate here.
        if (auroraSettings.onboardingDone) maybeRequestNotificationPermission()
        maybeStartWakeService()
        observeCallWindowFlags()
        observeMinimizeForOverlayPermission()
        handleShareIntent(intent)
        handleCallAnswer(intent)
        handleCallOpen(intent)
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
        handleCallOpen(intent)
    }

    /** The notification's Answer button launches us with this action — accept the call. */
    private fun handleCallAnswer(intent: Intent?) {
        if (intent?.action == Notifier.ACTION_ANSWER_CALL) {
            callManager.acceptCall()
        }
    }

    /** Tapping the ongoing-call notification restores the minimized call to full screen. */
    private fun handleCallOpen(intent: Intent?) {
        if (intent?.action == Notifier.ACTION_OPEN_CALL) {
            callManager.expand()
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
                val active = info.state == CallState.INCOMING ||
                    info.state == CallState.OUTGOING ||
                    info.state == CallState.CONNECTING ||
                    info.state == CallState.CONNECTED
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

    override fun onStart() {
        super.onStart()
        // Back in the foreground: the in-app bar/bubble represents the call now, so drop
        // the over-other-apps floating window.
        overlayManager.hide()
    }

    override fun onStop() {
        super.onStop()
        // Re-lock when the app leaves the foreground (app-lock enabled only).
        appLockManager.lock()
        // If a call is live, float it over other apps (Messenger/Viber-style bubble) so
        // leaving Aurora doesn't drop the call from view. No-op without the overlay grant.
        val s = callManager.call.value.state
        val callActive = s == CallState.OUTGOING ||
            s == CallState.INCOMING ||
            s == CallState.CONNECTING ||
            s == CallState.CONNECTED
        if (callActive) overlayManager.show()
    }

    /**
     * The first time the user minimizes a call, ask once for the "display over other
     * apps" permission so the call can float over other apps. Declining is fine — the
     * call still runs with the in-app bar and the ongoing-call notification; it just
     * won't float over other apps.
     */
    private fun observeMinimizeForOverlayPermission() {
        lifecycleScope.launch {
            callManager.minimized.collect { minimized ->
                if (minimized && !auroraSettings.overlayPromptShown &&
                    !android.provider.Settings.canDrawOverlays(this@MainActivity)
                ) {
                    auroraSettings.overlayPromptShown = true
                    runCatching {
                        startActivity(
                            Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                .setData(Uri.parse("package:$packageName"))
                        )
                    }
                }
            }
        }
    }

    /** Parse an inbound system-share (ACTION_SEND/SEND_MULTIPLE) into a pending share. */
    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        // A contact shortcut (direct-share row / launcher long-press) was tapped.
        if (intent.action == Intent.ACTION_VIEW) {
            intent.getStringExtra(ShareShortcuts.EXTRA_CONTACT_NODE_ID)
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
