package com.aura.backup

import android.util.Base64
import com.aura.crypto.HybridPrivateKey
import com.aura.crypto.HybridPublicKey
import com.aura.crypto.HybridSigningKey
import com.aura.crypto.HybridVerifyKey
import com.aura.crypto.NodeIdentity
import com.aura.crypto.NodePrivateIdentity
import com.aura.crypto.NodePublicIdentity
import com.aura.crypto.SymmetricCipher
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.KemRatchetEntity
import com.aura.db.MessageDao
import com.aura.db.MessageEntity
import com.aura.db.RatchetDao
import com.aura.di.IoDispatcher
import com.aura.identity.IdentityStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.inject.Inject

/**
 * Opt-in, passphrase-encrypted backup of the user's identity + established contacts +
 * ratchet state + messages, for restoring after a reinstall or move to a new phone.
 *
 * Never automatic, never a cloud upload — the user picks a passphrase and a file. The
 * passphrase is stretched with **Argon2id** (memory-hard, so a weak passphrase is far
 * harder to brute-force) into a 32-byte key, and the payload is sealed with the same
 * XChaCha20-Poly1305 AEAD used everywhere else. The container carries only the random
 * salt and the sealed blob; the passphrase itself is never stored.
 *
 * Restoring overwrites the current identity, so the caller hard-exits afterwards and
 * the next launch boots into the restored state. Media *files* aren't included (they'd
 * bloat the file) — media messages restore as rows but their previews won't load.
 */
class Backups @Inject constructor(
    private val identityManager: IdentityStore,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val ratchetDao: RatchetDao,
    private val cipher: SymmetricCipher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    /** Build an encrypted backup blob (the bytes the caller writes to a user-chosen file). */
    suspend fun export(passphrase: CharArray): ByteArray = withContext(ioDispatcher) {
        val identity = identityManager.getOrCreate()
        val inner = JSONObject()
            .put("v", DATA_VERSION)
            .put("identity", identityToJson(identity))
            .put("contacts", JSONArray().apply { contactDao.activeForBackup().forEach { put(contactToJson(it)) } })
            // KEM Double Ratchet sessions (the only ratchet now; carries the SAS fingerprint +
            // media-at-rest key in-blob). Restored so a device MOVE continues conversations
            // without re-pairing (Aurora is one-device-per-identity, so restore is a migration).
            .put("kemRatchets", JSONArray().apply {
                ratchetDao.allKemForBackup().forEach {
                    put(JSONObject().put("c", it.contactNodeIdHex).put("s", it.sessionB64))
                }
            })
            .put("messages", JSONArray().apply { messageDao.allForBackup().forEach { put(messageToJson(it)) } })
        val plaintext = inner.toString().toByteArray(Charsets.UTF_8)
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        try {
            val sealed = cipher.encrypt(plaintext, key, BACKUP_AAD).getOrThrow()
            JSONObject()
                .put("magic", MAGIC)
                .put("v", CONTAINER_VERSION)
                .put("kdf", "argon2id")
                .put("salt", b64(salt))
                .put("data", b64(sealed))
                .toString().toByteArray(Charsets.UTF_8)
        } finally {
            key.fill(0); plaintext.fill(0)
        }
    }

    /**
     * Decrypt and restore a backup. Overwrites identity + contacts + ratchet + messages.
     * Returns failure on a wrong passphrase or a malformed/foreign file. The caller
     * should hard-exit on success so the restored identity loads cleanly next launch.
     */
    suspend fun import(blob: ByteArray, passphrase: CharArray): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val container = JSONObject(String(blob, Charsets.UTF_8))
                require(container.optString("magic") == MAGIC) { "Not an Aurora backup file" }
                val salt = b64d(container.getString("salt"))
                val sealed = b64d(container.getString("data"))
                val key = deriveKey(passphrase, salt)
                val plaintext = try {
                    cipher.decrypt(sealed, key, BACKUP_AAD).getOrNull()
                } finally { key.fill(0) }
                    ?: throw IllegalStateException("Wrong passphrase or corrupt backup")

                val inner = JSONObject(String(plaintext, Charsets.UTF_8))
                val dataVersion = inner.optInt("v", 1)
                identityManager.restore(jsonToIdentity(inner.getJSONObject("identity")))
                inner.getJSONArray("contacts").let { a -> for (i in 0 until a.length()) contactDao.upsert(jsonToContact(a.getJSONObject(i))) }
                // KEM ratchet sessions. The v3 blob format folds the SAS fingerprint + media key
                // into the session, so only restore from v3+ backups; older sessions are stale
                // under the retired symmetric ratchet and re-pair instead.
                if (dataVersion >= 3) {
                    inner.optJSONArray("kemRatchets")?.let { a ->
                        for (i in 0 until a.length()) {
                            val o = a.getJSONObject(i)
                            ratchetDao.kemUpsert(KemRatchetEntity(o.getString("c"), o.getString("s")))
                        }
                    }
                }
                inner.getJSONArray("messages").let { a -> for (i in 0 until a.length()) messageDao.insert(jsonToMessage(a.getJSONObject(i))) }
                plaintext.fill(0)
                Unit
            }
        }

    // ── Argon2id passphrase stretching ────────────────────────────────────────

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(ARGON2_ITERATIONS)
            .withMemoryAsKB(ARGON2_MEMORY_KB)
            .withParallelism(ARGON2_PARALLELISM)
            .withSalt(salt)
            .build()
        val out = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(passphrase, out)
        return out
    }

    // ── (de)serialization ─────────────────────────────────────────────────────

    private fun identityToJson(id: NodeIdentity) = JSONObject()
        .put("nodeId", b64(id.nodeId))
        .put("kemPub", b64(id.publicPart.kemPublicKey.encoded))
        .put("kemPriv", b64(id.privatePart.kemPrivateKey.encoded))
        .put("dilithiumPub", b64(id.publicPart.signingPublicKey.dilithiumPublicKey))
        .put("ed25519Pub", b64(id.publicPart.signingPublicKey.ed25519PublicKey))
        .put("dilithiumPriv", b64(id.privatePart.signingPrivateKey.dilithiumPrivateKey))
        .put("ed25519Priv", b64(id.privatePart.signingPrivateKey.ed25519PrivateKey))

    private fun jsonToIdentity(j: JSONObject): NodeIdentity {
        val nodeId = b64d(j.getString("nodeId"))
        val kemPub = HybridPublicKey(b64d(j.getString("kemPub")))
        val sigPub = HybridVerifyKey(b64d(j.getString("dilithiumPub")), b64d(j.getString("ed25519Pub")))
        val kemPriv = HybridPrivateKey(b64d(j.getString("kemPriv")))
        val sigPriv = HybridSigningKey(b64d(j.getString("dilithiumPriv")), b64d(j.getString("ed25519Priv")))
        return NodeIdentity(nodeId, NodePublicIdentity(nodeId, kemPub, sigPub), NodePrivateIdentity(nodeId, kemPriv, sigPriv))
    }

    private fun contactToJson(c: ContactEntity) = JSONObject()
        .put("nodeIdHex", c.nodeIdHex).put("displayName", c.displayName)
        .put("kemPubB64", c.kemPubB64).put("ed25519PubB64", c.ed25519PubB64)
        .putOpt("dilithiumPubB64", c.dilithiumPubB64)
        .put("createdAtMs", c.createdAtMs).put("pairingSent", c.pairingSent)
        .put("disappearingTimer", c.disappearingTimer).put("nicknameSet", c.nicknameSet)
        .put("pairState", c.pairState).put("isInitiator", c.isInitiator)

    private fun jsonToContact(j: JSONObject) = ContactEntity(
        nodeIdHex = j.getString("nodeIdHex"),
        displayName = j.getString("displayName"),
        kemPubB64 = j.getString("kemPubB64"),
        ed25519PubB64 = j.getString("ed25519PubB64"),
        dilithiumPubB64 = j.strOrNull("dilithiumPubB64"),
        sharedSecretB64 = "",
        createdAtMs = j.getLong("createdAtMs"),
        pairingSent = j.getBoolean("pairingSent"),
        disappearingTimer = j.optString("disappearingTimer", "off"),
        nicknameSet = j.getBoolean("nicknameSet"),
        pairState = j.optString("pairState", "active"),
        isInitiator = j.optBoolean("isInitiator", false)
    )

    private fun messageToJson(m: MessageEntity) = JSONObject()
        .put("id", m.id).put("contactNodeIdHex", m.contactNodeIdHex).put("fromMe", m.fromMe)
        .put("body", m.body).put("timestampMs", m.timestampMs).put("status", m.status)
        .put("type", m.type).putOpt("mediaPath", m.mediaPath).putOpt("expiresAtMs", m.expiresAtMs)
        .putOpt("replyToId", m.replyToId).putOpt("replyPreview", m.replyPreview)
        .putOpt("myReaction", m.myReaction).putOpt("theirReaction", m.theirReaction)
        .putOpt("durationMs", m.durationMs).put("read", m.read).putOpt("callStatus", m.callStatus)
        .putOpt("sealedB64", m.sealedB64).putOpt("ratchetN", m.ratchetN)

    private fun jsonToMessage(j: JSONObject) = MessageEntity(
        id = j.getString("id"), contactNodeIdHex = j.getString("contactNodeIdHex"),
        fromMe = j.getBoolean("fromMe"), body = j.getString("body"),
        timestampMs = j.getLong("timestampMs"), status = j.getString("status"),
        type = j.optString("type", "text"), mediaPath = j.strOrNull("mediaPath"),
        expiresAtMs = j.longOrNull("expiresAtMs"), replyToId = j.strOrNull("replyToId"),
        replyPreview = j.strOrNull("replyPreview"), myReaction = j.strOrNull("myReaction"),
        theirReaction = j.strOrNull("theirReaction"), durationMs = j.longOrNull("durationMs"),
        read = j.optBoolean("read", false), callStatus = j.strOrNull("callStatus"),
        sealedB64 = j.strOrNull("sealedB64"), ratchetN = j.longOrNull("ratchetN")
    )

    private fun JSONObject.strOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONObject.longOrNull(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val MAGIC = "AURABK"
        const val CONTAINER_VERSION = 1
        const val DATA_VERSION = 3   // v3: single KEM ratchet (SAS fp + media key in-blob); no symmetric ratchet
        const val SALT_BYTES = 16
        val BACKUP_AAD = "aura-backup-v1".toByteArray()
        // Argon2id: ~64 MB, 3 passes — strong against offline guessing, fine for one-shot on a phone.
        const val ARGON2_ITERATIONS = 3
        const val ARGON2_MEMORY_KB = 64 * 1024
        const val ARGON2_PARALLELISM = 2
    }
}
