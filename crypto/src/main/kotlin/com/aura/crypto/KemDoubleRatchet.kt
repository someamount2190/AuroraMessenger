package com.aura.crypto

/**
 * **Post-quantum asymmetric (KEM) Double Ratchet** — Phase 5 of
 * [docs/CRYPTO_MIGRATION_PLAN.md], specified in [docs/PQ_RATCHET_DESIGN.md].
 *
 * > ⚠ **FOR REVIEW — not yet wired into the app.** This is bespoke protocol crypto: a
 * > self-contained, pure-JVM reference implementation proven by [KemDoubleRatchetTest]
 * > (round-trips across many ratchet steps, **post-compromise-security "healing"**,
 * > out-of-order/skipped across epochs, tamper rejection). It must pass dedicated review
 * > before Aurora relies on it; integration into the transport frame format
 * > ([com.aura.transport]) is a deliberate later step. The shipping ratchet remains the
 * > symmetric [RatchetManager].
 *
 * Structure follows Signal's Double Ratchet, substituting an **X-Wing** (ML-KEM-768 + X25519)
 * KEM for the DH ratchet step so healing is hybrid post-quantum. Three KDF chains: a root
 * chain advanced by each KEM step, and symmetric send/receive chains (one message key each,
 * then the chain key is replaced and discarded — forward secrecy).
 *
 * The one structural difference from DH: a KEM encapsulation yields a *ciphertext that must be
 * transmitted*, so the new **sending**-chain derivation is deferred to the next send (carrying
 * the ciphertext in the header) rather than computed eagerly at receive time. The keypair
 * rotation happens at the receiving step, exactly as Signal rotates `DHs` in `DHRatchet`.
 */
class KemDoubleRatchet(
    private val kem: HybridKem,
    private val hkdf: Hkdf,
    private val cipher: SymmetricCipher
) {

    /** Header sent with every frame; binds into the AEAD AAD. */
    data class Header(
        val ratchetPub: HybridPublicKey,   // sender's current advertised ratchet key
        val kemCt: HybridCiphertext?,      // present only on a ratchet step
        val pn: Int,                       // # messages in the previous sending chain
        val n: Int                         // message number in the current sending chain
    ) {
        fun aad(extra: ByteArray): ByteArray =
            ratchetPub.toBytes() +
            (kemCt?.toBytes() ?: ByteArray(0)) +
            intTo4Bytes(if (kemCt != null) 1 else 0) +
            intTo4Bytes(pn) + intTo4Bytes(n) +
            extra
    }

    data class Message(val header: Header, val ciphertext: ByteArray)

    /**
     * Mutable per-peer ratchet state. Create via [initSender] / [initReceiver]. Treated as a
     * value: [decrypt] works on a deep copy and only commits back on a successful auth, so a
     * forged frame can never corrupt the live state.
     */
    class Session internal constructor(
        internal var rootKey: ByteArray,
        internal var sendChainKey: ByteArray?,
        internal var recvChainKey: ByteArray?,
        internal var ns: Int,
        internal var nr: Int,
        internal var pn: Int,
        internal var selfPriv: HybridPrivateKey?,
        internal var selfPub: HybridPublicKey?,
        internal var peerPub: HybridPublicKey?,
        internal var sendStepNeeded: Boolean,
        internal val skipped: LinkedHashMap<String, ByteArray>
    ) {
        internal fun deepCopy() = Session(
            rootKey.copyOf(), sendChainKey?.copyOf(), recvChainKey?.copyOf(),
            ns, nr, pn,
            selfPriv?.let { HybridPrivateKey(it.encoded.copyOf()) },
            selfPub?.let { HybridPublicKey(it.encoded.copyOf()) },
            peerPub?.let { HybridPublicKey(it.encoded.copyOf()) },
            sendStepNeeded,
            LinkedHashMap(skipped)
        )

        internal fun replaceWith(o: Session) {
            rootKey = o.rootKey; sendChainKey = o.sendChainKey; recvChainKey = o.recvChainKey
            ns = o.ns; nr = o.nr; pn = o.pn
            selfPriv = o.selfPriv; selfPub = o.selfPub; peerPub = o.peerPub
            sendStepNeeded = o.sendStepNeeded
            skipped.clear(); skipped.putAll(o.skipped)
        }
    }

    // ── Initialisation ──────────────────────────────────────────────────────
    //
    // Both peers start from the 32-byte pairing root. The initiator is given the responder's
    // initial ratchet *public* key (in integration: the PQXDH signed prekey); the responder
    // holds the matching keypair. The initiator takes the first sending step.

    suspend fun initSender(rootKey: ByteArray, peerInitialPub: HybridPublicKey): Session {
        val kp = kem.generateKeyPair().getOrThrow()
        return Session(
            rootKey = rootKey.copyOf(),
            sendChainKey = null, recvChainKey = null,
            ns = 0, nr = 0, pn = 0,
            selfPriv = kp.privateKey, selfPub = kp.publicKey,
            peerPub = peerInitialPub,
            sendStepNeeded = true,
            skipped = LinkedHashMap()
        )
    }

    fun initReceiver(rootKey: ByteArray, selfInitialKeyPair: HybridFullKeyPair): Session =
        Session(
            rootKey = rootKey.copyOf(),
            sendChainKey = null, recvChainKey = null,
            ns = 0, nr = 0, pn = 0,
            selfPriv = selfInitialKeyPair.privateKey, selfPub = selfInitialKeyPair.publicKey,
            peerPub = null,
            sendStepNeeded = false,
            skipped = LinkedHashMap()
        )

    // ── Encrypt ───────────────────────────────────────────────────────────────

    /** Seal [plaintext] for [s], advancing the sending chain (and stepping the root if a
     *  direction change is pending). Encryption never fails cryptographically, so it mutates
     *  [s] in place. */
    suspend fun encrypt(s: Session, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): Message {
        var stepCt: HybridCiphertext? = null
        if (s.sendStepNeeded || s.sendChainKey == null) {
            val peer = s.peerPub ?: error("no peer ratchet key — cannot send before first receive")
            val enc = kem.encapsulate(peer).getOrThrow()
            val (rk, ck) = kdfRoot(s.rootKey, enc.sharedSecret)
            enc.sharedSecret.fill(0)
            s.rootKey.fill(0); s.rootKey = rk
            s.sendChainKey = ck
            s.pn = s.ns; s.ns = 0
            s.sendStepNeeded = false
            stepCt = enc.ciphertext
        }
        val ck = s.sendChainKey!!
        val mk = kdfMsg(ck)
        s.sendChainKey = kdfChain(ck); ck.fill(0)
        val header = Header(s.selfPub!!, stepCt, s.pn, s.ns)
        s.ns += 1
        val ciphertext = cipher.encrypt(plaintext, mk, header.aad(aad)).getOrThrow()
        mk.fill(0)
        return Message(header, ciphertext)
    }

    // ── Decrypt ─────────────────────────────────────────────────────────────────

    /** Open [msg] for [s]. Works on a copy and commits only on a successful authentication,
     *  so a forged or replayed frame leaves [s] untouched. Returns null if it can't be opened
     *  (tamper, replay, unknown skip, or a skip beyond [MAX_SKIP]). */
    suspend fun decrypt(s: Session, msg: Message, aad: ByteArray = ByteArray(0)): ByteArray? {
        val w = s.deepCopy()
        val pt = tryDecrypt(w, msg, aad) ?: return null
        s.replaceWith(w)
        return pt
    }

    private suspend fun tryDecrypt(w: Session, msg: Message, aad: ByteArray): ByteArray? {
        val h = msg.header
        val fullAad = h.aad(aad)

        // 1. Out-of-order / retransmitted frame whose key we cached when we skipped past it.
        val skipKey = skipId(h.ratchetPub, h.n)
        w.skipped[skipKey]?.let { mk ->
            val pt = cipher.decrypt(msg.ciphertext, mk, fullAad).getOrNull() ?: return null
            w.skipped.remove(skipKey); mk.fill(0)
            return pt
        }

        // 2. New advertised ratchet key + a KEM ciphertext → receiving ratchet step.
        if (h.kemCt != null && (w.peerPub == null || !w.peerPub!!.encoded.contentEquals(h.ratchetPub.encoded))) {
            if (!skipInRecvChain(w, h.pn)) return null     // finish the previous receiving chain
            val ss = kem.decapsulate(h.kemCt, w.selfPriv!!).getOrNull() ?: return null
            val (rk, ck) = kdfRoot(w.rootKey, ss)
            ss.fill(0); w.rootKey.fill(0); w.rootKey = rk
            w.recvChainKey = ck
            w.peerPub = h.ratchetPub
            w.nr = 0
            // Rotate our own keypair so the peer encapsulates to a fresh key next; defer the
            // matching sending-chain derivation (and its ciphertext) to our next send.
            val kp = kem.generateKeyPair().getOrThrow()
            w.selfPriv = kp.privateKey; w.selfPub = kp.publicKey
            w.sendStepNeeded = true
        }

        // 3. Skip within the current receiving chain up to n, then derive this message's key.
        if (w.recvChainKey == null) return null
        if (!skipInRecvChain(w, h.n)) return null
        val cur = w.recvChainKey!!
        val mk = kdfMsg(cur)
        w.recvChainKey = kdfChain(cur); cur.fill(0)
        w.nr += 1
        val pt = cipher.decrypt(msg.ciphertext, mk, fullAad).getOrNull()
        mk.fill(0)
        return pt
    }

    /** Walk the receiving chain forward to [until], caching the skipped message keys under
     *  the current peer epoch. Returns false if the gap exceeds [MAX_SKIP] (skip-flood guard). */
    private fun skipInRecvChain(w: Session, until: Int): Boolean {
        val ck = w.recvChainKey ?: return true   // nothing to skip yet
        if (until < w.nr) return true
        if (until - w.nr > MAX_SKIP) return false
        val peer = w.peerPub ?: return true
        var chain = ck
        while (w.nr < until) {
            val mk = kdfMsg(chain)
            val next = kdfChain(chain); if (chain !== ck) chain.fill(0); chain = next
            w.skipped[skipId(peer, w.nr)] = mk
            w.nr += 1
            if (w.skipped.size > MAX_SKIPPED_STORED) {
                val oldest = w.skipped.keys.iterator().next()
                w.skipped.remove(oldest)
            }
        }
        w.recvChainKey = chain
        return true
    }

    // ── KDFs ──────────────────────────────────────────────────────────────────

    /** Root step: (RK', CK) = HKDF(salt=RK, ikm=ss). */
    private fun kdfRoot(rk: ByteArray, ss: ByteArray): Pair<ByteArray, ByteArray> {
        val out = hkdf.derive(ikm = ss, salt = rk, info = label("root"), outputLen = 64)
        val rkNext = out.copyOfRange(0, 32)
        val ck = out.copyOfRange(32, 64)
        out.fill(0)
        return rkNext to ck
    }

    private fun kdfMsg(ck: ByteArray) = hkdf.derive(ikm = ck, info = label("mk"))
    private fun kdfChain(ck: ByteArray) = hkdf.derive(ikm = ck, info = label("ck"))

    private fun label(suffix: String) = ("$INFO_PREFIX|$suffix").toByteArray()
    private fun skipId(pub: HybridPublicKey, n: Int) = "${B64.encode(pub.encoded)}|$n"

    companion object {
        private const val INFO_PREFIX = "aura-ratchet-v2"
        /** Largest forward gap walked in one step (skip-flood guard). */
        const val MAX_SKIP = 512
        /** Cap on retained skipped keys per session. */
        const val MAX_SKIPPED_STORED = 1024
    }
}
