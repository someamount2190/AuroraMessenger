package com.aura.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Per-contact **post-quantum KEM Double Ratchet** session — the single ratchet for all sealed
 * traffic (messages, media wire frames, call/RTC signaling). The stored blob is the static SAS
 * fingerprint + media-at-rest key followed by the entire serialized `KemDoubleRatchet.Session`
 * (root, send/receive chains, the X-Wing ratchet keypair, the peer's ratchet key, and the
 * skipped-message cache), rewritten on each message. Backs the crypto module's `KemSessionStore`.
 */
@Entity(tableName = "kem_ratchet")
data class KemRatchetEntity(
    @PrimaryKey val contactNodeIdHex: String,
    val sessionB64: String
)

@Dao
interface RatchetDao {
    @Query("SELECT sessionB64 FROM kem_ratchet WHERE contactNodeIdHex = :nodeIdHex")
    suspend fun kemSession(nodeIdHex: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun kemUpsert(row: KemRatchetEntity)

    @Query("DELETE FROM kem_ratchet WHERE contactNodeIdHex = :nodeIdHex")
    suspend fun kemDelete(nodeIdHex: String)

    @Query("DELETE FROM kem_ratchet")
    suspend fun kemDeleteAll()

    /** All KEM ratchet sessions — for encrypted backup export (one-device migration). */
    @Query("SELECT * FROM kem_ratchet")
    suspend fun allKemForBackup(): List<KemRatchetEntity>
}
