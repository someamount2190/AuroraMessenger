package com.aura.db

import com.aura.media.EncryptedMediaStore
import javax.inject.Inject

/**
 * Removes a contact and ALL of its residue in one place: the contact row, its
 * messages, its encrypted media files, and — crucially — its ratchet session
 * (root, chains, X-Wing ratchet keypair, skipped cache, SAS fingerprint, and the
 * media-at-rest key). Deleting just the contact row (as the old code did) left live
 * key material behind after a rejected/failed pairing. Destroying the ratchet keys is
 * a cryptographic erase of the conversation: the ciphertext that ever existed becomes
 * unrecoverable noise.
 */
class ContactEraser @Inject constructor(
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val kemRatchet: com.aura.crypto.KemRatchetManager,
    private val mediaStore: EncryptedMediaStore
) {
    suspend fun wipe(nodeIdHex: String) {
        // 1. Encrypted media files on disk.
        messageDao.mediaPathsForContact(nodeIdHex).forEach { mediaStore.delete(it) }
        // 2. Messages.
        messageDao.deleteForContact(nodeIdHex)
        // 3. Cryptographically erase the KEM ratchet session (root, chains, ratchet keypair,
        //    skipped cache, SAS fingerprint, media key) — one opaque blob.
        kemRatchet.wipe(nodeIdHex)
        // 4. The contact row itself.
        contactDao.deleteByNodeId(nodeIdHex)
    }
}
