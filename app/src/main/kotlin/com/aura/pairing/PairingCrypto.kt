package com.aura.pairing

import android.util.Base64
import com.aura.crypto.Hkdf
import com.aura.crypto.HybridPublicKey
import com.aura.crypto.HybridVerifyKey
import com.aura.crypto.hexToBytes
import java.security.MessageDigest
import javax.inject.Inject

/**
 * The cryptographic core of pairing, extracted from [PairingCoordinator]: deriving the
 * pairing **root secret** and checking the identity binding. Pure given its inputs
 * (depends only on [Hkdf] — SHA3/HKDF, no liboqs, no Android KEM), so it is exhaustively
 * unit-testable in isolation. The handshake *orchestration* (KEM encap/decap, prekey
 * consumption, DB state) stays in [PairingCoordinator]; this owns only the math that decides
 * what root both peers agree on and whether a nodeId really commits to its keys.
 */
class PairingCrypto @Inject constructor(private val hkdf: Hkdf) {

    /** Legacy (identity-only) ratchet root from the single KEM shared secret. */
    fun legacyRoot(s: ByteArray): ByteArray =
        hkdf.derive(ikm = s, info = "aura-pair-root-v2".toByteArray(), outputLen = 32)

    /**
     * Forward-secret (PQXDH) root, mixing the identity-key secret with the signed-prekey
     * and (optional) one-time-prekey secrets. Both peers compute this identically: the
     * scanner from the secrets it encapsulated, the responder from the secrets it
     * decapsulated. The transcript (node ids, prekey ids, and SHA3 of every ciphertext)
     * is bound into the HKDF info, so a swapped ciphertext yields a different root —
     * caught by the mutual SAS. [responderKyberPub] is the responder's identity Kyber
     * public key (the QR key the scanner saw / the responder's own key).
     */
    fun fsRoot(
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
     * Constant-time equality for SAS codes. Avoids leaking a match-prefix length through
     * early-exit string comparison. (Local + rate-limited, so timing leakage was never
     * remotely exploitable — this is defence-in-depth.)
     */
    fun sasEquals(entered: String, expected: String): Boolean =
        MessageDigest.isEqual(entered.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    /** True iff nodeId == SHA3-256(kemPub ‖ signPub) for the supplied (Base64) keys. */
    fun nodeIdMatches(nodeIdHex: String, kemB64: String, ed25519B64: String, dilithiumB64: String): Boolean = try {
        val kemPub = HybridPublicKey.fromBytes(Base64.decode(kemB64, Base64.NO_WRAP))
        val signPub = HybridVerifyKey(Base64.decode(dilithiumB64, Base64.NO_WRAP), Base64.decode(ed25519B64, Base64.NO_WRAP))
        MessageDigest.isEqual(hkdf.sha3_256(kemPub.toBytes() + signPub.toBytes()), hexToBytes(nodeIdHex))
    } catch (e: Exception) {
        false
    }
}
