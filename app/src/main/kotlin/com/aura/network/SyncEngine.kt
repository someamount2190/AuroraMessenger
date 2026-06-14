package com.aura.network

import com.aura.crypto.PrekeyManager
import com.aura.crypto.toHex
import com.aura.identity.IdentityManager
import com.aura.notify.Notifier
import com.aura.pairing.PairingManager
import com.aura.settings.AuroraSettings
import com.aura.transport.MessageSender
import com.aura.transport.TcpMessageServer
import com.aura.ux.MessagePulse
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground sync loop (Phases 2–3):
 *  - check in with the rendezvous server on start and every 5 minutes
 *  - drain our signal queue every few seconds and dispatch messages by type
 *  - retry pairing messages that never reached the server
 *
 * Runs only while the app process lives — Aurora has no background service by
 * design (the rendezvous server holds no messages, so there is nothing to miss
 * beyond what peers re-send when we come online).
 */
@Singleton
class SyncEngine @Inject constructor(
    private val identityManager: IdentityManager,
    private val rendezvousClient: RendezvousClient,
    private val prekeyManager: PrekeyManager,
    private val pairingManager: PairingManager,
    private val messageSender: MessageSender,
    private val tcpServer: TcpMessageServer,
    private val settings: AuroraSettings,
    private val messagePulse: MessagePulse,
    private val notifier: Notifier
) {
    // Process-lifetime scope: the sync loop and TCP server must survive Activity
    // destruction (BACK to launcher), so they never run on a viewModelScope.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastCheckinMs = 0L
    // When the current run of undelivered outbound messages began (0 = none pending).
    private var pendingStreakStartMs = 0L

    private val _lastCheckinResult = MutableStateFlow<String?>(null)
    val lastCheckinResult: StateFlow<String?> = _lastCheckinResult

    /** Handlers for non-pairing signal types (Phase 7 call signaling registers here). */
    private val signalHandlers = mutableMapOf<String, suspend (JSONObject) -> Unit>()

    fun registerSignalHandler(type: String, handler: suspend (JSONObject) -> Unit) {
        signalHandlers[type] = handler
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        tcpServer.start(engineScope)   // Phase 4: accept inbound peer connections
        // Inbound messages (text + media both pulse) raise a generic, preview-free
        // notification while the app is backgrounded. Process-scoped so it survives
        // Activity destruction, like the rest of the sync loop.
        engineScope.launch {
            messagePulse.pulses.collect { notifier.notifyNewMessage() }
        }
        job = engineScope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The loop must never die — log the tick failure and continue.
                    Log.w("AuroraSync", "sync tick failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                // Pace the next iteration. With an undelivered message, retry actively
                // (fast at first, tapering) so we catch the recipient's online window
                // within seconds; once everything is delivered, drop to the minimal
                // parked-wake listener. The foreground WakeService keeps this loop alive
                // while the app is backgrounded.
                pace()
            }
        }
    }

    /**
     * Choose the inter-tick delay. While outbound messages are pending we stay in an
     * "active" mode — fast retries for the first minute (the recipient usually returns
     * soon after a tap), tapering over the next few minutes — then fall back to the
     * parked long-poll. Minimal mode still retries on every parked return (~25s), so a
     * message is never abandoned; active mode just shortens the catch-up latency.
     */
    private suspend fun pace() {
        val hasPending = try { messageSender.hasPendingOutbound() } catch (e: Exception) { false }
        if (!hasPending) {
            pendingStreakStartMs = 0L
            awaitWakeOrDelay()
            return
        }
        val now = System.currentTimeMillis()
        if (pendingStreakStartMs == 0L) pendingStreakStartMs = now
        when (val elapsed = now - pendingStreakStartMs) {
            in 0 until ACTIVE_FAST_WINDOW_MS -> delay(ACTIVE_RETRY_FAST_MS)
            in ACTIVE_FAST_WINDOW_MS until ACTIVE_TOTAL_WINDOW_MS -> delay(ACTIVE_RETRY_SLOW_MS)
            else -> awaitWakeOrDelay()   // active window spent → minimal (still retries each park)
        }
    }

    /** Block until the server taps us awake, its hold window elapses, or we back off. */
    private suspend fun awaitWakeOrDelay() {
        val identity = try { identityManager.getOrCreate() } catch (e: Exception) {
            delay(POLL_INTERVAL_MS); return
        }
        val server = settings.serverAddress.value
        val result = rendezvousClient.waitForWake(server, identity)
        when {
            result.exceptionOrNull() is DrainUnauthorizedException -> {
                lastCheckinMs = 0L            // re-check-in next tick, then re-park
                delay(POLL_INTERVAL_MS)
            }
            result.isFailure -> delay(POLL_INTERVAL_MS)   // network error/timeout — back off
            // woken → loop straight to tick(); idle/unsupported → small breather so an
            // older server lacking /wait (immediate 404→false) can't busy-loop.
            result.getOrDefault(false) == false -> delay(WAKE_IDLE_BREATHER_MS)
        }
    }

    private suspend fun tick() {
        val identity = try { identityManager.getOrCreate() } catch (e: Exception) { return }
        val server = settings.serverAddress.value

        // Check in on start + every 5 minutes (server rate-limits to 1/min anyway).
        val now = System.currentTimeMillis()
        if (now - lastCheckinMs >= CHECKIN_INTERVAL_MS) {
            val result = rendezvousClient.checkIn(
                server, identity,
                advertisedOverride = settings.advertisedAddress.value.ifBlank { null }
            )
            if (result.isSuccess) {
                lastCheckinMs = now
                // Publish our forward-secret prekey bundle so initiators who scan our QR
                // can run the PQXDH handshake. Best-effort: an older server without
                // /prekeys (or a network hiccup) just means peers fall back to the legacy
                // handshake. Rotation/replenishment happens inside publicBundle().
                runCatching {
                    val bundle = prekeyManager.publicBundle(identity).toString()
                    rendezvousClient.publishPrekeys(server, identity, bundle)
                }
            }
            _lastCheckinResult.value = result.fold({ "ok" }, { it.message })
        }

        pairingManager.retryPendingPairingSends()

        // Phase 4: push any queued messages (peers that just came online get them now).
        messageSender.flushPending()

        // Drain and dispatch signals (signed request — see RendezvousClient.getSignals).
        val signalsResult = rendezvousClient.getSignals(server, identity)
        if (signalsResult.exceptionOrNull() is DrainUnauthorizedException) {
            // The server doesn't recognise us (e.g. it restarted) — force a fresh
            // check-in next tick so our drain authenticates again.
            lastCheckinMs = 0L
        }
        signalsResult.getOrNull()?.forEach { raw ->
            val json = try { JSONObject(raw) } catch (e: Exception) { return@forEach }
            when (val type = json.optString("type")) {
                "pairreq"     -> pairingManager.handlePairRequest(json)
                "pairaccept"  -> pairingManager.handlePairAccept(json)
                "pairreject"  -> pairingManager.handlePairReject(json)
                "paircancel"  -> pairingManager.handlePairCancel(json)
                "pairverify"  -> pairingManager.handlePairVerify(json)
                else          -> signalHandlers[type]?.invoke(json)
            }
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 5_000L
        const val CHECKIN_INTERVAL_MS = 5 * 60_000L
        /** Breather after an idle long-poll return so an old (no-/wait) server can't spin. */
        const val WAKE_IDLE_BREATHER_MS = 3_000L
        // Active retry while an outbound message is undelivered: fast for the first
        // minute, slower for the next few, then back to the parked listener.
        const val ACTIVE_RETRY_FAST_MS = 5_000L
        const val ACTIVE_RETRY_SLOW_MS = 12_000L
        const val ACTIVE_FAST_WINDOW_MS = 60_000L
        const val ACTIVE_TOTAL_WINDOW_MS = 5 * 60_000L
    }
}
