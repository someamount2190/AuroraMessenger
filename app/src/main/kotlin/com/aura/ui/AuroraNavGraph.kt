package com.aura.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aura.call.CallState
import com.aura.ui.call.CallScreen
import com.aura.ui.conversation.ConversationScreen
import com.aura.ui.home.HomeScreen
import com.aura.ui.legal.LegalScreen
import com.aura.ui.onboarding.OnboardingScreen
import com.aura.ui.onboarding.PermissionsScreen
import com.aura.ui.qr.MyCodeScreen
import com.aura.ui.qr.ScanScreen
import com.aura.ui.settings.SettingsScreen
import com.aura.ui.shadowmesh.ShadowMeshScreen
import com.aura.ui.share.ShareScreen
import com.aura.ui.splash.SplashScreen

/**
 * The app's navigation graph. Extracted from AuroraAppContent so the screen wiring
 * lives apart from the lock-state branch, the cross-cutting effects (pairing toasts,
 * share routing, call auto-present), and the minimized-call overlay that surround it.
 *
 * [hasPendingShare] is the already-evaluated `pendingShare != null` so this graph
 * stays decoupled from the share-intent payload type; the parent keys its own
 * effect off the live value.
 */
@Composable
internal fun AuroraNavHost(
    navController: NavHostController,
    viewModel: AuroraAppViewModel,
    postSplashDestination: String,
    openContact: String?,
    hasPendingShare: Boolean
) {
    NavHost(navController = navController, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(
                showLogo = viewModel.showSplashLogo,
                onFinished = {
                    viewModel.markSplashShown()
                    // Cold-start deep links resolved once: a tapped contact shortcut
                    // opens that chat; an inbound share opens the picker; else normal.
                    val oc = openContact
                    // An active call must win this hand-off. When the Activity is recreated
                    // while a call is live (returning from the notification, the floating
                    // bubble, or after the OS reclaimed it in the background), the default
                    // splash->home navigation would pop the call screen and — if the call
                    // wasn't explicitly minimized — leave no way back to it. Go straight to
                    // the call screen instead; a minimized call falls through to home, where
                    // the ongoing-call bar/bubble provide the way back.
                    val callInfo = viewModel.callManager.call.value
                    val callLive = callInfo.state == CallState.INCOMING ||
                        callInfo.state == CallState.OUTGOING ||
                        callInfo.state == CallState.CONNECTING ||
                        callInfo.state == CallState.CONNECTED
                    val dest = when {
                        callLive && !viewModel.callManager.minimized.value -> Routes.CALL
                        postSplashDestination != Routes.HOME -> postSplashDestination
                        oc != null -> {
                            viewModel.shareIntentBus.consumeOpenContact()
                            Routes.conversation(oc)
                        }
                        hasPendingShare -> Routes.SHARE
                        else -> postSplashDestination
                    }
                    navController.navigate(dest) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                // The info pages don't complete onboarding — they hand off to the
                // permission gate, which is what actually unlocks the app.
                onDone = {
                    navController.navigate(Routes.PERMISSIONS) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
                onOpenLegal = { doc -> navController.navigate(Routes.legal(doc)) }
            )
        }
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(
                onComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PERMISSIONS) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onAddContact   = { navController.navigate(Routes.SCAN) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenConversation = { id -> navController.navigate(Routes.conversation(id)) }
            )
        }
        composable(
            Routes.MY_CODE,
            arguments = listOf(navArgument("host") {
                type = NavType.StringType; defaultValue = "false"
            })
        ) {
            MyCodeScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SCAN) {
            ScanScreen(
                onBack = { navController.popBackStack() },
                onShowMyCode = { navController.navigate(Routes.myCode(host = false)) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenLegal = { doc -> navController.navigate(Routes.legal(doc)) },
                onOpenShadowMesh = { navController.navigate(Routes.SHADOWMESH) }
            )
        }
        composable(Routes.SHADOWMESH) {
            ShadowMeshScreen(onBack = { navController.popBackStack() })
        }
        composable(
            Routes.CONVERSATION,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType })
        ) { backStackEntry ->
            ConversationScreen(
                contactId = backStackEntry.arguments?.getString("contactId").orEmpty(),
                onBack = { navController.popBackStack() },
                onStartCall = { contactId, video ->
                    viewModel.callManager.startCall(contactId, video)
                }
            )
        }
        composable(
            Routes.LEGAL,
            arguments = listOf(navArgument("doc") { type = NavType.StringType })
        ) { backStackEntry ->
            LegalScreen(
                docKey = backStackEntry.arguments?.getString("doc").orEmpty(),
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.CALL) {
            // Back does not end the call: minimize it and drop to the main menu. The
            // call keeps running (CallController is process-scoped) and the floating bubble
            // + ongoing notification take over.
            BackHandler {
                viewModel.callManager.minimize()
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.HOME) { inclusive = false }
                    launchSingleTop = true
                }
            }
            CallScreen(
                onCallEnded = {
                    if (navController.currentDestination?.route == Routes.CALL) {
                        navController.popBackStack()
                    }
                }
            )
        }
        composable(Routes.SHARE) {
            ShareScreen(
                onShared = { contactId ->
                    navController.navigate(Routes.conversation(contactId)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onCancel = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.HOME) { popUpTo(Routes.SHARE) { inclusive = true } }
                    }
                }
            )
        }
    }
}
