package com.aura.call

import android.util.Base64
import com.aura.crypto.RatchetManager
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.identity.IdentityStore
import com.aura.network.RendezvousClient
import com.aura.settings.AuroraSettings
import org.json.JSONObject
import javax.inject.Inject

/**
 * The sealed transport for WebRTC call signaling, extracted from [CallController].
 *
 * Each inner signal (`offer`/`answer`/`ice`/`bye`) is sealed with the conversation's
 * forward-secret ratchet (XChaCha20-Poly1305, AAD-bound to the directed pair) and
 * relayed through the rendezvous `/signal` queue — the server only ever sees
 * ciphertext. This class owns the crypto + envelope; [CallController] owns the call
 * state machine and WebRTC. Keeping them apart isolates the security-critical sealing
 * from the WebRTC lifecycle.
 */
class CallSignalCodec @Inject constructor(
    private val identityManager: IdentityStore,
    private val contactDao: ContactDao,
    private val ratchet: RatchetManager,
    private val rendezvousClient: RendezvousClient,
    private val settings: AuroraSettings
) {
    /** A decrypted inbound signal and who it came from. */
    data class Incoming(val from: String, val inner: JSONObject)

    /**
     * Seal [inner] for [peerNodeIdHex] and post it to the rendezvous signal queue.
     * Returns false if the contact is unknown or the ratchet isn't seeded.
     */
    suspend fun send(peerNodeIdHex: String, inner: JSONObject): Boolean {
        val identity = identityManager.getOrCreate()
        contactDao.byNodeId(peerNodeIdHex) ?: return false
        val aad = aad(identity.nodeId.toHex(), peerNodeIdHex)
        val sealed = ratchet.sealNext(peerNodeIdHex, inner.toString().toByteArray(), aad) ?: return false
        val payload = JSONObject()
            .put("type", "call")
            .put("from", identity.nodeId.toHex())
            .put("to", peerNodeIdHex)
            .put("n", sealed.n)
            .put("sealed", Base64.encodeToString(sealed.bytes, Base64.NO_WRAP))
        // Surface a failed post (the offer/answer/ICE never reached the queue) so callers
        // can react instead of assuming it was sent — a dropped ICE candidate quietly
        // prevents the connection, and a dropped offer means the peer never rings.
        val posted = rendezvousClient.postSignal(settings.serverAddress.value, peerNodeIdHex, payload.toString())
        posted.onFailure { android.util.Log.w("AuroraCall", "signal post failed (${inner.optString("kind")}): ${it.message}") }
        return posted.isSuccess
    }

    /**
     * Verify, decrypt and parse an inbound `call` signal. Returns null if it isn't
     * addressed to us, the sender isn't a contact, decryption fails (tamper/replay),
     * or the plaintext isn't valid JSON.
     */
    suspend fun receive(json: JSONObject): Incoming? {
        val identity = identityManager.getOrCreate()
        val from = json.optString("from")
        if (json.optString("to") != identity.nodeId.toHex()) return null
        contactDao.byNodeId(from) ?: return null
        val sealed = Base64.decode(json.optString("sealed"), Base64.NO_WRAP)
        val n = json.optLong("n", -1)
        val plaintext = ratchet.open(from, n, sealed, aad(from, identity.nodeId.toHex())) ?: return null
        val inner = try { JSONObject(String(plaintext)) } catch (e: Exception) { return null }
        return Incoming(from, inner)
    }

    companion object {
        /** Canonical AEAD associated-data binding a signal to its directed sender→recipient pair. */
        fun aad(fromHex: String, toHex: String): ByteArray = "aura-call-v1|$fromHex|$toHex".toByteArray()
    }
}
