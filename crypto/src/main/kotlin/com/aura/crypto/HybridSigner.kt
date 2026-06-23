package com.aura.crypto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner
import java.security.SecureRandom

/**
 * ML-DSA-65 (FIPS 204) + Ed25519 hybrid signature scheme, backed entirely by
 * **BouncyCastle** (`org.bouncycastle.pqc.crypto.mldsa` + `Ed25519Signer`) — no liboqs,
 * pure-JVM. See [docs/CRYPTO_MIGRATION_PLAN.md].
 *
 * Both schemes sign the message independently. Verification requires BOTH to pass; an
 * adversary must forge both signatures simultaneously. The two components stay **separable**
 * in the wire format (deliberately not a LAMPS composite signature) because the pairing flow
 * verifies the Ed25519 half alone in the brief window before the peer's ML-DSA key is known
 * (see [verifyHybridEd25519PartSync]).
 *
 * Wire format: [4-byte big-endian ML-DSA sig length][ML-DSA sig][Ed25519 sig (64 bytes)].
 * For ML-DSA-65 the signature is 3309 bytes (FIPS 204 Table 2).
 *
 * The `dilithium*` field/parameter names are retained across the codebase (DB columns,
 * backup/wire JSON, identity store) to keep this migration localized; they now carry
 * ML-DSA-65 key/signature bytes. The DB schema documents the column as "Dilithium-3 (ML-DSA)".
 *
 * Ed25519 keys use the NaCl 64-byte format for the private key (seed || public key).
 * BouncyCastle reads the first 32 bytes (the seed) when constructing
 * Ed25519PrivateKeyParameters.
 *
 * Thread-safety: stateless. All suspend operations dispatched on Dispatchers.IO.
 */
class HybridSigner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    // ── Key generation ────────────────────────────────────────────────────

    suspend fun generateSigningKeyPair(): CryptoResult<HybridSigningKeyPair> =
        withContext(ioDispatcher) {
            cryptoRunCatching {
                // ML-DSA-65 keypair (BouncyCastle, pure-Java); keys held as raw encoded bytes.
                val mldsaGen = MLDSAKeyPairGenerator()
                mldsaGen.init(MLDSAKeyGenerationParameters(SecureRandom(), MLDSAParameters.ml_dsa_65))
                val mldsaKp   = mldsaGen.generateKeyPair()
                val mldsaPub  = (mldsaKp.public  as MLDSAPublicKeyParameters).encoded
                val mldsaPriv = (mldsaKp.private as MLDSAPrivateKeyParameters).encoded

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
                        dilithiumPublicKey = mldsaPub,
                        ed25519PublicKey   = pubBytes
                    ),
                    privateKey = HybridSigningKey(
                        dilithiumPrivateKey = mldsaPriv,
                        ed25519PrivateKey   = seed + pubBytes   // 64-byte NaCl format
                    )
                )
            }
        }

    // ── Sign ──────────────────────────────────────────────────────────────

    /**
     * Sign [message] with both ML-DSA-65 and Ed25519 (detached mode).
     * Returns: [4B ML-DSA len][ML-DSA sig][Ed25519 sig (64 bytes)]
     */
    suspend fun sign(
        message:    ByteArray,
        privateKey: HybridSigningKey
    ): CryptoResult<ByteArray> =
        withContext(ioDispatcher) {
            cryptoRunCatching {
                val mldsaSig   = mldsaSign(message, privateKey.dilithiumPrivateKey)
                val ed25519Sig = ed25519Sign(message, privateKey.ed25519PrivateKey)
                serializeHybridSig(mldsaSig, ed25519Sig)
            }
        }

    // ── Verify ────────────────────────────────────────────────────────────

    /**
     * Verify a hybrid signature. BOTH ML-DSA-65 and Ed25519 must pass.
     * Returns Success(true) on success; Failure with reason on any failure.
     */
    suspend fun verify(
        message:   ByteArray,
        signature: ByteArray,
        publicKey: HybridVerifyKey
    ): CryptoResult<Boolean> =
        withContext(ioDispatcher) {
            cryptoRunCatching {
                val (mldsaSig, ed25519Sig) = deserializeHybridSig(signature)

                val mldsaOk   = mldsaVerify(message, mldsaSig, publicKey.dilithiumPublicKey)
                val ed25519Ok = ed25519Verify(message, ed25519Sig, publicKey.ed25519PublicKey)

                if (!mldsaOk)   throw IllegalStateException("ML-DSA-65 signature verification failed")
                if (!ed25519Ok) throw IllegalStateException("Ed25519 signature verification failed")

                true
            }
        }

    // ── Ed25519-only (compact) sign/verify ───────────────────────────────
    //
    // Used by QR pairing and rendezvous check-ins where carrying a full
    // ML-DSA-65 signature (~3.3 KB) is prohibitive.
    // Output is exactly 64 bytes. NOT post-quantum safe.

    /**
     * Produce a 64-byte Ed25519 detached signature over [message].
     * Use in bandwidth-constrained paths (QR, rendezvous). NOT post-quantum safe.
     */
    suspend fun signEd25519Only(
        message:    ByteArray,
        privateKey: HybridSigningKey
    ): CryptoResult<ByteArray> =
        withContext(ioDispatcher) {
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
        withContext(ioDispatcher) {
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
    // coroutine wrapper. Must not be called from the main thread (ML-DSA
    // verification is CPU-heavy).

    /**
     * Synchronously verify a full hybrid signature — BOTH ML-DSA-65 and Ed25519
     * must pass. Returns false on any malformed input or verification failure.
     */
    fun verifyHybridSync(
        message:   ByteArray,
        signature: ByteArray,
        publicKey: HybridVerifyKey
    ): Boolean = try {
        val (mldsaSig, ed25519Sig) = deserializeHybridSig(signature)
        mldsaVerify(message, mldsaSig, publicKey.dilithiumPublicKey) &&
            ed25519Verify(message, ed25519Sig, publicKey.ed25519PublicKey)
    } catch (e: Exception) {
        false
    }

    /**
     * Verify ONLY the Ed25519 component carried inside a hybrid [signature], against
     * [ed25519Pub]. Classical fallback for the brief pairing window before a peer's
     * ML-DSA public key has been exchanged off-QR — not post-quantum.
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

    // ── ML-DSA-65 helpers (BouncyCastle low-level) ────────────────────────

    private fun mldsaSign(message: ByteArray, encodedPriv: ByteArray): ByteArray {
        val signer = MLDSASigner()
        signer.init(true, MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65, encodedPriv))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private fun mldsaVerify(message: ByteArray, signature: ByteArray, encodedPub: ByteArray): Boolean =
        try {
            val verifier = MLDSASigner()
            verifier.init(false, MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, encodedPub))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
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

    private fun serializeHybridSig(mldsaSig: ByteArray, ed25519Sig: ByteArray): ByteArray =
        intTo4Bytes(mldsaSig.size) + mldsaSig + ed25519Sig

    private fun deserializeHybridSig(sig: ByteArray): Pair<ByteArray, ByteArray> {
        require(sig.size >= 4) { "Hybrid signature too short for length prefix" }
        val dLen = readInt4(sig, 0)
        require(dLen > 0) { "Hybrid signature: ML-DSA length must be positive, got $dLen" }
        require(dLen == MLDSA65_SIG_BYTES) {
            "Hybrid signature: ML-DSA-65 signature must be exactly $MLDSA65_SIG_BYTES bytes, got $dLen"
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
        /** ML-DSA-65 detached signature size (FIPS 204 Table 2). */
        const val MLDSA65_SIG_BYTES  = 3309
        const val ED25519_SIG_BYTES  = 64   // detached Ed25519 signature
        const val ED25519_PUB_BYTES  = 32   // Ed25519 public key
        const val ED25519_PRIV_BYTES = 64   // NaCl format: seed (32) || public key (32)
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

data class HybridSigningKeyPair(
    val publicKey:  HybridVerifyKey,
    val privateKey: HybridSigningKey
)

/**
 * Wire format: [4-byte big-endian ML-DSA key length][ML-DSA key][Ed25519 key (32 bytes)]
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
                "HybridVerifyKey: negative ML-DSA key length ($dLen)"
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
