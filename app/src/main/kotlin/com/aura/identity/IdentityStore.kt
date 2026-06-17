package com.aura.identity

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aura.crypto.HybridKem
import com.aura.crypto.HybridPrivateKey
import com.aura.crypto.HybridPublicKey
import com.aura.crypto.HybridSigner
import com.aura.crypto.HybridSigningKey
import com.aura.crypto.HybridVerifyKey
import com.aura.crypto.Hkdf
import com.aura.crypto.NodeIdentity
import com.aura.crypto.NodeIdentityGenerator
import com.aura.crypto.NodePrivateIdentity
import com.aura.crypto.NodePublicIdentity
import com.aura.crypto.toHex
import com.aura.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the device's stable node identity (Phase 1 requirement: generated once
 * on first launch, never regenerates).
 *
 * Post-quantum private keys are too large for the Android Keystore itself, so
 * they are stored in EncryptedSharedPreferences whose master key lives in the
 * Keystore — the standard pattern for large key material.
 */
@Singleton
class IdentityStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kem:    HybridKem,
    private val signer: HybridSigner,
    private val hkdf:   Hkdf,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val mutex = Mutex()
    @Volatile private var cached: NodeIdentity? = null

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "aura_identity",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Load the persisted identity, generating it on first launch. Thread-safe. */
    suspend fun getOrCreate(): NodeIdentity = cached ?: mutex.withLock {
        cached ?: withContext(ioDispatcher) {
            load() ?: generateAndPersist()
        }.also { cached = it }
    }

    val nodeIdHexOrNull: String?
        get() = cached?.nodeId?.toHex()

    /** Wipe the identity (Settings → Clear all data). The next launch regenerates. */
    suspend fun clear() = mutex.withLock {
        withContext(ioDispatcher) { prefs.edit().clear().commit() }
        cached = null
    }

    // ── Persistence ───────────────────────────────────────────────────────

    private suspend fun generateAndPersist(): NodeIdentity {
        val identity = NodeIdentityGenerator(kem, signer, hkdf).generate().getOrThrow()
        persist(identity)
        return identity
    }

    /** Overwrite the stored identity from a restored backup (replaces any current one). */
    suspend fun restore(identity: NodeIdentity) = mutex.withLock {
        withContext(ioDispatcher) { persist(identity) }
        cached = identity
    }

    private fun persist(identity: NodeIdentity) {
        prefs.edit()
            .putString(KEY_NODE_ID,        b64(identity.nodeId))
            .putString(KEY_KYBER_PUB,      b64(identity.publicPart.kemPublicKey.kyberPublicKey))
            .putString(KEY_X25519_PUB,     b64(identity.publicPart.kemPublicKey.x25519PublicKey))
            .putString(KEY_KYBER_PRIV,     b64(identity.privatePart.kemPrivateKey.kyberPrivateKey))
            .putString(KEY_X25519_PRIV,    b64(identity.privatePart.kemPrivateKey.x25519PrivateKey))
            .putString(KEY_DILITHIUM_PUB,  b64(identity.publicPart.signingPublicKey.dilithiumPublicKey))
            .putString(KEY_ED25519_PUB,    b64(identity.publicPart.signingPublicKey.ed25519PublicKey))
            .putString(KEY_DILITHIUM_PRIV, b64(identity.privatePart.signingPrivateKey.dilithiumPrivateKey))
            .putString(KEY_ED25519_PRIV,   b64(identity.privatePart.signingPrivateKey.ed25519PrivateKey))
            .commit()
    }

    private fun load(): NodeIdentity? {
        val nodeId = unb64(KEY_NODE_ID) ?: return null
        val kemPub = HybridPublicKey(
            kyberPublicKey  = unb64(KEY_KYBER_PUB)  ?: return null,
            x25519PublicKey = unb64(KEY_X25519_PUB) ?: return null
        )
        val sigPub = HybridVerifyKey(
            dilithiumPublicKey = unb64(KEY_DILITHIUM_PUB) ?: return null,
            ed25519PublicKey   = unb64(KEY_ED25519_PUB)   ?: return null
        )
        val kemPriv = HybridPrivateKey(
            kyberPrivateKey       = unb64(KEY_KYBER_PRIV)  ?: return null,
            kyberPublicKeyForSalt = kemPub.kyberPublicKey,
            x25519PrivateKey      = unb64(KEY_X25519_PRIV) ?: return null
        )
        val sigPriv = HybridSigningKey(
            dilithiumPrivateKey = unb64(KEY_DILITHIUM_PRIV) ?: return null,
            ed25519PrivateKey   = unb64(KEY_ED25519_PRIV)   ?: return null
        )
        return NodeIdentity(
            nodeId      = nodeId,
            publicPart  = NodePublicIdentity(nodeId, kemPub, sigPub),
            privatePart = NodePrivateIdentity(nodeId, kemPriv, sigPriv)
        )
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(key: String): ByteArray? =
        prefs.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    private companion object {
        const val KEY_NODE_ID        = "node_id"
        const val KEY_KYBER_PUB      = "kyber_pub"
        const val KEY_KYBER_PRIV     = "kyber_priv"
        const val KEY_X25519_PUB     = "x25519_pub"
        const val KEY_X25519_PRIV    = "x25519_priv"
        const val KEY_DILITHIUM_PUB  = "dilithium_pub"
        const val KEY_DILITHIUM_PRIV = "dilithium_priv"
        const val KEY_ED25519_PUB    = "ed25519_pub"
        const val KEY_ED25519_PRIV   = "ed25519_priv"
    }
}
