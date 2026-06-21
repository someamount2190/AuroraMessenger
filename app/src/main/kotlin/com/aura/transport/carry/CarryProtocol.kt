package com.aura.transport.carry

import org.json.JSONObject

/**
 * Wire protocol + policy for the **degraded carry relay** (group chat, piece 3). Pure (JSON
 * only — no Android, DB or transport types), so framing / validation / TTL logic is
 * unit-testable without a device. See `Aurora Instructions/GROUP_DEGRADED_RELAY.md`.
 *
 * A `carry` frame asks a co-member to hold an opaque, end-to-end-sealed `msg` envelope and
 * deliver it to [Carry.target] later. The courier can neither read nor forge the inner
 * envelope (it's sealed with the original sender↔target ratchet); it only routes it, because
 * the wire protocol is content-addressed by the inner frame's `from`/`to`.
 */
object CarryProtocol {
    const val FRAME_CARRY = "carry"
    const val FRAME_CARRY_ACK = "carryack"

    /** Default time a courier holds an envelope before dropping it (48h). */
    const val DEFAULT_TTL_MS = 48L * 60 * 60 * 1000
    /** Hard ceiling on a requested TTL (7 days) — clamps a hostile/buggy sender. */
    const val MAX_TTL_MS = 7L * 24 * 60 * 60 * 1000
    /** Couriers chosen per undelivered envelope on the origin (send) side. */
    const val MAX_CARRIERS = 3
    /** Caps on what a courier will hold, bounding abuse + storage. */
    const val MAX_QUEUE = 500
    const val MAX_PER_ORIGIN = 100

    /** A validated inbound carry request. */
    data class Carry(
        val groupId: String,
        val target: String,
        val originNodeId: String,
        val msgId: String,
        val ttlMs: Long,
        val inner: JSONObject
    )

    /** Build a `carry` frame wrapping [inner] (a `msg` frame already addressed to [target]). */
    fun build(groupId: String, target: String, ttlMs: Long, inner: JSONObject): JSONObject =
        JSONObject()
            .put("t", FRAME_CARRY)
            .put("groupId", groupId)
            .put("target", target)
            .put("ttlMs", clampTtl(ttlMs))
            .put("inner", inner)

    /** Build the receipt a courier (or the target) sends back so copies can be dropped. */
    fun buildAck(msgId: String, target: String): JSONObject =
        JSONObject().put("t", FRAME_CARRY_ACK).put("id", msgId).put("target", target)

    /**
     * Parse + validate an inbound `carry` frame. Returns null if it's malformed or internally
     * inconsistent — the inner isn't a `msg`, isn't addressed to [target], or is missing ids.
     */
    fun parse(frame: JSONObject): Carry? {
        if (frame.optString("t") != FRAME_CARRY) return null
        val groupId = frame.optString("groupId").ifEmpty { return null }
        val target = frame.optString("target").ifEmpty { return null }
        val inner = frame.optJSONObject("inner") ?: return null
        if (inner.optString("t") != "msg") return null
        val msgId = inner.optString("id").ifEmpty { return null }
        val origin = inner.optString("from").ifEmpty { return null }
        if (inner.optString("to") != target) return null   // the envelope must be for the carry target
        return Carry(groupId, target, origin, msgId, clampTtl(frame.optLong("ttlMs", DEFAULT_TTL_MS)), inner)
    }

    /** True once a held envelope is past its TTL and should be dropped. */
    fun isExpired(createdMs: Long, ttlMs: Long, nowMs: Long): Boolean = createdMs + ttlMs <= nowMs

    private fun clampTtl(ttlMs: Long): Long = when {
        ttlMs <= 0L -> DEFAULT_TTL_MS
        ttlMs > MAX_TTL_MS -> MAX_TTL_MS
        else -> ttlMs
    }
}
