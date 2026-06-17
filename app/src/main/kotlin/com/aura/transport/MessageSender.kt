package com.aura.transport

import android.util.Base64
import com.aura.crypto.RatchetManager
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.MeshPeerDao
import com.aura.db.MeshPeerEntity
import com.aura.db.MessageDao
import com.aura.identity.IdentityStore
import com.aura.network.RendezvousClient
import com.aura.settings.AuroraSettings
import com.aura.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 outbound path: resolve a contact's address via the rendezvous server
 * (verifying which of the 10 candidates is real), then deliver pending
 * messages over TCP — through one mesh-peer relay hop when possible, falling
 * back to a direct connection (per spec: empty/unreachable peer table → direct).
 *
 * Phase 3 lives here too: every /find response's padding candidates are
 * persisted into the meshPeerTable as future ShadowMesh bootstrap peers.
 */
@Singleton
class MessageSender @Inject constructor(
    private val identityManager: IdentityStore,
    private val ratchet: RatchetManager,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val meshPeerDao: MeshPeerDao,
    private val rendezvousClient: RendezvousClient,
    private val settings: AuroraSettings,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val flushMutex = Mutex()

    /** Phase 6 hook: stamp expiry when one of our messages is acked (delivered). */
    var onOutboundDelivered: (suspend (messageId: String, contactNodeIdHex: String) -> Unit)? = null

    data class PeerAddress(val ip: String, val port: Int)

    /** True while any outbound message is still awaiting delivery (drives active retry). */
    suspend fun hasPendingOutbound(): Boolean = messageDao.contactsWithPending().isNotEmpty()

    /** Try to deliver all pending messages for all contacts. Safe to call often. */
    suspend fun flushPending() = flushMutex.withLock {
        val contactsWithPending = messageDao.contactsWithPending()
        for (nodeIdHex in contactsWithPending) {
            val contact = contactDao.byNodeId(nodeIdHex) ?: continue
            val address = resolvePeerAddress(contact)
            if (address == null) {
                android.util.Log.d("AuroraSend", "resolve failed for ${nodeIdHex.take(8)}")
                // Peer isn't registered right now — tap their wake channel so the
                // backgrounded app comes online; next flush delivers directly.
                rendezvousClient.tap(settings.serverAddress.value, nodeIdHex)
                continue
            }
            deliverPendingTo(contact, address)
        }
    }

    /** Resolve [contact]'s current address through the rendezvous server. */
    suspend fun resolvePeerAddress(contact: ContactEntity): PeerAddress? {
        val server = settings.serverAddress.value
        val candidates = rendezvousClient.find(server, contact.nodeIdHex).getOrNull() ?: return null
        val ed25519Pub = Base64.decode(contact.ed25519PubB64, Base64.NO_WRAP)
        val dilithiumPub = contact.dilithiumPubB64?.let { Base64.decode(it, Base64.NO_WRAP) }
        val real = rendezvousClient.verifyCandidates(contact.nodeIdHex, ed25519Pub, dilithiumPub, candidates)

        // Phase 3: the other 9 candidates seed the mesh peer table.
        val now = System.currentTimeMillis()
        meshPeerDao.upsertAll(
            candidates
                .filter { real == null || "${it.ip}:${it.port}" != "${real.ip}:${real.port}" }
                .map { MeshPeerEntity("${it.ip}:${it.port}", it.ip, it.port, now, now) }
        )

        return real?.let { PeerAddress(it.ip, it.port) }
    }

    private suspend fun deliverPendingTo(contact: ContactEntity, address: PeerAddress) {
        val pending = messageDao.pendingForContact(contact.nodeIdHex)
        if (pending.isEmpty()) return
        val identity = identityManager.getOrCreate()
        val myNodeId = identity.nodeId.toHex()

        for (message in pending) {
            // Idempotent retransmit: reuse the ratchet step assigned on first send,
            // otherwise seal now (which advances + discards this send-chain key).
            val sealedB64: String
            val n: Long
            val existingSealed = message.sealedB64
            val existingN = message.ratchetN
            if (existingSealed != null && existingN != null) {
                sealedB64 = existingSealed
                n = existingN
            } else {
                val inner = JSONObject()
                    .put("body", message.body)
                    .put("type", message.type)
                    .put("durationMs", message.durationMs ?: JSONObject.NULL)
                message.replyToId?.let { inner.put("replyToId", it) }
                message.replyPreview?.let { inner.put("replyPreview", it) }
                val aad = "aura-msg-v1|$myNodeId|${contact.nodeIdHex}".toByteArray()
                val sealed = ratchet.sealNext(
                    contact.nodeIdHex, inner.toString().toByteArray(Charsets.UTF_8), aad
                ) ?: continue
                sealedB64 = Base64.encodeToString(sealed.bytes, Base64.NO_WRAP)
                n = sealed.n
                messageDao.setSealed(message.id, sealedB64, n)
            }

            val frame = JSONObject()
                .put("t", "msg")
                .put("from", myNodeId)
                .put("to", contact.nodeIdHex)
                .put("id", message.id)
                .put("ts", message.timestampMs)
                .put("n", n)
                .put("sealed", sealedB64)

            val response = exchangeFrame(address, frame)
            if (response == null) {
                // Resolved but unreachable (peer dropped) — tap to wake, retry next flush.
                rendezvousClient.tap(settings.serverAddress.value, contact.nodeIdHex)
                break
            }
            if (response.optString("t") == "ack" && response.optString("id") == message.id) {
                messageDao.setStatus(message.id, "delivered")
                onOutboundDelivered?.invoke(message.id, contact.nodeIdHex)
            } else {
                messageDao.setStatus(message.id, "sent")
            }
        }
    }

    /** Send a sealed control frame (Phase 6 timer sync). Returns true if acked. */
    suspend fun sendControl(contact: ContactEntity, payload: JSONObject): Boolean {
        val identity = identityManager.getOrCreate()
        val myNodeId = identity.nodeId.toHex()
        val address = resolvePeerAddress(contact) ?: return false
        val aad = "aura-ctl-v1|$myNodeId|${contact.nodeIdHex}".toByteArray()
        val sealed = ratchet.sealNext(
            contact.nodeIdHex, payload.toString().toByteArray(Charsets.UTF_8), aad
        ) ?: return false
        val frame = JSONObject()
            .put("t", "ctl")
            .put("from", myNodeId)
            .put("to", contact.nodeIdHex)
            .put("n", sealed.n)
            .put("sealed", Base64.encodeToString(sealed.bytes, Base64.NO_WRAP))
        return exchangeFrame(address, frame)?.optString("t") == "ack"
    }

    /**
     * Deliver a media payload over a single direct TCP connection: a small "media"
     * start frame followed by the sealed bytes streamed as ~256 KiB "mediachunk"
     * frames, then read the peer's ack. Always direct (no relay) — large blobs
     * shouldn't be proxied through someone else's device, and chunking keeps each
     * frame tiny so the receiver never allocates a giant pre-auth buffer.
     */
    suspend fun sendMediaChunked(
        address: PeerAddress,
        startFrame: JSONObject,
        sealedBytes: ByteArray
    ): JSONObject? = withContext(ioDispatcher) {
        try {
            val chunkCount = (sealedBytes.size + MEDIA_CHUNK_BYTES - 1) / MEDIA_CHUNK_BYTES
            startFrame.put("chunks", chunkCount)
            val id = startFrame.optString("id")
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address.ip, address.port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = TcpMessageServer.SOCKET_TIMEOUT_MS
                val out = socket.getOutputStream()
                WireFrames.write(out, startFrame)
                var off = 0
                var i = 0
                while (off < sealedBytes.size) {
                    val end = minOf(off + MEDIA_CHUNK_BYTES, sealedBytes.size)
                    WireFrames.write(
                        out,
                        JSONObject()
                            .put("t", "mediachunk")
                            .put("id", id)
                            .put("i", i)
                            .put("data", Base64.encodeToString(sealedBytes.copyOfRange(off, end), Base64.NO_WRAP))
                    )
                    off = end; i++
                }
                out.flush()
                WireFrames.read(socket.getInputStream())   // the ack
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c   // never swallow coroutine cancellation
        } catch (e: Exception) {
            null
        }
    }

    /**
     * One round trip to [address]: preferred path is through a single onion
     * relay hop picked from the mesh peer table; on any relay failure, direct.
     */
    suspend fun exchangeFrame(address: PeerAddress, frame: JSONObject): JSONObject? =
        withContext(ioDispatcher) {
            relayExchange(address, frame) ?: directExchange(address, frame)
        }

    private fun directExchange(address: PeerAddress, frame: JSONObject): JSONObject? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address.ip, address.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = TcpMessageServer.SOCKET_TIMEOUT_MS
            WireFrames.write(socket.getOutputStream(), frame)
            WireFrames.read(socket.getInputStream())
        }
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c   // never swallow coroutine cancellation
    } catch (e: Exception) {
        null
    }

    private suspend fun relayExchange(address: PeerAddress, frame: JSONObject): JSONObject? {
        // ShadowMesh is opt-in: when off, never route our own traffic through a
        // relay hop — connect directly only (Terms §9 / Privacy §6).
        if (!settings.shadowMeshEnabled.value) return null
        val relays = meshPeerDao.all()
            .filter { it.ip != address.ip || it.port != address.port }
            .shuffled()
            .take(1)
        if (relays.isEmpty()) return null
        val relay = relays.first()

        return try {
            val framed = ByteArrayOutputStream().also { WireFrames.write(it, frame) }
            val relayFrame = JSONObject()
                .put("t", "relay")
                .put("ip", address.ip)
                .put("port", address.port)
                .put("payload", Base64.encodeToString(framed.toByteArray(), Base64.NO_WRAP))

            Socket().use { socket ->
                socket.connect(InetSocketAddress(relay.ip, relay.port), RELAY_CONNECT_TIMEOUT_MS)
                socket.soTimeout = TcpMessageServer.SOCKET_TIMEOUT_MS
                WireFrames.write(socket.getOutputStream(), relayFrame)
                val response = WireFrames.read(socket.getInputStream()) ?: return null
                if (response.optString("t") != "relayed") return null
                val payload = Base64.decode(response.optString("payload"), Base64.NO_WRAP)
                if (payload.isEmpty()) return null
                WireFrames.read(payload.inputStream())
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c   // never swallow coroutine cancellation
        } catch (e: Exception) {
            null  // dummy/unreachable relay → caller falls back to direct
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val RELAY_CONNECT_TIMEOUT_MS = 2_000
        /** Raw bytes per media chunk (~256 KiB → ~341 KiB base64, well under the frame cap). */
        const val MEDIA_CHUNK_BYTES = 256 * 1024
    }
}
