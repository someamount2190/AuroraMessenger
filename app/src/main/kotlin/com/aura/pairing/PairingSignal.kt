package com.aura.pairing

import android.util.Base64
import com.aura.crypto.HybridSigner
import com.aura.crypto.NodeIdentity
import com.aura.network.RendezvousClient
import com.aura.settings.AuroraSettings
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-signal transport for pairing, extracted from [PairingCoordinator].
 *
 * Pairing signals are exchanged *before* a ratchet exists, so they are authenticated
 * with detached **Ed25519 signatures** (not ratchet-sealed) over canonical
 * `aura-<type>-vN|from|to` strings and relayed through the rendezvous `/signal` queue.
 * This class owns signing, posting, and verification; [PairingCoordinator] owns the
 * handshake state machine and decides *what* to sign/verify.
 */
@Singleton
class PairingSignal @Inject constructor(
    private val signer: HybridSigner,
    private val rendezvousClient: RendezvousClient,
    private val settings: AuroraSettings
) {
    /** Ed25519-sign a canonical signed-part string; null on failure. */
    suspend fun sign(signedPart: String, identity: NodeIdentity): ByteArray? =
        signer.signEd25519Only(signedPart.toByteArray(), identity.privatePart.signingPrivateKey).getOrNull()

    /** Post a pre-built signal JSON to a peer's rendezvous queue. */
    suspend fun post(toNodeIdHex: String, json: String) {
        rendezvousClient.postSignal(settings.serverAddress.value, toNodeIdHex, json)
    }

    /**
     * Sign + post a simple control signal `{type, from, to, sig}`, signed over
     * `aura-<type>-v1|from|to`. Used for paircancel / pairreject / pairverify /
     * contactremove. No-op (drops) if signing fails.
     */
    suspend fun sendSimple(type: String, fromNodeIdHex: String, toNodeIdHex: String, identity: NodeIdentity) {
        val signedPart = "aura-$type-v1|$fromNodeIdHex|$toNodeIdHex"
        val sig = sign(signedPart, identity) ?: return
        post(
            toNodeIdHex,
            JSONObject().put("type", type).put("from", fromNodeIdHex).put("to", toNodeIdHex)
                .put("sig", Base64.encodeToString(sig, Base64.NO_WRAP)).toString()
        )
    }

    /** Verify an Ed25519 signature over [signedPart] against a Base64 key + Base64 sig. */
    suspend fun verifyEd(signedPart: String, ed25519B64: String, sigB64: String): Boolean = try {
        val ed = Base64.decode(ed25519B64, Base64.NO_WRAP)
        val sig = Base64.decode(sigB64, Base64.NO_WRAP)
        signer.verifyEd25519Only(signedPart.toByteArray(), sig, ed).getOrElse { false }
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c   // never swallow coroutine cancellation
    } catch (e: Exception) {
        false
    }

    /** Verify an Ed25519 signature over raw [message] bytes (prekey-bundle signatures). */
    suspend fun verifyEdBytes(message: ByteArray, ed25519Pub: ByteArray, sig: ByteArray): Boolean =
        signer.verifyEd25519Only(message, sig, ed25519Pub).getOrElse { false }
}
