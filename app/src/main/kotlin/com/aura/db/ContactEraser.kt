package com.aura.db

import com.aura.crypto.RatchetManager
import com.aura.media.MediaStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes a contact and ALL of its residue in one place: the contact row, its
 * messages, its encrypted media files, and — crucially — its ratchet state
 * (chain keys + SAS fingerprint + skipped keys). Deleting just the contact row
 * (as the old code did) left live key material behind after a rejected/failed
 * pairing. Destroying the ratchet keys is a cryptographic erase of the
 * conversation: the ciphertext that ever existed becomes unrecoverable noise.
 */
@Singleton
class ContactEraser @Inject constructor(
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val ratchet: RatchetManager,
    private val mediaStore: MediaStore
) {
    suspend fun wipe(nodeIdHex: String) {
        // 1. Encrypted media files on disk.
        messageDao.mediaPathsForContact(nodeIdHex).forEach { mediaStore.delete(it) }
        // 2. Messages.
        messageDao.deleteForContact(nodeIdHex)
        // 3. Cryptographically erase the ratchet (chain keys + SAS fp + skipped keys).
        ratchet.wipe(nodeIdHex)
        // 4. The contact row itself.
        contactDao.deleteByNodeId(nodeIdHex)
    }
}
