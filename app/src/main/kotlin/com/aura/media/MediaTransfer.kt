package com.aura.media

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.identity.IdentityStore
import com.aura.transport.MessageSender
import com.aura.transport.TcpMessageServer
import com.aura.transport.WireFrames
import com.aura.transport.rtc.PeerTransport
import com.aura.ux.MessagePulse
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aura.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Socket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 5: send and receive encrypted media over the existing TCP transport.
 *
 * A media message is one "media" wire frame carrying the whole sealed blob
 * (Base64) plus metadata. The blob is XChaCha20-Poly1305 sealed with the
 * conversation's shared secret — identical ciphertext stored at rest and on the
 * wire. The receiver writes it straight to encrypted storage and decrypts only
 * for preview/export. Progress is surfaced as a 0..1 fraction per message id.
 */
@Singleton
class MediaTransfer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityStore,
    private val kemRatchet: com.aura.crypto.KemRatchetManager,   // wire seal/open + media-at-rest key
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val mediaStore: EncryptedMediaStore,
    private val messageSender: MessageSender,
    private val tcpServer: TcpMessageServer,
    private val rtcTransport: PeerTransport,
    private val messagePulse: MessagePulse,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    // Serializes the background retry sweep; the per-id [inFlight] set stops a retry from
    // racing the original inline send (or another sweep) into a double delivery.
    private val flushMutex = Mutex()
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Register inbound media handling on both transports (called once at startup). */
    fun wireReceiver() {
        // TCP: drain the streamed chunks off the socket, then store + ack.
        tcpServer.mediaHandler = handler@{ frame, socket ->
            handleIncomingTcp(frame, socket)
        }
        // RTC: the data session reassembles the blob; we just store + ack.
        rtcTransport.mediaHandler = { meta, sealed -> storeIncomingMedia(meta, sealed) }
    }

    /** Pick → seal → store → send a gallery image/video. Returns the new message id. */
    suspend fun sendMedia(contactNodeIdHex: String, uri: Uri, type: String): String? =
        withContext(ioDispatcher) {
            val plaintext = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            sendBytes(contactNodeIdHex, plaintext, type, durationMs = null)
        }

    /** Seal → store → send a recorded voice message. */
    suspend fun sendAudio(contactNodeIdHex: String, plaintext: ByteArray, durationMs: Long): String? =
        withContext(ioDispatcher) { sendBytes(contactNodeIdHex, plaintext, "audio", durationMs) }

    private suspend fun sendBytes(
        contactNodeIdHex: String,
        plaintext: ByteArray,
        type: String,
        durationMs: Long?
    ): String? {
        contactDao.byNodeId(contactNodeIdHex) ?: return null
        if (plaintext.size > EncryptedMediaStore.MAX_MEDIA_BYTES) return null
        // Local-only key: media is encrypted at rest with a key that survives the
        // ratchet (it never travels), so old media stays viewable on this device.
        val mediaKey = kemRatchet.mediaKey(contactNodeIdHex) ?: return null

        val messageId = UUID.randomUUID().toString()
        setProgress(messageId, 0.05f)

        val path = mediaStore.writeEncrypted(messageId, plaintext, mediaKey)
        messageDao.insert(
            MessageEntity(
                id               = messageId,
                contactNodeIdHex = contactNodeIdHex,
                fromMe           = true,
                body             = bodyLabel(type),
                timestampMs      = System.currentTimeMillis(),
                status           = "pending",
                type             = type,
                mediaPath        = path,
                durationMs       = durationMs,
                read             = true
            )
        )
        setProgress(messageId, 0.2f)

        attemptDeliver(messageId, contactNodeIdHex, type, plaintext, durationMs)
        return messageId
    }

    /**
     * Try to deliver one media message and stamp the result. Guarded by [inFlight] so the
     * inline send and the background [flushPendingMedia] sweep never deliver the same blob
     * twice. A failed send leaves the row "pending" for the next sweep to retry (once an RTC
     * session warms up, a CGNAT peer TCP can't reach becomes reachable).
     */
    private suspend fun attemptDeliver(
        messageId: String,
        contactNodeIdHex: String,
        type: String,
        plaintext: ByteArray,
        durationMs: Long?
    ): Boolean {
        if (!inFlight.add(messageId)) return false   // already being sent elsewhere
        return try {
            val delivered = deliverMedia(contactNodeIdHex, messageId, type, plaintext, durationMs)
            messageDao.setStatus(messageId, if (delivered) "delivered" else "pending")
            setProgress(messageId, if (delivered) 1f else 0f)
            delivered
        } finally {
            inFlight.remove(messageId)
        }
    }

    /**
     * Retry every still-pending outbound media message. Media isn't queued through
     * [MessageSender.flushPending] (that path only carries text frames — it would re-send a
     * photo as the literal "📷 Photo" label), so the sync loop drives this sweep instead. The
     * sealed wire blob is re-derived from the at-rest file, so nothing large is kept in the DB.
     */
    suspend fun flushPendingMedia() = flushMutex.withLock {
        for (msg in messageDao.pendingMedia()) {
            val path = msg.mediaPath ?: continue
            val mediaKey = kemRatchet.mediaKey(msg.contactNodeIdHex) ?: continue
            val plaintext = mediaStore.readDecrypted(path, mediaKey) ?: continue
            attemptDeliver(msg.id, msg.contactNodeIdHex, msg.type, plaintext, msg.durationMs)
        }
    }

    private fun bodyLabel(type: String) = when (type) {
        "video" -> "📹 Video"
        "audio" -> "🎤 Voice message"
        else    -> "📷 Photo"
    }

    private suspend fun deliverMedia(
        contactNodeIdHex: String,
        messageId: String,
        type: String,
        plaintext: ByteArray,
        durationMs: Long?
    ): Boolean {
        val identity = identityManager.getOrCreate()
        val contact = contactDao.byNodeId(contactNodeIdHex) ?: return false
        // Seal the blob for the wire with a fresh ratchet key (distinct from the
        // at-rest key), so intercepted media gets the same forward secrecy as text.
        val sealed = kemRatchet.sealNext(contactNodeIdHex, plaintext, MEDIA_WIRE_AAD) ?: return false

        // The sealed bytes are streamed as mediachunk frames by the transport, so the start
        // frame carries only metadata (no blob); each transport stamps its own chunk count.
        val startFrame = JSONObject()
            .put("t", "media")
            .put("from", identity.nodeId.toHex())
            .put("to", contactNodeIdHex)
            .put("id", messageId)
            .put("ts", System.currentTimeMillis())
            .put("mtype", type)
            .put("duration", durationMs ?: JSONObject.NULL)
            .put("n", sealed.n)

        setProgress(messageId, 0.6f)

        // Preferred path: the peer-to-peer WebRTC data channel — the same NAT-traversing
        // route messages and calls take. Falls back to direct TCP for LAN / reachable peers.
        if (rtcTransport.isConnected(contactNodeIdHex)) {
            val ack = rtcTransport.sendMedia(contactNodeIdHex, startFrame, sealed.bytes)
            if (ack?.optString("t") == "ack" && ack.optString("id") == messageId) return true
        } else {
            // Warm an RTC session so the next sweep can use it (a CGNAT peer TCP can't reach).
            rtcTransport.connectAsync(contactNodeIdHex)
        }

        val address = messageSender.resolvePeerAddress(contact) ?: return false
        val response = messageSender.sendMediaChunked(address, startFrame, sealed.bytes)
        return response?.optString("t") == "ack" && response.optString("id") == messageId
    }

    /**
     * TCP server callback: drain the streamed chunks off the socket, then store + ack.
     * (The RTC path reassembles the blob in the data session and calls [storeIncomingMedia]
     * directly — no socket draining needed.)
     */
    private suspend fun handleIncomingTcp(frame: JSONObject, socket: Socket): Boolean {
        val identity = identityManager.getOrCreate()
        val myNodeId = identity.nodeId.toHex()
        val from = frame.optString("from")
        if (frame.optString("to") != myNodeId) return false
        contactDao.byNodeId(from) ?: return false

        val messageId = frame.optString("id")
        val chunkCount = frame.optInt("chunks", -1)
        if (chunkCount < 0 || chunkCount > MAX_MEDIA_CHUNKS) return false

        // Drain the streamed mediachunk frames (always — the sender writes them
        // regardless of whether we already have the message) and reassemble the sealed
        // blob, bailing if it would exceed the media size cap.
        val input = socket.getInputStream()
        val buf = java.io.ByteArrayOutputStream()
        var received = 0
        while (received < chunkCount) {
            val cf = WireFrames.read(input) ?: return false
            if (cf.optString("t") != "mediachunk" || cf.optString("id") != messageId) return false
            buf.write(Base64.decode(cf.optString("data"), Base64.NO_WRAP))
            if (buf.size() > EncryptedMediaStore.MAX_MEDIA_BYTES + REASSEMBLY_SLACK) return false
            received++
        }

        val ack = storeIncomingMedia(frame, buf.toByteArray()) ?: return false
        WireFrames.write(socket.getOutputStream(), ack)
        return true
    }

    /**
     * Transport-agnostic: validate, decrypt and persist a fully-reassembled sealed media
     * blob, returning the ack frame to send back over whichever transport delivered it (the
     * RTC data channel or the TCP socket), or null if the frame isn't for us / can't be opened.
     */
    private suspend fun storeIncomingMedia(meta: JSONObject, sealed: ByteArray): JSONObject? {
        val identity = identityManager.getOrCreate()
        val myNodeId = identity.nodeId.toHex()
        val from = meta.optString("from")
        if (meta.optString("to") != myNodeId) return null
        contactDao.byNodeId(from) ?: return null

        val messageId = meta.optString("id")
        // Reject a peer-supplied id that isn't filename-safe (path traversal) before it ever
        // reaches the media store. Drops the frame cleanly rather than throwing.
        if (!mediaStore.isValidId(messageId)) return null

        // Retransmit after a lost ack: already stored → re-ack without decrypting
        // (the ratchet key for this counter is already spent).
        if (messageId.isNotEmpty() && messageDao.byId(messageId) != null) {
            return JSONObject().put("t", "ack").put("id", messageId)
        }

        val type = meta.optString("mtype", "image")
        val duration = if (meta.isNull("duration")) null else meta.optLong("duration")

        // Unseal the wire blob, then re-encrypt at rest with the local media key.
        val plaintext = kemRatchet.open(from, sealed, MEDIA_WIRE_AAD) ?: return null
        val mediaKey = kemRatchet.mediaKey(from) ?: return null
        val path = mediaStore.writeEncrypted(messageId, plaintext, mediaKey)
        messageDao.insert(
            MessageEntity(
                id               = messageId,
                contactNodeIdHex = from,
                fromMe           = false,
                body             = bodyLabel(type),
                timestampMs      = meta.optLong("ts", System.currentTimeMillis()),
                status           = "delivered",
                type             = type,
                mediaPath        = path,
                durationMs       = duration
            )
        )
        messagePulse.pulse(from)
        return JSONObject().put("t", "ack").put("id", messageId)
    }

    /** Decrypt a stored media file for preview (returns plaintext bytes). */
    suspend fun decryptForPreview(contactNodeIdHex: String, path: String): ByteArray? {
        val mediaKey = kemRatchet.mediaKey(contactNodeIdHex) ?: return null
        return mediaStore.readDecrypted(path, mediaKey)
    }

    private fun setProgress(id: String, value: Float) {
        _progress.value = _progress.value.toMutableMap().apply { put(id, value) }
    }

    private companion object {
        // Wire-transport AAD for media frames (the at-rest blob uses its own AAD).
        private val MEDIA_WIRE_AAD = "aura-media-v1".toByteArray()
        // 256 × 256 KiB ≈ 64 MiB ceiling on a reassembled media blob.
        private const val MAX_MEDIA_CHUNKS = 256
        private const val REASSEMBLY_SLACK = 64 * 1024
    }
}
