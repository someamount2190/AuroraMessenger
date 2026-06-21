package com.aura.group

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Group membership control protocol (piece 1). Admin-authoritative, **snapshot-based**: every
 * membership change broadcasts the full member list with a monotonic [Snapshot.epoch]; recipients
 * replace their local state when a newer snapshot arrives from the group's admin. Snapshots are
 * idempotent and self-healing — a later one supersedes any the recipient missed — which is far
 * simpler than add/remove deltas and tolerant of the best-effort control channel.
 *
 * Pure (JSON only), so the framing + the epoch-acceptance rule are unit-testable. Authenticity of
 * `from` comes from the sealed `ctl` channel (the double ratchet), as with reactions / timer sync.
 * See `Aurora Instructions/GROUP_DEGRADED_RELAY.md` §B (membership) and the group-chat plan.
 */
object GroupControl {
    const val SYNC = "grp_sync"      // admin -> members: full membership snapshot
    const val REMOVE = "grp_remove"  // admin -> a removed member: "you are no longer in this group"
    const val LEAVE = "grp_leave"    // member -> admin: "remove me"

    const val ROLE_ADMIN = "admin"
    const val ROLE_MEMBER = "member"

    data class Member(val nodeId: String, val role: String)
    data class Snapshot(
        val groupId: String,
        val name: String,
        val epoch: Long,
        val admin: String,
        val members: List<Member>
    )

    fun buildSync(s: Snapshot): JSONObject {
        val arr = JSONArray()
        s.members.forEach { arr.put(JSONObject().put("nodeId", it.nodeId).put("role", it.role)) }
        return JSONObject()
            .put("ctl", SYNC)
            .put("id", UUID.randomUUID().toString())
            .put("groupId", s.groupId)
            .put("name", s.name)
            .put("epoch", s.epoch)
            .put("admin", s.admin)
            .put("members", arr)
    }

    fun buildRemove(groupId: String): JSONObject =
        JSONObject().put("ctl", REMOVE).put("id", UUID.randomUUID().toString()).put("groupId", groupId)

    fun buildLeave(groupId: String): JSONObject =
        JSONObject().put("ctl", LEAVE).put("id", UUID.randomUUID().toString()).put("groupId", groupId)

    /** Parse + validate a `grp_sync` payload. Null if malformed or empty. */
    fun parseSync(inner: JSONObject): Snapshot? {
        if (inner.optString("ctl") != SYNC) return null
        val groupId = inner.optString("groupId").ifEmpty { return null }
        val admin = inner.optString("admin").ifEmpty { return null }
        val epoch = inner.optLong("epoch", -1L)
        if (epoch < 1L) return null
        val arr = inner.optJSONArray("members") ?: return null
        val members = ArrayList<Member>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val nid = o.optString("nodeId")
            if (nid.isEmpty()) continue
            members.add(Member(nid, o.optString("role").ifEmpty { ROLE_MEMBER }))
        }
        if (members.isEmpty()) return null
        return Snapshot(groupId, inner.optString("name"), epoch, admin, members)
    }

    /**
     * Should an incoming snapshot from [from] be applied, given local state?
     *  - Unknown group ([localAdmin] null): accept only if [from] is the admin the snapshot names
     *    (we join a group created by a contact who lists themselves as admin).
     *  - Known group: only the recorded admin may update it, and only strictly forward in epoch.
     */
    fun acceptSync(from: String, snapshot: Snapshot, localAdmin: String?, localEpoch: Long?): Boolean =
        if (localAdmin == null) snapshot.admin == from
        else from == localAdmin && (localEpoch == null || snapshot.epoch > localEpoch)
}
