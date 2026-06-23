package com.aura.crypto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Store-backed driver for the post-quantum [KemDoubleRatchet] — the component that replaces the
 * symmetric [RatchetManager] on the transport path (Phase 5 integration; see
 * [docs/PQ_RATCHET_DESIGN.md] and [docs/CRYPTO_MIGRATION_PLAN.md]).
 *
 * It owns persistence (one serialized [KemDoubleRatchet.Session] blob per contact via
 * [KemSessionStore]) and the **bootstrap**: both peers derive the responder's initial ratchet
 * key deterministically from the shared pairing root, the **initiator** is set up to send first
 * (auto-bootstrap on pairing), and the responder receives. After the first exchange, fresh
 * random ratchet steps restore post-compromise security.
 *
 * > ⚠ Review-gated bespoke protocol crypto. Wiring this into `MessageSender`/`TcpMessageServer`
 * > + a Room-backed [KemSessionStore] + the pairing seed calls is the remaining live-flip step.
 */
class KemRatchetManager(
    private val store: KemSessionStore,
    private val kem: HybridKem,
    private val hkdf: Hkdf,
    cipher: SymmetricCipher
) {
    private val dr = KemDoubleRatchet(kem, hkdf, cipher)

    /** A sealed wire frame plus the message number the peer presents to [open]. */
    data class Sealed(val bytes: ByteArray, val n: Long)

    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(c: String) = locks.getOrPut(c) { Mutex() }

    /**
     * Establish the ratchet for a freshly paired contact from the 32-byte [root].
     * [iAmInitiator] (the scanner/initiator side, which sends first) is set up as the sender;
     * the responder as the receiver. Both derive the same bootstrap key from [root].
     */
    suspend fun seed(
        contactNodeIdHex: String,
        root: ByteArray,
        iAmInitiator: Boolean
    ) = lockFor(contactNodeIdHex).withLock {
        val bootstrap = kem.deterministicKeyPair(bootstrapSeed(root)).getOrThrow()
        val session = if (iAmInitiator) dr.initSender(root.copyOf(), bootstrap.publicKey)
                      else dr.initReceiver(root.copyOf(), bootstrap)
        store.save(contactNodeIdHex, KemRatchetCodec.sessionToBytes(session))
    }

    suspend fun isSeeded(contactNodeIdHex: String): Boolean = store.load(contactNodeIdHex) != null

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
        val session = load(contactNodeIdHex) ?: return@withLock null
        val msg = try { dr.encrypt(session, plaintext, aad) } catch (e: IllegalStateException) {
            return@withLock null   // responder cannot send before receiving the first message
        }
        store.save(contactNodeIdHex, KemRatchetCodec.sessionToBytes(session))
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
        val session = load(contactNodeIdHex) ?: return@withLock null
        val message = try { KemRatchetCodec.messageFromBytes(sealed) } catch (e: Exception) { return@withLock null }
        val pt = dr.decrypt(session, message, aad) ?: return@withLock null
        store.save(contactNodeIdHex, KemRatchetCodec.sessionToBytes(session))
        pt
    }

    suspend fun wipe(contactNodeIdHex: String) = lockFor(contactNodeIdHex).withLock {
        store.delete(contactNodeIdHex)
    }

    suspend fun clear() = store.deleteAll()

    private suspend fun load(contactNodeIdHex: String): KemDoubleRatchet.Session? =
        store.load(contactNodeIdHex)?.let { KemRatchetCodec.sessionFromBytes(it) }

    private fun bootstrapSeed(root: ByteArray): ByteArray =
        hkdf.derive(ikm = root, info = "aura-ratchet-v2|bootstrap".toByteArray(), outputLen = 32)
}
