package com.aura.transport

import android.util.Base64
import com.aura.crypto.KemRatchetManager
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.identity.IdentityProvider
import com.aura.di.IoDispatcher
import com.aura.settings.AuroraSettings
import com.aura.ux.MessagePulse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4: accepts direct peer connections and handles inbound frames.
 *
 *  - "msg": authenticate + decrypt with the sender's shared secret, store,
 *           reply "ack" (the sender's double tick).
 *  - "relay": forward an opaque frame to the requested ip:port and pipe the
 *           response back — this node acting as the single onion hop for
 *           someone else. Payload is E2E sealed; we can't read it.
 *  - "ctl": sealed control messages (Phase 6 disappearing-timer sync).
 */
@Singleton
class TcpMessageServer @Inject constructor(
    private val identityManager: IdentityProvider,
    private val ratchet: KemRatchetManager,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val settings: AuroraSettings,
    private val messagePulse: MessagePulse,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : FrameInbox {
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null

    // Connection-flood guards (a frame can be large and is read pre-authentication).
    private val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)
    private val connectionsByIp = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** Phase 6 registers a handler for sealed control payloads (disappearing-timer sync). */
    var controlHandler: (suspend (fromNodeIdHex: String, plaintext: JSONObject) -> Unit)? = null
    /** Emoji-reaction control handler (Messenger reactions). */
    var reactionHandler: (suspend (fromNodeIdHex: String, plaintext: JSONObject) -> Unit)? = null
    /** Phase 5 media frames are routed here. */
    var mediaHandler: (suspend (frame: JSONObject, socket: Socket) -> Boolean)? = null
    /** Phase 6: stamp expiry when an incoming message lands. */
    var onMessageStored: (suspend (MessageEntity) -> Unit)? = null

    @Synchronized
    fun start(scope: CoroutineScope, port: Int = PORT) {
        if (job?.isActive == true && serverSocket?.isClosed == false) return
        try { serverSocket?.close() } catch (e: Exception) {}
        job?.cancel()
        job = scope.launch(ioDispatcher) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress(port))
                serverSocket = ss
                android.util.Log.i("AuroraTcp", "listening on $port")
                while (!ss.isClosed) {
                    val socket = try { ss.accept() } catch (e: Exception) { break }
                    // Bound concurrent connections (total + per remote IP) so a socket
                    // flood can't exhaust memory/threads with large pre-auth frames.
                    val ip = socket.inetAddress?.hostAddress ?: "?"
                    val ipCount = connectionsByIp.computeIfAbsent(ip) { java.util.concurrent.atomic.AtomicInteger(0) }
                    if (activeConnections.get() >= MAX_CONNECTIONS || ipCount.get() >= MAX_CONNECTIONS_PER_IP) {
                        try { socket.close() } catch (e: Exception) {}
                        continue
                    }
                    activeConnections.incrementAndGet()
                    ipCount.incrementAndGet()
                    launch(ioDispatcher) {
                        try { handleConnection(socket) } catch (e: Exception) {
                            android.util.Log.w("AuroraTcp", "connection handler failed: ${e.message}")
                        } finally {
                            activeConnections.decrementAndGet()
                            // Remove the per-IP entry once idle so the map can't grow unbounded
                            // across distinct/churning source IPs (slow memory DoS).
                            if (ipCount.decrementAndGet() <= 0) connectionsByIp.remove(ip, ipCount)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AuroraTcp", "server failed to start on $port: ${e.message}")
            }
        }
    }

    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        job?.cancel()
        job = null
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.use { s ->
            s.soTimeout = SOCKET_TIMEOUT_MS
            val input = s.getInputStream()
            val output = s.getOutputStream()
            while (true) {
                val frame = WireFrames.read(input) ?: break
                when (frame.optString("t")) {
                    "msg"   -> processMsg(frame)?.let { WireFrames.write(output, it) }
                    "ctl"   -> processCtl(frame)?.let { WireFrames.write(output, it) }
                    "relay" -> handleRelay(frame, output)
                    "media" -> { if (mediaHandler?.invoke(frame, s) != true) break }
                    else    -> break
                }
            }
        }
    }

    /**
     * Transport-agnostic frame handling reused by the WebRTC data channel
     * ([com.aura.transport.rtc.RtcTransport]). Returns the ack frame to send back, or
     * null when no response is warranted (frame not for us / unknown peer / decrypt
     * fails). The caller writes the ack over whatever transport delivered the frame.
     */
    override suspend fun processFrame(frame: JSONObject): JSONObject? = when (frame.optString("t")) {
        "msg" -> processMsg(frame)
        "ctl" -> processCtl(frame)
        else  -> null
    }

    private suspend fun processMsg(frame: JSONObject): JSONObject? {
        val identity = identityManager.getOrCreate()
        val myNodeId = identity.nodeId.toHex()
        val from = frame.optString("from")
        val to = frame.optString("to")
        if (to != myNodeId) return null

        contactDao.byNodeId(from) ?: return null   // ignore frames from unknown peers
        val id = frame.optString("id")

        // Already stored (a retransmit after a lost ack): re-ack idempotently. The
        // message id rides in the cleartext frame header, so no decryption needed —
        // and the ratchet key for this counter has already been consumed.
        if (id.isNotEmpty() && messageDao.byId(id) != null) {
            return JSONObject().put("t", "ack").put("id", id)
        }

        val sealed = Base64.decode(frame.optString("sealed"), Base64.NO_WRAP)
        val aad = "aura-msg-v1|$from|$to".toByteArray()

        val plaintext = ratchet.open(from, sealed, aad) ?: return null
        val inner = try { JSONObject(String(plaintext, Charsets.UTF_8)) } catch (e: Exception) { return null }

        val entity = MessageEntity(
            id               = frame.optString("id"),
            contactNodeIdHex = from,
            fromMe           = false,
            body             = inner.optString("body"),
            timestampMs      = frame.optLong("ts", System.currentTimeMillis()),
            status           = "delivered",
            type             = inner.optString("type", "text"),
            mediaPath        = null,
            replyToId        = inner.optString("replyToId").ifEmpty { null },
            replyPreview     = inner.optString("replyPreview").ifEmpty { null },
            durationMs       = if (inner.isNull("durationMs")) null else inner.optLong("durationMs")
        )
        messageDao.insert(entity)
        onMessageStored?.invoke(entity)
        messagePulse.pulse(from)

        return JSONObject().put("t", "ack").put("id", frame.optString("id"))
    }

    private suspend fun processCtl(frame: JSONObject): JSONObject? {
        val identity = identityManager.getOrCreate()
        val myNodeId = identity.nodeId.toHex()
        val from = frame.optString("from")
        if (frame.optString("to") != myNodeId) return null
        contactDao.byNodeId(from) ?: return null   // ignore frames from unknown peers

        val sealed = Base64.decode(frame.optString("sealed"), Base64.NO_WRAP)
        val aad = "aura-ctl-v1|$from|$myNodeId".toByteArray()
        val plaintext = ratchet.open(from, sealed, aad) ?: return null
        val inner = try { JSONObject(String(plaintext, Charsets.UTF_8)) } catch (e: Exception) { return null }

        // Route sealed control messages by their inner "ctl" type.
        when (inner.optString("ctl")) {
            // Auto-bootstrap: opening the frame above already advanced our receive ratchet, so
            // the responder can now send too. Nothing else to do — just ack.
            "bootstrap" -> { /* no-op */ }
            "react" -> reactionHandler?.invoke(from, inner)
            else    -> controlHandler?.invoke(from, inner)
        }
        return JSONObject().put("t", "ack").put("id", inner.optString("id"))
    }

    /** Single-hop onion relay: forward opaque bytes, pipe one response frame back. */
    private fun handleRelay(frame: JSONObject, output: java.io.OutputStream) {
        // ShadowMesh is opt-in: only relay on behalf of other users when the user
        // has joined the relay network (Terms §9 / Privacy §6). Otherwise ignore.
        if (!settings.shadowMeshEnabled.value) return
        val ip = frame.optString("ip")
        val port = frame.optInt("port", -1)
        if (ip.isEmpty() || port !in 1..65535) return

        // SSRF guard: resolve the target ONCE and only relay to a public unicast
        // address. A relay node must never be usable to reach loopback, private
        // (RFC-1918), link-local or multicast hosts — otherwise it becomes a proxy
        // into the relayer's internal network. Connecting to the resolved address
        // (not the string) also blocks DNS-rebinding.
        val targetAddr = try { java.net.InetAddress.getByName(ip) } catch (e: Exception) { return }
        if (targetAddr.isLoopbackAddress || targetAddr.isAnyLocalAddress ||
            targetAddr.isLinkLocalAddress || targetAddr.isSiteLocalAddress ||
            targetAddr.isMulticastAddress) return
        // isSiteLocalAddress only covers the deprecated IPv6 fec0::/10 — explicitly reject the
        // modern Unique-Local range fc00::/7, and conservatively refuse IPv4-mapped IPv6, so the
        // relay can't be steered into an internal IPv6 network.
        val raw = targetAddr.address
        if (raw.size == 16) {
            if ((raw[0].toInt() and 0xFE) == 0xFC) return                                  // fc00::/7 ULA
            val v4Mapped = (0..9).all { raw[it].toInt() == 0 } &&
                (raw[10].toInt() and 0xFF) == 0xFF && (raw[11].toInt() and 0xFF) == 0xFF
            if (v4Mapped) return
        }

        val payload = Base64.decode(frame.optString("payload"), Base64.NO_WRAP)

        try {
            Socket().use { target ->
                target.connect(java.net.InetSocketAddress(targetAddr, port), SOCKET_TIMEOUT_MS)
                target.soTimeout = SOCKET_TIMEOUT_MS
                target.getOutputStream().apply { write(payload); flush() }
                val response = WireFrames.read(target.getInputStream())
                WireFrames.write(
                    output,
                    JSONObject()
                        .put("t", "relayed")
                        .put("payload", if (response != null) {
                            val bytes = response.toString().toByteArray(Charsets.UTF_8)
                            val framed = java.io.ByteArrayOutputStream()
                            WireFrames.write(framed, response)
                            Base64.encodeToString(framed.toByteArray(), Base64.NO_WRAP)
                        } else "")
                )
            }
        } catch (e: Exception) {
            // Relay target unreachable — close silently; the origin falls back to direct.
        }
    }

    companion object {
        const val PORT = 8765
        const val SOCKET_TIMEOUT_MS = 15_000
        const val MAX_CONNECTIONS = 48
        const val MAX_CONNECTIONS_PER_IP = 6
    }
}
