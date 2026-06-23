package com.aura.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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
    val ratchetN: Long? = null
)

/** Projection: unread message count for one contact. */
data class UnreadByContact(val contactNodeIdHex: String, val count: Int)

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
