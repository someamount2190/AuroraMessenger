package com.aura.pairing

import android.util.Base64
import com.aura.crypto.HybridKem
import com.aura.crypto.PrekeyManager
import com.aura.crypto.RatchetManager
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.ContactEntity
import com.aura.db.ContactEraser
import com.aura.db.PairState
import com.aura.identity.IdentityStore
import com.aura.network.Rendezvous
import com.aura.settings.AuroraSettings
import org.json.JSONObject
import javax.inject.Inject

/**
 * Scanner side of pairing (we scanned their QR): post the request, and handle the
 * peer's accept/reject. See [PairingCoordinator] for the full flow.
 */
class ScannerPairing @Inject constructor(
    private val identityManager: IdentityStore,
    private val kem: HybridKem,
    private val pairingCrypto: PairingCrypto,
    private val pairingSignal: PairingSignal,
    private val ratchet: RatchetManager,
    private val contactDao: ContactDao,
    private val eraser: ContactEraser,
    private val rendezvousClient: Rendezvous,
    private val settings: AuroraSettings,
    private val events: PairingEvents
) {
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
        // Downgrade guard: the peer advertised forward-secret prekeys (pkVersion ≥ 1) but we
        // got no usable bundle. That's either a transient server issue or an active attacker
        // suppressing the bundle to strip forward secrecy. We still pair (legacy is mutually
        // authenticated and SAS/MITM-protected) but WARN the user that this pairing is not
        // forward-secret, rather than downgrading silently.
        if (fs == null && payload.pkVersion >= 1) {
            android.util.Log.w("AuroraPairing", "FS prekeys advertised but unavailable — pairing WITHOUT forward secrecy (possible downgrade)")
            events.emit(PairEvent.WeakPairing(payload.nodeIdHex))
        }
        val ctB64: String
        val rootB64: String
        if (fs != null) {
            ctB64 = fs.ctIK
            rootB64 = fs.rootB64
        } else {
            val kemResult = kem.encapsulate(payload.kemPublicKey).getOrThrow()
            ctB64 = Base64.encodeToString(kemResult.ciphertext.toBytes(), Base64.NO_WRAP)
            val root = pairingCrypto.legacyRoot(kemResult.sharedSecret)
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
        val signature = pairingSignal.sign(signedPart, identity) ?: error("pairreq signature failed")
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
        pairingSignal.post(payload.nodeIdHex, message)
        Unit
    }

    /** Scanner side: abandon a still-pending request and tell the peer to drop it. */
    suspend fun cancelOutgoing(contactNodeIdHex: String): Result<Unit> = runCatching {
        val identity = identityManager.getOrCreate()
        contactDao.byNodeId(contactNodeIdHex) ?: return@runCatching Unit
        eraser.wipe(contactNodeIdHex)
        pairingSignal.sendSimple("paircancel", identity.nodeId.toHex(), contactNodeIdHex, identity)
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
        require(pairingCrypto.nodeIdMatches(from, contact.kemPubB64, contact.ed25519PubB64, dilithiumB64)) { "accepter nodeId mismatch" }
        val signedPart = "aura-pairaccept-v1|$from|$myNodeIdHex|$dilithiumB64"
        require(pairingSignal.verifyEd(signedPart, contact.ed25519PubB64, json.optString("sig"))) { "pairaccept signature failed" }

        // sharedSecretB64 already holds the finished pairing root (derived at scan time,
        // forward-secret when prekeys were used). Seed the ratchet straight from it.
        val root = Base64.decode(contact.sharedSecretB64, Base64.NO_WRAP)
        ratchet.seedFromSharedSecret(from, myNodeIdHex, from, root)   // wipes root
        contactDao.markVerifyReady(from, PairState.VERIFY, dilithiumB64)
        events.emit(PairEvent.Accepted(from))
        Unit
    }

    /** Scanner side: the peer rejected our request — drop the contact, toast. */
    suspend fun handlePairReject(json: JSONObject): Result<Unit> = runCatching {
        val myNodeIdHex = identityManager.getOrCreate().nodeId.toHex()
        val from = json.optString("from")
        require(json.optString("to") == myNodeIdHex) { "pairreject addressed elsewhere" }
        val contact = contactDao.byNodeId(from) ?: return@runCatching Unit
        if (contact.pairState != PairState.REQUESTED) return@runCatching Unit
        if (!pairingSignal.verifyEd("aura-pairreject-v1|$from|$myNodeIdHex", contact.ed25519PubB64, json.optString("sig"))) {
            return@runCatching Unit
        }
        eraser.wipe(from)
        events.emit(PairEvent.Declined(from))
        Unit
    }

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
        if (!pairingSignal.verifyEdBytes(spkMsg, ed, bundle.spkSig)) return null

        // One-time prekey is optional; drop it (rather than abort) if its signature fails.
        var opkPub = bundle.opkPub
        var opkId = bundle.opkId
        if (opkPub != null && opkId != null && bundle.opkSig != null) {
            val opkPubB64 = Base64.encodeToString(opkPub.toBytes(), Base64.NO_WRAP)
            val opkMsg = PrekeyManager.prekeyMessage(payload.nodeIdHex, PrekeyManager.KIND_OPK, opkId, opkPubB64)
            if (!pairingSignal.verifyEdBytes(opkMsg, ed, bundle.opkSig)) { opkPub = null; opkId = null }
        } else { opkPub = null; opkId = null }

        val rIK  = kem.encapsulate(payload.kemPublicKey).getOrThrow()
        val rSPK = kem.encapsulate(bundle.spkPub).getOrThrow()
        val rOPK = opkPub?.let { kem.encapsulate(it).getOrThrow() }

        val ctIK  = rIK.ciphertext.toBytes()
        val ctSpk = rSPK.ciphertext.toBytes()
        val ctOpk = rOPK?.ciphertext?.toBytes()
        val root = pairingCrypto.fsRoot(
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
}
