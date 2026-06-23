package com.aura.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.crypto.KemSessionStore
import com.aura.crypto.PrekeyRecord
import com.aura.crypto.PrekeyStore
import com.aura.crypto.testutil.FakeKemSessionStore
import com.aura.crypto.testutil.FakePrekeyStore
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
 * Proves the Room adapters (`RoomKemSessionStore`, `RoomPrekeyStore`) honour the SAME
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

    private suspend fun kemSessionContract(store: KemSessionStore) {
        val blobA = ByteArray(64) { it.toByte() }
        val blobB = ByteArray(8) { 0x7F }
        store.save("a", blobA)
        store.save("b", blobB)
        assertNotNull(store.load("a"))
        assertEquals(blobA.toList(), store.load("a")!!.toList())   // exact bytes round-trip

        store.save("a", blobB)                                     // upsert replaces
        assertEquals(blobB.toList(), store.load("a")!!.toList())

        store.delete("a")
        assertNull(store.load("a"))
        assertNotNull(store.load("b"))                             // unrelated row untouched

        store.deleteAll()
        assertNull(store.load("b"))
    }

    private fun prekey(id: String, kind: String, created: Long) =
        PrekeyRecord(id, kind, "kp", "kpr", created)

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

    @Test fun kemSessionStore_fake_satisfiesContract() = runBlocking { kemSessionContract(FakeKemSessionStore()) }

    @Test fun kemSessionStore_room_satisfiesContract() = runBlocking { kemSessionContract(RoomKemSessionStore(db.ratchetDao())) }

    @Test fun prekeyStore_fake_satisfiesContract() = runBlocking { prekeyContract(FakePrekeyStore()) }

    @Test fun prekeyStore_room_satisfiesContract() = runBlocking { prekeyContract(RoomPrekeyStore(db.prekeyDao())) }
}
