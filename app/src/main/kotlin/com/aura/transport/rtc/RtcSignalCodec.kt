package com.aura.transport.rtc

import android.util.Base64
import com.aura.crypto.RatchetManager
import com.aura.crypto.toHex
import com.aura.db.ContactDao
import com.aura.identity.IdentityStore
import com.aura.network.Rendezvous
import com.aura.settings.AuroraSettings
import org.json.JSONObject
import javax.inject.Inject

/**
 * Sealed transport for WebRTC **data-channel** signaling — the message-transport
 * twin of [com.aura.call.CallSignalCodec]. Each inner signal
 * (`offer`/`answer`/`ice`/`bye`) is sealed with the conversation's forward-secret
 * ratchet (XChaCha20-Poly1305, AAD-bound to the directed pair) and relayed through
 * the rendezvous `/signal` queue — the server only ever sees ciphertext, and once
 * ICE connects it falls off the path entirely.
 *
 * The signal `type` is `"rtc"` (dispatched by [com.aura.network.SyncEngine]); the
 * AAD label `aura-rtc-v1` keeps these signals cryptographically distinct from call
 * signaling and message frames even though they share the per-contact ratchet.
 */
class RtcSignalCodec @Inject constructor(
    private val identityManager: IdentityStore,
    private val contactDao: ContactDao,
    private val ratchet: RatchetManager,
    private val rendezvousClient: Rendezvous,
    private val settings: AuroraSettings
) {
    /** A decrypted inbound signal and who it came from. */
    data class Incoming(val from: String, val inner: JSONObject)

    /** Seal [inner] for [peerNodeIdHex] and post it to the rendezvous signal queue. */
    suspend fun send(peerNodeIdHex: String, inner: JSONObject): Boolean {
        val identity = identityManager.getOrCreate()
        contactDao.byNodeId(peerNodeIdHex) ?: return false
        val aad = aad(identity.nodeId.toHex(), peerNodeIdHex)
        val sealed = ratchet.sealNext(peerNodeIdHex, inner.toString().toByteArray(), aad) ?: return false
        val payload = JSONObject()
            .put("type", "rtc")
            .put("from", identity.nodeId.toHex())
            .put("to", peerNodeIdHex)
            .put("n", sealed.n)
            .put("sealed", Base64.encodeToString(sealed.bytes, Base64.NO_WRAP))
        // Surface a failed post so RtcTransport can tear the half-open session down and
        // retry, instead of treating an unsent offer/answer/ICE as delivered.
        val posted = rendezvousClient.postSignal(settings.serverAddress.value, peerNodeIdHex, payload.toString())
        posted.onFailure { android.util.Log.w("AuroraRtc", "signal post failed (${inner.optString("kind")}): ${it.message}") }
        return posted.isSuccess
    }

    /** Verify, decrypt and parse an inbound `rtc` signal; null if not for us / tampered. */
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
        /** Canonical AEAD associated-data binding a data-signal to its directed sender→recipient pair. */
        fun aad(fromHex: String, toHex: String): ByteArray = "aura-rtc-v1|$fromHex|$toHex".toByteArray()
    }
}
