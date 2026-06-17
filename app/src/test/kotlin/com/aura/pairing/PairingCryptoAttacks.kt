package com.aura.pairing

import android.util.Base64
import com.aura.crypto.Hkdf
import com.aura.crypto.HybridPublicKey
import com.aura.crypto.HybridVerifyKey
import com.aura.crypto.toHex
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Adversarial tests against the pairing handshake math. Each asserts a known handshake
 * attack vector is defeated (different inputs ⇒ different root ⇒ SAS mismatch ⇒ pairing
 * fails closed, or identity substitution is rejected).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingCryptoAttacks {

    private val hkdf = Hkdf()
    private val pc = PairingCrypto(hkdf)

    private fun root(
        init: String = "alice", resp: String = "bob",
        sIK: ByteArray = ByteArray(32) { 1 }, sSPK: ByteArray = ByteArray(32) { 2 }, sOPK: ByteArray? = ByteArray(32) { 3 },
        spkId: String = "spk1", opkId: String? = "opk1",
        ctIK: ByteArray = ByteArray(16) { 4 }, ctSpk: ByteArray = ByteArray(16) { 5 }, ctOpk: ByteArray? = ByteArray(16) { 6 },
        rkp: ByteArray = ByteArray(32) { 7 }
    ) = pc.fsRoot(init, resp, sIK, sSPK, sOPK, spkId, opkId, ctIK, ctSpk, ctOpk, rkp)

    private fun differs(a: ByteArray, b: ByteArray) = assertFalse(a.contentEquals(b))

    /** MITM ciphertext substitution: swapping any handshake ciphertext changes the root. */
    @Test fun mitm_ciphertextSubstitution_changesRoot() {
        val base = root()
        differs(base, root(ctIK = ByteArray(16) { 0x40 }))
        differs(base, root(ctSpk = ByteArray(16) { 0x40 }))
        differs(base, root(ctOpk = ByteArray(16) { 0x40 }))
    }

    /** Unknown Key-Share: the root binds the directed identity pair, so swapping roles diverges. */
    @Test fun unknownKeyShare_identityBinding_changesRoot() {
        differs(root(init = "alice", resp = "bob"), root(init = "bob", resp = "alice"))
    }

    /** The root is salted by the responder's identity key the scanner saw — a substituted
     *  responder key yields a different root (prevents responder substitution). */
    @Test fun responderKeyBinding_changesRoot() {
        differs(root(), root(rkp = ByteArray(32) { 0x55 }))
    }

    /** Prekey-id confusion: the SPK/OPK ids are in the transcript, so mismatched ids diverge. */
    @Test fun prekeyIdBinding_changesRoot() {
        differs(root(), root(spkId = "evil"))
        differs(root(), root(opkId = "evil"))
    }

    /** Dropping the one-time prekey (downgrade to no-FS-OPK) yields a distinct root, not a collision. */
    @Test fun opkDowngrade_changesRoot() {
        differs(root(), root(sOPK = null, opkId = null, ctOpk = null))
    }

    /** Key-compromise impersonation / UKS at the identity layer: a nodeId only validates for
     *  the exact keys it commits to; substituting the signing key is rejected. */
    @Test fun identitySubstitution_isRejected() {
        val kem = HybridPublicKey(ByteArray(1568) { (it * 3).toByte() }, ByteArray(32) { it.toByte() })
        val dil = ByteArray(1952) { (it * 5).toByte() }
        val ed = ByteArray(32) { (it + 7).toByte() }
        val nodeId = hkdf.sha3_256(kem.toBytes() + HybridVerifyKey(dil, ed).toBytes()).toHex()
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

        // attacker swaps the signing key but reuses the victim's nodeId+kem → rejected
        assertFalse(pc.nodeIdMatches(nodeId, b64(kem.toBytes()), b64(ByteArray(32) { 0x11 }), b64(dil)))
        assertFalse(pc.nodeIdMatches(nodeId, b64(kem.toBytes()), b64(ed), b64(ByteArray(1952) { 0x22 })))
        // attacker swaps the kem key (would let them decrypt) → rejected
        val otherKem = HybridPublicKey(ByteArray(1568) { 0x33 }, ByteArray(32) { 0x44 })
        assertFalse(pc.nodeIdMatches(nodeId, b64(otherKem.toBytes()), b64(ed), b64(dil)))
    }
}
