package com.aura.security

import android.content.Context
import com.aura.db.AuroraDatabase
import com.aura.db.DbKeyManager
import com.aura.identity.IdentityManager
import com.aura.media.MediaStore
import com.aura.settings.AuroraSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full cryptographic erase of the device's Aurora data.
 *
 * The strong primitive here is **key destruction**, not file overwriting. Flash
 * storage remaps and wear-levels, so scrubbing bytes is unreliable — but every
 * sensitive thing on disk is encrypted, so destroying the keys (the SQLCipher DB
 * key and the Keystore-backed identity/lock vaults) turns the remaining ciphertext
 * into unrecoverable noise instantly. We drop rows and delete files too, but it's
 * the keyless ciphertext that makes the wipe real.
 *
 * Call [exitProcess] immediately after [wipeEverything] so no stale in-memory key
 * or database handle survives; the next launch starts from a clean identity.
 */
@Singleton
class SecureWipe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AuroraDatabase,
    private val identityManager: IdentityManager,
    private val dbKeyManager: DbKeyManager,
    private val mediaStore: MediaStore,
    private val settings: AuroraSettings,
    private val appLockManager: AppLockManager
) {
    suspend fun wipeEverything() = withContext(Dispatchers.IO) {
        // 1. Drop all rows (logical wipe even if the file delete below is blocked).
        runCatching {
            database.contactDao().deleteAll()
            database.messageDao().deleteAll()
            database.meshPeerDao().deleteAll()
            database.ratchetDao().deleteAllSkipped()
            database.ratchetDao().deleteAllState()
            database.prekeyDao().deleteAll()
        }
        // 2. Encrypted media files.
        runCatching { mediaStore.wipeAll() }
        // 3. App-layer key vaults: lock PINs, identity keys, settings.
        runCatching { appLockManager.disableLock() }
        runCatching { identityManager.clear() }
        runCatching { settings.clearAll() }
        // 4. SQLCipher key, then close + delete the now-keyless database file.
        runCatching { dbKeyManager.clear() }
        runCatching { database.close() }
        runCatching { context.deleteDatabase("aura.db") }
        // 5. Remove the encrypted backing stores entirely.
        listOf("aura_identity", "aura_lock", "aura_dbkey").forEach {
            runCatching { context.deleteSharedPreferences(it) }
        }
    }

    /** Hard-stop so nothing half-wiped keeps running; relaunch is a fresh install. */
    fun exitProcess() {
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
