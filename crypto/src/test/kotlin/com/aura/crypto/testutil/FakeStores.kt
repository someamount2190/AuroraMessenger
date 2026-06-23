package com.aura.crypto.testutil

import com.aura.crypto.KemSessionStore
import com.aura.crypto.PrekeyRecord
import com.aura.crypto.PrekeyStore

/**
 * In-memory [KemSessionStore] for tests — no database, no Android. Stores one opaque
 * per-contact ratchet blob, exactly like the real Room adapter.
 */
class FakeKemSessionStore : KemSessionStore {
    val sessions = HashMap<String, ByteArray>()

    override suspend fun load(contactNodeIdHex: String): ByteArray? = sessions[contactNodeIdHex]
    override suspend fun save(contactNodeIdHex: String, session: ByteArray) { sessions[contactNodeIdHex] = session }
    override suspend fun delete(contactNodeIdHex: String) { sessions.remove(contactNodeIdHex) }
    override suspend fun deleteAll() { sessions.clear() }
}

/** In-memory [PrekeyStore] for tests (used by the T2 PrekeyManager suite). */
class FakePrekeyStore : PrekeyStore {
    val records = LinkedHashMap<String, PrekeyRecord>()

    override suspend fun insert(prekey: PrekeyRecord) {
        records[prekey.prekeyId] = prekey
    }

    override suspend fun byId(id: String): PrekeyRecord? = records[id]

    override suspend fun currentSpk(): PrekeyRecord? =
        records.values.filter { it.kind == "spk" }.maxByOrNull { it.createdAtMs }

    override suspend fun unusedOpks(n: Int): List<PrekeyRecord> =
        records.values.filter { it.kind == "opk" }.take(n)

    override suspend fun unusedOpkCount(): Int = records.values.count { it.kind == "opk" }

    override suspend fun delete(id: String) {
        records.remove(id)
    }

    override suspend fun pruneOldSpks(cutoff: Long) {
        records.values.filter { it.kind == "spk" && it.createdAtMs < cutoff }
            .map { it.prekeyId }.forEach { records.remove(it) }
    }

    override suspend fun deleteAll() {
        records.clear()
    }
}
