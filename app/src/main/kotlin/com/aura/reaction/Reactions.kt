package com.aura.reaction

import com.aura.db.ContactDao
import com.aura.db.MessageDao
import com.aura.transport.MessageSender
import com.aura.transport.TcpMessageServer
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Messenger-style emoji reactions. A reaction is a sealed control message
 * (`{ctl:"react", id, emoji}`) referencing a message id both peers share. The
 * reaction travels over the same end-to-end-encrypted control channel as the
 * disappearing-timer sync, so the server never sees who reacted with what.
 */
@Singleton
class Reactions @Inject constructor(
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val messageSender: MessageSender,
    private val tcpServer: TcpMessageServer
) {
    /** Register the inbound reaction handler (called once at startup). */
    fun start() {
        tcpServer.reactionHandler = handler@{ fromNodeIdHex, plaintext ->
            val targetId = plaintext.optString("id")
            if (targetId.isEmpty()) return@handler
            // Confirm the reacted message belongs to this conversation.
            val msg = messageDao.byId(targetId) ?: return@handler
            if (msg.contactNodeIdHex != fromNodeIdHex) return@handler
            val emoji = plaintext.optString("emoji").ifEmpty { null }
            messageDao.setTheirReaction(targetId, emoji)
        }
    }

    /** React to (or clear, with null) a message and tell the peer. */
    suspend fun react(contactNodeIdHex: String, messageId: String, emoji: String?) {
        messageDao.setMyReaction(messageId, emoji)
        val contact = contactDao.byNodeId(contactNodeIdHex) ?: return
        val payload = JSONObject()
            .put("ctl", "react")
            .put("id", messageId)
            .put("emoji", emoji ?: JSONObject.NULL)
        runCatching { messageSender.sendControl(contact, payload) }
    }
}
