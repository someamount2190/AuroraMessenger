package com.aura.network

import com.aura.crypto.HybridPublicKey
import com.aura.crypto.NodeIdentity

/** A signed signal drain was rejected (e.g. the server lost our registration). */
class DrainUnauthorizedException : Exception("signal drain unauthorized — re-check-in needed")

/**
 * One candidate from a `/find` response. The real record's [signatureB64] verifies
 * against the target node's key; the rest are same-shaped padding.
 */
data class FindCandidate(
    val ip: String,
    val port: Int,
    val timestampMs: Long,
    val signatureB64: String
)

/**
 * One peer's forward-secret prekeys, fetched from the rendezvous server: the signed
 * prekey (always present) and one popped one-time prekey (null once the pool is
 * exhausted). Signatures are verified by the caller against the peer's Ed25519 key.
 */
data class PrekeyBundle(
    val spkId: String,
    val spkPub: HybridPublicKey,
    val spkSig: ByteArray,
    val opkId: String?,
    val opkPub: HybridPublicKey?,
    val opkSig: ByteArray?
)

/**
 * The rendezvous **control plane**: the single point both peers talk to for check-in,
 * peer discovery, opaque signal relay, prekey publish/fetch, and wake. It never sees
 * message content — only `nodeId → address` mappings and opaque signal payloads.
 *
 * Extracting this as an interface lets the production [RendezvousClient] (TLS+pinned
 * HTTP) be swapped for an in-memory broker that routes between two **in-process peers**,
 * enabling two-peer simulation (e.g. the full pairing handshake) with no network — see
 * `InMemoryRendezvous` in the test sources. `serverBaseUrl` is meaningful to the HTTP
 * client and ignored by an in-memory broker.
 */
interface Rendezvous {
    suspend fun checkIn(
        serverBaseUrl: String,
        identity: NodeIdentity,
        listenPort: Int = DEFAULT_LISTEN_PORT,
        advertisedOverride: String? = null
    ): Result<String>

    suspend fun find(serverBaseUrl: String, nodeIdHex: String): Result<List<FindCandidate>>

    suspend fun probe(serverBaseUrl: String): Boolean

    suspend fun firstReachable(candidates: List<String>): String?

    suspend fun postSignal(serverBaseUrl: String, nodeIdHex: String, payload: String): Result<Unit>

    suspend fun getSignals(serverBaseUrl: String, identity: NodeIdentity): Result<List<String>>

    suspend fun waitForWake(serverBaseUrl: String, identity: NodeIdentity): Result<Boolean>

    suspend fun tap(serverBaseUrl: String, peerNodeIdHex: String): Result<Unit>

    fun verifyCandidates(
        nodeIdHex: String,
        ed25519Pub: ByteArray,
        dilithiumPub: ByteArray?,
        candidates: List<FindCandidate>
    ): FindCandidate?

    suspend fun publishPrekeys(serverBaseUrl: String, identity: NodeIdentity, bundleJson: String): Result<Unit>

    suspend fun fetchPrekeyBundle(serverBaseUrl: String, nodeIdHex: String): Result<PrekeyBundle?>

    companion object {
        /** Port the direct-TCP message listener binds to. */
        const val DEFAULT_LISTEN_PORT = 8765
    }
}
