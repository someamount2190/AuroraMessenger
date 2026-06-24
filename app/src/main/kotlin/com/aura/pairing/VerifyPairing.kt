package com.aura.pairing

import com.aura.crypto.KemRatchetManager
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.db.ContactEraser
import com.aura.db.PairState
import com.aura.identity.IdentityStore
import com.aura.settings.AuroraSettings
import com.aura.transport.MessageSender
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Mutual verification (both sides): show our own SAS code, accept the peer's, and flip
 * to ACTIVE once both have entered the other's. See [PairingCoordinator].
 */
class VerifyPairing @Inject constructor(
    private val identityManager: IdentityStore,
    private val kemRatchet: KemRatchetManager,
    private val pairingCrypto: PairingCrypto,
    private val pairingSignal: PairingSignal,
    private val contactDao: ContactDao,
    private val eraser: ContactEraser,
    private val settings: AuroraSettings,
    private val events: PairingEvents,
    private val messageSender: MessageSender
) {
    /**
     * Per-contact lock serializing the read→decide→write of the verify flags. The two
     * verify transitions run in different scopes — [submitVerifyCode] from the UI and
     * [handlePairVerify] from the SyncEngine — and both do a read-modify-write of
     * (iVerified, theyVerified, pairState). Without serialization they can interleave so
     * each clobbers the other's flag and the pair wedges in VERIFY forever. We also re-read
     * the row inside the lock so the second writer sees the first's flag and flips to ACTIVE.
     */
    private val verifyLocks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(nodeIdHex: String): Mutex = verifyLocks.computeIfAbsent(nodeIdHex) { Mutex() }
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
        return kemRatchet.sasCodeFor(contactNodeIdHex, myNodeIdHex)
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
        val expected = kemRatchet.sasCodeFor(contactNodeIdHex, contactNodeIdHex) ?: return@runCatching false
        val codeMatches = pairingCrypto.sasEquals(code.trim(), expected)

        lockFor(contactNodeIdHex).withLock {
            // Everything that reads-then-writes the verify flags runs under the lock and re-reads
            // the row first, so a concurrent handlePairVerify can't be clobbered — and, critically,
            // the wrong-code blocklist+wipe can't destroy a contact that the peer's pairverify just
            // activated (which would also blocklist the honest peer).
            val fresh = contactDao.byNodeId(contactNodeIdHex) ?: return@runCatching false
            if (fresh.pairState == PairState.ACTIVE) return@runCatching true   // already mutually verified
            if (fresh.pairState != PairState.VERIFY) return@runCatching false

            if (!codeMatches) {
                contactDao.incVerifyAttempts(contactNodeIdHex)
                val attempts = contactDao.byNodeId(contactNodeIdHex)?.verifyAttempts ?: 0
                if (attempts >= MAX_VERIFY_ATTEMPTS) {
                    settings.blockNode(contactNodeIdHex)
                    eraser.wipe(contactNodeIdHex)
                    events.emit(PairEvent.Failed(contactNodeIdHex))
                }
                return@runCatching false
            }

            // Re-read theyVerified under the lock: whichever transition runs second sees the
            // other's flag and flips to ACTIVE instead of clobbering it back to VERIFY.
            if (fresh.theyVerified) {
                contactDao.setVerify(contactNodeIdHex, iv = true, tv = true, state = PairState.ACTIVE)
                events.activated(contactNodeIdHex)
                bootstrapIfInitiator(contactNodeIdHex)
            } else {
                contactDao.setVerify(contactNodeIdHex, iv = true, tv = false, state = PairState.VERIFY)
            }
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
        lockFor(from).withLock {
            // Re-read under the lock so a concurrent submitVerifyCode (which may have set
            // iVerified) isn't clobbered — the second writer here flips to ACTIVE.
            val fresh = contactDao.byNodeId(from) ?: return@runCatching Unit
            when (fresh.pairState) {
                PairState.VERIFY -> if (fresh.iVerified) {
                    contactDao.setVerify(from, iv = true, tv = true, state = PairState.ACTIVE)
                    events.activated(from)
                    bootstrapIfInitiator(from)
                } else {
                    contactDao.setVerify(from, iv = false, tv = true, state = PairState.VERIFY)
                }
                else -> { /* already ACTIVE (or gone): nothing to do */ }
            }
        }
        Unit
    }

    private companion object {
        const val MAX_VERIFY_ATTEMPTS = 5
    }
}
