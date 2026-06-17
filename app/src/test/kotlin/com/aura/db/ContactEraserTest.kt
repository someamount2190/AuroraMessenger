package com.aura.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.crypto.Hkdf
import com.aura.crypto.RatchetManager
import com.aura.crypto.SymmetricCipher
import com.aura.media.EncryptedMediaStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T4 security — cryptographic erase. After wipe, the contact row, its messages, its
 * media file, AND its ratchet keys must all be gone (so prior ciphertext is noise).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContactEraserTest {

    private lateinit var db: AuroraDatabase
    private val cipher = SymmetricCipher()
    private lateinit var ratchet: RatchetManager
    private lateinit var media: EncryptedMediaStore
    private lateinit var eraser: ContactEraser

    private val node = "deadbeef"
    private val peer = "ffffffff"

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AuroraDatabase::class.java).allowMainThreadQueries().build()
        ratchet = RatchetManager(RoomRatchetStore(db.ratchetDao()), Hkdf(), cipher)
        media = EncryptedMediaStore(ctx, cipher)
        eraser = ContactEraser(db.contactDao(), db.messageDao(), ratchet, media)

        db.contactDao().upsert(
            ContactEntity(node, "Alice", "k", "e", createdAtMs = 1, pairingSent = true)
        )
        val mediaPath = media.writeEncrypted("msg-media", "video-bytes".toByteArray(), cipher.generateKey())
        db.messageDao().insert(
            MessageEntity("msg-media", node, fromMe = false, body = "", timestampMs = 1,
                status = "sent", type = "video", mediaPath = mediaPath)
        )
        db.messageDao().insert(
            MessageEntity("msg-text", node, fromMe = false, body = "hi", timestampMs = 2, status = "sent")
        )
        ratchet.seedFromSharedSecret(node, node, peer, ByteArray(32) { 5 })
    }

    @After fun tearDown() = db.close()

    @Test fun wipe_removesEverythingForContact() = runBlocking {
        val mediaPath = db.messageDao().mediaPathsForContact(node).single()
        assertTrue(File(mediaPath).exists())
        assertTrue(ratchet.isSeeded(node))

        eraser.wipe(node)

        assertNull(db.contactDao().byNodeId(node), "contact row gone")
        assertNull(db.messageDao().byId("msg-text"), "messages gone")
        assertNull(db.messageDao().byId("msg-media"), "media message gone")
        assertFalse(File(mediaPath).exists(), "encrypted media file deleted")
        assertFalse(ratchet.isSeeded(node), "ratchet cryptographically erased")
        assertNull(db.ratchetDao().state(node), "ratchet state row gone")
    }

    @Test fun wipe_isCryptographicErase_priorFramesUnreadable() = runBlocking {
        // seal a frame for the contact, then wipe — the chain key is destroyed, so even
        // with the ciphertext in hand the conversation can no longer be opened.
        val sealed = ratchet.sealNext(node, "secret".toByteArray(), "aad".toByteArray())!!
        eraser.wipe(node)
        assertNull(ratchet.open(node, sealed.n, sealed.bytes, "aad".toByteArray()))
    }
}
