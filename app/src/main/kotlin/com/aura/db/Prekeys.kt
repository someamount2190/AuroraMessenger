package com.aura.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Our own published prekeys for the forward-secret pairing handshake (PQXDH).
 *
 * Two kinds: a medium-term **signed prekey** ("spk", rotated ~weekly) and a pool of
 * single-use **one-time prekeys** ("opk"). Each is a full hybrid (Kyber-1024 + X25519)
 * KEM keypair. The *public* halves are published (signed) to the rendezvous server so
 * an initiator who scans our QR can encapsulate against them; the *private* halves stay
 * here in the SQLCipher-encrypted DB.
 *
 * Forward secrecy comes from these being ephemeral and destroyed: an OPK row is deleted
 * the instant it is consumed by an incoming handshake, and SPKs are rotated and pruned.
 * So an attacker who later recovers the long-term identity key — having recorded the
 * handshake earlier — still cannot reconstruct the session secret: the prekey privates
 * that also fed it no longer exist.
 */
@Entity(tableName = "prekeys")
data class PrekeyEntity(
    @PrimaryKey val prekeyId: String,
    /** "spk" (signed prekey) or "opk" (one-time prekey). */
    val kind: String,
    /** X-Wing public key (Base64) — what an initiator encapsulates against. */
    val kemPubB64: String,
    /** X-Wing private key seed (Base64) — used to decapsulate, then destroyed. */
    val kemPrivB64: String,
    val createdAtMs: Long,
    /** Set when consumed (OPKs are deleted outright; column kept for future SPK accounting). */
    val usedAtMs: Long? = null
)

@Dao
interface PrekeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(prekey: PrekeyEntity)

    @Query("SELECT * FROM prekeys WHERE prekeyId = :id")
    suspend fun byId(id: String): PrekeyEntity?

    /** Newest signed prekey (the one currently advertised). */
    @Query("SELECT * FROM prekeys WHERE kind = 'spk' ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun currentSpk(): PrekeyEntity?

    /** Oldest-first unused one-time prekeys, up to [n], for publishing. */
    @Query("SELECT * FROM prekeys WHERE kind = 'opk' AND usedAtMs IS NULL ORDER BY createdAtMs ASC LIMIT :n")
    suspend fun unusedOpks(n: Int): List<PrekeyEntity>

    @Query("SELECT COUNT(*) FROM prekeys WHERE kind = 'opk' AND usedAtMs IS NULL")
    suspend fun unusedOpkCount(): Int

    /** Destroy one prekey (one-time prekeys are deleted the moment they're consumed). */
    @Query("DELETE FROM prekeys WHERE prekeyId = :id")
    suspend fun delete(id: String)

    /** Drop signed prekeys older than [cutoff] (rotation + grace window). */
    @Query("DELETE FROM prekeys WHERE kind = 'spk' AND createdAtMs < :cutoff")
    suspend fun pruneOldSpks(cutoff: Long)

    @Query("DELETE FROM prekeys")
    suspend fun deleteAll()
}
