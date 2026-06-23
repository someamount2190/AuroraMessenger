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

/**
 * Persistence for the post-quantum KEM Double Ratchet ([KemRatchetManager]). One opaque blob
 * per contact holds the static SAS fingerprint + media-at-rest key followed by the whole
 * serialized [KemDoubleRatchet.Session] (root, chains, ratchet keypair, peer key, skipped
 * cache); the host backs this with an SQLCipher-encrypted row.
 */
interface KemSessionStore {
    suspend fun load(contactNodeIdHex: String): ByteArray?
    suspend fun save(contactNodeIdHex: String, session: ByteArray)
    suspend fun delete(contactNodeIdHex: String)
    suspend fun deleteAll()
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
