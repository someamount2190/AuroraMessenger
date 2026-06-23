package com.aura.crypto

import com.google.crypto.tink.subtle.XChaCha20Poly1305
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * XChaCha20-Poly1305 AEAD symmetric encryption, backed by **Google Tink**
 * ([com.google.crypto.tink.subtle.XChaCha20Poly1305]). No hand-rolled HChaCha20 —
 * see [docs/CRYPTO_MIGRATION_PLAN.md].
 *
 * - Key size:   32 bytes
 * - Nonce size: 24 bytes (XChaCha20 extended nonce — random nonce collision negligible)
 * - Tag size:   16 bytes (Poly1305 MAC)
 *
 * Wire format: `[24-byte nonce][ciphertext + 16-byte tag]` — identical to libsodium's
 * `crypto_aead_xchacha20poly1305_ietf_*` and to Tink's own output, so this swap is
 * wire-compatible with the prior hand-rolled construction (same nonce/ct/tag layout).
 *
 * BouncyCastle still ships no XChaCha engine in any released version, which is why the
 * inner AEAD comes from Tink rather than BC.
 */
class SymmetricCipher(
    // Injected (with a sensible default) so tests can supply a TestDispatcher and so
    // dispatcher choice isn't hardcoded — per the Android coroutines best-practice guide.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val rng = SecureRandom()

    /**
     * Encrypt [plaintext] with [key], optionally binding to [aad] (additional authenticated data).
     *
     * When [aad] is non-null, it is included in the Poly1305 MAC but is NOT stored in the
     * wire output — the receiver must supply the same [aad] to [decrypt]. Tink chooses a fresh
     * random 24-byte nonce internally and prepends it, giving the `[nonce][ct‖tag]` wire format.
     */
    suspend fun encrypt(
        plaintext: ByteArray,
        key:       ByteArray,
        aad:       ByteArray? = null
    ): CryptoResult<ByteArray> =
        withContext(ioDispatcher) {
            cryptoRunCatching {
                require(key.size == KEY_BYTES) {
                    "Key must be $KEY_BYTES bytes, got ${key.size}"
                }
                XChaCha20Poly1305(key).encrypt(plaintext, aad ?: EMPTY_AAD)
            }
        }

    /**
     * Decrypt [noncePlusCiphertext] with [key].
     * Input must be in `[nonce || ciphertext+tag]` format as produced by [encrypt].
     *
     * When [aad] is non-null it is bound into the Poly1305 MAC: decryption succeeds only if
     * the caller supplies the SAME [aad] used at encryption. An auth failure is a hard
     * failure (tamper / wrong key) and propagates as CryptoResult.Failure.
     */
    suspend fun decrypt(
        noncePlusCiphertext: ByteArray,
        key:                 ByteArray,
        aad:                 ByteArray? = null
    ): CryptoResult<ByteArray> =
        withContext(ioDispatcher) {
            cryptoRunCatching {
                require(key.size == KEY_BYTES) {
                    "Key must be $KEY_BYTES bytes, got ${key.size}"
                }
                require(noncePlusCiphertext.size >= NONCE_BYTES + MAC_BYTES) {
                    "Input too short: ${noncePlusCiphertext.size} bytes"
                }
                XChaCha20Poly1305(key).decrypt(noncePlusCiphertext, aad ?: EMPTY_AAD)
            }
        }

    fun generateKey(): ByteArray = ByteArray(KEY_BYTES).also { rng.nextBytes(it) }

    companion object {
        const val KEY_BYTES   = 32
        const val NONCE_BYTES = 24  // XChaCha20 extended nonce
        const val MAC_BYTES   = 16  // Poly1305 tag
        private val EMPTY_AAD = ByteArray(0)
    }
}
