package com.aura.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aura.crypto.SymmetricCipher
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T4 — app-private encrypted media at rest (Robolectric: real filesDir + pure cipher). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EncryptedMediaStoreTest {

    private val cipher = SymmetricCipher()
    private val store by lazy {
        EncryptedMediaStore(ApplicationProvider.getApplicationContext<Context>(), cipher)
    }

    @Test fun writeThenRead_roundTrips() = runBlocking {
        val key = cipher.generateKey()
        val plaintext = "SECRET_PLAINTEXT_MARKER".toByteArray()
        val path = store.writeEncrypted("m1", plaintext, key)
        assertContentEquals(plaintext, store.readDecrypted(path, key))
    }

    @Test fun onDiskBytes_containNoPlaintext() = runBlocking {
        val key = cipher.generateKey()
        val marker = "SECRET_PLAINTEXT_MARKER".toByteArray()
        val path = store.writeEncrypted("m2", marker, key)
        val raw = File(path).readBytes()
        // sealed bytes must not contain the cleartext marker
        assertFalse(raw.toList().windowed(marker.size).any { it.toByteArray().contentEquals(marker) })
    }

    @Test fun wrongKey_returnsNull() = runBlocking {
        val path = store.writeEncrypted("m3", "x".toByteArray(), cipher.generateKey())
        assertNull(store.readDecrypted(path, cipher.generateKey()))
    }

    @Test fun delete_removesFile() = runBlocking {
        val path = store.writeEncrypted("m4", "x".toByteArray(), cipher.generateKey())
        assertTrue(File(path).exists())
        store.delete(path)
        assertFalse(File(path).exists())
    }

    @Test fun wipeAll_clearsEverything() = runBlocking {
        val key = cipher.generateKey()
        val p1 = store.writeEncrypted("a", "1".toByteArray(), key)
        val p2 = store.writeEncrypted("b", "2".toByteArray(), key)
        store.wipeAll()
        assertFalse(File(p1).exists()); assertFalse(File(p2).exists())
    }

    @Test fun pathTraversalIds_areRejected_writeStaysInMediaDir() = runBlocking {
        // A peer-controlled media id must never escape the media dir (CVE-class path traversal).
        for (bad in listOf("../../evil", "../x", "a/b", "a\\b", "..", ".", "", "with space")) {
            assertFalse(store.isValidId(bad), "id '$bad' must be rejected")
            assertFailsWith<IllegalArgumentException>("id '$bad' must not produce a file") {
                store.fileFor(bad)
            }
        }
        // A real UUID (what the app actually mints) is accepted and stays under filesDir/media.
        val id = java.util.UUID.randomUUID().toString()
        assertTrue(store.isValidId(id))
        val path = store.writeEncrypted(id, "x".toByteArray(), cipher.generateKey())
        assertTrue(File(path).canonicalPath.startsWith(File(mediaDir).canonicalPath + File.separator))
    }

    private val mediaDir get() =
        File(ApplicationProvider.getApplicationContext<Context>().filesDir, "media").absolutePath
}
