package com.aura.crypto.testutil

import com.aura.crypto.PrekeyRecord
import com.aura.crypto.PrekeyStore
import com.aura.crypto.RatchetState
import com.aura.crypto.RatchetStore
import com.aura.crypto.SkippedKey

/**
 * App-test copy of the in-memory store fakes (the crypto module's test source set is
 * not visible across modules). Used by StoreAdapterConformanceTest to run the same
 * contract against these and the Room-backed adapters.
 */
class FakeRatchetStore : RatchetStore {
    val states = HashMap<String, RatchetState>()
    val skips = HashMap<Pair<String, Long>, SkippedKey>()

    override suspend fun upsertState(state: RatchetState) { states[state.contactNodeIdHex] = state }
    override suspend fun state(nodeIdHex: String): RatchetState? = states[nodeIdHex]
    override suspend fun putSkipped(key: SkippedKey) { skips[key.contactNodeIdHex to key.n] = key }
    override suspend fun skipped(nodeIdHex: String, n: Long): SkippedKey? = skips[nodeIdHex to n]
    override suspend fun deleteSkipped(nodeIdHex: String, n: Long) { skips.remove(nodeIdHex to n) }
    override suspend fun pruneSkipped(nodeIdHex: String, keep: Int) {
        skips.values.filter { it.contactNodeIdHex == nodeIdHex }
            .sortedByDescending { it.n }.drop(keep)
            .forEach { skips.remove(it.contactNodeIdHex to it.n) }
    }
    override suspend fun deleteState(nodeIdHex: String) { states.remove(nodeIdHex) }
    override suspend fun deleteSkippedForContact(nodeIdHex: String) {
        skips.keys.filter { it.first == nodeIdHex }.toList().forEach { skips.remove(it) }
    }
    override suspend fun deleteAllState() { states.clear() }
    override suspend fun deleteAllSkipped() { skips.clear() }
}

class FakePrekeyStore : PrekeyStore {
    val records = LinkedHashMap<String, PrekeyRecord>()

    override suspend fun insert(prekey: PrekeyRecord) { records[prekey.prekeyId] = prekey }
    override suspend fun byId(id: String): PrekeyRecord? = records[id]
    override suspend fun currentSpk(): PrekeyRecord? =
        records.values.filter { it.kind == "spk" }.maxByOrNull { it.createdAtMs }
    override suspend fun unusedOpks(n: Int): List<PrekeyRecord> =
        records.values.filter { it.kind == "opk" }.sortedBy { it.createdAtMs }.take(n)
    override suspend fun unusedOpkCount(): Int = records.values.count { it.kind == "opk" }
    override suspend fun delete(id: String) { records.remove(id) }
    override suspend fun pruneOldSpks(cutoff: Long) {
        records.values.filter { it.kind == "spk" && it.createdAtMs < cutoff }
            .map { it.prekeyId }.forEach { records.remove(it) }
    }
    override suspend fun deleteAll() { records.clear() }
}
