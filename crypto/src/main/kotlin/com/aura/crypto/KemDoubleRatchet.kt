package com.aura.crypto

import java.io.ByteArrayOutputStream

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
            w.recvChainKey?.fill(0)                        // wipe the finished previous chain key
            w.recvChainKey = ck
            w.peerPub = h.ratchetPub
            w.nr = 0
            // Rotate our own keypair so the peer encapsulates to a fresh key next; defer the
            // matching sending-chain derivation (and its ciphertext) to our next send. Fail closed
            // (return null on the working copy) rather than throw out of decrypt.
            val kp = kem.generateKeyPair().getOrNull() ?: return null
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
        if (chain !== ck) ck.fill(0)   // the original chain key is now orphaned — wipe it
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

/**
 * Binary (de)serialization for the KEM Double Ratchet — the substrate the transport
 * integration needs: [messageToBytes]/[messageFromBytes] give a **self-contained wire frame**
 * (header ‖ AEAD ciphertext), and [sessionToBytes]/[sessionFromBytes] persist the full
 * [KemDoubleRatchet.Session] (root, chains, ratchet keypair, peer key, skipped cache) so it can
 * be stored between messages via a `RatchetStore`. All lengths are 4-byte big-endian; an
 * optional field is encoded as length −1.
 */
object KemRatchetCodec {

    fun headerToBytes(h: KemDoubleRatchet.Header): ByteArray {
        val w = Writer()
        w.blob(h.ratchetPub.encoded)
        w.opt(h.kemCt?.encoded)
        w.int(h.pn); w.int(h.n)
        return w.toByteArray()
    }

    fun headerFromBytes(bytes: ByteArray): KemDoubleRatchet.Header {
        val r = Reader(bytes)
        val pub = HybridPublicKey(r.blob())
        val ct = r.opt()?.let { HybridCiphertext(it) }
        return KemDoubleRatchet.Header(pub, ct, r.int(), r.int())
    }

    /** Self-contained frame: [4B header len][header][AEAD ciphertext]. */
    fun messageToBytes(m: KemDoubleRatchet.Message): ByteArray {
        val h = headerToBytes(m.header)
        return Writer().apply { blob(h); raw(m.ciphertext) }.toByteArray()
    }

    fun messageFromBytes(bytes: ByteArray): KemDoubleRatchet.Message {
        val r = Reader(bytes)
        val header = headerFromBytes(r.blob())
        return KemDoubleRatchet.Message(header, r.rest())
    }

    fun sessionToBytes(s: KemDoubleRatchet.Session): ByteArray {
        val w = Writer()
        w.blob(s.rootKey)
        w.opt(s.sendChainKey); w.opt(s.recvChainKey)
        w.int(s.ns); w.int(s.nr); w.int(s.pn)
        w.opt(s.selfPriv?.encoded); w.opt(s.selfPub?.encoded); w.opt(s.peerPub?.encoded)
        w.int(if (s.sendStepNeeded) 1 else 0)
        w.int(s.skipped.size)
        for ((k, v) in s.skipped) { w.blob(k.toByteArray(Charsets.UTF_8)); w.blob(v) }
        return w.toByteArray()
    }

    fun sessionFromBytes(bytes: ByteArray): KemDoubleRatchet.Session {
        val r = Reader(bytes)
        val rootKey = r.blob()
        val sendCk = r.opt(); val recvCk = r.opt()
        val ns = r.int(); val nr = r.int(); val pn = r.int()
        val selfPriv = r.opt()?.let { HybridPrivateKey(it) }
        val selfPub = r.opt()?.let { HybridPublicKey(it) }
        val peerPub = r.opt()?.let { HybridPublicKey(it) }
        val sendStepNeeded = r.int() == 1
        val skipped = LinkedHashMap<String, ByteArray>()
        repeat(r.int()) { skipped[String(r.blob(), Charsets.UTF_8)] = r.blob() }
        return KemDoubleRatchet.Session(
            rootKey, sendCk, recvCk, ns, nr, pn, selfPriv, selfPub, peerPub, sendStepNeeded, skipped
        )
    }

    private class Writer {
        private val out = ByteArrayOutputStream()
        fun raw(b: ByteArray) { out.write(b) }
        fun int(v: Int) { out.write(intTo4Bytes(v)) }
        fun blob(b: ByteArray) { int(b.size); out.write(b) }
        fun opt(b: ByteArray?) { if (b == null) int(-1) else blob(b) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class Reader(private val b: ByteArray) {
        private var p = 0
        fun int(): Int { val v = readInt4(b, p); p += 4; return v }
        fun blob(): ByteArray { val n = int(); require(n in 0..(b.size - p)) { "bad blob length $n" }; return b.copyOfRange(p, p + n).also { p += n } }
        fun opt(): ByteArray? { val n = readInt4(b, p); return if (n < 0) { p += 4; null } else blob() }
        fun rest(): ByteArray = b.copyOfRange(p, b.size)
    }
}
