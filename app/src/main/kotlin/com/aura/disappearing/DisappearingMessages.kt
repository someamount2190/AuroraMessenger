package com.aura.disappearing

import com.aura.db.ContactDao
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.media.EncryptedMediaStore
import com.aura.settings.DisappearingTimer
import com.aura.transport.MessageSender
import com.aura.transport.TcpMessageServer
import com.aura.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 6: per-conversation disappearing messages.
 *
 * The timer starts at DELIVERY (status flips to "delivered"), independently on
 * each device — `expiresAtMs` is stamped when a message lands or is acked. A
 * purge sweep deletes expired rows and their media files on both sides. Timer
 * changes propagate as sealed "ctl" control messages so both peers agree on the
 * default for new messages.
 */
@Singleton
class DisappearingMessages @Inject constructor(
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val mediaStore: EncryptedMediaStore,
    private val messageSender: MessageSender,
    private val tcpServer: TcpMessageServer,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val scope = CoroutineScope(ioDispatcher)
    private var sweepJob: Job? = null

    fun start() {
        // Receive timer-change control messages from peers.
        tcpServer.controlHandler = handler@{ fromNodeIdHex, plaintext ->
            if (plaintext.optString("ctl") == "timer") {
                val key = DisappearingTimer.fromKey(plaintext.optString("timer")).key
                contactDao.setDisappearingTimer(fromNodeIdHex, key)
            }
        }
        // Stamp expiry on inbound messages as they are stored.
        tcpServer.onMessageStored = { message -> stampExpiryIfNeeded(message) }
        // Stamp expiry on our own messages once they're acked (delivered).
        messageSender.onOutboundDelivered = { messageId, contactNodeIdHex ->
            stampOutbound(messageId, contactNodeIdHex)
        }

        if (sweepJob?.isActive == true) return
        sweepJob = scope.launch {
            while (isActive) {
                purgeExpired()
                delay(SWEEP_INTERVAL_MS)
            }
        }
    }

    /** Stamp expiry for a freshly delivered [message] based on its contact's timer. */
    suspend fun stampExpiryIfNeeded(message: MessageEntity) {
        if (message.expiresAtMs != null) return
        val contact = contactDao.byNodeId(message.contactNodeIdHex) ?: return
        val timer = DisappearingTimer.fromKey(contact.disappearingTimer)
        val ttl = ttlMillis(timer) ?: return
        messageDao.setExpiry(message.id, System.currentTimeMillis() + ttl)
    }

    /** Stamp expiry for a specific outbound message once it reaches "delivered". */
    private suspend fun stampOutbound(messageId: String, contactNodeIdHex: String) {
        val contact = contactDao.byNodeId(contactNodeIdHex) ?: return
        val ttl = ttlMillis(DisappearingTimer.fromKey(contact.disappearingTimer)) ?: return
        messageDao.setExpiry(messageId, System.currentTimeMillis() + ttl)
    }

    /** Change the timer for a conversation and notify the peer. */
    suspend fun setTimer(contactNodeIdHex: String, timer: DisappearingTimer) {
        contactDao.setDisappearingTimer(contactNodeIdHex, timer.key)
        val contact = contactDao.byNodeId(contactNodeIdHex) ?: return
        val payload = JSONObject()
            .put("ctl", "timer")
            .put("id", java.util.UUID.randomUUID().toString())
            .put("timer", timer.key)
        runCatching { messageSender.sendControl(contact, payload) }
    }

    internal suspend fun purgeExpired() {
        val now = System.currentTimeMillis()
        val expired = messageDao.expired(now)
        if (expired.isEmpty()) return
        expired.forEach { message -> message.mediaPath?.let { mediaStore.delete(it) } }
        messageDao.deleteByIds(expired.map { it.id })
    }

    private fun ttlMillis(timer: DisappearingTimer): Long? = when (timer) {
        DisappearingTimer.OFF      -> null
        DisappearingTimer.ONE_HOUR -> 60 * 60_000L
        DisappearingTimer.ONE_DAY  -> 24 * 60 * 60_000L
        DisappearingTimer.ONE_WEEK -> 7 * 24 * 60 * 60_000L
    }

    companion object {
        const val SWEEP_INTERVAL_MS = 30_000L
    }
}
