package com.aura.db

import android.util.Base64
import com.aura.crypto.KemSessionStore

/**
 * Backs the crypto module's [KemSessionStore] with Aurora's SQLCipher Room database: the
 * serialized KEM Double Ratchet session is held as one Base64 blob per contact in `kem_ratchet`.
 */
class RoomKemSessionStore(private val dao: RatchetDao) : KemSessionStore {
    override suspend fun load(contactNodeIdHex: String): ByteArray? =
        dao.kemSession(contactNodeIdHex)?.let { Base64.decode(it, Base64.NO_WRAP) }

    override suspend fun save(contactNodeIdHex: String, session: ByteArray) =
        dao.kemUpsert(KemRatchetEntity(contactNodeIdHex, Base64.encodeToString(session, Base64.NO_WRAP)))

    override suspend fun delete(contactNodeIdHex: String) = dao.kemDelete(contactNodeIdHex)
    override suspend fun deleteAll() = dao.kemDeleteAll()
}
