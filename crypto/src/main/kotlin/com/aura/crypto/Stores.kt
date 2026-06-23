package com.aura.crypto

/**
 * Persistence abstractions for the crypto core.
 *
 * The library defines WHAT it needs stored (these interfaces + plain data records) but
 * not HOW. The host application supplies the implementation — in Aurora's case, thin
 * adapters over the SQLCipher-encrypted Room database. This keeps the crypto library
 * free of any Android / database dependency while letting the stateful ratchet and
 * prekey managers live inside it.
 */

// ── Ratchet persistence ─────────────────────────────────────────────────────────

/** Per-contact forward-secret ratchet state (mirrors the host's stored row). */
data class RatchetState(
    val contactNodeIdHex: String,
    val sendChainKeyB64: String,
    val sendN: Long,
    val recvChainKeyB64: String,
    val recvN: Long,
    val sasFingerprintB64: String,
    val mediaKeyB64: String
)

/** A message key kept for a skipped (out-of-order) ratchet counter. */
data class SkippedKey(
    val contactNodeIdHex: String,
    val n: Long,
    val messageKeyB64: String
)

interface RatchetStore {
    suspend fun upsertState(state: RatchetState)
    suspend fun state(nodeIdHex: String): RatchetState?
    suspend fun putSkipped(key: SkippedKey)
    suspend fun skipped(nodeIdHex: String, n: Long): SkippedKey?
    suspend fun deleteSkipped(nodeIdHex: String, n: Long)
    suspend fun pruneSkipped(nodeIdHex: String, keep: Int)
    suspend fun deleteState(nodeIdHex: String)
    suspend fun deleteSkippedForContact(nodeIdHex: String)
    suspend fun deleteAllState()
    suspend fun deleteAllSkipped()
}

// ── Prekey persistence ──────────────────────────────────────────────────────────

/** One of our own published prekeys (signed prekey "spk" or one-time prekey "opk"). */
data class PrekeyRecord(
    val prekeyId: String,
    val kind: String,
    /** X-Wing public key (Base64) — what an initiator encapsulates against. */
    val kemPubB64: String,
    /** X-Wing private key seed (Base64) — used to decapsulate, then destroyed. */
    val kemPrivB64: String,
    val createdAtMs: Long
)

interface PrekeyStore {
    suspend fun insert(prekey: PrekeyRecord)
    suspend fun byId(id: String): PrekeyRecord?
    suspend fun currentSpk(): PrekeyRecord?
    suspend fun unusedOpks(n: Int): List<PrekeyRecord>
    suspend fun unusedOpkCount(): Int
    suspend fun delete(id: String)
    suspend fun pruneOldSpks(cutoff: Long)
    suspend fun deleteAll()
}
