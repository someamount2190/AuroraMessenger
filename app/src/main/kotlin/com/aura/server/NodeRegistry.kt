package com.aura.server

import java.util.concurrent.ConcurrentHashMap

/** A registered node: its advertised address + the check-in signature that proves it. */
data class RegisteredNode(
    val nodeIdHex:    String,
    val ip:           String,
    val port:         Int,
    val timestampMs:  Long,
    val ed25519PubB64: String,
    val signatureB64: String,
    val storedAtMs:   Long
)

/**
 * In-memory node directory: the `nodeId → address` map plus the per-node check-in
 * rate-limit clock. All entries expire on TTL via [purge]. No disk, no logging.
 */
internal class NodeRegistry {
    private val nodes = ConcurrentHashMap<String, RegisteredNode>()
    private val lastCheckinByNode = ConcurrentHashMap<String, Long>()

    fun get(nodeIdHex: String): RegisteredNode? = nodes[nodeIdHex]

    fun put(node: RegisteredNode) { nodes[node.nodeIdHex] = node }

    /** True if this node checked in within [rateLimitMs] (so the new check-in is rejected). */
    fun checkinRateLimited(nodeIdHex: String, now: Long, rateLimitMs: Long): Boolean =
        now - (lastCheckinByNode[nodeIdHex] ?: 0L) < rateLimitMs

    /** Record a successful check-in time (only after the signature verifies). */
    fun markCheckin(nodeIdHex: String, now: Long) { lastCheckinByNode[nodeIdHex] = now }

    fun purge(cutoff: Long) {
        nodes.values.removeIf { it.storedAtMs < cutoff }
        lastCheckinByNode.values.removeIf { it < cutoff }
    }

    fun count(): Int = nodes.size
}
