package com.aura.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

// ── Entities ──────────────────────────────────────────────────────────────────

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

/** A message in a conversation. Body is plaintext *inside* the SQLCipher-encrypted DB. */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val contactNodeIdHex: String,
    val fromMe: Boolean,
    val body: String,
    val timestampMs: Long,
    /** pending → sent → delivered (Phase 4 wire protocol drives transitions). */
    val status: String,
    /** text | image | video | audio */
    val type: String = "text",
    /** For media messages (Phase 5): path inside app-private encrypted storage. */
    val mediaPath: String? = null,
    /** Epoch ms when this message self-destructs (Phase 6). Null = never. */
    val expiresAtMs: Long? = null,
    /** Messenger reply: the id of the message this one replies to (null = not a reply). */
    val replyToId: String? = null,
    /** Denormalised snippet of the replied-to message, so the quote survives deletion. */
    val replyPreview: String? = null,
    /** Single emoji reactions — one per person, as in Messenger. */
    val myReaction: String? = null,
    val theirReaction: String? = null,
    /** Duration in ms for audio (voice) / video messages. */
    val durationMs: Long? = null,
    /** False until the recipient opens the conversation. Outgoing rows are read=true. */
    val read: Boolean = false,
    /**
     * For call-log rows (type = "call"): "answered" | "missed" | "declined" | "no_answer".
     * Direction is [fromMe] (true = outgoing call). [durationMs] holds answered duration.
     */
    val callStatus: String? = null,
    /**
     * Forward-secrecy bookkeeping for outbound text/control messages: the sealed
     * wire bytes (nonce+ciphertext, Base64) and the ratchet counter assigned when
     * this message was first sealed. Stored so retransmits resend identical
     * ciphertext instead of consuming a fresh ratchet step. Null for inbound rows
     * and for media (which seals its own blob).
     */
    val sealedB64: String? = null,
    val ratchetN: Long? = null,
    /**
     * Group chat: the group this message belongs to (null = 1:1). For a group message the
     * conversation key in [contactNodeIdHex] is the groupId, and [senderNodeId] is the author.
     */
    val groupId: String? = null,
    /** For inbound group messages (fromMe = false): who actually sent it. Null for 1:1. */
    val senderNodeId: String? = null
)

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
    /** Public halves (Base64) — what an initiator encapsulates against. */
    val kyberPubB64: String,
    val x25519PubB64: String,
    /** Private halves (Base64) — used to decapsulate, then destroyed. */
    val kyberPrivB64: String,
    val x25519PrivB64: String,
    val createdAtMs: Long,
    /** Set when consumed (OPKs are deleted outright; column kept for future SPK accounting). */
    val usedAtMs: Long? = null
)

/** Projection: unread message count for one contact. */
data class UnreadByContact(val contactNodeIdHex: String, val count: Int)

/** Padding IPs from /find responses — future ShadowMesh bootstrap peers (Phase 3). */
@Entity(tableName = "mesh_peers")
data class MeshPeerEntity(
    @PrimaryKey val ipPort: String,
    val ip: String,
    val port: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long
)

/**
 * Degraded carry relay (group chat, piece 3): an opaque, end-to-end-sealed envelope this device
 * is holding to deliver to [target] (a co-member of [groupId]) when it next becomes reachable.
 * [innerJson] is the original sender's `msg` frame addressed to [target] — we ferry it, we can't
 * read it. Stored encrypted at rest (SQLCipher). See Aurora Instructions/GROUP_DEGRADED_RELAY.md.
 */
@Entity(tableName = "carry_queue", primaryKeys = ["msgId", "target"])
data class CarryEntity(
    val msgId: String,
    val target: String,
    val groupId: String,
    val originNodeId: String,
    val innerJson: String,
    val createdMs: Long,
    val ttlMs: Long,
    val attempts: Int,
    val lastAttemptMs: Long
)

/** A group conversation (group chat, piece 1). [createdByNodeId] is the admin / source of truth. */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val createdByNodeId: String,
    val epoch: Long,
    val createdMs: Long,
    val active: Boolean
)

/** One member of a group; [role] is "admin" or "member". */
@Entity(tableName = "group_members", primaryKeys = ["groupId", "nodeId"])
data class GroupMemberEntity(
    val groupId: String,
    val nodeId: String,
    val role: String,
    val addedMs: Long
)

/** Per-member delivery state for an outbound group message (fan-out tracking). */
@Entity(tableName = "group_delivery", primaryKeys = ["msgId", "memberNodeId"])
data class GroupDeliveryEntity(
    val msgId: String,
    val groupId: String,
    val memberNodeId: String,
    val status: String,          // pending | delivered
    val sealedB64: String? = null,
    val ratchetN: Long? = null
)

// ── DAOs ──────────────────────────────────────────────────────────────────────

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

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE contactNodeIdHex = :contactNodeIdHex ORDER BY timestampMs ASC")
    fun observeConversation(contactNodeIdHex: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE contactNodeIdHex = :contactNodeIdHex ORDER BY timestampMs DESC LIMIT 1")
    fun observeLastMessage(contactNodeIdHex: String): Flow<MessageEntity?>

    /** Latest message per contact (drives the home conversation-row preview). */
    @Query("SELECT * FROM messages m WHERE timestampMs = " +
           "(SELECT MAX(timestampMs) FROM messages WHERE contactNodeIdHex = m.contactNodeIdHex) " +
           "GROUP BY contactNodeIdHex")
    fun observeLatestPerContact(): Flow<List<MessageEntity>>

    /** Unread counts grouped by contact (unread = incoming/missed not yet seen). */
    @Query("SELECT contactNodeIdHex AS contactNodeIdHex, COUNT(*) AS count FROM messages WHERE read = 0 GROUP BY contactNodeIdHex")
    fun observeUnreadByContact(): Flow<List<UnreadByContact>>

    /** Mark every unread message in a conversation as read (called when it's opened). */
    @Query("UPDATE messages SET read = 1 WHERE contactNodeIdHex = :contactNodeIdHex AND read = 0")
    suspend fun markConversationRead(contactNodeIdHex: String)

    /** Message timestamps for a contact since [sinceMs] — feeds streak computation. */
    @Query("SELECT timestampMs FROM messages WHERE contactNodeIdHex = :contactNodeIdHex AND timestampMs >= :sinceMs")
    suspend fun messageTimestamps(contactNodeIdHex: String, sinceMs: Long): List<Long>

    // Text only: this drives the MessageSender "msg"-frame path. Media rows have their own
    // chunked transport (MediaTransfer) — sending one here would deliver the "📷 Photo"
    // label as a text message and never carry the blob.
    @Query("SELECT * FROM messages WHERE fromMe = 1 AND status = 'pending' AND type = 'text' AND contactNodeIdHex = :contactNodeIdHex ORDER BY timestampMs ASC")
    suspend fun pendingForContact(contactNodeIdHex: String): List<MessageEntity>

    /** Pending outbound media (photo/video/voice), retried by MediaTransfer.flushPendingMedia. */
    @Query("SELECT * FROM messages WHERE fromMe = 1 AND status = 'pending' AND type IN ('image', 'video', 'audio') ORDER BY timestampMs ASC")
    suspend fun pendingMedia(): List<MessageEntity>

    @Query("SELECT DISTINCT contactNodeIdHex FROM messages WHERE fromMe = 1 AND status = 'pending'")
    suspend fun contactsWithPending(): List<String>

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    /** Persist the sealed wire bytes + ratchet counter so a retransmit reuses them. */
    @Query("UPDATE messages SET sealedB64 = :sealedB64, ratchetN = :n WHERE id = :id")
    suspend fun setSealed(id: String, sealedB64: String, n: Long)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: String): MessageEntity?

    /** Full-text-ish search over message bodies (text messages only). Local DB only. */
    @Query("SELECT * FROM messages WHERE type = 'text' AND body LIKE '%' || :q || '%' ORDER BY timestampMs DESC LIMIT 50")
    suspend fun searchMessages(q: String): List<MessageEntity>

    /** My reaction on a message (Messenger-style, one per person). Null clears it. */
    @Query("UPDATE messages SET myReaction = :emoji WHERE id = :id")
    suspend fun setMyReaction(id: String, emoji: String?)

    /** The peer's reaction, applied when their "react" control arrives. */
    @Query("UPDATE messages SET theirReaction = :emoji WHERE id = :id")
    suspend fun setTheirReaction(id: String, emoji: String?)

    @Query("UPDATE messages SET expiresAtMs = :expiresAtMs WHERE id = :id AND expiresAtMs IS NULL")
    suspend fun setExpiry(id: String, expiresAtMs: Long)

    @Query("SELECT * FROM messages WHERE expiresAtMs IS NOT NULL AND expiresAtMs <= :nowMs")
    suspend fun expired(nowMs: Long): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** Encrypted media file paths for a contact — so they can be erased on wipe. */
    @Query("SELECT mediaPath FROM messages WHERE contactNodeIdHex = :nodeIdHex AND mediaPath IS NOT NULL")
    suspend fun mediaPathsForContact(nodeIdHex: String): List<String>

    @Query("DELETE FROM messages WHERE contactNodeIdHex = :nodeIdHex")
    suspend fun deleteForContact(nodeIdHex: String)

    /** All messages — for encrypted backup export. */
    @Query("SELECT * FROM messages")
    suspend fun allForBackup(): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface RatchetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: RatchetStateEntity)

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

@Dao
interface MeshPeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(peers: List<MeshPeerEntity>)

    @Query("SELECT * FROM mesh_peers ORDER BY lastSeenMs DESC")
    suspend fun all(): List<MeshPeerEntity>

    @Query("SELECT COUNT(*) FROM mesh_peers")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM mesh_peers")
    suspend fun deleteAll()
}

@Dao
interface CarryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CarryEntity)

    @Query("SELECT DISTINCT target FROM carry_queue")
    suspend fun targets(): List<String>

    @Query("SELECT * FROM carry_queue WHERE target = :target ORDER BY createdMs ASC")
    suspend fun forTarget(target: String): List<CarryEntity>

    @Query("DELETE FROM carry_queue WHERE msgId = :msgId AND target = :target")
    suspend fun delete(msgId: String, target: String)

    @Query("UPDATE carry_queue SET attempts = attempts + 1, lastAttemptMs = :nowMs WHERE msgId = :msgId AND target = :target")
    suspend fun markAttempt(msgId: String, target: String, nowMs: Long)

    /** GC: drop every envelope past its TTL. */
    @Query("DELETE FROM carry_queue WHERE createdMs + ttlMs <= :nowMs")
    suspend fun expireOlderThan(nowMs: Long)

    @Query("SELECT COUNT(*) FROM carry_queue")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM carry_queue WHERE originNodeId = :origin")
    suspend fun countFromOrigin(origin: String): Int

    /** Oldest entry, used to evict when the queue hits its cap. */
    @Query("SELECT * FROM carry_queue ORDER BY createdMs ASC LIMIT 1")
    suspend fun oldest(): CarryEntity?

    @Query("DELETE FROM carry_queue")
    suspend fun deleteAll()
}

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: GroupEntity)

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    suspend fun group(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE active = 1 ORDER BY createdMs DESC")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    fun observeGroup(groupId: String): Flow<GroupEntity?>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("UPDATE groups SET epoch = :epoch WHERE groupId = :groupId")
    suspend fun setEpoch(groupId: String, epoch: Long)

    @Query("UPDATE groups SET name = :name WHERE groupId = :groupId")
    suspend fun setName(groupId: String, name: String)

    @Query("UPDATE groups SET active = :active WHERE groupId = :groupId")
    suspend fun setActive(groupId: String, active: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: GroupMemberEntity)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND nodeId = :nodeId")
    suspend fun removeMember(groupId: String, nodeId: String)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun members(groupId: String): List<GroupMemberEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM group_members WHERE groupId = :groupId AND nodeId = :nodeId)")
    suspend fun isMember(groupId: String, nodeId: String): Boolean

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: String)

    // ── group message fan-out delivery ──
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDelivery(row: GroupDeliveryEntity)

    @Query("SELECT * FROM group_delivery WHERE status = 'pending'")
    suspend fun pendingDeliveries(): List<GroupDeliveryEntity>

    @Query("UPDATE group_delivery SET status = 'delivered' WHERE msgId = :msgId AND memberNodeId = :member")
    suspend fun markDelivered(msgId: String, member: String)

    @Query("UPDATE group_delivery SET sealedB64 = :sealedB64, ratchetN = :n WHERE msgId = :msgId AND memberNodeId = :member")
    suspend fun setDeliverySealed(msgId: String, member: String, sealedB64: String, n: Long)

    @Query("SELECT COUNT(*) FROM group_delivery WHERE msgId = :msgId AND status = 'pending'")
    suspend fun pendingCount(msgId: String): Int
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [
        ContactEntity::class, MessageEntity::class, MeshPeerEntity::class,
        RatchetStateEntity::class, RatchetSkippedKeyEntity::class, PrekeyEntity::class,
        CarryEntity::class, GroupEntity::class, GroupMemberEntity::class, GroupDeliveryEntity::class
    ],
    version = 10,
    // Export the schema so version 6 became the migration baseline: from here on,
    // changes ship as @AutoMigration / Migration objects that PRESERVE user data
    // instead of wiping it. (Schema JSONs land in app/schemas — commit them.)
    // v6→v7 adds `prekeys` (PQXDH handshake); v7→v8 adds `carry_queue` (degraded carry
    // relay); v8→v9 adds `groups` + `group_members`; v9→v10 adds `group_delivery` and the
    // groupId/senderNodeId columns on `messages` (group chat fan-out). All purely additive,
    // so automatic migrations carry every real user across.
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10)
    ]
)
abstract class AuroraDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun meshPeerDao(): MeshPeerDao
    abstract fun ratchetDao(): RatchetDao
    abstract fun prekeyDao(): PrekeyDao
    abstract fun carryDao(): CarryDao
    abstract fun groupDao(): GroupDao

    companion object {
        /** Build the SQLCipher-encrypted database. [dbKey] must be 32 bytes. */
        fun build(context: Context, dbKey: ByteArray): AuroraDatabase {
            require(dbKey.size == 32) { "DB key must be 32 bytes" }
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, AuroraDatabase::class.java, "aura.db")
                .openHelperFactory(SupportOpenHelperFactory(dbKey))
                // Only the pre-export schemas (v1..v5, which have no committed schema to
                // migrate from) are allowed to wipe. From v6 onward every bump MUST ship a
                // migration, so a real user's contacts/messages survive app updates.
                .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                .build()
        }
    }
}
