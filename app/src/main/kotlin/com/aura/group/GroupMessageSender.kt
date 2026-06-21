package com.aura.group

import android.util.Base64
import com.aura.crypto.RatchetManager
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.GroupDao
import com.aura.db.GroupDeliveryEntity
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.di.IoDispatcher
import com.aura.identity.IdentityStore
import com.aura.transport.MessageSender
import com.aura.transport.carry.CarryProtocol
import com.aura.transport.rtc.RtcTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Group messaging by **pairwise fan-out** (piece 2): a group message is sealed once per member
 * over that member's existing double-ratchet session and delivered over the same transport as a
 * 1:1 message (prefer the RTC data channel, fall back to direct TCP). Each recipient gets the
 * strongest 1:1 guarantees (forward secrecy + post-compromise security); sender authenticity is
 * free from the pairwise seal. The authenticated `groupId` rides *inside* the sealed payload, so
 * a courier or observer can't move a message between groups.
 *
 * One logical message row is stored (conversation key = groupId); per-member progress lives in
 * `group_delivery`, retried by [flushGroupPending] from the sync loop — the group analogue of
 * [MessageSender.flushPending]. Delivery to a member who isn't (yet) a contact is skipped.
 */
@Singleton
class GroupMessageSender @Inject constructor(
    private val ratchet: RatchetManager,
    private val identityManager: IdentityStore,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val groupDao: GroupDao,
    private val rtcTransport: RtcTransport,
    private val messageSender: MessageSender,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val flushMutex = Mutex()

    /** Post a message to [groupId]. Returns the new message id, or null if we can't send to it. */
    suspend fun sendGroupMessage(
        groupId: String,
        body: String,
        type: String = "text",
        replyToId: String? = null,
        replyPreview: String? = null
    ): String? = withContext(ioDispatcher) {
        val me = identityManager.getOrCreate().nodeId.toHex()
        val group = groupDao.group(groupId) ?: return@withContext null
        if (!group.active || !groupDao.isMember(groupId, me)) return@withContext null

        val messageId = UUID.randomUUID().toString()
        messageDao.insert(
            MessageEntity(
                id = messageId,
                contactNodeIdHex = groupId,
                fromMe = true,
                body = body,
                timestampMs = System.currentTimeMillis(),
                status = "pending",
                type = type,
                replyToId = replyToId,
                replyPreview = replyPreview,
                read = true,
                groupId = groupId,
                senderNodeId = me
            )
        )
        groupDao.members(groupId).filter { it.nodeId != me }.forEach { m ->
            groupDao.upsertDelivery(GroupDeliveryEntity(messageId, groupId, m.nodeId, "pending"))
        }
        flushGroupPending()
        messageId
    }

    /**
     * Deliver every pending group envelope. Seals per member on first attempt (storing the sealed
     * bytes + ratchet counter so a retry reuses them, like the 1:1 path), then sends; on the
     * member's ack the row is marked delivered, and the logical message flips to "delivered" once
     * no member is outstanding. Safe to call often.
     */
    suspend fun flushGroupPending() = flushMutex.withLock {
        withContext(ioDispatcher) {
            val me = identityManager.getOrCreate().nodeId.toHex()
            for (d in groupDao.pendingDeliveries()) {
                val msg = messageDao.byId(d.msgId)
                if (msg == null) { groupDao.markDelivered(d.msgId, d.memberNodeId); continue } // orphan
                contactDao.byNodeId(d.memberNodeId) ?: continue                                // not a contact

                val sealedB64: String
                val n: Long
                if (d.sealedB64 != null && d.ratchetN != null) {
                    sealedB64 = d.sealedB64; n = d.ratchetN
                } else {
                    val inner = JSONObject()
                        .put("body", msg.body)
                        .put("type", msg.type)
                        .put("groupId", d.groupId)   // authenticated: lives inside the seal
                        .put("durationMs", msg.durationMs ?: JSONObject.NULL)
                    msg.replyToId?.let { inner.put("replyToId", it) }
                    msg.replyPreview?.let { inner.put("replyPreview", it) }
                    val aad = "aura-msg-v1|$me|${d.memberNodeId}".toByteArray()
                    val sealed = ratchet.sealNext(
                        d.memberNodeId, inner.toString().toByteArray(Charsets.UTF_8), aad
                    ) ?: continue
                    sealedB64 = Base64.encodeToString(sealed.bytes, Base64.NO_WRAP)
                    n = sealed.n
                    groupDao.setDeliverySealed(d.msgId, d.memberNodeId, sealedB64, n)
                }

                val frame = JSONObject()
                    .put("t", "msg")
                    .put("from", me)
                    .put("to", d.memberNodeId)
                    .put("id", d.msgId)
                    .put("ts", msg.timestampMs)
                    .put("n", n)
                    .put("sealed", sealedB64)

                val ack = deliver(d.memberNodeId, frame)
                if (ack?.optString("t") == "ack" && ack.optString("id") == d.msgId) {
                    groupDao.markDelivered(d.msgId, d.memberNodeId)
                    if (groupDao.pendingCount(d.msgId) == 0) messageDao.setStatus(d.msgId, "delivered")
                } else {
                    // Couldn't reach the member directly — leave it pending (we retry) and hand the
                    // sealed envelope to reachable co-members via the degraded carry relay.
                    offloadToCarriers(d.groupId, d.memberNodeId, frame, me)
                }
            }
        }
    }

    /** Deliver one envelope to [target]: prefer the open RTC data channel, else direct TCP. */
    private suspend fun deliver(target: String, frame: JSONObject): JSONObject? {
        if (rtcTransport.isConnected(target)) {
            rtcTransport.send(target, frame)?.let { return it }
        }
        val contact = contactDao.byNodeId(target) ?: return null
        val address = messageSender.resolvePeerAddress(contact) ?: return null
        return messageSender.exchangeFrame(address, frame)
    }

    /**
     * Hand [target]'s sealed envelope to up to [CarryProtocol.MAX_CARRIERS] currently-connected
     * co-members, who hold it (degraded carry relay) and deliver when [target] next reappears.
     * Best-effort and fire-and-forget; the carrier can't read the envelope.
     */
    private suspend fun offloadToCarriers(groupId: String, target: String, innerFrame: JSONObject, me: String) {
        val carriers = groupDao.members(groupId).map { it.nodeId }
            .filter { it != me && it != target && rtcTransport.isConnected(it) }
            .take(CarryProtocol.MAX_CARRIERS)
        if (carriers.isEmpty()) return
        val carryFrame = CarryProtocol.build(groupId, target, CarryProtocol.DEFAULT_TTL_MS, innerFrame)
        carriers.forEach { carrier -> runCatching { rtcTransport.send(carrier, carryFrame) } }
    }
}
