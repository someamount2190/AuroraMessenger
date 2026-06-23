package com.aura.pairing

import android.util.Base64
import com.aura.crypto.HybridCiphertext
import com.aura.crypto.HybridKem
import com.aura.crypto.PrekeyManager
import com.aura.crypto.RatchetManager
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.ContactEraser
import com.aura.db.PairState
import com.aura.identity.IdentityStore
import com.aura.notify.Notifier
import com.aura.settings.AuroraSettings
import org.json.JSONObject
import javax.inject.Inject

/**
 * Receiver side of pairing (they scanned our QR): verify and store an incoming request,
 * accept/reject it, and handle contact teardown (either side). See [PairingCoordinator].
 */
class ReceiverPairing @Inject constructor(
    private val identityManager: IdentityStore,
    private val kem: HybridKem,
    private val pairingCrypto: PairingCrypto,
    private val pairingSignal: PairingSignal,
    private val ratchet: RatchetManager,
    private val prekeys: PrekeyManager,
    private val contactDao: ContactDao,
    private val eraser: ContactEraser,
    private val settings: AuroraSettings,
    private val notifier: Notifier,
    private val events: PairingEvents
) {
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
        require(pairingCrypto.nodeIdMatches(from, kemB64, ed25519B64, dilithiumB64)) { "requester nodeId mismatch" }

        val spkId = json.optString("spkId").ifEmpty { null }
        val ctSpk = json.optString("ctSpk").ifEmpty { null }
        val opkId = json.optString("opkId").ifEmpty { null }
        val ctOpk = json.optString("ctOpk").ifEmpty { null }

        // Forward-secret (PQXDH) request is MANDATORY — the legacy identity-only (no-FS)
        // handshake has been removed. A request without a signed prekey is rejected.
        require(spkId != null && ctSpk != null) { "pair-req missing forward-secret prekey (legacy no-FS pairing is no longer accepted)" }
        // Verify the signature, then consume OUR prekeys and fold identity + signed prekey
        // (+ one-time prekey) into the same root the scanner derived. The one-time prekey is
        // destroyed on consume.
        val signedPart = "aura-pairreq-v2|$from|$myNodeIdHex|$ctB64|$ctSpk|${ctOpk ?: "-"}|$spkId|${opkId ?: "-"}"
        require(pairingSignal.verifyEd(signedPart, ed25519B64, json.optString("sig"))) { "pair-req(v2) signature failed" }
        val consumed = prekeys.consume(spkId, opkId) ?: error("unknown signed prekey")
        if (consumed.opkMissing) {
            // The one-time prekey was already used (stale / reused code) — fail closed
            // and ask the scanner to regenerate, rather than pair on a mismatched root.
            pairingSignal.sendSimple("pairreject", myNodeIdHex, from, identity)
            return@runCatching Unit
        }
        val ctIKb  = Base64.decode(ctB64, Base64.NO_WRAP)
        val ctSpkb = Base64.decode(ctSpk, Base64.NO_WRAP)
        val ctOpkb = ctOpk?.let { Base64.decode(it, Base64.NO_WRAP) }
        val sIK  = kem.decapsulate(HybridCiphertext.fromBytes(ctIKb), identity.privatePart.kemPrivateKey).getOrThrow()
        val sSPK = kem.decapsulate(HybridCiphertext.fromBytes(ctSpkb), consumed.spkPriv).getOrThrow()
        val opkPriv = consumed.opkPriv   // local val: opkPriv is a cross-module nullable, can't smart-cast in place
        val sOPK = if (ctOpkb != null && opkPriv != null)
            kem.decapsulate(HybridCiphertext.fromBytes(ctOpkb), opkPriv).getOrThrow() else null
        val root = pairingCrypto.fsRoot(
            initiatorHex = from, responderHex = myNodeIdHex,
            sIK = sIK, sSPK = sSPK, sOPK = sOPK,
            spkId = spkId, opkId = opkId,
            ctIK = ctIKb, ctSpk = ctSpkb, ctOpk = ctOpkb,
            responderKemPub = identity.publicPart.kemPublicKey.encoded
        )
        sIK.fill(0); sSPK.fill(0); sOPK?.fill(0)
        val rootB64: String = Base64.encodeToString(root, Base64.NO_WRAP).also { root.fill(0) }

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
        // Tell the UI a request arrived so a host sitting on the "Show my code" screen
        // is pulled to where they can Accept/Reject — otherwise the request only appears
        // on the home list behind the QR screen (and the foreground notification is
        // suppressed), so the host thinks nothing happened.
        events.emit(PairEvent.IncomingRequest(from))
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
        val sig = pairingSignal.sign(signedPart, identity) ?: error("pairaccept signature failed")
        pairingSignal.post(
            contactNodeIdHex,
            JSONObject().put("type", "pairaccept").put("from", myNodeIdHex).put("to", contactNodeIdHex)
                .put("dilithium", dilithiumB64).put("sig", Base64.encodeToString(sig, Base64.NO_WRAP)).toString()
        )
        events.navigateToContact(contactNodeIdHex)   // open the verify screen
        Unit
    }

    /**
     * Either side, any time: remove an established contact on BOTH devices. We sign and
     * post a `contactremove` so the peer wipes their copy and is told they lost the
     * contact, then cryptographically erase our own. The signal is best-effort: if the
     * peer is offline it queues server-side for their next sync; if the post fails
     * outright we still remove our local copy, so deletion is never blocked by network.
     */
    suspend fun deleteContact(contactNodeIdHex: String): Result<Unit> = runCatching {
        val identity = identityManager.getOrCreate()
        contactDao.byNodeId(contactNodeIdHex) ?: return@runCatching Unit
        runCatching { pairingSignal.sendSimple("contactremove", identity.nodeId.toHex(), contactNodeIdHex, identity) }
        eraser.wipe(contactNodeIdHex)
        Unit
    }

    /** Peer removed us as a contact — verify, wipe our side, and surface the loss. */
    suspend fun handleContactRemove(json: JSONObject): Result<Unit> = runCatching {
        val myNodeIdHex = identityManager.getOrCreate().nodeId.toHex()
        val from = json.optString("from")
        require(json.optString("to") == myNodeIdHex) { "contactremove addressed elsewhere" }
        val contact = contactDao.byNodeId(from) ?: return@runCatching Unit
        if (!pairingSignal.verifyEd("aura-contactremove-v1|$from|$myNodeIdHex", contact.ed25519PubB64, json.optString("sig"))) {
            return@runCatching Unit
        }
        val name = contact.displayName
        eraser.wipe(from)
        notifier.notifyContactRemoved()                       // tray alert when backgrounded
        events.emit(PairEvent.ContactRemoved(from, name))     // toast when foreground
        Unit
    }

    /** Receiver side: reject → tell the scanner, delete, optionally blocklist. */
    suspend fun rejectIncoming(contactNodeIdHex: String, block: Boolean): Result<Unit> = runCatching {
        val identity = identityManager.getOrCreate()
        contactDao.byNodeId(contactNodeIdHex) ?: return@runCatching Unit
        pairingSignal.sendSimple("pairreject", identity.nodeId.toHex(), contactNodeIdHex, identity)
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
        if (!pairingSignal.verifyEd("aura-paircancel-v1|$from|$myNodeIdHex", contact.ed25519PubB64, json.optString("sig"))) {
            return@runCatching Unit
        }
        eraser.wipe(from)
        notifier.cancelContactRequest()
        Unit
    }
}
