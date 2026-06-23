package com.aura.crypto

import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Authoritative known-answer tests for the **classical** primitives underpinning Aurora's
 * hybrids: **Ed25519** (RFC 8032 §7.1) exercised through Aurora's own Ed25519-only signing
 * path ([HybridSigner.signEd25519Only] / [HybridSigner.verifyEd25519Only]), and **X25519**
 * (RFC 7748 §5.2) on the BouncyCastle primitive that forms the classical half of X-Wing.
 *
 * These vectors are **self-validating**: each pins a full cryptographic relationship
 * (seed → signature, scalar·point → output), so a mistranscribed byte breaks the relation and
 * fails the test rather than passing silently — unlike a lone expected-ciphertext.
 */
class ClassicalKatTest {

    private val signer = HybridSigner()

    // ── Ed25519 — RFC 8032 §7.1 (through Aurora's Ed25519-only path) ────────────

    @Test fun ed25519_rfc8032_test1_emptyMessage() = runBlocking {
        val sk = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val pk = hexToBytes("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val expectedSig = hexToBytes(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e0652249015" +
            "55fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        )
        val msg = ByteArray(0)
        val key = HybridSigningKey(dilithiumPrivateKey = ByteArray(0), ed25519PrivateKey = sk)
        assertEquals(expectedSig.toHex(), signer.signEd25519Only(msg, key).getOrThrow().toHex())
        assertTrue(signer.verifyEd25519Only(msg, expectedSig, pk).getOrThrow())
    }

    @Test fun ed25519_rfc8032_test2_oneByteMessage() = runBlocking {
        val sk = hexToBytes("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val pk = hexToBytes("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        val expectedSig = hexToBytes(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69d" +
            "a085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
        )
        val msg = hexToBytes("72")
        val key = HybridSigningKey(dilithiumPrivateKey = ByteArray(0), ed25519PrivateKey = sk)
        assertEquals(expectedSig.toHex(), signer.signEd25519Only(msg, key).getOrThrow().toHex())
        assertTrue(signer.verifyEd25519Only(msg, expectedSig, pk).getOrThrow())
    }

    @Test fun ed25519_wrongKey_rejected() = runBlocking {
        val sig = hexToBytes(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e0652249015" +
            "55fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        )
        // TEST2's public key must not verify TEST1's signature.
        val otherPk = hexToBytes("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        assertEquals(false, signer.verifyEd25519Only(ByteArray(0), sig, otherPk).getOrThrow())
    }

    // ── X25519 — RFC 7748 §5.2 (BouncyCastle primitive; X-Wing's classical half) ──

    private fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        val a = X25519Agreement()
        a.init(X25519PrivateKeyParameters(scalar, 0))
        val out = ByteArray(a.agreementSize)
        a.calculateAgreement(X25519PublicKeyParameters(u, 0), out, 0)
        return out
    }

    @Test fun x25519_rfc7748_vector1() {
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            x25519(
                hexToBytes("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                hexToBytes("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
            ).toHex()
        )
    }

    @Test fun x25519_rfc7748_vector2() {
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            x25519(
                hexToBytes("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                hexToBytes("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")
            ).toHex()
        )
    }

    /** RFC 7748 §5.2 iterative test, after one iteration (scalar = u = base point 9). */
    @Test fun x25519_rfc7748_iterative_oneIteration() {
        val base = hexToBytes("0900000000000000000000000000000000000000000000000000000000000000")
        assertEquals(
            "422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079",
            x25519(base.copyOf(), base.copyOf()).toHex()
        )
    }
}
