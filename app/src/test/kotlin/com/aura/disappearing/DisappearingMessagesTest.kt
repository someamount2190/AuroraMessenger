package com.aura.disappearing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.db.AuroraDatabase
import com.aura.db.ContactEntity
import com.aura.db.MessageEntity
import com.aura.media.EncryptedMediaStore
import com.aura.settings.DisappearingTimer
import com.aura.transport.MessageSender
import com.aura.transport.TcpMessageServer
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Disappearing messages — expiry stamping and the purge sweep. A purge that forgot to delete the
 * media file would leave "disappeared" content on disk; stamping from the wrong timer would leak
 * (no expiry) or destroy (early expiry) messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DisappearingMessagesTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java).allowMainThreadQueries().build()
    private val media = mockk<EncryptedMediaStore>(relaxed = true)
    private val sender = mockk<MessageSender>(relaxed = true)
    private val tcp = mockk<TcpMessageServer>(relaxed = true)

    private val node = "cd".repeat(32)

    private fun dm() = DisappearingMessages(db.contactDao(), db.messageDao(), media, sender, tcp, Dispatchers.Unconfined)

    @AfterTest fun tearDown() = db.close()

    private suspend fun contactWithTimer(timer: DisappearingTimer) {
        db.contactDao().upsert(ContactEntity(node, "A", "k", "e", createdAtMs = 1, pairingSent = true,
            disappearingTimer = timer.key))
    }

    @Test fun stampExpiry_setsExpiryWhenTimerOn(): Unit = runBlocking {
        contactWithTimer(DisappearingTimer.ONE_HOUR)
        db.messageDao().insert(MessageEntity("m1", node, fromMe = false, body = "hi", timestampMs = 1, status = "delivered"))
        dm().stampExpiryIfNeeded(db.messageDao().byId("m1")!!)
        assertNotNull(db.messageDao().byId("m1")!!.expiresAtMs, "a message under an active timer must get an expiry")
    }

    @Test fun stampExpiry_noExpiryWhenTimerOff(): Unit = runBlocking {
        contactWithTimer(DisappearingTimer.OFF)
        db.messageDao().insert(MessageEntity("m1", node, fromMe = false, body = "hi", timestampMs = 1, status = "delivered"))
        dm().stampExpiryIfNeeded(db.messageDao().byId("m1")!!)
        assertNull(db.messageDao().byId("m1")!!.expiresAtMs, "timer OFF must not stamp an expiry")
    }

    @Test fun purgeExpired_deletesExpiredRowsAndMedia_keepsLive(): Unit = runBlocking {
        contactWithTimer(DisappearingTimer.ONE_HOUR)
        val past = System.currentTimeMillis() - 1_000
        db.messageDao().insert(MessageEntity("expired", node, fromMe = false, body = "", timestampMs = 1,
            status = "delivered", type = "image", mediaPath = "/enc/old", expiresAtMs = past))
        db.messageDao().insert(MessageEntity("live", node, fromMe = false, body = "stay", timestampMs = 2,
            status = "delivered"))   // no expiry

        dm().purgeExpired()

        assertNull(db.messageDao().byId("expired"), "expired row must be purged")
        assertNotNull(db.messageDao().byId("live"), "non-expiring row must survive")
        coVerify { media.delete("/enc/old") }     // the disappeared message's media file is deleted
    }

    @Test fun setTimer_persistsAndNotifiesPeer(): Unit = runBlocking {
        contactWithTimer(DisappearingTimer.OFF)
        dm().setTimer(node, DisappearingTimer.ONE_DAY)
        coVerify { sender.sendControl(any(), any()) }   // peer is told the new default
    }
}
