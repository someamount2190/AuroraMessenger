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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cryptographic heart of pairing — root derivation + identity binding. Previously
 * untested (trapped inside PairingCoordinator). Robolectric is used only because
 * nodeIdMatches decodes Android Base64; the math itself is plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingCryptoTest {

    private val hkdf = Hkdf()
    private val pc = PairingCrypto(hkdf)

    // ── fsRoot ───────────────────────────────────────────────────────────────
    private fun fsRoot(
        sIK: ByteArray = ByteArray(32) { 1 },
        sSPK: ByteArray = ByteArray(32) { 2 },
        sOPK: ByteArray? = ByteArray(32) { 3 },
        spkId: String = "spk1",
        opkId: String? = "opk1",
        ctIK: ByteArray = ByteArray(16) { 4 },
        ctSpk: ByteArray = ByteArray(16) { 5 },
        ctOpk: ByteArray? = ByteArray(16) { 6 },
        rkp: ByteArray = ByteArray(32) { 7 }
    ) = pc.fsRoot("alice", "bob", sIK, sSPK, sOPK, spkId, opkId, ctIK, ctSpk, ctOpk, rkp)

    @Test fun fsRoot_is32Bytes_andBothPeersAgree() {
        // Same transcript+secrets ⇒ identical root (what the scanner and responder each compute).
        val a = fsRoot()
        val b = fsRoot()
        assertEquals(32, a.size)
        assertTrue(a.contentEquals(b))
    }

    @Test fun fsRoot_swappedCiphertext_yieldsDifferentRoot() {
        // Transcript binding: a substituted ciphertext must change the root (MITM caught by SAS).
        assertFalse(fsRoot().contentEquals(fsRoot(ctIK = ByteArray(16) { 99 })))
        assertFalse(fsRoot().contentEquals(fsRoot(ctSpk = ByteArray(16) { 99 })))
    }

    @Test fun fsRoot_dependsOnSecretsAndIds() {
        assertFalse(fsRoot().contentEquals(fsRoot(sIK = ByteArray(32) { 99 })))
        assertFalse(fsRoot().contentEquals(fsRoot(spkId = "other")))
    }

    @Test fun fsRoot_withAndWithoutOpk_differ() {
        assertFalse(fsRoot().contentEquals(fsRoot(sOPK = null, opkId = null, ctOpk = null)))
    }

    // ── nodeIdMatches ────────────────────────────────────────────────────────
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    @Test fun nodeIdMatches_trueForCommittedKeys_falseOtherwise() {
        val kemPub = HybridPublicKey(ByteArray(1216) { (it * 3).toByte() })
        val dil = ByteArray(1952) { (it * 5).toByte() }
        val ed = ByteArray(32) { (it + 7).toByte() }
        val signPub = HybridVerifyKey(dil, ed)
        val nodeId = hkdf.sha3_256(kemPub.toBytes() + signPub.toBytes()).toHex()

        assertTrue(pc.nodeIdMatches(nodeId, b64(kemPub.toBytes()), b64(ed), b64(dil)))
        // wrong nodeId
        assertFalse(pc.nodeIdMatches(hkdf.sha3_256("other".toByteArray()).toHex(), b64(kemPub.toBytes()), b64(ed), b64(dil)))
        // substituted signing key → identity no longer commits
        assertFalse(pc.nodeIdMatches(nodeId, b64(kemPub.toBytes()), b64(ed), b64(ByteArray(1952) { 9 })))
        // malformed input fails closed (not a crash)
        assertFalse(pc.nodeIdMatches(nodeId, "!!notbase64!!", b64(ed), b64(dil)))
    }

    @Test fun sasEquals_matchesExactly_andRejectsOtherwise() {
        assertTrue(pc.sasEquals("250156", "250156"))
        assertFalse(pc.sasEquals("250157", "250156"))
        assertFalse(pc.sasEquals("250156", "250157"))
        assertFalse(pc.sasEquals("25015", "250156"))   // length differs
        assertFalse(pc.sasEquals("", "250156"))
    }
}
