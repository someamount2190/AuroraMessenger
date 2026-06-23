package com.aura.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Per-contact forward-secret ratchet state. Seeded from the KEM shared secret at
 * pairing; the root secret itself is never stored. Two symmetric chains (one for
 * sending, one for receiving) advance independently; each chain key is replaced
 * by its successor as messages flow, so a stolen current key cannot recover past
 * message keys.
 */
@Entity(tableName = "ratchet_state")
data class RatchetStateEntity(
    @PrimaryKey val contactNodeIdHex: String,
    /** Current sending chain key (Base64, 32 bytes) and the next counter to use. */
    val sendChainKeyB64: String,
    val sendN: Long,
    /** Current receiving chain key (Base64, 32 bytes) and the next expected counter. */
    val recvChainKeyB64: String,
    val recvN: Long,
    /** 8-byte fingerprint of the root secret (Base64) — feeds the SAS verification code. */
    val sasFingerprintB64: String,
    /** Local-only random key (Base64, 32 bytes) for encrypting media at rest. Never on the wire. */
    val mediaKeyB64: String
)

/**
 * Message keys for ratchet steps that were skipped (out-of-order / interleaved
 * frame types). Retained until the matching frame arrives, then consumed. Bounded
 * per contact to cap memory and resist a skip-flood.
 */
@Entity(tableName = "ratchet_skipped", primaryKeys = ["contactNodeIdHex", "n"])
data class RatchetSkippedKeyEntity(
    val contactNodeIdHex: String,
    val n: Long,
    val messageKeyB64: String
)

/**
 * Per-contact **post-quantum KEM Double Ratchet** session (Phase 5). The entire serialized
 * `KemDoubleRatchet.Session` — root, send/receive chains, the X-Wing ratchet keypair, the peer's
 * ratchet key, and the skipped-message cache — is stored as one opaque Base64 blob and rewritten
 * on each message. Backs the crypto module's `KemSessionStore`.
 */
@Entity(tableName = "kem_ratchet")
data class KemRatchetEntity(
    @PrimaryKey val contactNodeIdHex: String,
    val sessionB64: String
)

@Dao
interface RatchetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: RatchetStateEntity)

    // ── KEM Double Ratchet session blob (Phase 5) ──
    @Query("SELECT sessionB64 FROM kem_ratchet WHERE contactNodeIdHex = :nodeIdHex")
    suspend fun kemSession(nodeIdHex: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun kemUpsert(row: KemRatchetEntity)

    @Query("DELETE FROM kem_ratchet WHERE contactNodeIdHex = :nodeIdHex")
    suspend fun kemDelete(nodeIdHex: String)

    @Query("DELETE FROM kem_ratchet")
    suspend fun kemDeleteAll()

    @Query("SELECT * FROM ratchet_state WHERE contactNodeIdHex = :nodeIdHex")
    suspend fun state(nodeIdHex: String): RatchetStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSkipped(key: RatchetSkippedKeyEntity)

    @Query("SELECT * FROM ratchet_skipped WHERE contactNodeIdHex = :nodeIdHex AND n = :n")
    suspend fun skipped(nodeIdHex: String, n: Long): RatchetSkippedKeyEntity?

    @Query("DELETE FROM ratchet_skipped WHERE contactNodeIdHex = :nodeIdHex AND n = :n")
    suspend fun deleteSkipped(nodeIdHex: String, n: Long)

    @Query("SELECT COUNT(*) FROM ratchet_skipped WHERE contactNodeIdHex = :nodeIdHex")
    suspend fun skippedCount(nodeIdHex: String): Int

    /** Prune the oldest skipped keys for a contact, keeping at most [keep]. */
    @Query("DELETE FROM ratchet_skipped WHERE contactNodeIdHex = :nodeIdHex AND n NOT IN " +
           "(SELECT n FROM ratchet_skipped WHERE contactNodeIdHex = :nodeIdHex ORDER BY n DESC LIMIT :keep)")
    suspend fun pruneSkipped(nodeIdHex: String, keep: Int)

    /** Cryptographically erase one contact's ratchet: chain keys + SAS fingerprint. */
    @Query("DELETE FROM ratchet_state WHERE contactNodeIdHex = :nodeIdHex")
    suspend fun deleteState(nodeIdHex: String)

    @Query("DELETE FROM ratchet_skipped WHERE contactNodeIdHex = :nodeIdHex")
    suspend fun deleteSkippedForContact(nodeIdHex: String)

    /** All ratchet states — for encrypted backup export. */
    @Query("SELECT * FROM ratchet_state")
    suspend fun allStatesForBackup(): List<RatchetStateEntity>

    @Query("DELETE FROM ratchet_state")
    suspend fun deleteAllState()

    @Query("DELETE FROM ratchet_skipped")
    suspend fun deleteAllSkipped()
}
