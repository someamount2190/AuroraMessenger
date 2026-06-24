package com.aura.security

import com.aura.db.AuroraDatabase
import com.aura.di.IoDispatcher
import com.aura.identity.IdentityStore
import com.aura.media.EncryptedMediaStore
import com.aura.settings.AuroraSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * One-time data migrations run at startup.
 *
 * The crypto re-engineering (X-Wing / ML-DSA, new nodeId derivation, new ratchet) is a
 * deliberate clean break: a pre-FIPS identity and all of its contacts/ratchets/prekeys are
 * cryptographically dead and can never pair or message again. So on the first launch of the
 * FIPS build over a pre-FIPS install we reset that dead state to a clean slate — the user
 * re-pairs their contacts. A fresh install (no legacy identity) just records the current era.
 *
 * This only ever runs once (gated on [AuroraSettings.cryptoEra]); a normal launch returns
 * immediately without touching anything.
 */
class StartupMigrations @Inject constructor(
    private val settings: AuroraSettings,
    private val identityStore: IdentityStore,
    private val database: AuroraDatabase,
    private val mediaStore: EncryptedMediaStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun run() = withContext(ioDispatcher) {
        if (settings.cryptoEra >= CRYPTO_ERA_FIPS) return@withContext

        if (identityStore.hasLegacyIdentity()) {
            // Pre-FIPS upgrade: the legacy identity + every contact, ratchet and prekey is
            // cryptographically unusable under the new formats. Reset to a clean slate (the
            // user re-pairs); message history under dead contacts is removed with them.
            runCatching { database.contactDao().deleteAll() }
            runCatching { database.messageDao().deleteAll() }
            runCatching { database.meshPeerDao().deleteAll() }
            runCatching { database.ratchetDao().kemDeleteAll() }
            runCatching { database.prekeyDao().deleteAll() }
            runCatching { mediaStore.wipeAll() }
            runCatching { identityStore.clear() }   // forces a fresh FIPS identity next getOrCreate
        }
        settings.cryptoEra = CRYPTO_ERA_FIPS
    }

    companion object {
        /** FIPS era: X-Wing (ML-KEM-768 + X25519) KEM + ML-DSA-65 signatures. Pre-FIPS was 0. */
        const val CRYPTO_ERA_FIPS = 2
    }
}
