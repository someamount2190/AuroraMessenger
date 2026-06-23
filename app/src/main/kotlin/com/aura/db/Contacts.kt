package com.aura.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A paired contact. Created by QR pairing (Phase 2). */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val nodeIdHex: String,
    val displayName: String,
    /** Full HybridPublicKey bytes (Kyber-1024 + X25519), Base64. */
    val kemPubB64: String,
    /** Raw 32-byte Ed25519 public key, Base64 — verifies rendezvous check-ins. */
    val ed25519PubB64: String,
    /**
     * Dilithium-3 (ML-DSA) public key, Base64 — the post-quantum half of the
     * peer's signing key. Learned off-QR during the pairing handshake (the QR is
     * too small to carry it). Null until the pair-ack arrives; when present,
     * rendezvous check-ins are verified with the full hybrid signature.
     */
    val dilithiumPubB64: String? = null,
    /**
     * Legacy field. The KEM shared secret is no longer stored at rest: it seeds a
     * forward-secret ratchet at pairing and is then destroyed (see RatchetManager).
     * Kept empty for pre-ratchet rows only.
     */
    val sharedSecretB64: String = "",
    val createdAtMs: Long,
    /** True once our pairing message (KEM ciphertext) reached the peer's signal queue. */
    val pairingSent: Boolean,
    /** Disappearing-message timer key ("off", "1h", "24h", "7d") — Phase 6. */
    val disappearingTimer: String = "off",
    /**
     * False until the user has named this contact. No longer blocks chatting — the
     * empty chat just shows a hint to rename (see ConversationScreen).
     */
    val nicknameSet: Boolean = false,
    /**
     * Pairing lifecycle — see [PairState]. "requested" (we scanned them, awaiting
     * their accept) and "incoming" (they scanned us, awaiting our accept/reject) are
     * not openable; "verify" opens to the mutual code screen; "active" is a normal
     * chat. Defaults to active so any non-pairing row is usable.
     */
    val pairState: String = PairState.ACTIVE,
    /** We initiated (scanned their QR) — drives "awaiting" vs "accept/reject" UI. */
    val isInitiator: Boolean = false,
    /** Mutual verification: we entered their code correctly. */
    val iVerified: Boolean = false,
    /** Mutual verification: they confirmed entering our code correctly. */
    val theyVerified: Boolean = false,
    /** Wrong code-entry attempts during verification (blocklist past the cap). */
    val verifyAttempts: Int = 0
)

/** Pairing lifecycle states stored in [ContactEntity.pairState]. */
object PairState {
    const val REQUESTED = "requested"   // we scanned them; waiting for their accept
    const val INCOMING  = "incoming"    // they scanned us; waiting for our accept/reject
    const val VERIFY    = "verify"      // handshake done; mutual code check pending
    const val ACTIVE    = "active"      // verified; full chat
}

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("SELECT * FROM contacts ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE nodeIdHex = :nodeIdHex")
    suspend fun byNodeId(nodeIdHex: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE nodeIdHex = :nodeIdHex")
    fun observeByNodeId(nodeIdHex: String): Flow<ContactEntity?>

    @Query("SELECT * FROM contacts WHERE pairingSent = 0")
    suspend fun pendingPairingSends(): List<ContactEntity>

    @Query("UPDATE contacts SET pairingSent = 1 WHERE nodeIdHex = :nodeIdHex")
    suspend fun markPairingSent(nodeIdHex: String)

    /** Rename and mark the nickname as set (dismisses the rename hint in the chat). */
    @Query("UPDATE contacts SET displayName = :name, nicknameSet = 1 WHERE nodeIdHex = :nodeIdHex")
    suspend fun rename(nodeIdHex: String, name: String)

    @Query("UPDATE contacts SET disappearingTimer = :timerKey WHERE nodeIdHex = :nodeIdHex")
    suspend fun setDisappearingTimer(nodeIdHex: String, timerKey: String)

    // ── Pairing lifecycle ───────────────────────────────────────────────────
    @Query("UPDATE contacts SET pairState = :state WHERE nodeIdHex = :nodeIdHex")
    suspend fun setPairState(nodeIdHex: String, state: String)

    /** Accept: move to verify, learn the peer's dilithium, drop the held secret. */
    @Query("UPDATE contacts SET pairState = :state, dilithiumPubB64 = :dil, sharedSecretB64 = '' WHERE nodeIdHex = :nodeIdHex")
    suspend fun markVerifyReady(nodeIdHex: String, state: String, dil: String)

    @Query("UPDATE contacts SET pairState = :state, sharedSecretB64 = '' WHERE nodeIdHex = :nodeIdHex")
    suspend fun markVerify(nodeIdHex: String, state: String)

    @Query("UPDATE contacts SET iVerified = :iv, theyVerified = :tv, pairState = :state WHERE nodeIdHex = :nodeIdHex")
    suspend fun setVerify(nodeIdHex: String, iv: Boolean, tv: Boolean, state: String)

    @Query("UPDATE contacts SET verifyAttempts = verifyAttempts + 1 WHERE nodeIdHex = :nodeIdHex")
    suspend fun incVerifyAttempts(nodeIdHex: String)

    @Query("DELETE FROM contacts WHERE nodeIdHex = :nodeIdHex")
    suspend fun deleteByNodeId(nodeIdHex: String)

    /** Established contacts only — for encrypted backup export. */
    @Query("SELECT * FROM contacts WHERE pairState = 'active'")
    suspend fun activeForBackup(): List<ContactEntity>

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}
