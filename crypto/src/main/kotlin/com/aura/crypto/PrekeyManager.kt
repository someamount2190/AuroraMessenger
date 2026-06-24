package com.aura.crypto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Forward-secret prekeys for the pairing handshake (the post-quantum X3DH / "PQXDH"
 * upgrade).
 *
 * The pairing root used to be derived from a single KEM encapsulation against the
 * peer's *long-term* identity key. That left a gap: an attacker who recorded the
 * handshake and *later* obtained the device's long-term identity key could
 * reconstruct the root and decrypt the whole conversation (a key-compromise
 * "harvest now, decrypt later"). Post-quantum strength doesn't help there — the
 * attacker holds the real key, nothing is being broken.
 *
 * The fix is forward secrecy at the handshake: the responder also contributes
 * **ephemeral** KEM keys whose private halves are destroyed. The session secret then
 * needs the identity key AND a signed prekey AND a one-time prekey; destroying the
 * one-time key after use means a later identity-key compromise can no longer rebuild
 * it. Each prekey is a full X-Wing (ML-KEM-768 + X25519) keypair, so the forward
 * secrecy is itself post-quantum.
 *
 * This manager owns OUR prekeys:
 *  - a medium-term **signed prekey** (SPK), rotated ~weekly,
 *  - a pool of single-use **one-time prekeys** (OPK), each deleted on consume.
 *
 * The public halves (each signed by our identity key) are published to the
 * rendezvous server as a bundle; an initiator who scans our QR fetches the bundle
 * and encapsulates against it. See [publicBundle] / [consume].
 *
 * Persistence is delegated to a host-supplied [PrekeyStore] so this class carries no
 * database or platform dependency. It is DI-framework-agnostic; the host wires it up
 * (Aurora provides it as a singleton from its Hilt module).
 */
class PrekeyManager(
    private val store: PrekeyStore,
    private val kem: HybridKem,
    private val signer: HybridSigner,
    /** Wall clock, injectable so SPK rotation / pruning can be driven deterministically in tests. */
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val rng = SecureRandom()
    private val mutex = Mutex()

    /** Private prekey material recovered for an incoming handshake. */
    data class Consumed(
        val spkPriv: HybridPrivateKey,
        val opkPriv: HybridPrivateKey?,
        /** True when the handshake named an OPK we no longer have (stale/reused QR). */
        val opkMissing: Boolean
    )

    /**
     * Ensure a current SPK and a full OPK pool exist, prune expired SPKs, and return
     * the signed public bundle JSON to publish: `{ spk:{id,pub,sig}, opks:[{id,pub,sig}] }`.
     * Each `pub` is the hybrid public key bytes; each `sig` is an Ed25519 signature by
     * our identity key over [prekeyMessage] (verifiable by a scanner from the QR's
     * Ed25519 key alone, before the peer's Dilithium key is known).
     */
    suspend fun publicBundle(identity: NodeIdentity): JSONObject = mutex.withLock {
        ensureSpkLocked()
        replenishOpksLocked(OPK_POOL_TARGET)
        store.pruneOldSpks(now() - (SPK_ROTATE_MS + SPK_GRACE_MS))
        val nodeId = identity.nodeId.toHex()
        val spk = store.currentSpk() ?: error("SPK missing after ensure")
        val opks = store.unusedOpks(OPK_PUBLISH_COUNT)
        JSONObject()
            .put("spk", prekeyJson(nodeId, KIND_SPK, spk, identity))
            .put("opks", JSONArray().apply { opks.forEach { put(prekeyJson(nodeId, KIND_OPK, it, identity)) } })
    }

    /**
     * Recover the private prekeys named by an incoming handshake. The one-time prekey
     * is **deleted on the spot** (forward secrecy). Returns null if the SPK is unknown
     * (we never published it / it was pruned); returns [Consumed.opkMissing] = true when
     * the OPK was already consumed, so the caller can fail closed and ask for a fresh code.
     */
    suspend fun consume(spkId: String, opkId: String?): Consumed? = mutex.withLock {
        val spk = store.byId(spkId) ?: return@withLock null
        val spkPriv = privOf(spk)
        if (opkId == null) return@withLock Consumed(spkPriv, null, opkMissing = false)
        val opk = store.byId(opkId) ?: return@withLock Consumed(spkPriv, null, opkMissing = true)
        store.delete(opk.prekeyId)            // one-time: destroy the instant it's used
        Consumed(spkPriv, privOf(opk), opkMissing = false)
    }

    /** Wipe all of our prekeys (Settings → Clear all data / secure wipe). */
    suspend fun wipeAll() = mutex.withLock { store.deleteAll() }

    // ── internals ───────────────────────────────────────────────────────────

    private suspend fun ensureSpkLocked() {
        val cur = store.currentSpk()
        if (cur == null || now() - cur.createdAtMs >= SPK_ROTATE_MS) {
            generateLocked(KIND_SPK)
        }
    }

    private suspend fun replenishOpksLocked(target: Int) {
        var have = store.unusedOpkCount()
        while (have < target) { generateLocked(KIND_OPK); have++ }
    }

    private suspend fun generateLocked(kind: String) {
        val kp = kem.generateKeyPair().getOrThrow()
        store.insert(
            PrekeyRecord(
                prekeyId    = randomId(),
                kind        = kind,
                kemPubB64   = b64(kp.publicKey.encoded),
                kemPrivB64  = b64(kp.privateKey.encoded),
                createdAtMs = now()
            )
        )
    }

    private suspend fun prekeyJson(
        nodeId: String, kind: String, e: PrekeyRecord, identity: NodeIdentity
    ): JSONObject {
        val pubB64 = b64(hybridPub(e).toBytes())
        val sig = signer.signEd25519Only(
            prekeyMessage(nodeId, kind, e.prekeyId, pubB64),
            identity.privatePart.signingPrivateKey
        ).getOrThrow()
        return JSONObject().put("id", e.prekeyId).put("pub", pubB64).put("sig", b64(sig))
    }

    private fun hybridPub(e: PrekeyRecord) = HybridPublicKey(B64.decode(e.kemPubB64))

    private fun privOf(e: PrekeyRecord) = HybridPrivateKey(B64.decode(e.kemPrivB64))

    private fun randomId(): String = ByteArray(16).also(rng::nextBytes).toHex()
    private fun b64(x: ByteArray) = B64.encode(x)

    companion object {
        const val KIND_SPK = "spk"
        const val KIND_OPK = "opk"
        /** How many unused OPKs to keep on hand / publish at a time. */
        const val OPK_POOL_TARGET = 20
        const val OPK_PUBLISH_COUNT = 20
        /** Rotate the signed prekey weekly; keep the previous one one extra week. */
        const val SPK_ROTATE_MS = 7L * 24 * 60 * 60 * 1000
        const val SPK_GRACE_MS  = 7L * 24 * 60 * 60 * 1000

        /** Canonical bytes signed (Ed25519) to authenticate one published prekey. */
        fun prekeyMessage(nodeIdHex: String, kind: String, id: String, pubB64: String): ByteArray =
            "aura-prekey-v1|$nodeIdHex|$kind|$id|$pubB64".toByteArray()
    }
}
