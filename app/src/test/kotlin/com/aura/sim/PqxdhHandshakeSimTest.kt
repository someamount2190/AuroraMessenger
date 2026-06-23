package com.aura.sim

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.crypto.Hkdf
import com.aura.crypto.HybridCiphertext
import com.aura.crypto.HybridKem
import com.aura.crypto.HybridPublicKey
import com.aura.crypto.HybridSigner
import com.aura.crypto.NodeIdentityGenerator
import com.aura.crypto.PrekeyManager
import com.aura.crypto.RatchetManager
import com.aura.crypto.SymmetricCipher
import com.aura.crypto.toHex
import com.aura.db.AuroraDatabase
import com.aura.db.RoomPrekeyStore
import com.aura.db.RoomRatchetStore
import com.aura.pairing.PairingCrypto
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end **real PQXDH handshake** in one JVM — no shortcuts. Previously the pairing
 * simulation seeded the ratchet from a synthetic shared root because the KEM handshake needed
 * liboqs native; now the whole stack is pure-JVM, so this drives the genuine path and composes
 * every Phase 0–4b primitive:
 *
 *   real X-Wing + ML-DSA identities (NodeIdentityGenerator) → responder publishes an
 *   Ed25519-signed X-Wing prekey bundle (PrekeyManager) → initiator verifies the prekey
 *   signature and triple-encapsulates (X-Wing) to identity + signed prekey + one-time prekey →
 *   both sides derive the forward-secret root (PairingCrypto.fsRoot, HKDF-SHA-256 with
 *   transcript binding) → ratchet seal/open (Tink XChaCha20-Poly1305).
 *
 * The decisive assertion is that **both peers independently derive the same 32-byte root** from
 * the real encapsulations — i.e. the post-quantum key agreement actually agrees — after which a
 * message round-trips through the ratchet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PqxdhHandshakeSimTest {

    private val hkdf = Hkdf()
    private val kem = HybridKem()
    private val signer = HybridSigner()
    private val cipher = SymmetricCipher()
    private val pairingCrypto = PairingCrypto(hkdf)

    @Test
    fun real_pqxdh_handshake_bothPeersDeriveSameRoot_thenMessageRoundTrips() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val gen = NodeIdentityGenerator(kem, signer, hkdf)
        val initId = gen.generate().getOrThrow()
        val respId = gen.generate().getOrThrow()
        val initHex = initId.nodeId.toHex()
        val respHex = respId.nodeId.toHex()

        // ── Responder publishes its signed prekey bundle. ──
        val respDb = inMemoryDb(ctx)
        val respPrekeys = PrekeyManager(RoomPrekeyStore(respDb.prekeyDao()), kem, signer)
        val bundle: JSONObject = respPrekeys.publicBundle(respId)

        val spk = bundle.getJSONObject("spk")
        val spkId = spk.getString("id")
        val spkPubB64 = spk.getString("pub")
        val spkPub = HybridPublicKey.fromBytes(b64d(spkPubB64))
        // Initiator verifies the signed-prekey signature against the responder's QR Ed25519 key.
        val spkMsg = PrekeyManager.prekeyMessage(respHex, PrekeyManager.KIND_SPK, spkId, spkPubB64)
        assertTrue(
            "signed-prekey signature must verify",
            signer.verifyEd25519Only(spkMsg, b64d(spk.getString("sig")), respId.publicPart.signingPublicKey.ed25519PublicKey).getOrThrow()
        )

        val opk = bundle.getJSONArray("opks").getJSONObject(0)
        val opkId = opk.getString("id")
        val opkPub = HybridPublicKey.fromBytes(b64d(opk.getString("pub")))

        // ── Initiator: triple X-Wing encapsulation, then derive the forward-secret root. ──
        val eIK = kem.encapsulate(respId.publicPart.kemPublicKey).getOrThrow()
        val eSPK = kem.encapsulate(spkPub).getOrThrow()
        val eOPK = kem.encapsulate(opkPub).getOrThrow()
        val ctIK = eIK.ciphertext.toBytes()
        val ctSpk = eSPK.ciphertext.toBytes()
        val ctOpk = eOPK.ciphertext.toBytes()
        val initRoot = pairingCrypto.fsRoot(
            initiatorHex = initHex, responderHex = respHex,
            sIK = eIK.sharedSecret.copyOf(), sSPK = eSPK.sharedSecret.copyOf(), sOPK = eOPK.sharedSecret.copyOf(),
            spkId = spkId, opkId = opkId,
            ctIK = ctIK, ctSpk = ctSpk, ctOpk = ctOpk,
            responderKemPub = respId.publicPart.kemPublicKey.encoded
        )

        // ── Responder: consume the prekeys, decapsulate, derive the SAME root. ──
        val consumed = respPrekeys.consume(spkId, opkId) ?: error("responder lost its own signed prekey")
        assertFalse("one-time prekey should be present and consumed", consumed.opkMissing)
        val sIK = kem.decapsulate(HybridCiphertext.fromBytes(ctIK), respId.privatePart.kemPrivateKey).getOrThrow()
        val sSPK = kem.decapsulate(HybridCiphertext.fromBytes(ctSpk), consumed.spkPriv).getOrThrow()
        val sOPK = kem.decapsulate(HybridCiphertext.fromBytes(ctOpk), consumed.opkPriv!!).getOrThrow()
        val respRoot = pairingCrypto.fsRoot(
            initiatorHex = initHex, responderHex = respHex,
            sIK = sIK, sSPK = sSPK, sOPK = sOPK,
            spkId = spkId, opkId = opkId,
            ctIK = ctIK, ctSpk = ctSpk, ctOpk = ctOpk,
            responderKemPub = respId.publicPart.kemPublicKey.encoded
        )

        // ── The handshake agreed: both sides derived the same root from real X-Wing KEM. ──
        assertArrayEquals("PQXDH roots must match on both peers", initRoot, respRoot)

        // ── Seed both ratchets from their root and exchange sealed messages both ways. ──
        val initDb = inMemoryDb(ctx)
        val initRatchet = RatchetManager(RoomRatchetStore(initDb.ratchetDao()), hkdf, cipher)
        val respRatchet = RatchetManager(RoomRatchetStore(respDb.ratchetDao()), hkdf, cipher)
        initRatchet.seedFromSharedSecret(respHex, initHex, respHex, initRoot.copyOf())
        respRatchet.seedFromSharedSecret(initHex, respHex, initHex, respRoot.copyOf())

        val aad = "aura-msg-v1".toByteArray()
        val s1 = initRatchet.sealNext(respHex, "hello over real PQXDH".toByteArray(), aad)!!
        assertEquals("hello over real PQXDH", String(respRatchet.open(initHex, s1.n, s1.bytes, aad)!!))
        val s2 = respRatchet.sealNext(initHex, "reply".toByteArray(), aad)!!
        assertEquals("reply", String(initRatchet.open(respHex, s2.n, s2.bytes, aad)!!))

        // Both peers compute the same SAS from the shared root (MITM would diverge).
        assertEquals(initRatchet.sasCodeFor(respHex, respHex), respRatchet.sasCodeFor(initHex, respHex))
    }

    @Test
    fun swappedPrekeyCiphertext_yieldsDifferentRoot() = runBlocking {
        // Transcript binding: if a network attacker swaps a prekey ciphertext, the responder
        // derives a different root (caught by the mutual SAS). Exercised with real encapsulations.
        val gen = NodeIdentityGenerator(kem, signer, hkdf)
        val initId = gen.generate().getOrThrow(); val respId = gen.generate().getOrThrow()
        val initHex = initId.nodeId.toHex(); val respHex = respId.nodeId.toHex()
        val eIK = kem.encapsulate(respId.publicPart.kemPublicKey).getOrThrow()
        val eSPK = kem.encapsulate(respId.publicPart.kemPublicKey).getOrThrow()
        fun root(ctSpk: ByteArray) = pairingCrypto.fsRoot(
            initHex, respHex, eIK.sharedSecret.copyOf(), eSPK.sharedSecret.copyOf(), null,
            "spk1", null, eIK.ciphertext.toBytes(), ctSpk, null, respId.publicPart.kemPublicKey.encoded
        )
        val honest = root(eSPK.ciphertext.toBytes())
        val tampered = root(kem.encapsulate(respId.publicPart.kemPublicKey).getOrThrow().ciphertext.toBytes())
        assertFalse("swapped ciphertext must change the root", honest.contentEquals(tampered))
    }

    private fun inMemoryDb(ctx: Context): AuroraDatabase =
        Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java).allowMainThreadQueries().build()

    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
