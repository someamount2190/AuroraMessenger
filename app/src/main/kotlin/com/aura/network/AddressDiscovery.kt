package com.aura.network

import com.aura.server.RendezvousServerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers the addresses a host beacon can advertise (Phase: easier P2P
 * onboarding) and classifies how reachable it actually is.
 *
 * Reachability is the honest crux of "be a beacon over the internet":
 *  - LAN: the local IPv4 is always reachable by peers on the same Wi-Fi.
 *  - INTERNET: only when the router maps a port to us. We try NAT-PMP/PCP
 *    (common on home routers) to open the rendezvous port automatically. On
 *    carrier-grade NAT (most mobile data) no mapping is possible — we report
 *    that plainly instead of advertising an address that will never connect.
 */
@Singleton
class AddressDiscovery @Inject constructor() {

    enum class Reach { LAN_ONLY, INTERNET, UNKNOWN }

    data class HostAddresses(
        val candidates: List<String>,   // ordered base URLs to embed in the QR
        val lanIp: String?,
        val publicIp: String?,
        val portMapped: Boolean,
        val reach: Reach
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /** Build the advertised candidate list for hosting on [port]. */
    suspend fun discover(port: Int): HostAddresses = withContext(Dispatchers.IO) {
        val lan = RendezvousServerController.localIpAddress()
        val pub = publicIp()

        // Best-effort automatic port mapping so the public address is actually reachable.
        val mapped = if (pub != null && lan != null) tryNatPmpMap(port) else false

        val candidates = buildList {
            // LAN first: zero-setup, instant on the same network.
            if (lan != null) add("http://$lan:$port")
            // Emulator test affordance: on an AVD the LAN IP (10.0.2.15) is the
            // emulator's own isolated NAT and unreachable by a peer emulator. The
            // host-loopback alias 10.0.2.2 is bridged to the dev host by adb, so a
            // peer emulator can reach this server through it. No-op on real devices.
            if (isEmulator() && lan == "10.0.2.15") add("http://10.0.2.2:$port")
            // Public only if we believe a peer could reach it.
            if (pub != null && mapped) add("http://$pub:$port")
        }

        val reach = when {
            pub != null && mapped -> Reach.INTERNET
            lan != null           -> Reach.LAN_ONLY
            else                  -> Reach.UNKNOWN
        }
        HostAddresses(candidates, lan, pub, mapped, reach)
    }

    /** Best-effort public IP via a plain HTTP echo. Null if the network blocks it. */
    suspend fun publicIp(): String? = withContext(Dispatchers.IO) {
        for (url in PUBLIC_IP_ECHOS) {
            val ip = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string()?.trim() else null
                }
            }.getOrNull()
            if (ip != null && ip.matches(IPV4_REGEX)) return@withContext ip
        }
        null
    }

    /**
     * Attempt a NAT-PMP (RFC 6886) external→internal TCP port mapping with the
     * default gateway. Returns true if the gateway acknowledged the mapping.
     * Silently false on PCP-only / UPnP-only / CGNAT routers.
     */
    private suspend fun tryNatPmpMap(port: Int): Boolean = withContext(Dispatchers.IO) {
        val gateway = defaultGateway() ?: return@withContext false
        withTimeoutOrNull(1500) {
            runCatching {
                DatagramSocket().use { sock ->
                    sock.soTimeout = 1000
                    // NAT-PMP MAP TCP request: ver=0, op=2, reserved=0,
                    // internal port, suggested external port, lifetime=3600s.
                    val req = ByteArray(12)
                    req[0] = 0; req[1] = 2
                    req[4] = (port shr 8).toByte(); req[5] = port.toByte()
                    req[6] = (port shr 8).toByte(); req[7] = port.toByte()
                    req[8] = 0; req[9] = 0; req[10] = 0x0E; req[11] = 0x10  // 3600s
                    sock.send(DatagramPacket(req, req.size, gateway, 5351))

                    val resp = ByteArray(16)
                    sock.receive(DatagramPacket(resp, resp.size))
                    // ver=0, op=130 (response), result code 0 == success
                    resp[0].toInt() == 0 && (resp[1].toInt() and 0xFF) == 130 &&
                        resp[2].toInt() == 0 && resp[3].toInt() == 0
                }
            }.getOrDefault(false)
        } ?: false
    }

    /** Derive the likely default-gateway address from our LAN IPv4 (x.y.z.1). */
    private fun defaultGateway(): InetAddress? {
        val lan = RendezvousServerController.localIpAddress() ?: return null
        val parts = lan.split(".")
        if (parts.size != 4) return null
        return runCatching { InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.1") }.getOrNull()
    }

    private fun isEmulator(): Boolean {
        val fp = android.os.Build.FINGERPRINT ?: ""
        return fp.contains("generic") || fp.contains("emulator") ||
            android.os.Build.MODEL.contains("sdk", ignoreCase = true) ||
            android.os.Build.PRODUCT.contains("sdk", ignoreCase = true)
    }

    companion object {
        private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
        private val PUBLIC_IP_ECHOS = listOf(
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://icanhazip.com"
        )
    }
}
