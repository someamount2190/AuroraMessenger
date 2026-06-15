package com.aura.crypto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Forward-secret symmetric ratchet (Phase 9 hardening).
 *
 * Each contact gets two independent hash chains seeded from the KEM shared
 * secret at pairing: one for the messages we send, one for the messages we
 * receive. Roles are assigned deterministically by node-id ordering so both
 * peers agree which chain is which. Every message draws a one-time key from the
 * current chain key, then the chain key is replaced by its successor and the old
 * one is discarded — so a key seized today cannot decrypt yesterday's traffic.
 *
 * Crucially the *root* shared secret is destroyed right after seeding (see
 * [seedFromSharedSecret]); only the moving chain keys survive at rest, which is
 * what makes the forward secrecy real rather than cosmetic.
 *
 * Out-of-order / interleaved frames (a reaction sent between two texts, media in
 * flight) are handled with a bounded skipped-key cache.
 *
 * Persistence is delegated to a host-supplied [RatchetStore] so this class carries
 * no database or platform dependency. It is DI-framework-agnostic; the host wires it
 * up (Aurora provides it as a singleton from its Hilt module).
 */
class RatchetManager(
    private val store: RatchetStore,
    private val hkdf: Hkdf,
    private val cipher: SymmetricCipher
) {
    /** A sealed frame plus the ratchet counter the receiver needs to decrypt it. */
    data class Sealed(val bytes: ByteArray, val n: Long)

    // One lock per contact so concurrent seals/opens can't race the chain state.
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(nodeIdHex: String) = locks.getOrPut(nodeIdHex) { Mutex() }

    /**
     * Establish the ratchet for a freshly paired (or re-paired) contact from the
     * 32-byte KEM [root] secret. Wipes [root] before returning. Resets counters
     * and clears any stale skipped keys from a previous secret.
     */
    suspend fun seedFromSharedSecret(
        contactNodeIdHex: String,
        myNodeIdHex: String,
        peerNodeIdHex: String,
        root: ByteArray
    ) = lockFor(contactNodeIdHex).withLock {
        try {
            val low  = hkdf.derive(ikm = root, info = label("chain|low"))
            val high = hkdf.derive(ikm = root, info = label("chain|high"))
            // The lexicographically-smaller node sends on "low"; the peer mirrors it.
            val iAmLow = myNodeIdHex < peerNodeIdHex
            val sendCk = if (iAmLow) low else high
            val recvCk = if (iAmLow) high else low

            val sasFp    = hkdf.derive(ikm = root, info = "aura-sas-v1".toByteArray(), outputLen = 8)
            val mediaKey = cipher.generateKey()

            store.upsertState(
                RatchetState(
                    contactNodeIdHex  = contactNodeIdHex,
                    sendChainKeyB64   = b64(sendCk),
                    sendN             = 0,
                    recvChainKeyB64   = b64(recvCk),
                    recvN             = 0,
                    sasFingerprintB64 = b64(sasFp),
                    mediaKeyB64       = b64(mediaKey)
                )
            )
            store.pruneSkipped(contactNodeIdHex, keep = 0)   // drop stale skips from any prior secret
            low.fill(0); high.fill(0); sasFp.fill(0); mediaKey.fill(0)
        } finally {
            root.fill(0)
        }
    }

    /** True once a ratchet exists for this contact (i.e. pairing completed). */
    suspend fun isSeeded(contactNodeIdHex: String): Boolean = store.state(contactNodeIdHex) != null

    /**
     * Seal [plaintext] for [contactNodeIdHex] with the next send-chain key, then
     * advance (and discard) that chain key. Returns the sealed bytes and the
     * counter the peer must present to [open]. Null if the contact has no ratchet.
     */
    suspend fun sealNext(
        contactNodeIdHex: String,
        plaintext: ByteArray,
        aad: ByteArray
    ): Sealed? = lockFor(contactNodeIdHex).withLock {
        val state = store.state(contactNodeIdHex) ?: return@withLock null
        val sendCk = B64.decode(state.sendChainKeyB64)
        val mk      = hkdf.derive(ikm = sendCk, info = label("mk"))
        val nextCk  = hkdf.derive(ikm = sendCk, info = label("ck"))
        try {
            val sealed = cipher.encrypt(plaintext, mk, aad).getOrNull() ?: return@withLock null
            val n = state.sendN
            store.upsertState(state.copy(sendChainKeyB64 = b64(nextCk), sendN = n + 1))
            Sealed(sealed, n)
        } finally {
            sendCk.fill(0); mk.fill(0); nextCk.fill(0)
        }
    }

    /**
     * Open a frame sealed at counter [n] from [contactNodeIdHex]. Advances the
     * receive chain (caching message keys for any skipped counters) on success;
     * leaves state untouched on an authentication failure so a forged frame can't
     * burn the chain. Returns null if it can't be decrypted (replay, tamper,
     * unseeded contact, or a skip larger than [MAX_SKIP_AHEAD]).
     */
    suspend fun open(
        contactNodeIdHex: String,
        n: Long,
        sealed: ByteArray,
        aad: ByteArray
    ): ByteArray? = lockFor(contactNodeIdHex).withLock {
        val state = store.state(contactNodeIdHex) ?: return@withLock null

        // Counter already passed: this is an out-of-order / retransmitted frame.
        // Its key, if any, lives in the skipped cache (single use).
        if (n < state.recvN) {
            val sk = store.skipped(contactNodeIdHex, n) ?: return@withLock null
            val mk = B64.decode(sk.messageKeyB64)
            return@withLock try {
                cipher.decrypt(sealed, mk, aad).getOrNull()
                    ?.also { store.deleteSkipped(contactNodeIdHex, n) }
            } finally { mk.fill(0) }
        }

        // Counter at or ahead of expected: walk the chain forward, remembering the
        // keys for any gap, but commit nothing until the frame actually decrypts.
        if (n - state.recvN > MAX_SKIP_AHEAD) return@withLock null
        var ck = B64.decode(state.recvChainKeyB64)
        val skipped = ArrayList<Pair<Long, ByteArray>>()
        var i = state.recvN
        while (i < n) {
            val mkI   = hkdf.derive(ikm = ck, info = label("mk"))
            val nextI = hkdf.derive(ikm = ck, info = label("ck"))
            ck.fill(0); ck = nextI
            skipped.add(i to mkI)
            i++
        }
        val mk     = hkdf.derive(ikm = ck, info = label("mk"))
        val nextCk = hkdf.derive(ikm = ck, info = label("ck"))
        try {
            val plaintext = cipher.decrypt(sealed, mk, aad).getOrNull()
                ?: return@withLock null   // auth failure → do not advance, do not persist skips
            for ((idx, key) in skipped) {
                store.putSkipped(SkippedKey(contactNodeIdHex, idx, b64(key)))
            }
            store.upsertState(state.copy(recvChainKeyB64 = b64(nextCk), recvN = n + 1))
            store.pruneSkipped(contactNodeIdHex, keep = MAX_SKIPPED_STORED)
            plaintext
        } finally {
            ck.fill(0); mk.fill(0); nextCk.fill(0)
            skipped.forEach { it.second.fill(0) }
        }
    }

    /** 6-digit SAS verification code from the stored root fingerprint (20 bits). */
    suspend fun sasCode(contactNodeIdHex: String): String? {
        val fp = store.state(contactNodeIdHex)?.sasFingerprintB64 ?: return null
        val d = B64.decode(fp)
        if (d.size < 3) return null
        val bits20 = ((d[0].toInt() and 0xFF) shl 12) or
                     ((d[1].toInt() and 0xFF) shl 4) or
                     ((d[2].toInt() and 0xFF) ushr 4)
        return "%06d".format(bits20 % 1_000_000)
    }

    /**
     * Per-identity 6-digit SAS code, bound to [targetNodeIdHex], derived from the
     * shared root fingerprint. Both peers compute the same value for a given identity
     * (they share the fingerprint) — so each shows its own code and verifies the one
     * read off the other's screen. A man-in-the-middle gets a different root, hence
     * different codes, and entry fails. Never transmitted.
     */
    suspend fun sasCodeFor(contactNodeIdHex: String, targetNodeIdHex: String): String? {
        val fp = store.state(contactNodeIdHex)?.sasFingerprintB64 ?: return null
        val d = B64.decode(fp)
        val bound = hkdf.derive(ikm = d, info = "aura-sas-id-v1|$targetNodeIdHex".toByteArray(), outputLen = 4)
        val bits20 = ((bound[0].toInt() and 0xFF) shl 12) or
                     ((bound[1].toInt() and 0xFF) shl 4) or
                     ((bound[2].toInt() and 0xFF) ushr 4)
        return "%06d".format(bits20 % 1_000_000)
    }

    /** Local-only key for encrypting this contact's media at rest (never on the wire). */
    suspend fun mediaKey(contactNodeIdHex: String): ByteArray? =
        store.state(contactNodeIdHex)?.mediaKeyB64?.let { B64.decode(it) }

    /** Cryptographically erase one contact's ratchet (chain keys, SAS fp, skipped keys). */
    suspend fun wipe(contactNodeIdHex: String) = lockFor(contactNodeIdHex).withLock {
        store.deleteState(contactNodeIdHex)
        store.deleteSkippedForContact(contactNodeIdHex)
    }

    /** Settings → Clear all data. */
    suspend fun clear() {
        store.deleteAllSkipped()
        store.deleteAllState()
    }

    private fun label(suffix: String) = ("$INFO_PREFIX|$suffix").toByteArray()
    private fun b64(bytes: ByteArray) = B64.encode(bytes)

    companion object {
        private const val INFO_PREFIX = "aura-ratchet-v1"
        /** Largest forward gap we will walk in one step (skip-flood guard). */
        const val MAX_SKIP_AHEAD = 512L
        /** Cap on retained skipped keys per contact. */
        const val MAX_SKIPPED_STORED = 1024
    }
}
