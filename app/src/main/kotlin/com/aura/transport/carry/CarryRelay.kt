package com.aura.transport.carry

import com.aura.db.CarryDao
import com.aura.db.CarryEntity
import com.aura.db.ContactDao
import com.aura.di.IoDispatcher
import com.aura.transport.MessageSender
import com.aura.transport.TcpMessageServer
import com.aura.transport.rtc.RtcTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Degraded carry relay (group chat, **piece 3**): hold opaque, end-to-end-sealed envelopes for
 * co-members who were offline at send time, and deliver them when they next become reachable.
 * Best-effort, one-hop, TTL'd — **not** ShadowMesh and **not** a server mailbox (the server is
 * never in this path). See `Aurora Instructions/GROUP_DEGRADED_RELAY.md`.
 *
 * Active as of piece 3: [wireReceiver] registers inbound `carry` handling on the transports,
 * [GroupMessageSender] offloads to reachable carriers on a fan-out miss, and the sync loop drives
 * [flushCarryQueue]. The policy ([CarryProtocol]) and membership gate are unit-tested; end-to-end
 * delivery across CGNAT still needs the 2-device test the rest of the data-channel work awaits.
 */
@Singleton
class CarryRelay @Inject constructor(
    private val carryDao: CarryDao,
    private val gate: GroupMembershipGate,
    private val contactDao: ContactDao,
    private val rtcTransport: RtcTransport,
    private val messageSender: MessageSender,
    private val tcpServer: TcpMessageServer,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val flushMutex = Mutex()

    /** Register inbound carry handling on the TCP/RTC transports (called once at startup). */
    fun wireReceiver() {
        tcpServer.carryHandler = { frame -> onCarryReceived(frame) }
    }

    /**
     * Accept an inbound `carry` frame: store the envelope iff it's for a group we're in,
     * addressed to a real co-member, and within caps. Returns true if stored. **Fails closed** —
     * anything unverified or malformed is rejected.
     */
    suspend fun onCarryReceived(frame: JSONObject): Boolean = withContext(ioDispatcher) {
        val carry = CarryProtocol.parse(frame) ?: return@withContext false
        if (!gate.isMemberOf(carry.groupId)) return@withContext false
        if (!gate.areCoMembers(carry.groupId, carry.target)) return@withContext false
        if (carryDao.countFromOrigin(carry.originNodeId) >= CarryProtocol.MAX_PER_ORIGIN) return@withContext false
        if (carryDao.count() >= CarryProtocol.MAX_QUEUE) {
            carryDao.oldest()?.let { carryDao.delete(it.msgId, it.target) }   // evict oldest to make room
        }
        carryDao.upsert(
            CarryEntity(
                msgId = carry.msgId,
                target = carry.target,
                groupId = carry.groupId,
                originNodeId = carry.originNodeId,
                innerJson = carry.inner.toString(),
                createdMs = System.currentTimeMillis(),
                ttlMs = carry.ttlMs,
                attempts = 0,
                lastAttemptMs = 0L
            )
        )
        true
    }

    /**
     * Best-effort delivery sweep: drop expired envelopes, then try to hand each held envelope to
     * its target over whatever transport reaches it. On the target's `ack` we drop our copy;
     * receiver-side dedup (`messages.byId`) makes a duplicate delivery harmless. Intended to be
     * called from the sync loop (piece-3 activation) and opportunistically.
     */
    suspend fun flushCarryQueue() = flushMutex.withLock {
        withContext(ioDispatcher) {
            carryDao.expireOlderThan(System.currentTimeMillis())
            for (target in carryDao.targets()) {
                for (item in carryDao.forTarget(target)) {
                    val inner = try { JSONObject(item.innerJson) } catch (e: Exception) {
                        carryDao.delete(item.msgId, item.target); continue   // unparseable → drop
                    }
                    val ack = deliver(target, inner)
                    if (ack?.optString("t") == "ack") carryDao.delete(item.msgId, item.target)
                    else carryDao.markAttempt(item.msgId, item.target, System.currentTimeMillis())
                }
            }
        }
    }

    /** Deliver one envelope to [target]: prefer the open RTC data channel, else direct TCP. */
    private suspend fun deliver(target: String, inner: JSONObject): JSONObject? {
        if (rtcTransport.isConnected(target)) {
            rtcTransport.send(target, inner)?.let { return it }
        }
        val contact = contactDao.byNodeId(target) ?: return null
        val address = messageSender.resolvePeerAddress(contact) ?: return null
        return messageSender.exchangeFrame(address, inner)
    }
}
