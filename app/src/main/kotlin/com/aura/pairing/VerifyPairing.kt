package com.aura.pairing

import com.aura.crypto.RatchetManager
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.ContactEraser
import com.aura.db.PairState
import com.aura.identity.IdentityStore
import com.aura.settings.AuroraSettings
import com.aura.transport.MessageSender
import org.json.JSONObject
import javax.inject.Inject

/**
 * Mutual verification (both sides): show our own SAS code, accept the peer's, and flip
 * to ACTIVE once both have entered the other's. See [PairingCoordinator].
 */
class VerifyPairing @Inject constructor(
    private val identityManager: IdentityStore,
    private val ratchet: RatchetManager,
    private val pairingCrypto: PairingCrypto,
    private val pairingSignal: PairingSignal,
    private val contactDao: ContactDao,
    private val eraser: ContactEraser,
    private val settings: AuroraSettings,
    private val events: PairingEvents,
    private val messageSender: MessageSender
) {
    /** Initiator auto-bootstrap: once paired+verified, the initiator sends a sealed no-op
     *  control frame so the responder's KEM receive ratchet is live and either side can text
     *  first. Best-effort; the initiator's first real message bootstraps otherwise. */
    private suspend fun bootstrapIfInitiator(contactNodeIdHex: String) {
        val c = contactDao.byNodeId(contactNodeIdHex) ?: return
        if (c.isInitiator) runCatching { messageSender.sendBootstrap(c) }
    }
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
        if (!pairingCrypto.sasEquals(code.trim(), expected)) {
            contactDao.incVerifyAttempts(contactNodeIdHex)
            val attempts = contactDao.byNodeId(contactNodeIdHex)?.verifyAttempts ?: 0
            if (attempts >= MAX_VERIFY_ATTEMPTS) {
                settings.blockNode(contactNodeIdHex)
                eraser.wipe(contactNodeIdHex)
                events.emit(PairEvent.Failed(contactNodeIdHex))
            }
            return@runCatching false
        }

        if (contact.theyVerified) {
            contactDao.setVerify(contactNodeIdHex, iv = true, tv = true, state = PairState.ACTIVE)
            events.activated(contactNodeIdHex)
            bootstrapIfInitiator(contactNodeIdHex)
        } else {
            contactDao.setVerify(contactNodeIdHex, iv = true, tv = false, state = PairState.VERIFY)
        }
        pairingSignal.sendSimple("pairverify", myNodeIdHex, contactNodeIdHex, identity)
        true
    }

    /** Peer reports they entered our code correctly. Activate if we already did too. */
    suspend fun handlePairVerify(json: JSONObject): Result<Unit> = runCatching {
        val myNodeIdHex = identityManager.getOrCreate().nodeId.toHex()
        val from = json.optString("from")
        require(json.optString("to") == myNodeIdHex) { "pairverify addressed elsewhere" }
        val contact = contactDao.byNodeId(from) ?: return@runCatching Unit
        if (contact.pairState != PairState.VERIFY) return@runCatching Unit
        if (!pairingSignal.verifyEd("aura-pairverify-v1|$from|$myNodeIdHex", contact.ed25519PubB64, json.optString("sig"))) {
            return@runCatching Unit
        }
        if (contact.iVerified) {
            contactDao.setVerify(from, iv = true, tv = true, state = PairState.ACTIVE)
            events.activated(from)
            bootstrapIfInitiator(from)
        } else {
            contactDao.setVerify(from, iv = false, tv = true, state = PairState.VERIFY)
        }
        Unit
    }

    private companion object {
        const val MAX_VERIFY_ATTEMPTS = 5
    }
}
