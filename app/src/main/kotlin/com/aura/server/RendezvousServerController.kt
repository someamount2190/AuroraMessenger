package com.aura.server

import com.aura.crypto.HybridSigner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/** Lifecycle + status for the in-app rendezvous server (Phase 0 Server Mode). */
@Singleton
class RendezvousServerController @Inject constructor(
    private val signer: HybridSigner
) {
    sealed interface Status {
        data object Stopped : Status
        data class Running(
            val port: Int,
            val localIp: String,
            val startedAtMs: Long,
            val nodeCount: Int
        ) : Status
        data class Error(val message: String) : Status
    }

    private var server: AuroraRendezvousServer? = null
    private var startedAtMs: Long = 0

    private val _status = MutableStateFlow<Status>(Status.Stopped)
    val status: StateFlow<Status> = _status

    @Synchronized
    fun start(port: Int = AuroraRendezvousServer.DEFAULT_PORT) {
        if (server != null) return
        try {
            val s = AuroraRendezvousServer(port, signer)
            s.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
            startedAtMs = System.currentTimeMillis()
            refreshStatus()
        } catch (e: Exception) {
            _status.value = Status.Error(e.message ?: "failed to start server")
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        _status.value = Status.Stopped
    }

    /** Re-poll node count / uptime; cheap, called by UI on a ticker. */
    fun refreshStatus() {
        val s = server ?: return
        _status.value = Status.Running(
            port        = AuroraRendezvousServer.DEFAULT_PORT,
            localIp     = localIpAddress() ?: "unknown",
            startedAtMs = startedAtMs,
            nodeCount   = s.registeredNodeCount
        )
    }

    companion object {
        /** Best-effort local IPv4 (on the emulator this is typically 10.0.2.15). */
        fun localIpAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
