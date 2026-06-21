package com.aura.transport.carry

/**
 * Seam to group membership (built in **piece 1** of group chat). The carry relay consults it
 * to accept an envelope only for a group this device belongs to, addressed to a genuine
 * co-member — never for strangers. That bounds the degraded relay to **your own groups**
 * (consented by joining one), which is what distinguishes it from ShadowMesh's relay-for-
 * strangers opt-in. See `Aurora Instructions/GROUP_DEGRADED_RELAY.md`.
 *
 * No Hilt binding exists yet: piece 1's membership store will provide one, at which point the
 * relay activates. Until then [CarryRelay] is constructable and testable but not wired into
 * the live transport / sync loop, so inbound carry frames are never offered to it.
 */
interface GroupMembershipGate {
    /** True if this device is a member of [groupId]. */
    suspend fun isMemberOf(groupId: String): Boolean

    /** True if [nodeIdHex] is a fellow member of [groupId] (a valid carry target). */
    suspend fun areCoMembers(groupId: String, nodeIdHex: String): Boolean
}
