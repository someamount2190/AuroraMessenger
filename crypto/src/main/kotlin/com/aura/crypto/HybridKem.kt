package com.aura.crypto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.pqc.crypto.xwing.XWingKEMExtractor
import org.bouncycastle.pqc.crypto.xwing.XWingKEMGenerator
import org.bouncycastle.pqc.crypto.xwing.XWingKeyGenerationParameters
import org.bouncycastle.pqc.crypto.xwing.XWingKeyPairGenerator
import org.bouncycastle.pqc.crypto.xwing.XWingPrivateKeyParameters
import org.bouncycastle.pqc.crypto.xwing.XWingPublicKeyParameters
import java.security.SecureRandom

/**
 * **X-Wing** hybrid Key Encapsulation Mechanism (ML-KEM-768 + X25519), via BouncyCastle's
 * `org.bouncycastle.pqc.crypto.xwing` lightweight API — pure-JVM, no liboqs. See
 * [docs/CRYPTO_MIGRATION_PLAN.md].
 *
 * X-Wing (draft-connolly-cfrg-xwing-kem) performs the hybrid combine **internally**: the
 * 32-byte shared secret is a SHA3-256-based KDF over both the ML-KEM-768 shared secret and
 * the X25519 output, binding the ML-KEM ciphertext and the X25519 keys. So Aurora no longer
 * hand-rolls the KEM combiner or the transcript-binding HKDF — the library subsumes both.
 *
 * Wire representations are single opaque blobs (BC X-Wing encodings):
 *   - public key  ≈ 1216 B (ML-KEM-768 pk 1184 ‖ X25519 pk 32)
 *   - private key   = 32 B  (X-Wing seed; expanded on use)
 *   - ciphertext  ≈ 1120 B (ML-KEM-768 ct 1088 ‖ X25519 ephemeral pk 32)
 *   - shared secret = 32 B
 *
 * ⚠ BC's X-Wing tracks an in-progress IETF draft (currently -07); pin the BC version and
 * re-verify on any bump — the encoding is not yet a stabilised standard.
 */
class HybridKem(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val rng = SecureRandom()

    suspend fun generateKeyPair(): CryptoResult<HybridFullKeyPair> =
        withContext(ioDispatcher) {
            cryptoRunCatching {
                val gen = XWingKeyPairGenerator()
                gen.init(XWingKeyGenerationParameters(rng))
                val kp = gen.generateKeyPair()
                HybridFullKeyPair(
                    publicKey  = HybridPublicKey((kp.public  as XWingPublicKeyParameters).encoded),
                    privateKey = HybridPrivateKey((kp.private as XWingPrivateKeyParameters).encoded)
                )
            }
        }

    /**
     * **Bootstrap-only:** derive an X-Wing keypair *deterministically* from [seed]. The KEM
     * Double Ratchet seeds the responder's initial ratchet key from the shared pairing root via
     * this, so both peers derive the same bootstrap key (the initiator gets the public half, the
     * responder the private half). NOT for long-term identity keys — the very first ratchet step
     * provides no post-compromise security (PCS heals on the next, fresh, random step).
     */
    suspend fun deterministicKeyPair(seed: ByteArray): CryptoResult<HybridFullKeyPair> =
        withContext(ioDispatcher) {
            cryptoRunCatching {
                val gen = XWingKeyPairGenerator()
                gen.init(XWingKeyGenerationParameters(StreamRng(seed)))
                val kp = gen.generateKeyPair()
                HybridFullKeyPair(
                    publicKey  = HybridPublicKey((kp.public  as XWingPublicKeyParameters).encoded),
                    privateKey = HybridPrivateKey((kp.private as XWingPrivateKeyParameters).encoded)
                )
            }
        }

    /** Reproducible, inexhaustible byte stream (SHA-256 counter over [seed]) for deterministic
     *  keygen. Same seed ⇒ same stream ⇒ same keypair on both peers. */
    private class StreamRng(seed: ByteArray) : SecureRandom() {
        private val seed = seed.copyOf()
        private var counter = 0L
        private var buf = ByteArray(0)
        private var off = 0
        override fun nextBytes(bytes: ByteArray) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            var i = 0
            while (i < bytes.size) {
                if (off >= buf.size) {
                    md.reset(); md.update(seed)
                    md.update(ByteArray(8) { ((counter ushr (56 - it * 8)) and 0xff).toByte() })
                    counter++; buf = md.digest(); off = 0
                }
                bytes[i++] = buf[off++]
            }
        }
        override fun generateSeed(numBytes: Int): ByteArray = ByteArray(numBytes).also { nextBytes(it) }
    }

    suspend fun encapsulate(recipientPublicKey: HybridPublicKey): CryptoResult<HybridKemResult> =
        withContext(ioDispatcher) {
            cryptoRunCatching {
                val pub = XWingPublicKeyParameters(recipientPublicKey.encoded)
                val enc = XWingKEMGenerator(rng).generateEncapsulated(pub)
                HybridKemResult(
                    sharedSecret = enc.secret,
                    ciphertext   = HybridCiphertext(enc.encapsulation)
                )
            }
        }

    suspend fun decapsulate(
        ciphertext:       HybridCiphertext,
        recipientPrivKey: HybridPrivateKey
    ): CryptoResult<ByteArray> =
        withContext(ioDispatcher) {
            cryptoRunCatching {
                val priv = XWingPrivateKeyParameters(recipientPrivKey.encoded)
                XWingKEMExtractor(priv).extractSecret(ciphertext.encoded)
            }
        }
}

// ── Data classes ──────────────────────────────────────────────────────────────
//
// Each is a single opaque X-Wing blob. toBytes()/fromBytes() keep a 4-byte length prefix
// so NodePublicIdentity can split a concatenated [kemPublicKey ‖ signingPublicKey] stream
// without hardcoding the (draft-dependent) X-Wing key length.

/** X-Wing public key (encoded). Wire: [4B len][encoded]. */
data class HybridPublicKey(val encoded: ByteArray) {
    fun toBytes(): ByteArray = intTo4Bytes(encoded.size) + encoded

    companion object {
        fun fromBytes(bytes: ByteArray): HybridPublicKey {
            require(bytes.size >= 4) { "HybridPublicKey: too short (${bytes.size})" }
            val len = readInt4(bytes, 0)
            require(len >= 0 && bytes.size == 4 + len) {
                "HybridPublicKey: wrong total length — expected ${4 + len}, got ${bytes.size}"
            }
            return HybridPublicKey(bytes.copyOfRange(4, 4 + len))
        }
    }

    override fun equals(other: Any?) = other is HybridPublicKey && encoded.contentEquals(other.encoded)
    override fun hashCode() = encoded.contentHashCode()
}

/** X-Wing private key (32-byte seed, expanded on use). Never transmitted. */
data class HybridPrivateKey(val encoded: ByteArray) {
    override fun equals(other: Any?) = other is HybridPrivateKey && encoded.contentEquals(other.encoded)
    override fun hashCode() = encoded.contentHashCode()
}

/** X-Wing ciphertext (encapsulation). Wire: [4B len][encoded]. */
data class HybridCiphertext(val encoded: ByteArray) {
    fun toBytes(): ByteArray = intTo4Bytes(encoded.size) + encoded

    companion object {
        fun fromBytes(bytes: ByteArray): HybridCiphertext {
            require(bytes.size >= 4) { "HybridCiphertext: too short (${bytes.size})" }
            val len = readInt4(bytes, 0)
            require(len >= 0 && bytes.size == 4 + len) {
                "HybridCiphertext: wrong total length — expected ${4 + len}, got ${bytes.size}"
            }
            return HybridCiphertext(bytes.copyOfRange(4, 4 + len))
        }
    }

    override fun equals(other: Any?) = other is HybridCiphertext && encoded.contentEquals(other.encoded)
    override fun hashCode() = encoded.contentHashCode()
}

data class HybridFullKeyPair(
    val publicKey:  HybridPublicKey,
    val privateKey: HybridPrivateKey
)

data class HybridKemResult(
    val sharedSecret: ByteArray,
    val ciphertext:   HybridCiphertext
) {
    override fun equals(other: Any?) = other is HybridKemResult &&
        sharedSecret.contentEquals(other.sharedSecret)
    override fun hashCode() = sharedSecret.contentHashCode()
}
