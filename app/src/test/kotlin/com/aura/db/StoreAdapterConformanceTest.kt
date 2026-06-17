package com.aura.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.crypto.PrekeyRecord
import com.aura.crypto.PrekeyStore
import com.aura.crypto.RatchetState
import com.aura.crypto.RatchetStore
import com.aura.crypto.SkippedKey
import com.aura.crypto.testutil.FakePrekeyStore
import com.aura.crypto.testutil.FakeRatchetStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Proves the Room adapters (`RoomRatchetStore`, `RoomPrekeyStore`) honour the SAME
 * contract as the in-memory test fakes — i.e. the POJO↔entity mapping is faithful.
 * The identical assertion blocks run against both implementations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoreAdapterConformanceTest {

    private lateinit var db: AuroraDatabase

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun ratchetState(id: String) =
        RatchetState(id, "send", 3, "recv", 4, "fp", "mk")

    private suspend fun ratchetContract(store: RatchetStore) {
        store.upsertState(ratchetState("a"))
        val got = store.state("a")!!
        assertEquals(3, got.sendN); assertEquals("recv", got.recvChainKeyB64)

        store.putSkipped(SkippedKey("a", 1, "k1"))
        store.putSkipped(SkippedKey("a", 2, "k2"))
        store.putSkipped(SkippedKey("a", 3, "k3"))
        assertNotNull(store.skipped("a", 2))
        store.deleteSkipped("a", 2)
        assertNull(store.skipped("a", 2))

        store.pruneSkipped("a", keep = 1)            // keep newest (n=3)
        assertNotNull(store.skipped("a", 3))
        assertNull(store.skipped("a", 1))

        store.deleteState("a")
        assertNull(store.state("a"))
        store.deleteSkippedForContact("a")
        assertNull(store.skipped("a", 3))
    }

    private fun prekey(id: String, kind: String, created: Long) =
        PrekeyRecord(id, kind, "kp", "xp", "kpr", "xpr", created)

    private suspend fun prekeyContract(store: PrekeyStore) {
        store.insert(prekey("s1", "spk", 1))
        store.insert(prekey("s2", "spk", 2))
        assertEquals("s2", store.currentSpk()!!.prekeyId)        // newest spk

        store.insert(prekey("o1", "opk", 1))
        store.insert(prekey("o2", "opk", 2))
        assertEquals(2, store.unusedOpkCount())
        assertEquals(2, store.unusedOpks(5).size)

        store.delete("o1")
        assertNull(store.byId("o1"))
        store.deleteAll()
        assertNull(store.currentSpk())
        assertEquals(0, store.unusedOpkCount())
    }

    @Test fun ratchetStore_fake_satisfiesContract() = runBlocking { ratchetContract(FakeRatchetStore()) }

    @Test fun ratchetStore_room_satisfiesContract() = runBlocking { ratchetContract(RoomRatchetStore(db.ratchetDao())) }

    @Test fun prekeyStore_fake_satisfiesContract() = runBlocking { prekeyContract(FakePrekeyStore()) }

    @Test fun prekeyStore_room_satisfiesContract() = runBlocking { prekeyContract(RoomPrekeyStore(db.prekeyDao())) }
}
