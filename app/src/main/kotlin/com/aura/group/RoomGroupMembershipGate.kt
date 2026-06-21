package com.aura.group

import com.aura.crypto.toHex
import com.aura.db.GroupDao
import com.aura.identity.IdentityStore
import com.aura.transport.carry.GroupMembershipGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backs [GroupMembershipGate] with the local group membership store (piece 1). Wiring this binding
 * is what **activates** the degraded carry relay's admission check: it will now accept an envelope
 * only for a group this device belongs to, addressed to a genuine co-member.
 */
@Singleton
class RoomGroupMembershipGate @Inject constructor(
    private val groupDao: GroupDao,
    private val identityManager: IdentityStore
) : GroupMembershipGate {

    override suspend fun isMemberOf(groupId: String): Boolean {
        val me = identityManager.getOrCreate().nodeId.toHex()
        return groupDao.isMember(groupId, me)
    }

    override suspend fun areCoMembers(groupId: String, nodeIdHex: String): Boolean =
        groupDao.isMember(groupId, nodeIdHex)
}
