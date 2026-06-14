package com.aura.pairing

import android.util.Base64
import com.aura.crypto.HybridCiphertext
import com.aura.crypto.HybridKem
import com.aura.crypto.HybridPublicKey
import com.aura.crypto.HybridSigner
import com.aura.crypto.HybridVerifyKey
import com.aura.crypto.Hkdf
import com.aura.crypto.NodeIdentity
import com.aura.crypto.PrekeyManager
import com.aura.crypto.RatchetManager
import com.aura.crypto.hexToBytes
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.PairState
import com.aura.identity.IdentityManager
import com.aura.network.RendezvousClient
import com.aura.notify.Notifier
import com.aura.settings.AuroraSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recognition-then-verify pairing.
 *
 * A scan no longer auto-creates a usable contact, and there is no read-aloud code.
 * The flow is:
 *  1. **Scan** — the scanner encapsulates against the QR's KEM key (authentic, read
 *     in person) → shared secret S, stores a `REQUESTED` contact holding S, and posts
 *     a signed `pairreq` (keys + KEM ciphertext) to the peer's queue. On the scanner's
 *     home screen the contact shows "Awaiting handshake" with a Cancel button; it
 *     can't be opened yet.
 *  2. **Accept / Reject** — the requested phone gets a notification, then sees the
 *     contact with Accept / Reject. Reject (`pairreject`) or a scanner Cancel
 *     (`paircancel`) deletes the contact on both sides. Accept seeds the ratchet from
 *     S and sends `pairaccept` (carrying the accepter's Dilithium key); the scanner
 *     seeds the same root on receipt. Both contacts move to `VERIFY`.
 *  3. **Mutual verify** — opening a `VERIFY` contact shows the code screen: each phone
 *     displays its own 6-digit SAS code (derived locally from the shared root, never
 *     sent) and must type the other's, read off their screen. A correct entry sends a
 *     signed `pairverify`; once both sides have entered the other's code, both flip to
 *     `ACTIVE` and the chat opens with a success toast. A man-in-the-middle derives a
 *     different root, so the codes won't match and entry fails.
 */
@Singleton
class PairingManager @Inject constructor(
    private val identityManager: IdentityManager,
    private val kem: HybridKem,
    private val signer: HybridSigner,
    private val hkdf: Hkdf,
    private val ratchet: RatchetManager,
    private val prekeys: PrekeyManager,
    private val contactDao: ContactDao,
    private val eraser: com.aura.db.ContactEraser,
    private val rendezvousClient: RendezvousClient,
    private val settings: AuroraSettings,
    private val notifier: Notifier
) {
    /** Emits a contact nodeId to navigate to (open the verify screen, then the chat). */
    private val _incomingPaired = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val incomingPaired: SharedFlow<String> = _incomingPaired

    /** One-shot pairing outcomes for toasts. */
    sealed interface PairEvent {
        val nodeIdHex: String
        data class Accepted(override val nodeIdHex: String) : PairEvent  // scanner: peer accepted, now verify
        data class Success(override val nodeIdHex: String) : PairEvent   // both verified → chat open
        data class Declined(override val nodeIdHex: String) : PairEvent  // peer rejected our request
        data class Failed(override val nodeIdHex: String) : PairEvent    // verification gave up (blocked)
    }

    private val _events = MutableSharedFlow<PairEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PairEvent> = _events

    // ── Scanner side ────────────────────────────────────────────────────────

    /** Scan a QR → store a REQUESTED contact and post a pairing request. */
    suspend fun pairFromQr(qrContent: String): Result<Unit> = runCatching {
        val payload = QrPayload.decode(qrContent)
        val identity = identityManager.getOrCreate()
        val myNodeIdHex = identity.nodeId.toHex()
        require(payload.nodeIdHex != myNodeIdHex) { "That's your own code" }
        require(!settings.isBlocked(payload.nodeIdHex)) { "This contact is blocked" }

        // Host code: adopt the issuer's reachable rendezvous address.
        if (payload.isHostCode) {
            val reachable = rendezvousClient.firstReachable(payload.rendezvousCandidates)
            settings.setServerAddress(reachable ?: payload.rendezvousCandidates.first())
        }

        val existing = contactDao.byNodeId(payload.nodeIdHex)
        if (existing != null && existing.pairState != PairState.REQUESTED) return@runCatching Unit

        val kemB64 = Base64.encodeToString(payload.kemPublicKey.toBytes(), Base64.NO_WRAP)
        val ed25519B64 = Base64.encodeToString(payload.ed25519Pub, Base64.NO_WRAP)

        // Forward-secret (PQXDH) path when the peer advertises prekeys and we can fetch a
        // verified bundle: encapsulate to their identity key + signed prekey + one-time
        // prekey and fold all three into the root. Otherwise fall back to the legacy
        // identity-only encapsulation. Either way we store the finished 32-byte pairing
        // *root* (not the raw KEM secret), so Accept just seeds the ratchet from it.
        val fs = tryFsEncapsulate(payload, myNodeIdHex)
        val ctB64: String
        val rootB64: String
        if (fs != null) {
            ctB64 = fs.ctIK
            rootB64 = fs.rootB64
        } else {
            val kemResult = kem.encapsulate(payload.kemPublicKey).getOrThrow()
            ctB64 = Base64.encodeToString(kemResult.ciphertext.toBytes(), Base64.NO_WRAP)
            val root = legacyRoot(kemResult.sharedSecret)
            kemResult.sharedSecret.fill(0)
            rootB64 = Base64.encodeToString(root, Base64.NO_WRAP)
            root.fill(0)
        }

        // Hold the pairing root in the (SQLCipher-encrypted) row so Accept survives a restart.
        contactDao.upsert(
            ContactEntity(
                nodeIdHex = payload.nodeIdHex,
                displayName = DEFAULT_NAME,
                kemPubB64 = kemB64,
                ed25519PubB64 = ed25519B64,
                dilithiumPubB64 = null,                       // arrives in pairaccept
                sharedSecretB64 = rootB64,
                createdAtMs = System.currentTimeMillis(),
                pairingSent = true,
                nicknameSet = false,
                pairState = PairState.REQUESTED,
                isInitiator = true
            )
        )

        val signedPart = if (fs != null)
            "aura-pairreq-v2|$myNodeIdHex|${payload.nodeIdHex}|$ctB64|${fs.ctSpk}|${fs.ctOpk ?: "-"}|${fs.spkId}|${fs.opkId ?: "-"}"
        else
            "aura-pairreq-v1|$myNodeIdHex|${payload.nodeIdHex}|$ctB64"
        val signature = signer.signEd25519Only(signedPart.toByteArray(), identity.privatePart.signingPrivateKey).getOrThrow()
        val message = JSONObject()
            .put("type", "pairreq")
            .put("from", myNodeIdHex)
            .put("to", payload.nodeIdHex)
            .put("kem", Base64.encodeToString(identity.publicPart.kemPublicKey.toBytes(), Base64.NO_WRAP))
            .put("ed25519", Base64.encodeToString(identity.publicPart.signingPublicKey.ed25519PublicKey, Base64.NO_WRAP))
            .put("dilithium", Base64.encodeToString(identity.publicPart.signingPublicKey.dilithiumPublicKey, Base64.NO_WRAP))
            .put("ct", ctB64)
            .put("sig", Base64.encodeToString(signature, Base64.NO_WRAP))
            .apply {
                if (fs != null) {
                    put("spkId", fs.spkId)
                    put("ctSpk", fs.ctSpk)
                    fs.ctOpk?.let { put("ctOpk", it) }
                    fs.opkId?.let { put("opkId", it) }
                }
            }
            .toString()
        rendezvousClient.postSignal(settings.serverAddress.value, payload.nodeIdHex, message)
        Unit
    }

    /** Scanner side: abandon a still-pending request and tell the peer to drop it. */
    suspend fun cancelOutgoing(contactNodeIdHex: String): Result<Unit> = runCatching {
        val identity = identityManager.getOrCreate()
        contactDao.byNodeId(contactNodeIdHex) ?: return@runCatching Unit
        eraser.wipe(contactNodeIdHex)
        sendSimple("paircancel", identity.nodeId.toHex(), contactNodeIdHex, identity)
    }

    /** Scanner side: the peer accepted — learn their Dilithium key and seed the root. */
    suspend fun handlePairAccept(json: JSONObject): Result<Unit> = runCatching {
        val identity = identityManager.getOrCreate()
        val myNodeIdHex = identity.nodeId.toHex()
        val from = json.optString("from")
        require(json.optString("to") == myNodeIdHex) { "pairaccept addressed elsewhere" }
        val contact = contactDao.byNodeId(from) ?: return@runCatching Unit
        if (contact.pairState != PairState.REQUESTED) return@runCatching Unit

        val dilithiumB64 = json.optString("dilithium")
        require(nodeIdMatches(from, contact.kemPubB64, contact.ed25519PubB64, dilithiumB64)) { "accepter nodeId mismatch" }
        val signedPart = "aura-pairaccept-v1|$from|$myNodeIdHex|$dilithiumB64"
        require(verifyEd(signedPart, contact.ed25519PubB64, json.optString("sig"))) { "pairaccept signature failed" }

        // sharedSecretB64 already holds the finished pairing root (derived at scan time,
        // forward-secret when prekeys were used). Seed the ratchet straight from it.
        val root = Base64.decode(contact.sharedSecretB64, Base64.NO_WRAP)
        ratchet.seedFromSharedSecret(from, myNodeIdHex, from, root)   // wipes root
        contactDao.markVerifyReady(from, PairState.VERIFY, dilithiumB64)
        _events.tryEmit(PairEvent.Accepted(from))
        Unit
    }

    // ── Receiver side ─────────────────────────────────────────────────────────

    /** A pairing request arrived: verify it, store an INCOMING contact, notify. */
    suspend fun handlePairRequest(json: JSONObject): Result<Unit> = runCatching {
        val identity = identityManager.getOrCreate()
        val myNodeIdHex = identity.nodeId.toHex()
        val from = json.optString("from")
        require(json.optString("to") == myNodeIdHex) { "pair-req addressed elsewhere" }
        require(from.length == 64) { "bad sender nodeId" }
        require(!settings.isBlocked(from)) { "blocked" }

        val existing = contactDao.byNodeId(from)
        if (existing != null && existing.pairState == PairState.ACTIVE) return@runCatching Unit

        val kemB64 = json.optString("kem")
        val ed25519B64 = json.optString("ed25519")
        val dilithiumB64 = json.optString("dilithium")
        val ctB64 = json.optString("ct")
        require(nodeIdMatches(from, kemB64, ed25519B64, dilithiumB64)) { "requester nodeId mismatch" }

        val spkId = json.optString("spkId").ifEmpty { null }
        val ctSpk = json.optString("ctSpk").ifEmpty { null }
        val opkId = json.optString("opkId").ifEmpty { null }
        val ctOpk = json.optString("ctOpk").ifEmpty { null }

        val rootB64: String = if (spkId != null && ctSpk != null) {
            // Forward-secret (PQXDH) request: verify the signature, then consume OUR
            // prekeys and fold identity + signed prekey (+ one-time prekey) into the same
            // root the scanner derived. The one-time prekey is destroyed on consume.
            val signedPart = "aura-pairreq-v2|$from|$myNodeIdHex|$ctB64|$ctSpk|${ctOpk ?: "-"}|$spkId|${opkId ?: "-"}"
            require(verifyEd(signedPart, ed25519B64, json.optString("sig"))) { "pair-req(v2) signature failed" }
            val consumed = prekeys.consume(spkId, opkId) ?: error("unknown signed prekey")
            if (consumed.opkMissing) {
                // The one-time prekey was already used (stale / reused code) — fail closed
                // and ask the scanner to regenerate, rather than pair on a mismatched root.
                sendSimple("pairreject", myNodeIdHex, from, identity)
                return@runCatching Unit
            }
            val ctIKb  = Base64.decode(ctB64, Base64.NO_WRAP)
            val ctSpkb = Base64.decode(ctSpk, Base64.NO_WRAP)
            val ctOpkb = ctOpk?.let { Base64.decode(it, Base64.NO_WRAP) }
            val sIK  = kem.decapsulate(HybridCiphertext.fromBytes(ctIKb), identity.privatePart.kemPrivateKey).getOrThrow()
            val sSPK = kem.decapsulate(HybridCiphertext.fromBytes(ctSpkb), consumed.spkPriv).getOrThrow()
            val sOPK = if (ctOpkb != null && consumed.opkPriv != null)
                kem.decapsulate(HybridCiphertext.fromBytes(ctOpkb), consumed.opkPriv).getOrThrow() else null
            val root = fsRoot(
                initiatorHex = from, responderHex = myNodeIdHex,
                sIK = sIK, sSPK = sSPK, sOPK = sOPK,
                spkId = spkId, opkId = opkId,
                ctIK = ctIKb, ctSpk = ctSpkb, ctOpk = ctOpkb,
                responderKyberPub = identity.publicPart.kemPublicKey.kyberPublicKey
            )
            sIK.fill(0); sSPK.fill(0); sOPK?.fill(0)
            Base64.encodeToString(root, Base64.NO_WRAP).also { root.fill(0) }
        } else {
            // Legacy identity-only request.
            val signedPart = "aura-pairreq-v1|$from|$myNodeIdHex|$ctB64"
            require(verifyEd(signedPart, ed25519B64, json.optString("sig"))) { "pair-req signature failed" }
            val ciphertext = HybridCiphertext.fromBytes(Base64.decode(ctB64, Base64.NO_WRAP))
            val s = kem.decapsulate(ciphertext, identity.privatePart.kemPrivateKey).getOrThrow()
            val root = legacyRoot(s); s.fill(0)
            Base64.encodeToString(root, Base64.NO_WRAP).also { root.fill(0) }
        }

        contactDao.upsert(
            ContactEntity(
                nodeIdHex = from,
                displayName = DEFAULT_NAME,
                kemPubB64 = kemB64,
                ed25519PubB64 = ed25519B64,
                dilithiumPubB64 = dilithiumB64,
                sharedSecretB64 = rootB64,
                createdAtMs = System.currentTimeMillis(),
                pairingSent = true,
                nicknameSet = false,
                pairState = PairState.INCOMING,
                isInitiator = false
            )
        )
        notifier.notifyContactRequest()
        Unit
    }

    /** Receiver side: accept → seed the root, move to VERIFY, tell the scanner. */
    suspend fun acceptIncoming(contactNodeIdHex: String): Result<Unit> = runCatching {
        val identity = identityManager.getOrCreate()
        val myNodeIdHex = identity.nodeId.toHex()
        val contact = contactDao.byNodeId(contactNodeIdHex) ?: return@runCatching Unit
        if (contact.pairState != PairState.INCOMING) return@runCatching Unit

        // sharedSecretB64 holds the finished pairing root (forward-secret when prekeys
        // were used at request time). Seed the ratchet straight from it.
        val root = Base64.decode(contact.sharedSecretB64, Base64.NO_WRAP)
        ratchet.seedFromSharedSecret(contactNodeIdHex, myNodeIdHex, contactNodeIdHex, root)   // wipes root
        contactDao.markVerify(contactNodeIdHex, PairState.VERIFY)
        notifier.cancelContactRequest()

        val dilithiumB64 = Base64.encodeToString(identity.publicPart.signingPublicKey.dilithiumPublicKey, Base64.NO_WRAP)
        val signedPart = "aura-pairaccept-v1|$myNodeIdHex|$contactNodeIdHex|$dilithiumB64"
        val sig = signer.signEd25519Only(signedPart.toByteArray(), identity.privatePart.signingPrivateKey).getOrThrow()
        rendezvousClient.postSignal(
            settings.serverAddress.value, contactNodeIdHex,
            JSONObject().put("type", "pairaccept").put("from", myNodeIdHex).put("to", contactNodeIdHex)
                .put("dilithium", dilithiumB64).put("sig", Base64.encodeToString(sig, Base64.NO_WRAP)).toString()
        )
        _incomingPaired.tryEmit(contactNodeIdHex)   // open the verify screen
        Unit
    }

    /** Receiver side: reject → tell the scanner, delete, optionally blocklist. */
    suspend fun rejectIncoming(contactNodeIdHex: String, block: Boolean): Result<Unit> = runCatching {
        val identity = identityManager.getOrCreate()
        contactDao.byNodeId(contactNodeIdHex) ?: return@runCatching Unit
        sendSimple("pairreject", identity.nodeId.toHex(), contactNodeIdHex, identity)
        eraser.wipe(contactNodeIdHex)
        if (block) settings.blockNode(contactNodeIdHex)
        notifier.cancelContactRequest()
        Unit
    }

    /** Receiver side: the scanner cancelled before we accepted — drop the request. */
    suspend fun handlePairCancel(json: JSONObject): Result<Unit> = runCatching {
        val myNodeIdHex = identityManager.getOrCreate().nodeId.toHex()
        val from = json.optString("from")
        require(json.optString("to") == myNodeIdHex) { "paircancel addressed elsewhere" }
        val contact = contactDao.byNodeId(from) ?: return@runCatching Unit
        if (contact.pairState != PairState.INCOMING) return@runCatching Unit
        if (!verifyEd("aura-paircancel-v1|$from|$myNodeIdHex", contact.ed25519PubB64, json.optString("sig"))) {
            return@runCatching Unit
        }
        eraser.wipe(from)
        notifier.cancelContactRequest()
        Unit
    }

    /** Scanner side: the peer rejected our request — drop the contact, toast. */
    suspend fun handlePairReject(json: JSONObject): Result<Unit> = runCatching {
        val myNodeIdHex = identityManager.getOrCreate().nodeId.toHex()
        val from = json.optString("from")
        require(json.optString("to") == myNodeIdHex) { "pairreject addressed elsewhere" }
        val contact = contactDao.byNodeId(from) ?: return@runCatching Unit
        if (contact.pairState != PairState.REQUESTED) return@runCatching Unit
        if (!verifyEd("aura-pairreject-v1|$from|$myNodeIdHex", contact.ed25519PubB64, json.optString("sig"))) {
            return@runCatching Unit
        }
        eraser.wipe(from)
        _events.tryEmit(PairEvent.Declined(from))
        Unit
    }

    // ── Mutual verification (both sides) ───────────────────────────────────────

    /** The 6-digit code THIS device shows on the verify screen (its own SAS code). */
    suspend fun myVerifyCode(contactNodeIdHex: String): String? {
        val myNodeIdHex = identityManager.getOrCreate().nodeId.toHex()
        return ratchet.sasCodeFor(contactNodeIdHex, myNodeIdHex)
    }

    /**
     * Submit the code read off the peer's screen. Returns true on a match. On a match
     * we mark our side verified and signal the peer; once both sides have matched, the
     * contact flips to ACTIVE. Too many wrong tries blocklists and removes the contact.
     */
    suspend fun submitVerifyCode(contactNodeIdHex: String, code: String): Result<Boolean> = runCatching {
        val contact = contactDao.byNodeId(contactNodeIdHex) ?: return@runCatching false
        if (contact.pairState != PairState.VERIFY) return@runCatching false
        val identity = identityManager.getOrCreate()
        val myNodeIdHex = identity.nodeId.toHex()

        // The peer shows the code bound to THEIR identity; we compute the same locally.
        val expected = ratchet.sasCodeFor(contactNodeIdHex, contactNodeIdHex) ?: return@runCatching false
        if (code.trim() != expected) {
            contactDao.incVerifyAttempts(contactNodeIdHex)
            val attempts = contactDao.byNodeId(contactNodeIdHex)?.verifyAttempts ?: 0
            if (attempts >= MAX_VERIFY_ATTEMPTS) {
                settings.blockNode(contactNodeIdHex)
                eraser.wipe(contactNodeIdHex)
                _events.tryEmit(PairEvent.Failed(contactNodeIdHex))
            }
            return@runCatching false
        }

        if (contact.theyVerified) {
            contactDao.setVerify(contactNodeIdHex, iv = true, tv = true, state = PairState.ACTIVE)
            onActivated(contactNodeIdHex)
        } else {
            contactDao.setVerify(contactNodeIdHex, iv = true, tv = false, state = PairState.VERIFY)
        }
        sendSimple("pairverify", myNodeIdHex, contactNodeIdHex, identity)
        true
    }

    /** Peer reports they entered our code correctly. Activate if we already did too. */
    suspend fun handlePairVerify(json: JSONObject): Result<Unit> = runCatching {
        val myNodeIdHex = identityManager.getOrCreate().nodeId.toHex()
        val from = json.optString("from")
        require(json.optString("to") == myNodeIdHex) { "pairverify addressed elsewhere" }
        val contact = contactDao.byNodeId(from) ?: return@runCatching Unit
        if (contact.pairState != PairState.VERIFY) return@runCatching Unit
        if (!verifyEd("aura-pairverify-v1|$from|$myNodeIdHex", contact.ed25519PubB64, json.optString("sig"))) {
            return@runCatching Unit
        }
        if (contact.iVerified) {
            contactDao.setVerify(from, iv = true, tv = true, state = PairState.ACTIVE)
            onActivated(from)
        } else {
            contactDao.setVerify(from, iv = false, tv = true, state = PairState.VERIFY)
        }
        Unit
    }

    /** Interactive pairing has no half-finished background sends to retry. */
    suspend fun retryPendingPairingSends() { /* no-op */ }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun onActivated(nodeIdHex: String) {
        _events.tryEmit(PairEvent.Success(nodeIdHex))
        _incomingPaired.tryEmit(nodeIdHex)
    }

    /** Legacy (identity-only) ratchet root from the single KEM shared secret. */
    private fun legacyRoot(s: ByteArray): ByteArray =
        hkdf.derive(ikm = s, info = "aura-pair-root-v2".toByteArray(), outputLen = 32)

    /** A scanner-side forward-secret encapsulation result, ready to post + store. */
    private class FsEncap(
        val rootB64: String,
        val ctIK: String,
        val ctSpk: String,
        val ctOpk: String?,
        val spkId: String,
        val opkId: String?
    )

    /**
     * Forward-secret root, mixing the identity-key secret with the signed-prekey and
     * (optional) one-time-prekey secrets. Both peers compute this identically: the
     * scanner from the secrets it encapsulated, the responder from the secrets it
     * decapsulated. The transcript (node ids, prekey ids, and hashes of every
     * ciphertext) is bound into the HKDF info so a swapped ciphertext yields a
     * different root — caught by the mutual SAS. [responderKyberPub] is the responder's
     * identity Kyber public key (the QR key the scanner saw / the responder's own key).
     */
    private fun fsRoot(
        initiatorHex: String,
        responderHex: String,
        sIK: ByteArray,
        sSPK: ByteArray,
        sOPK: ByteArray?,
        spkId: String,
        opkId: String?,
        ctIK: ByteArray,
        ctSpk: ByteArray,
        ctOpk: ByteArray?,
        responderKyberPub: ByteArray
    ): ByteArray {
        val ikm = sIK + sSPK + (sOPK ?: ByteArray(0))
        val header = ("aura-pair-root-v3|$initiatorHex|$responderHex|$spkId|" +
            "${opkId ?: "-"}|${if (sOPK != null) "1" else "0"}|").toByteArray()
        val info = header + hkdf.sha3_256(ctIK) + hkdf.sha3_256(ctSpk) +
            (ctOpk?.let { hkdf.sha3_256(it) } ?: ByteArray(0))
        val root = hkdf.derive(ikm = ikm, salt = hkdf.sha3_256(responderKyberPub), info = info, outputLen = 32)
        ikm.fill(0)
        return root
    }

    /**
     * Scanner side: if the peer advertises prekeys ([QrPayload.pkVersion] ≥ 1), fetch
     * their signed bundle, verify each prekey's signature against the QR's authentic
     * Ed25519 key, encapsulate to identity + signed prekey (+ one-time prekey), and
     * derive the forward-secret root. Returns null (→ legacy handshake) when no bundle
     * is published or anything fails — pairing still works, just without the FS upgrade.
     */
    private suspend fun tryFsEncapsulate(payload: QrPayload, myNodeIdHex: String): FsEncap? {
        if (payload.pkVersion < 1) return null
        val bundle = rendezvousClient
            .fetchPrekeyBundle(settings.serverAddress.value, payload.nodeIdHex)
            .getOrNull() ?: return null

        val ed = payload.ed25519Pub
        val spkPubB64 = Base64.encodeToString(bundle.spkPub.toBytes(), Base64.NO_WRAP)
        val spkMsg = PrekeyManager.prekeyMessage(payload.nodeIdHex, PrekeyManager.KIND_SPK, bundle.spkId, spkPubB64)
        if (!verifyEdBytes(spkMsg, ed, bundle.spkSig)) return null

        // One-time prekey is optional; drop it (rather than abort) if its signature fails.
        var opkPub = bundle.opkPub
        var opkId = bundle.opkId
        if (opkPub != null && opkId != null && bundle.opkSig != null) {
            val opkPubB64 = Base64.encodeToString(opkPub.toBytes(), Base64.NO_WRAP)
            val opkMsg = PrekeyManager.prekeyMessage(payload.nodeIdHex, PrekeyManager.KIND_OPK, opkId, opkPubB64)
            if (!verifyEdBytes(opkMsg, ed, bundle.opkSig)) { opkPub = null; opkId = null }
        } else { opkPub = null; opkId = null }

        val rIK  = kem.encapsulate(payload.kemPublicKey).getOrThrow()
        val rSPK = kem.encapsulate(bundle.spkPub).getOrThrow()
        val rOPK = opkPub?.let { kem.encapsulate(it).getOrThrow() }

        val ctIK  = rIK.ciphertext.toBytes()
        val ctSpk = rSPK.ciphertext.toBytes()
        val ctOpk = rOPK?.ciphertext?.toBytes()
        val root = fsRoot(
            initiatorHex = myNodeIdHex, responderHex = payload.nodeIdHex,
            sIK = rIK.sharedSecret, sSPK = rSPK.sharedSecret, sOPK = rOPK?.sharedSecret,
            spkId = bundle.spkId, opkId = opkId,
            ctIK = ctIK, ctSpk = ctSpk, ctOpk = ctOpk,
            responderKyberPub = payload.kemPublicKey.kyberPublicKey
        )
        rIK.sharedSecret.fill(0); rSPK.sharedSecret.fill(0); rOPK?.sharedSecret?.fill(0)
        return FsEncap(
            rootB64 = Base64.encodeToString(root, Base64.NO_WRAP),
            ctIK  = Base64.encodeToString(ctIK, Base64.NO_WRAP),
            ctSpk = Base64.encodeToString(ctSpk, Base64.NO_WRAP),
            ctOpk = ctOpk?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            spkId = bundle.spkId,
            opkId = opkId
        ).also { root.fill(0) }
    }

    private suspend fun verifyEdBytes(message: ByteArray, ed25519Pub: ByteArray, sig: ByteArray): Boolean =
        signer.verifyEd25519Only(message, sig, ed25519Pub).getOrElse { false }

    /** True iff nodeId == SHA3-256(kemPub ‖ signPub) for the supplied keys. */
    private fun nodeIdMatches(nodeIdHex: String, kemB64: String, ed25519B64: String, dilithiumB64: String): Boolean = try {
        val kemPub = HybridPublicKey.fromBytes(Base64.decode(kemB64, Base64.NO_WRAP))
        val signPub = HybridVerifyKey(Base64.decode(dilithiumB64, Base64.NO_WRAP), Base64.decode(ed25519B64, Base64.NO_WRAP))
        MessageDigest.isEqual(hkdf.sha3_256(kemPub.toBytes() + signPub.toBytes()), hexToBytes(nodeIdHex))
    } catch (e: Exception) {
        false
    }

    private suspend fun verifyEd(signedPart: String, ed25519B64: String, sigB64: String): Boolean = try {
        val ed = Base64.decode(ed25519B64, Base64.NO_WRAP)
        val sig = Base64.decode(sigB64, Base64.NO_WRAP)
        signer.verifyEd25519Only(signedPart.toByteArray(), sig, ed).getOrElse { false }
    } catch (e: Exception) {
        false
    }

    private suspend fun sendSimple(type: String, fromNodeIdHex: String, toNodeIdHex: String, identity: NodeIdentity) {
        val signedPart = "aura-$type-v1|$fromNodeIdHex|$toNodeIdHex"
        val sig = signer.signEd25519Only(signedPart.toByteArray(), identity.privatePart.signingPrivateKey).getOrNull() ?: return
        rendezvousClient.postSignal(
            settings.serverAddress.value, toNodeIdHex,
            JSONObject().put("type", type).put("from", fromNodeIdHex).put("to", toNodeIdHex)
                .put("sig", Base64.encodeToString(sig, Base64.NO_WRAP)).toString()
        )
    }

    private companion object {
        const val MAX_VERIFY_ATTEMPTS = 5
        const val DEFAULT_NAME = "New contact"
    }
}
