package com.aura

import com.aura.call.CallController
import com.aura.disappearing.DisappearingMessages
import com.aura.media.MediaTransfer
import com.aura.network.SyncEngine
import com.aura.reaction.Reactions
import com.aura.server.RendezvousServerController
import com.aura.settings.AuroraSettings
import com.aura.share.ShareShortcuts
import com.aura.transport.rtc.RtcTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single, idempotent entry point that wires every process-lifetime receiver and
 * starts the sync loop. Both the UI ([AuroraAppViewModel]) and the background
 * [com.aura.service.WakeService] call this, so the app is fully able to receive
 * messages, media and calls whether it was launched by the user or resurrected by
 * the system (START_STICKY) into just the service. Without this, a woken-but-UI-less
 * process would have no call/media handlers registered.
 *
 * Uses a process-lifetime scope (not a viewModelScope) so handlers survive the
 * Activity being destroyed.
 */
@Singleton
class AppWiring @Inject constructor(
    private val settings: AuroraSettings,
    private val serverController: RendezvousServerController,
    private val mediaTransfer: MediaTransfer,
    private val disappearingManager: DisappearingMessages,
    private val reactionManager: Reactions,
    private val callManager: CallController,
    private val shareShortcutManager: ShareShortcuts,
    private val rtcTransport: RtcTransport,
    private val syncEngine: SyncEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    @Synchronized
    fun ensureStarted() {
        if (started) return
        started = true
        // Server Mode survives restarts: resume the in-app rendezvous server if left on.
        if (settings.serverMode.value) serverController.start()
        // Register inbound handlers BEFORE the TCP server starts accepting (syncEngine).
        mediaTransfer.wireReceiver()
        disappearingManager.start()
        reactionManager.start()
        callManager.init(scope)
        // Register the "rtc" data-channel signal handler BEFORE the sync loop starts
        // draining signals, so an inbound offer that arrives immediately is handled.
        rtcTransport.init(scope, syncEngine)
        shareShortcutManager.start()
        syncEngine.start()
    }
}
