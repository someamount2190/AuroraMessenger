package com.aura.crypto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Store-backed driver for the post-quantum [KemDoubleRatchet] — the single per-contact ratchet
 * for **all** sealed traffic: chat messages, media wire frames, and call / WebRTC signaling
 * (see [docs/PQ_RATCHET_DESIGN.md] and [docs/CRYPTO_MIGRATION_PLAN.md]). It supersedes the old
 * symmetric `RatchetManager`, which has been retired.
 *
 * Alongside the ratchet it owns two static, per-contact secrets derived/minted at pairing and
 * carried in the same persisted blob so they survive every ratchet step:
 *  - the 8-byte **SAS fingerprint** (HKDF of the shared pairing root) that backs the mutual
 *    short-authentication-string check ([sasCodeFor]); and
 *  - a local-only random **media-at-rest key** ([mediaKey]) that never travels, so old media
 *    stays viewable on this device even as the wire ratchet advances.
 *
 * Persistence is one opaque blob per contact via [KemSessionStore]:
 * `[1B ver][8B sasFp][32B mediaKey][serialized KemDoubleRatchet.Session]`. Bootstrap is
 * deterministic from the root: both peers derive the responder's initial ratchet key, the
 * **initiator** is set up to send first (auto-bootstrap on pairing), the responder receives.
 * After the first exchange, fresh random ratchet steps restore post-compromise security.
 */
class KemRatchetManager(
    private val store: KemSessionStore,
    private val kem: HybridKem,
    private val hkdf: Hkdf,
    private val cipher: SymmetricCipher
) {
    private val dr = KemDoubleRatchet(kem, hkdf, cipher)

    /** A sealed wire frame plus the message number the peer presents to [open]. */
    data class Sealed(val bytes: ByteArray, val n: Long)

    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(c: String) = locks.getOrPut(c) { Mutex() }

    /**
     * Establish the ratchet for a freshly paired contact from the 32-byte [root] (which is
     * **wiped** before returning). [iAmInitiator] (the scanner/initiator side, which sends first)
     * is set up as the sender; the responder as the receiver. Both derive the same bootstrap key
     * and the same SAS fingerprint from [root]; the media key is fresh local randomness.
     */
    suspend fun seed(
        contactNodeIdHex: String,
        root: ByteArray,
        iAmInitiator: Boolean
    ) = lockFor(contactNodeIdHex).withLock {
        try {
            val bootstrap = kem.deterministicKeyPair(bootstrapSeed(root)).getOrThrow()
            val session = if (iAmInitiator) dr.initSender(root.copyOf(), bootstrap.publicKey)
                          else dr.initReceiver(root.copyOf(), bootstrap)
            val sasFp = hkdf.derive(ikm = root, info = "aura-sas-v1".toByteArray(), outputLen = SAS_FP_BYTES)
            val mediaKey = cipher.generateKey()
            store.save(contactNodeIdHex, pack(sasFp, mediaKey, session))
            sasFp.fill(0); mediaKey.fill(0)
        } finally {
            root.fill(0)
        }
    }

    /** True once a usable (current-format) session exists for this contact. A malformed or
     *  legacy-format blob reads as not-seeded so callers re-pair rather than crash. */
    suspend fun isSeeded(contactNodeIdHex: String): Boolean = loadRecord(contactNodeIdHex) != null

    /**
     * Seal [plaintext] for [contactNodeIdHex], advancing (and persisting) the ratchet. Returns
     * the self-contained wire frame and the message number. Null if the contact has no session,
     * or if we are the responder and haven't received the initiator's first message yet.
     */
    suspend fun sealNext(
        contactNodeIdHex: String,
        plaintext: ByteArray,
        aad: ByteArray
    ): Sealed? = lockFor(contactNodeIdHex).withLock {
        val rec = loadRecord(contactNodeIdHex) ?: return@withLock null
        val msg = try { dr.encrypt(rec.session, plaintext, aad) } catch (e: IllegalStateException) {
            return@withLock null   // responder cannot send before receiving the first message
        }
        store.save(contactNodeIdHex, pack(rec.sasFp, rec.mediaKey, rec.session))
        Sealed(KemRatchetCodec.messageToBytes(msg), msg.header.n.toLong())
    }

    /**
     * Open a sealed frame from [contactNodeIdHex]. Persists the advanced ratchet on success;
     * leaves stored state untouched on an authentication failure (the [KemDoubleRatchet] decrypt
     * commits on a working copy). Returns null on tamper/replay/unknown skip/unseeded contact.
     */
    suspend fun open(
        contactNodeIdHex: String,
        sealed: ByteArray,
        aad: ByteArray
    ): ByteArray? = lockFor(contactNodeIdHex).withLock {
        val rec = loadRecord(contactNodeIdHex) ?: return@withLock null
        val message = try { KemRatchetCodec.messageFromBytes(sealed) } catch (e: Exception) { return@withLock null }
        val pt = dr.decrypt(rec.session, message, aad) ?: return@withLock null
        store.save(contactNodeIdHex, pack(rec.sasFp, rec.mediaKey, rec.session))
        pt
    }

    /**
     * Per-identity 6-digit SAS code, bound to [targetNodeIdHex], derived from the shared root
     * fingerprint. Both peers compute the same value for a given identity — so each shows its own
     * code and verifies the one read off the other's screen. A man-in-the-middle gets a different
     * root, hence different codes, and entry fails. Never transmitted.
     */
    suspend fun sasCodeFor(contactNodeIdHex: String, targetNodeIdHex: String): String? {
        val sasFp = loadRecord(contactNodeIdHex)?.sasFp ?: return null
        val bound = hkdf.derive(ikm = sasFp, info = "aura-sas-id-v1|$targetNodeIdHex".toByteArray(), outputLen = 4)
        val bits20 = ((bound[0].toInt() and 0xFF) shl 12) or
                     ((bound[1].toInt() and 0xFF) shl 4) or
                     ((bound[2].toInt() and 0xFF) ushr 4)
        return "%06d".format(bits20 % 1_000_000)
    }

    /** Local-only key for encrypting this contact's media at rest (never on the wire). */
    suspend fun mediaKey(contactNodeIdHex: String): ByteArray? = loadRecord(contactNodeIdHex)?.mediaKey

    suspend fun wipe(contactNodeIdHex: String) = lockFor(contactNodeIdHex).withLock {
        store.delete(contactNodeIdHex)
    }

    suspend fun clear() = store.deleteAll()

    // ── persistence record: static SAS fp + media key ‖ the moving ratchet session ──────────

    private class Record(val sasFp: ByteArray, val mediaKey: ByteArray, val session: KemDoubleRatchet.Session)

    // Fail closed: a malformed or legacy (pre-header) blob parses to null rather than throwing,
    // so a stale row degrades to "re-pair this contact" instead of crashing the caller.
    private suspend fun loadRecord(contactNodeIdHex: String): Record? =
        store.load(contactNodeIdHex)?.let { runCatching { unpack(it) }.getOrNull() }

    private fun pack(sasFp: ByteArray, mediaKey: ByteArray, session: KemDoubleRatchet.Session): ByteArray {
        require(sasFp.size == SAS_FP_BYTES && mediaKey.size == SymmetricCipher.KEY_BYTES)
        val s = KemRatchetCodec.sessionToBytes(session)
        val out = ByteArray(HEADER_BYTES + s.size)
        out[0] = REC_VERSION
        System.arraycopy(sasFp, 0, out, 1, SAS_FP_BYTES)
        System.arraycopy(mediaKey, 0, out, 1 + SAS_FP_BYTES, SymmetricCipher.KEY_BYTES)
        System.arraycopy(s, 0, out, HEADER_BYTES, s.size)
        return out
    }

    private fun unpack(blob: ByteArray): Record {
        require(blob.size >= HEADER_BYTES && blob[0] == REC_VERSION) { "malformed KEM ratchet record" }
        val sasFp = blob.copyOfRange(1, 1 + SAS_FP_BYTES)
        val mediaKey = blob.copyOfRange(1 + SAS_FP_BYTES, HEADER_BYTES)
        val session = KemRatchetCodec.sessionFromBytes(blob.copyOfRange(HEADER_BYTES, blob.size))
        return Record(sasFp, mediaKey, session)
    }

    private fun bootstrapSeed(root: ByteArray): ByteArray =
        hkdf.derive(ikm = root, info = "aura-ratchet-v2|bootstrap".toByteArray(), outputLen = 32)

    private companion object {
        const val REC_VERSION: Byte = 1
        const val SAS_FP_BYTES = 8
        val HEADER_BYTES = 1 + SAS_FP_BYTES + SymmetricCipher.KEY_BYTES   // 41
    }
}
