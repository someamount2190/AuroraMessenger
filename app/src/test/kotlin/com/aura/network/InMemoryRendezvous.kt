package com.aura.network

import com.aura.crypto.NodeIdentity
import com.aura.crypto.toHex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-memory [Rendezvous] for **in-process two-peer simulation**: a trusted broker that
 * relays opaque signals (and tracks check-ins) between simulated peers with no network,
 * TLS, or signature checks. Give each peer its own handle backed by the same [Broker]:
 *
 * ```
 * val broker = InMemoryRendezvous.Broker()
 * val alice  = InMemoryRendezvous(broker)
 * val bob    = InMemoryRendezvous(broker)
 * alice.postSignal("mem://", bobId.nodeId.toHex(), pairReqJson)
 * val inbox = bob.getSignals("mem://", bobId).getOrThrow()   // -> [pairReqJson]
 * ```
 *
 * Routing is keyed by nodeId, so the whole pairing handshake (pairreq → pairaccept →
 * pairverify, all carried as signals) can run between two object graphs in one process.
 * Forward-secret prekey fetch returns null (peers fall back to the legacy handshake),
 * which is a valid simulation state; extend [fetchPrekeyBundle] to exercise PQXDH.
 */
class InMemoryRendezvous(private val broker: Broker = Broker()) : Rendezvous {

    /** Shared routing state for a set of in-process peers. */
    class Broker {
        val signals = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
        val prekeys = ConcurrentHashMap<String, String>()      // nodeId -> published bundle JSON
        val registered = ConcurrentHashMap<String, Boolean>()  // nodeId -> checked in
    }

    override suspend fun checkIn(
        serverBaseUrl: String,
        identity: NodeIdentity,
        listenPort: Int,
        advertisedOverride: String?
    ): Result<String> = runCatching {
        broker.registered[identity.nodeId.toHex()] = true
        "registered $serverBaseUrl (in-memory)"
    }

    override suspend fun postSignal(serverBaseUrl: String, nodeIdHex: String, payload: String): Result<Unit> =
        runCatching { broker.signals.getOrPut(nodeIdHex) { ConcurrentLinkedQueue() }.add(payload); Unit }

    override suspend fun getSignals(serverBaseUrl: String, identity: NodeIdentity): Result<List<String>> =
        runCatching {
            val q = broker.signals[identity.nodeId.toHex()]
            val out = ArrayList<String>()
            if (q != null) { while (true) { out.add(q.poll() ?: break) } }
            out
        }

    override suspend fun find(serverBaseUrl: String, nodeIdHex: String): Result<List<FindCandidate>> =
        runCatching {
            if (broker.registered.containsKey(nodeIdHex))
                listOf(FindCandidate("127.0.0.1", Rendezvous.DEFAULT_LISTEN_PORT, System.currentTimeMillis(), ""))
            else emptyList()
        }

    override suspend fun publishPrekeys(serverBaseUrl: String, identity: NodeIdentity, bundleJson: String): Result<Unit> =
        runCatching { broker.prekeys[identity.nodeId.toHex()] = bundleJson; Unit }

    /** No prekey simulation yet → null means the peer falls back to the legacy handshake. */
    override suspend fun fetchPrekeyBundle(serverBaseUrl: String, nodeIdHex: String): Result<PrekeyBundle?> =
        Result.success(null)

    override suspend fun probe(serverBaseUrl: String): Boolean = true
    override suspend fun firstReachable(candidates: List<String>): String? = candidates.firstOrNull()
    override suspend fun waitForWake(serverBaseUrl: String, identity: NodeIdentity): Result<Boolean> = Result.success(false)
    override suspend fun tap(serverBaseUrl: String, peerNodeIdHex: String): Result<Unit> = Result.success(Unit)

    override fun verifyCandidates(
        nodeIdHex: String,
        ed25519Pub: ByteArray,
        dilithiumPub: ByteArray?,
        candidates: List<FindCandidate>
    ): FindCandidate? = candidates.firstOrNull()
}
