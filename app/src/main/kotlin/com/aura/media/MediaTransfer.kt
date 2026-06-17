package com.aura.media

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.aura.crypto.RatchetManager
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.identity.IdentityStore
import com.aura.transport.MessageSender
import com.aura.transport.TcpMessageServer
import com.aura.transport.WireFrames
import com.aura.ux.MessagePulse
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aura.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val ratchet: RatchetManager,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val mediaStore: EncryptedMediaStore,
    private val messageSender: MessageSender,
    private val tcpServer: TcpMessageServer,
    private val messagePulse: MessagePulse,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    /** Register inbound media handling on the TCP server (called once at startup). */
    fun wireReceiver() {
        tcpServer.mediaHandler = handler@{ frame, socket ->
            handleIncoming(frame, socket)
        }
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
        val mediaKey = ratchet.mediaKey(contactNodeIdHex) ?: return null

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

        val delivered = deliverMedia(contactNodeIdHex, messageId, type, plaintext, durationMs)
        messageDao.setStatus(messageId, if (delivered) "delivered" else "pending")
        setProgress(messageId, if (delivered) 1f else 0f)
        return messageId
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
        val address = messageSender.resolvePeerAddress(contact) ?: return false
        // Seal the blob for the wire with a fresh ratchet key (distinct from the
        // at-rest key), so intercepted media gets the same forward secrecy as text.
        val sealed = ratchet.sealNext(contactNodeIdHex, plaintext, MEDIA_WIRE_AAD) ?: return false

        // The sealed bytes are streamed as mediachunk frames by sendMediaChunked, so
        // the start frame carries only metadata (no blob).
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
        val response = messageSender.sendMediaChunked(address, startFrame, sealed.bytes)
        return response?.optString("t") == "ack" && response.optString("id") == messageId
    }

    /** TCP server callback: store an inbound media blob and ack. */
    private suspend fun handleIncoming(frame: JSONObject, socket: Socket): Boolean {
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
        val sealed = buf.toByteArray()

        // Retransmit after a lost ack: already stored → re-ack without decrypting
        // (the ratchet key for this counter is already spent).
        if (messageId.isNotEmpty() && messageDao.byId(messageId) != null) {
            com.aura.transport.WireFrames.write(
                socket.getOutputStream(), JSONObject().put("t", "ack").put("id", messageId)
            )
            return true
        }

        val type = frame.optString("mtype", "image")
        val n = frame.optLong("n", -1)
        val duration = if (frame.isNull("duration")) null else frame.optLong("duration")

        // Unseal the wire blob, then re-encrypt at rest with the local media key.
        val plaintext = ratchet.open(from, n, sealed, MEDIA_WIRE_AAD) ?: return false
        val mediaKey = ratchet.mediaKey(from) ?: return false
        val path = mediaStore.writeEncrypted(messageId, plaintext, mediaKey)
        messageDao.insert(
            MessageEntity(
                id               = messageId,
                contactNodeIdHex = from,
                fromMe           = false,
                body             = bodyLabel(type),
                timestampMs      = frame.optLong("ts", System.currentTimeMillis()),
                status           = "delivered",
                type             = type,
                mediaPath        = path,
                durationMs       = duration
            )
        )
        messagePulse.pulse(from)

        com.aura.transport.WireFrames.write(
            socket.getOutputStream(),
            JSONObject().put("t", "ack").put("id", messageId)
        )
        return true
    }

    /** Decrypt a stored media file for preview (returns plaintext bytes). */
    suspend fun decryptForPreview(contactNodeIdHex: String, path: String): ByteArray? {
        val mediaKey = ratchet.mediaKey(contactNodeIdHex) ?: return null
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
