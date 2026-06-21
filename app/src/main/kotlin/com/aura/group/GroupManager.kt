package com.aura.group

import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.GroupDao
import com.aura.db.GroupEntity
import com.aura.db.GroupMemberEntity
import com.aura.di.IoDispatcher
import com.aura.identity.IdentityStore
import com.aura.transport.MessageSender
import com.aura.transport.TcpMessageServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Group lifecycle + admin-authoritative membership (piece 1). The creator is the admin and the
 * single source of truth; membership changes broadcast a full snapshot ([GroupControl]) to every
 * member over the sealed `ctl` channel ([MessageSender.sendControl], which prefers the RTC data
 * channel and falls back to TCP). Members apply a snapshot only from their group's admin and only
 * forward in epoch. Members must be existing contacts (fully-connected — see the carry-relay spec).
 *
 * v1 scope (per spec): admin-authoritative, best-effort propagation, no membership log / CRDT.
 */
@Singleton
class GroupManager @Inject constructor(
    private val groupDao: GroupDao,
    private val contactDao: ContactDao,
    private val identityManager: IdentityStore,
    private val messageSender: MessageSender,
    private val tcpServer: TcpMessageServer,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    /** Register the inbound group-control handler (called once at startup). */
    fun start() {
        tcpServer.groupControlHandler = handler@{ from, inner -> applyControl(from, inner) }
    }

    private suspend fun me(): String = identityManager.getOrCreate().nodeId.toHex()

    /** Create a group with the given contacts. Returns the new groupId, or null if no I/O succeeds. */
    suspend fun createGroup(name: String, memberNodeIds: List<String>): String = withContext(ioDispatcher) {
        val me = me()
        val contacts = memberNodeIds.filter { it != me && contactDao.byNodeId(it) != null }.distinct()
        val groupId = randomGroupId()
        val members = buildList {
            add(GroupControl.Member(me, GroupControl.ROLE_ADMIN))
            contacts.forEach { add(GroupControl.Member(it, GroupControl.ROLE_MEMBER)) }
        }
        persist(groupId, name, epoch = 1L, admin = me, members = members)
        broadcastSync(groupId)
        groupId
    }

    /** Admin: add a contact to the group. */
    suspend fun addMember(groupId: String, nodeId: String) = withContext(ioDispatcher) {
        val g = adminGroupOrNull(groupId) ?: return@withContext
        if (contactDao.byNodeId(nodeId) == null || groupDao.isMember(groupId, nodeId)) return@withContext
        val members = groupDao.members(groupId).map { GroupControl.Member(it.nodeId, it.role) } +
            GroupControl.Member(nodeId, GroupControl.ROLE_MEMBER)
        persist(groupId, g.name, g.epoch + 1, g.createdByNodeId, members)
        broadcastSync(groupId)
    }

    /** Admin: remove a member; tell them directly so they deactivate the group too. */
    suspend fun removeMember(groupId: String, nodeId: String) = withContext(ioDispatcher) {
        val g = adminGroupOrNull(groupId) ?: return@withContext
        if (!groupDao.isMember(groupId, nodeId)) return@withContext
        val members = groupDao.members(groupId).filter { it.nodeId != nodeId }
            .map { GroupControl.Member(it.nodeId, it.role) }
        persist(groupId, g.name, g.epoch + 1, g.createdByNodeId, members)
        broadcastSync(groupId)
        contactDao.byNodeId(nodeId)?.let { runCatching { messageSender.sendControl(it, GroupControl.buildRemove(groupId)) } }
    }

    /** Admin: rename the group. */
    suspend fun rename(groupId: String, name: String) = withContext(ioDispatcher) {
        val g = adminGroupOrNull(groupId) ?: return@withContext
        val members = groupDao.members(groupId).map { GroupControl.Member(it.nodeId, it.role) }
        persist(groupId, name, g.epoch + 1, g.createdByNodeId, members)
        broadcastSync(groupId)
    }

    /** Leave a group. If admin, this is a no-op for now (admin transfer is a later feature). */
    suspend fun leaveGroup(groupId: String) = withContext(ioDispatcher) {
        val g = groupDao.group(groupId) ?: return@withContext
        val me = me()
        if (g.createdByNodeId == me) return@withContext   // admin can't leave yet (v1)
        groupDao.setActive(groupId, false)
        contactDao.byNodeId(g.createdByNodeId)?.let { runCatching { messageSender.sendControl(it, GroupControl.buildLeave(groupId)) } }
    }

    // ── inbound control ──────────────────────────────────────────────────────────

    private suspend fun applyControl(from: String, inner: JSONObject) {
        when (inner.optString("ctl")) {
            GroupControl.SYNC -> {
                val snap = GroupControl.parseSync(inner) ?: return
                val g = groupDao.group(snap.groupId)
                if (!GroupControl.acceptSync(from, snap, g?.createdByNodeId, g?.epoch)) return
                if (snap.members.none { it.nodeId == me() }) return   // ignore a group we're not in
                persist(snap.groupId, snap.name, snap.epoch, snap.admin, snap.members)
            }
            GroupControl.REMOVE -> {
                val groupId = inner.optString("groupId")
                val g = groupDao.group(groupId) ?: return
                if (from != g.createdByNodeId) return   // only the admin can remove us
                groupDao.clearMembers(groupId)
                groupDao.setActive(groupId, false)
            }
            GroupControl.LEAVE -> {
                val groupId = inner.optString("groupId")
                val g = groupDao.group(groupId) ?: return
                if (g.createdByNodeId != me()) return    // only the admin processes leaves
                if (!groupDao.isMember(groupId, from)) return
                groupDao.removeMember(groupId, from)
                groupDao.setEpoch(groupId, g.epoch + 1)
                broadcastSync(groupId)
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private suspend fun adminGroupOrNull(groupId: String): GroupEntity? {
        val g = groupDao.group(groupId) ?: return null
        return if (g.createdByNodeId == me()) g else null
    }

    private suspend fun persist(
        groupId: String, name: String, epoch: Long, admin: String, members: List<GroupControl.Member>
    ) {
        val now = System.currentTimeMillis()
        groupDao.upsertGroup(GroupEntity(groupId, name, admin, epoch, now, active = true))
        groupDao.clearMembers(groupId)
        members.forEach { groupDao.upsertMember(GroupMemberEntity(groupId, it.nodeId, it.role, now)) }
    }

    private suspend fun broadcastSync(groupId: String) {
        val g = groupDao.group(groupId) ?: return
        val me = me()
        val members = groupDao.members(groupId)
        val snapshot = GroupControl.Snapshot(
            groupId, g.name, g.epoch, g.createdByNodeId,
            members.map { GroupControl.Member(it.nodeId, it.role) }
        )
        val payload = GroupControl.buildSync(snapshot)
        members.filter { it.nodeId != me }.forEach { m ->
            contactDao.byNodeId(m.nodeId)?.let { runCatching { messageSender.sendControl(it, payload) } }
        }
    }

    private fun randomGroupId(): String =
        ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
}
