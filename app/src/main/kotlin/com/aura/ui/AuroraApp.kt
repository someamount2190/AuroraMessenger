package com.aura.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.viewModelScope
import com.aura.call.CallController
import com.aura.call.CallState
import com.aura.disappearing.DisappearingMessages
import com.aura.identity.IdentityStore
import com.aura.media.MediaTransfer
import com.aura.network.SyncEngine
import com.aura.pairing.PairingCoordinator
import com.aura.reaction.Reactions
import com.aura.security.AppLock
import com.aura.server.RendezvousServerController
import com.aura.settings.AuroraSettings
import com.aura.share.ShareIntentBus
import com.aura.share.ShareShortcuts
import com.aura.ui.share.ShareScreen
import com.aura.ui.call.CallBubble
import com.aura.ui.call.CallScreen
import com.aura.ui.call.OngoingCallBar
import com.aura.ui.lock.LockScreen
import com.aura.ui.conversation.ConversationScreen
import com.aura.ui.home.HomeScreen
import com.aura.ui.legal.LegalScreen
import com.aura.ui.onboarding.OnboardingScreen
import com.aura.ui.onboarding.PermissionsScreen
import com.aura.ui.qr.MyCodeScreen
import com.aura.ui.qr.ScanScreen
import com.aura.ui.settings.SettingsScreen
import com.aura.ui.shadowmesh.ShadowMeshScreen
import com.aura.ui.splash.SplashScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

object Routes {
    const val SPLASH       = "splash"
    const val ONBOARDING   = "onboarding"
    const val PERMISSIONS  = "permissions"
    const val HOME         = "home"
    const val MY_CODE      = "mycode?host={host}"
    const val SCAN         = "scan"
    fun myCode(host: Boolean = false) = "mycode?host=$host"
    const val SETTINGS     = "settings"
    const val CONVERSATION = "conversation/{contactId}"
    const val CALL         = "call"
    const val SHARE        = "share"
    const val SHADOWMESH   = "shadowmesh"
    const val LEGAL        = "legal/{doc}"
    fun conversation(contactId: String) = "conversation/$contactId"
    fun legal(doc: String) = "legal/$doc"
}

@HiltViewModel
class AuroraAppViewModel @Inject constructor(
    private val settings: AuroraSettings,
    private val appWiring: com.aura.AppWiring,
    val callManager: CallController,
    val appLockManager: AppLock,
    val pairingManager: PairingCoordinator,
    val shareIntentBus: ShareIntentBus,
    val identityManager: IdentityStore
) : ViewModel() {
    private val _startDestination = MutableStateFlow(
        if (settings.onboardingDone) Routes.HOME else Routes.ONBOARDING
    )
    val startDestination: StateFlow<String> = _startDestination

    /** Show the brand logo only on the first launch of a fresh install. */
    val showSplashLogo: Boolean = !settings.splashShown

    fun markSplashShown() { settings.splashShown = true }

    init {
        // All process-lifetime wiring (receivers + sync loop) lives in AppWiring so
        // the UI and the background WakeService start the app identically and only
        // once. See [com.aura.AppWiring].
        appWiring.ensureStarted()
    }
}

@Composable
fun AuroraApp(viewModel: AuroraAppViewModel = hiltViewModel()) {
    // Aurora streaks paint the whole app background; screens that want them
    // visible use a transparent Scaffold (Home, Conversation).
    com.aura.ui.theme.AuroraBackground(modifier = Modifier.fillMaxSize()) {
        AuroraAppContent(viewModel)
    }
}

@Composable
private fun AuroraAppContent(viewModel: AuroraAppViewModel) {
    val locked by viewModel.appLockManager.locked.collectAsState()
    val callState by viewModel.callManager.call.collectAsState()
    val minimized by viewModel.callManager.minimized.collectAsState()
    // Calls bypass the app lock: while locked, an incoming or ongoing call shows ONLY
    // the call screen (never the rest of the app), then falls back to the lock screen
    // the instant the call ends — so answering doesn't require entering the PIN first.
    val callActive = callState.state == CallState.INCOMING ||
        callState.state == CallState.OUTGOING ||
        callState.state == CallState.CONNECTING ||
        callState.state == CallState.CONNECTED
    if (locked) {
        if (callActive) CallScreen(onCallEnded = {}) else LockScreen()
        return
    }

    val navController = rememberNavController()
    val postSplashDestination by viewModel.startDestination.collectAsState()
    val pendingShare by viewModel.shareIntentBus.pending.collectAsState()

    val openContact by viewModel.shareIntentBus.openContact.collectAsState()

    // Pairing outcomes surface as toasts (success/declined) anywhere in the app.
    val appContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.pairingManager.events.collect { ev ->
            val msg = when (ev) {
                is com.aura.pairing.PairEvent.Success  -> "Contact added"
                is com.aura.pairing.PairEvent.Accepted -> "Accepted — verify the codes to start chatting"
                is com.aura.pairing.PairEvent.Declined -> "Your request was declined"
                is com.aura.pairing.PairEvent.Failed   -> "Verification failed — contact removed"
                is com.aura.pairing.PairEvent.ContactRemoved -> "${ev.name} removed you as a contact"
                is com.aura.pairing.PairEvent.IncomingRequest -> "Someone wants to connect"
            }
            android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
            // A request arrived while the host may be sitting on the "Show my code" /
            // add-contact screen — pull them back to home where the Accept/Reject card is.
            if (ev is com.aura.pairing.PairEvent.IncomingRequest) {
                val route = navController.currentDestination?.route
                if (route != null && (route.startsWith("mycode") || route == Routes.SCAN)) {
                    if (!navController.popBackStack(Routes.HOME, false)) navController.navigate(Routes.HOME)
                }
            }
        }
    }

    // Call couldn't connect / was lost — tell the user plainly (it otherwise just ended).
    LaunchedEffect(Unit) {
        viewModel.callManager.callError.collect { msg ->
            android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // System share sheet → Aurora (WARM start): a share arrived while the app was
    // already running. The cold-start case is handled in the splash hand-off below.
    // The `route != null` guard avoids the first-composition race where the route
    // is momentarily null (which would fire prematurely during the splash).
    LaunchedEffect(pendingShare) {
        val route = navController.currentDestination?.route
        if (pendingShare != null && route != null && route != Routes.SPLASH && route != Routes.SHARE) {
            navController.navigate(Routes.SHARE)
        }
    }

    // Contact shortcut tapped (direct-share row / launcher long-press), WARM start.
    LaunchedEffect(openContact) {
        val id = openContact ?: return@LaunchedEffect
        val route = navController.currentDestination?.route
        if (route != null && route != Routes.SPLASH) {
            viewModel.shareIntentBus.consumeOpenContact()
            navController.navigate(Routes.conversation(id)) { popUpTo(Routes.HOME) }
        }
    }

    // Host/receiver side: when a peer connects via an incoming signal, open the
    // conversation so the host also lands on the message screen and names them.
    LaunchedEffect(Unit) {
        viewModel.pairingManager.incomingPaired.collect { contactId ->
            navController.navigate(Routes.conversation(contactId)) {
                popUpTo(Routes.HOME)
            }
        }
    }

    // Auto-present the call screen on an incoming call (or while a call is live),
    // unless the user minimized it — then the floating bubble represents the call.
    LaunchedEffect(callState.state, minimized) {
        val s = callState.state
        val active = s == CallState.INCOMING ||
            s == CallState.OUTGOING ||
            s == CallState.CONNECTING ||
            s == CallState.CONNECTED
        if (active && !minimized && navController.currentDestination?.route != Routes.CALL) {
            navController.navigate(Routes.CALL) { launchSingleTop = true }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    AuroraNavHost(
        navController = navController,
        viewModel = viewModel,
        postSplashDestination = postSplashDestination,
        openContact = openContact,
        hasPendingShare = pendingShare != null
    )

    // While a call runs minimized, show both: a docked ongoing-call bar at the top
    // (Messenger/Viber style, works for voice and video) and the floating video bubble.
    // Tapping either restores the full call screen.
    if (callActive && minimized) {
        val restoreCall = {
            viewModel.callManager.expand()
            if (navController.currentDestination?.route != Routes.CALL) {
                navController.navigate(Routes.CALL) { launchSingleTop = true }
            }
        }
        OngoingCallBar(callManager = viewModel.callManager, onExpand = restoreCall)
        CallBubble(callManager = viewModel.callManager, onExpand = restoreCall)
    }
    }

}
