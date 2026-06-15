package com.aura.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.openquantumsafe.Signature
import java.security.SecureRandom

/**
 * Dilithium-3 + Ed25519 hybrid signature scheme.
 *
 * Both schemes sign the message independently. Verification requires BOTH
 * to pass. An adversary must forge both signatures simultaneously.
 *
 * Wire format: [4-byte big-endian Dilithium sig length][Dilithium sig][Ed25519 sig (64 bytes)]
 *
 * Ed25519 keys use the NaCl 64-byte format for the private key (seed || public key).
 * BouncyCastle reads the first 32 bytes (the seed) when constructing
 * Ed25519PrivateKeyParameters.
 *
 * Native resource management: all Signature objects are wrapped in use{}
 * blocks so close() is called immediately after use, releasing native memory.
 *
 * Thread-safety: stateless. All suspend operations dispatched on Dispatchers.IO.
 *
 * Ported from ShadowMesh core/crypto.
 */
class HybridSigner {

    // ── Key generation ────────────────────────────────────────────────────

    suspend fun generateSigningKeyPair(): CryptoResult<HybridSigningKeyPair> =
        withContext(Dispatchers.IO) {
            cryptoRunCatching {
                val dilithiumPub:  ByteArray
                val dilithiumPriv: ByteArray
                Signature(DILITHIUM_ALGORITHM).use { sig ->
                    dilithiumPub  = sig.generate_keypair()
                    dilithiumPriv = sig.export_secret_key().copyOf()
                }

                // Ed25519 keypair via BouncyCastle; store private key in NaCl format (seed || pub)
                val gen = Ed25519KeyPairGenerator()
                gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
                val kp         = gen.generateKeyPair()
                val privParams = kp.private as Ed25519PrivateKeyParameters
                val pubParams  = kp.public  as Ed25519PublicKeyParameters
                val seed       = privParams.encoded   // 32 bytes
                val pubBytes   = pubParams.encoded    // 32 bytes

                HybridSigningKeyPair(
                    publicKey  = HybridVerifyKey(
                        dilithiumPublicKey = dilithiumPub,
                        ed25519PublicKey   = pubBytes
                    ),
                    privateKey = HybridSigningKey(
                        dilithiumPrivateKey = dilithiumPriv,
                        ed25519PrivateKey   = seed + pubBytes   // 64-byte NaCl format
                    )
                )
            }
        }

    // ── Sign ──────────────────────────────────────────────────────────────

    /**
     * Sign [message] with both Dilithium-3 and Ed25519 (detached mode).
     * Returns: [4B Dilithium len][Dilithium sig][Ed25519 sig (64 bytes)]
     */
    suspend fun sign(
        message:    ByteArray,
        privateKey: HybridSigningKey
    ): CryptoResult<ByteArray> =
        withContext(Dispatchers.IO) {
            cryptoRunCatching {
                val dilithiumSig: ByteArray
                Signature(DILITHIUM_ALGORITHM).use { sig ->
                    sig.import_secret_key(privateKey.dilithiumPrivateKey)
                    dilithiumSig = sig.sign(message)
                }

                val ed25519Sig = ed25519Sign(message, privateKey.ed25519PrivateKey)
                serializeHybridSig(dilithiumSig, ed25519Sig)
            }
        }

    // ── Verify ────────────────────────────────────────────────────────────

    /**
     * Verify a hybrid signature. BOTH Dilithium-3 and Ed25519 must pass.
     * Returns Success(true) on success; Failure with reason on any failure.
     */
    suspend fun verify(
        message:   ByteArray,
        signature: ByteArray,
        publicKey: HybridVerifyKey
    ): CryptoResult<Boolean> =
        withContext(Dispatchers.IO) {
            cryptoRunCatching {
                val (dilithiumSig, ed25519Sig) = deserializeHybridSig(signature)

                val dilithiumOk: Boolean
                Signature(DILITHIUM_ALGORITHM).use { sig ->
                    dilithiumOk = sig.verify(message, dilithiumSig, publicKey.dilithiumPublicKey)
                }

                val ed25519Ok = ed25519Verify(message, ed25519Sig, publicKey.ed25519PublicKey)

                if (!dilithiumOk) throw IllegalStateException("Dilithium-3 signature verification failed")
                if (!ed25519Ok)   throw IllegalStateException("Ed25519 signature verification failed")

                true
            }
        }

    // ── Ed25519-only (compact) sign/verify ───────────────────────────────
    //
    // Used by QR pairing and rendezvous check-ins where carrying a full
    // Dilithium-3 signature (~3.3 KB) is prohibitive.
    // Output is exactly 64 bytes. NOT post-quantum safe.

    /**
     * Produce a 64-byte Ed25519 detached signature over [message].
     * Use in bandwidth-constrained paths (QR, rendezvous). NOT post-quantum safe.
     */
    suspend fun signEd25519Only(
        message:    ByteArray,
        privateKey: HybridSigningKey
    ): CryptoResult<ByteArray> =
        withContext(Dispatchers.IO) {
            cryptoRunCatching {
                ed25519Sign(message, privateKey.ed25519PrivateKey)
            }
        }

    /**
     * Verify a 64-byte Ed25519 detached [signature] over [message].
     * Counterpart to [signEd25519Only]. NOT post-quantum safe.
     */
    suspend fun verifyEd25519Only(
        message:   ByteArray,
        signature: ByteArray,
        publicKey: ByteArray   // raw 32-byte Ed25519 public key
    ): CryptoResult<Boolean> =
        withContext(Dispatchers.IO) {
            cryptoRunCatching {
                require(signature.size == ED25519_SIG_BYTES) {
                    "Ed25519 signature must be $ED25519_SIG_BYTES bytes, got ${signature.size}"
                }
                require(publicKey.size == ED25519_PUB_BYTES) {
                    "Ed25519 public key must be $ED25519_PUB_BYTES bytes, got ${publicKey.size}"
                }
                ed25519Verify(message, signature, publicKey)
            }
        }

    /**
     * Verify a 64-byte Ed25519 detached [signature] over [message] synchronously.
     * Returns false on malformed inputs or verification failure. Must not be called
     * from the main thread.
     */
    fun verifyEd25519OnlySync(
        message:   ByteArray,
        signature: ByteArray,
        publicKey: ByteArray
    ): Boolean = try {
        signature.size == ED25519_SIG_BYTES &&
        publicKey.size == ED25519_PUB_BYTES &&
        ed25519Verify(message, signature, publicKey)
    } catch (e: Exception) {
        false
    }

    // ── Hybrid sync verify (rendezvous check-in path) ─────────────────────
    //
    // The check-in/verifyCandidates path runs synchronously (NanoHTTPD serve;
    // RendezvousClient.verifyCandidates). These mirror [verify] without the
    // coroutine wrapper. Must not be called from the main thread (Dilithium
    // verification is a native call).

    /**
     * Synchronously verify a full hybrid signature — BOTH Dilithium-3 and Ed25519
     * must pass. Returns false on any malformed input or verification failure.
     */
    fun verifyHybridSync(
        message:   ByteArray,
        signature: ByteArray,
        publicKey: HybridVerifyKey
    ): Boolean = try {
        val (dilithiumSig, ed25519Sig) = deserializeHybridSig(signature)
        val dilithiumOk = Signature(DILITHIUM_ALGORITHM).use {
            it.verify(message, dilithiumSig, publicKey.dilithiumPublicKey)
        }
        dilithiumOk && ed25519Verify(message, ed25519Sig, publicKey.ed25519PublicKey)
    } catch (e: Exception) {
        false
    }

    /**
     * Verify ONLY the Ed25519 component carried inside a hybrid [signature], against
     * [ed25519Pub]. Classical fallback for the brief pairing window before a peer's
     * Dilithium public key has been exchanged off-QR — not post-quantum.
     */
    fun verifyHybridEd25519PartSync(
        message:   ByteArray,
        signature: ByteArray,
        ed25519Pub: ByteArray
    ): Boolean = try {
        val (_, ed25519Sig) = deserializeHybridSig(signature)
        ed25519Verify(message, ed25519Sig, ed25519Pub)
    } catch (e: Exception) {
        false
    }

    // ── Ed25519 helpers (BouncyCastle) ────────────────────────────────────

    private fun ed25519Sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        // privateKey may be 32-byte seed or 64-byte NaCl (seed || pub).
        // Ed25519PrivateKeyParameters reads exactly 32 bytes from offset 0 (the seed).
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private fun ed25519Verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    // ── Serialisation ─────────────────────────────────────────────────────

    private fun serializeHybridSig(dilithiumSig: ByteArray, ed25519Sig: ByteArray): ByteArray =
        intTo4Bytes(dilithiumSig.size) + dilithiumSig + ed25519Sig

    private fun deserializeHybridSig(sig: ByteArray): Pair<ByteArray, ByteArray> {
        require(sig.size >= 4) { "Hybrid signature too short for length prefix" }
        val dLen = readInt4(sig, 0)
        require(dLen > 0) { "Hybrid signature: Dilithium length must be positive, got $dLen" }
        require(dLen == DILITHIUM3_SIG_BYTES) {
            "Hybrid signature: Dilithium-3 signature must be exactly $DILITHIUM3_SIG_BYTES bytes, got $dLen"
        }
        require(sig.size == 4 + dLen + ED25519_SIG_BYTES) {
            "Hybrid signature: wrong total length — expected ${4 + dLen + ED25519_SIG_BYTES}, got ${sig.size}"
        }
        return Pair(
            sig.copyOfRange(4, 4 + dLen),
            sig.copyOfRange(4 + dLen, 4 + dLen + ED25519_SIG_BYTES)
        )
    }

    companion object {
        private const val DILITHIUM_ALGORITHM  = "Dilithium3"
        private const val DILITHIUM3_SIG_BYTES = 3293
        const val ED25519_SIG_BYTES            = 64   // detached Ed25519 signature
        const val ED25519_PUB_BYTES            = 32   // Ed25519 public key
        const val ED25519_PRIV_BYTES           = 64   // NaCl format: seed (32) || public key (32)
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

data class HybridSigningKeyPair(
    val publicKey:  HybridVerifyKey,
    val privateKey: HybridSigningKey
)

/**
 * Wire format: [4-byte big-endian Dilithium key length][Dilithium key][Ed25519 key (32 bytes)]
 */
data class HybridVerifyKey(
    val dilithiumPublicKey: ByteArray,
    val ed25519PublicKey:   ByteArray
) {
    fun toBytes(): ByteArray =
        intTo4Bytes(dilithiumPublicKey.size) + dilithiumPublicKey + ed25519PublicKey

    companion object {
        private const val ED25519_KEY_BYTES = 32

        fun fromBytes(bytes: ByteArray): HybridVerifyKey {
            require(bytes.size >= 4) {
                "HybridVerifyKey: too short for length prefix (${bytes.size} bytes)"
            }
            val dLen = readInt4(bytes, 0)
            require(dLen >= 0) {
                "HybridVerifyKey: negative Dilithium key length ($dLen)"
            }
            require(bytes.size == 4 + dLen + ED25519_KEY_BYTES) {
                "HybridVerifyKey: wrong total length — expected ${4 + dLen + ED25519_KEY_BYTES}, got ${bytes.size}"
            }
            return HybridVerifyKey(
                dilithiumPublicKey = bytes.copyOfRange(4, 4 + dLen),
                ed25519PublicKey   = bytes.copyOfRange(4 + dLen, 4 + dLen + ED25519_KEY_BYTES)
            )
        }
    }

    override fun equals(other: Any?) = other is HybridVerifyKey &&
        dilithiumPublicKey.contentEquals(other.dilithiumPublicKey) &&
        ed25519PublicKey.contentEquals(other.ed25519PublicKey)
    override fun hashCode() = 31 * dilithiumPublicKey.contentHashCode() + ed25519PublicKey.contentHashCode()
}

data class HybridSigningKey(
    val dilithiumPrivateKey: ByteArray,
    val ed25519PrivateKey:   ByteArray
) {
    override fun equals(other: Any?) = other is HybridSigningKey &&
        dilithiumPrivateKey.contentEquals(other.dilithiumPrivateKey) &&
        ed25519PrivateKey.contentEquals(other.ed25519PrivateKey)
    override fun hashCode() = 31 * dilithiumPrivateKey.contentHashCode() + ed25519PrivateKey.contentHashCode()
}
