package com.aura.crypto.testutil

import com.aura.crypto.PrekeyRecord
import com.aura.crypto.PrekeyStore
import com.aura.crypto.RatchetState
import com.aura.crypto.RatchetStore
import com.aura.crypto.SkippedKey

/**
 * In-memory [RatchetStore] for tests — no database, no Android. Faithful to the
 * contract the real Room adapter must satisfy (see StoreAdapterConformanceTest in
 * the app androidTest set). [pruneSkipped] retains the `keep` newest counters.
 */
class FakeRatchetStore : RatchetStore {
    val states = HashMap<String, RatchetState>()
    val skips = HashMap<Pair<String, Long>, SkippedKey>()

    override suspend fun upsertState(state: RatchetState) {
        states[state.contactNodeIdHex] = state
    }

    override suspend fun state(nodeIdHex: String): RatchetState? = states[nodeIdHex]

    override suspend fun putSkipped(key: SkippedKey) {
        skips[key.contactNodeIdHex to key.n] = key
    }

    override suspend fun skipped(nodeIdHex: String, n: Long): SkippedKey? = skips[nodeIdHex to n]

    override suspend fun deleteSkipped(nodeIdHex: String, n: Long) {
        skips.remove(nodeIdHex to n)
    }

    override suspend fun pruneSkipped(nodeIdHex: String, keep: Int) {
        val forContact = skips.values.filter { it.contactNodeIdHex == nodeIdHex }
            .sortedByDescending { it.n }
        forContact.drop(keep).forEach { skips.remove(it.contactNodeIdHex to it.n) }
    }

    override suspend fun deleteState(nodeIdHex: String) {
        states.remove(nodeIdHex)
    }

    override suspend fun deleteSkippedForContact(nodeIdHex: String) {
        skips.keys.filter { it.first == nodeIdHex }.toList().forEach { skips.remove(it) }
    }

    override suspend fun deleteAllState() {
        states.clear()
    }

    override suspend fun deleteAllSkipped() {
        skips.clear()
    }

    fun skippedCountFor(nodeIdHex: String): Int =
        skips.keys.count { it.first == nodeIdHex }
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
