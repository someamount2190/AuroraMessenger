package com.aura.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertTrue

/** T3 storage — in-memory Room (no SQLCipher) under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomDaoTest {

    private lateinit var db: AuroraDatabase
    private val contacts get() = db.contactDao()
    private val messages get() = db.messageDao()
    private val ratchets get() = db.ratchetDao()
    private val prekeys get() = db.prekeyDao()
    private val mesh get() = db.meshPeerDao()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun contact(id: String, name: String = "c", sent: Boolean = true, state: String = PairState.ACTIVE) =
        ContactEntity(
            nodeIdHex = id, displayName = name, kemPubB64 = "k", ed25519PubB64 = "e",
            createdAtMs = 1L, pairingSent = sent, pairState = state
        )

    private fun msg(id: String, c: String, body: String = "hi", ts: Long = 1L, status: String = "sent",
                    type: String = "text", read: Boolean = false, expires: Long? = null, media: String? = null) =
        MessageEntity(id = id, contactNodeIdHex = c, fromMe = false, body = body, timestampMs = ts,
            status = status, type = type, read = read, expiresAtMs = expires, mediaPath = media)

    // ── ContactDao ─────────────────────────────────────────────────────────────
    @Test fun contact_upsertAndRead() = runBlocking {
        contacts.upsert(contact("a"))
        assertEquals("c", contacts.byNodeId("a")!!.displayName)
        assertNull(contacts.byNodeId("missing"))
    }

    @Test fun contact_renameSetsNicknameFlag() = runBlocking {
        contacts.upsert(contact("a"))
        contacts.rename("a", "Alice")
        val c = contacts.byNodeId("a")!!
        assertEquals("Alice", c.displayName)
        assertTrue(c.nicknameSet)
    }

    @Test fun contact_verifyLifecycle() = runBlocking {
        contacts.upsert(contact("a", state = PairState.VERIFY))
        contacts.setVerify("a", iv = true, tv = false, state = PairState.VERIFY)
        contacts.incVerifyAttempts("a")
        val c = contacts.byNodeId("a")!!
        assertTrue(c.iVerified); assertTrue(!c.theyVerified); assertEquals(1, c.verifyAttempts)
    }

    @Test fun contact_filtersAndDelete() = runBlocking {
        contacts.upsert(contact("a", sent = false, state = PairState.ACTIVE))
        contacts.upsert(contact("b", sent = true, state = PairState.REQUESTED))
        assertEquals(listOf("a"), contacts.pendingPairingSends().map { it.nodeIdHex })
        assertEquals(listOf("a"), contacts.activeForBackup().map { it.nodeIdHex })
        contacts.deleteByNodeId("a")
        assertNull(contacts.byNodeId("a"))
    }

    @Test fun contact_observeAllEmits() = runBlocking {
        contacts.upsert(contact("a"))
        assertEquals(1, contacts.observeAll().first().size)
    }

    // ── MessageDao ─────────────────────────────────────────────────────────────
    @Test fun message_insertOrderingAndRead() = runBlocking {
        contacts.upsert(contact("a"))
        messages.insert(msg("m2", "a", ts = 2))
        messages.insert(msg("m1", "a", ts = 1))
        val convo = messages.observeConversation("a").first()
        assertEquals(listOf("m1", "m2"), convo.map { it.id })       // ASC by timestamp
        messages.markConversationRead("a")
        assertTrue(messages.observeConversation("a").first().all { it.read })
    }

    @Test fun message_statusAndSearch() = runBlocking {
        messages.insert(msg("m1", "a", body = "find me", status = "pending"))
        messages.setStatus("m1", "sent")
        assertEquals("sent", messages.byId("m1")!!.status)
        assertEquals(listOf("m1"), messages.searchMessages("find").map { it.id })
    }

    @Test fun message_expirySweepBoundary() = runBlocking {
        messages.insert(msg("past", "a", expires = 100))
        messages.insert(msg("future", "a", expires = 10_000))
        messages.insert(msg("never", "a", expires = null))
        assertEquals(listOf("past"), messages.expired(100).map { it.id })  // <= now
    }

    @Test fun message_deleteForContactAndMediaPaths() = runBlocking {
        messages.insert(msg("m1", "a", type = "image", media = "/enc/1"))
        messages.insert(msg("m2", "a"))
        assertEquals(listOf("/enc/1"), messages.mediaPathsForContact("a"))
        messages.deleteForContact("a")
        assertNull(messages.byId("m1"))
    }

    // ── PrekeyDao ──────────────────────────────────────────────────────────────
    @Test fun prekey_currentSpkIsNewest() = runBlocking {
        prekeys.insert(PrekeyEntity("s1", "spk", "kp", "kpr", createdAtMs = 1))
        prekeys.insert(PrekeyEntity("s2", "spk", "kp", "kpr", createdAtMs = 2))
        assertEquals("s2", prekeys.currentSpk()!!.prekeyId)
    }

    @Test fun prekey_unusedOpks() = runBlocking {
        prekeys.insert(PrekeyEntity("o1", "opk", "kp", "kpr", createdAtMs = 1))
        prekeys.insert(PrekeyEntity("o2", "opk", "kp", "kpr", createdAtMs = 2))
        prekeys.insert(PrekeyEntity("o3", "opk", "kp", "kpr", createdAtMs = 3, usedAtMs = 9))
        assertEquals(2, prekeys.unusedOpkCount())
        assertEquals(listOf("o1"), prekeys.unusedOpks(1).map { it.prekeyId })  // oldest first
        prekeys.delete("o1")
        assertNull(prekeys.byId("o1"))
    }

    // ── KEM ratchet session (Phase 5) + backup round-trip ───────────────────────
    @Test fun kemRatchet_upsert_load_backup_delete() = runBlocking {
        ratchets.kemUpsert(KemRatchetEntity("c1", "session-blob-1"))
        ratchets.kemUpsert(KemRatchetEntity("c2", "session-blob-2"))
        assertEquals("session-blob-1", ratchets.kemSession("c1"))
        assertEquals(2, ratchets.allKemForBackup().size)   // what Backups exports
        ratchets.kemUpsert(KemRatchetEntity("c1", "session-blob-1b"))   // overwrite
        assertEquals("session-blob-1b", ratchets.kemSession("c1"))
        ratchets.kemDelete("c1")
        assertNull(ratchets.kemSession("c1"))
        ratchets.kemDeleteAll()
        assertNull(ratchets.kemSession("c2"))
    }

    // ── MeshPeerDao ────────────────────────────────────────────────────────────
    @Test fun meshPeer_upsertAndCount() = runBlocking {
        mesh.upsertAll(listOf(MeshPeerEntity("1.1.1.1:80", "1.1.1.1", 80, 1, 2)))
        assertEquals(1, mesh.all().size)
        assertEquals(1, mesh.observeCount().first())
    }
}
