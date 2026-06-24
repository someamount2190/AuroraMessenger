package com.aura.security

import android.content.Context
import com.aura.db.AuroraDatabase
import com.aura.db.ContactDao
import com.aura.db.DbKeyStore
import com.aura.db.MeshPeerDao
import com.aura.db.MessageDao
import com.aura.db.PrekeyDao
import com.aura.db.RatchetDao
import com.aura.identity.IdentityStore
import com.aura.media.EncryptedMediaStore
import com.aura.settings.AuroraSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * T4 security — full erase. `wipeEverything` must invoke every destructive collaborator, and
 * because each step is independently `runCatching`-wrapped, a failure in one must NOT abort the
 * rest (a partial wipe that left key material behind would be a real confidentiality failure).
 */
class SecureWipeTest {

    private val contactDao = mockk<ContactDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val meshDao = mockk<MeshPeerDao>(relaxed = true)
    private val ratchetDao = mockk<RatchetDao>(relaxed = true)
    private val prekeyDao = mockk<PrekeyDao>(relaxed = true)
    private val db = mockk<AuroraDatabase>(relaxed = true) {
        every { contactDao() } returns contactDao
        every { messageDao() } returns messageDao
        every { meshPeerDao() } returns meshDao
        every { ratchetDao() } returns ratchetDao
        every { prekeyDao() } returns prekeyDao
    }
    private val identity = mockk<IdentityStore>(relaxed = true)
    private val dbKey = mockk<DbKeyStore>(relaxed = true)
    private val media = mockk<EncryptedMediaStore>(relaxed = true)
    private val settings = mockk<AuroraSettings>(relaxed = true)
    private val appLock = mockk<AppLock>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private fun wiper() = SecureWipe(context, db, identity, dbKey, media, settings, appLock, Dispatchers.Unconfined)

    @Test fun wipeEverything_invokesEveryCollaborator() = runBlocking {
        wiper().wipeEverything()

        coVerify { contactDao.deleteAll() }
        coVerify { messageDao.deleteAll() }
        coVerify { meshDao.deleteAll() }
        coVerify { ratchetDao.kemDeleteAll() }
        coVerify { prekeyDao.deleteAll() }
        coVerify { media.wipeAll() }
        coVerify { appLock.disableLock() }
        coVerify { identity.clear() }
        coVerify { settings.clearAll() }
        coVerify { dbKey.clear() }
        coVerify { db.close() }
        coVerify { context.deleteDatabase("aura.db") }
        coVerify { context.deleteSharedPreferences("aura_identity") }
        coVerify { context.deleteSharedPreferences("aura_lock") }
        coVerify { context.deleteSharedPreferences("aura_dbkey") }
    }

    @Test fun oneFailingStep_doesNotAbortTheRest() = runBlocking {
        coEvery { contactDao.deleteAll() } throws RuntimeException("db blocked")

        wiper().wipeEverything()        // must not throw

        // The independent later steps (key destruction — the part that makes the wipe real) still run.
        coVerify { media.wipeAll() }
        coVerify { identity.clear() }
        coVerify { settings.clearAll() }
        coVerify { dbKey.clear() }
        coVerify { context.deleteSharedPreferences("aura_identity") }
    }
}
