package com.aura.db

import com.aura.crypto.RatchetState
import com.aura.crypto.RatchetStore
import com.aura.crypto.SkippedKey

/**
 * Adapter that backs the crypto library's [RatchetStore] with Aurora's SQLCipher Room
 * database, mapping the library's platform-free records to/from Room entities. This is
 * how the standalone aura-crypto module persists without knowing about Room or Android.
 */
class RoomRatchetStore(private val dao: RatchetDao) : RatchetStore {

    override suspend fun upsertState(state: RatchetState) = dao.upsertState(
        RatchetStateEntity(
            contactNodeIdHex  = state.contactNodeIdHex,
            sendChainKeyB64   = state.sendChainKeyB64,
            sendN             = state.sendN,
            recvChainKeyB64   = state.recvChainKeyB64,
            recvN             = state.recvN,
            sasFingerprintB64 = state.sasFingerprintB64,
            mediaKeyB64       = state.mediaKeyB64
        )
    )

    override suspend fun state(nodeIdHex: String): RatchetState? =
        dao.state(nodeIdHex)?.let {
            RatchetState(
                it.contactNodeIdHex, it.sendChainKeyB64, it.sendN,
                it.recvChainKeyB64, it.recvN, it.sasFingerprintB64, it.mediaKeyB64
            )
        }

    override suspend fun putSkipped(key: SkippedKey) =
        dao.putSkipped(RatchetSkippedKeyEntity(key.contactNodeIdHex, key.n, key.messageKeyB64))

    override suspend fun skipped(nodeIdHex: String, n: Long): SkippedKey? =
        dao.skipped(nodeIdHex, n)?.let { SkippedKey(it.contactNodeIdHex, it.n, it.messageKeyB64) }

    override suspend fun deleteSkipped(nodeIdHex: String, n: Long) = dao.deleteSkipped(nodeIdHex, n)
    override suspend fun pruneSkipped(nodeIdHex: String, keep: Int) = dao.pruneSkipped(nodeIdHex, keep)
    override suspend fun deleteState(nodeIdHex: String) = dao.deleteState(nodeIdHex)
    override suspend fun deleteSkippedForContact(nodeIdHex: String) = dao.deleteSkippedForContact(nodeIdHex)
    override suspend fun deleteAllState() = dao.deleteAllState()
    override suspend fun deleteAllSkipped() = dao.deleteAllSkipped()
}
