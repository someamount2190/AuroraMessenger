package com.aura.db

import com.aura.crypto.PrekeyRecord
import com.aura.crypto.PrekeyStore

/**
 * Adapter that backs the crypto library's [PrekeyStore] with Aurora's SQLCipher Room
 * database. Maps the library's platform-free [PrekeyRecord] to/from the Room entity.
 */
class RoomPrekeyStore(private val dao: PrekeyDao) : PrekeyStore {

    private fun PrekeyEntity.toRecord() = PrekeyRecord(
        prekeyId, kind, kemPubB64, kemPrivB64, createdAtMs
    )

    override suspend fun insert(prekey: PrekeyRecord) = dao.insert(
        PrekeyEntity(
            prekeyId    = prekey.prekeyId,
            kind        = prekey.kind,
            kemPubB64   = prekey.kemPubB64,
            kemPrivB64  = prekey.kemPrivB64,
            createdAtMs = prekey.createdAtMs
        )
    )

    override suspend fun byId(id: String): PrekeyRecord? = dao.byId(id)?.toRecord()
    override suspend fun currentSpk(): PrekeyRecord? = dao.currentSpk()?.toRecord()
    override suspend fun unusedOpks(n: Int): List<PrekeyRecord> = dao.unusedOpks(n).map { it.toRecord() }
    override suspend fun unusedOpkCount(): Int = dao.unusedOpkCount()
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun pruneOldSpks(cutoff: Long) = dao.pruneOldSpks(cutoff)
    override suspend fun deleteAll() = dao.deleteAll()
}
