package com.aura.media

import android.content.Context
import com.aura.crypto.SymmetricCipher
import com.aura.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * App-private encrypted media storage (Phase 5).
 *
 * Media files live under filesDir/media, each encrypted at rest with the same
 * XChaCha20-Poly1305 cipher used for messages but a per-conversation media key
 * derived from the shared secret. Files never land in the public gallery unless
 * the user explicitly exports one ("Save to gallery").
 */
class EncryptedMediaStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: SymmetricCipher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mediaDir: File by lazy {
        File(context.filesDir, "media").apply { mkdirs() }
    }

    fun fileFor(messageId: String): File = File(mediaDir, "$messageId.enc")

    /** Encrypt [plaintext] bytes to the message's media file. Returns the file path. */
    suspend fun writeEncrypted(messageId: String, plaintext: ByteArray, key: ByteArray): String =
        withContext(ioDispatcher) {
            val sealed = cipher.encrypt(plaintext, key, MEDIA_AAD).getOrThrow()
            val file = fileFor(messageId)
            file.writeBytes(sealed)
            file.absolutePath
        }

    /** Store already-sealed bytes received over the wire (sender did the encryption). */
    suspend fun writeSealed(messageId: String, sealed: ByteArray): String =
        withContext(ioDispatcher) {
            val file = fileFor(messageId)
            file.writeBytes(sealed)
            file.absolutePath
        }

    /** Decrypt a media file to plaintext bytes (for preview / export). */
    suspend fun readDecrypted(path: String, key: ByteArray): ByteArray? =
        withContext(ioDispatcher) {
            val file = File(path)
            if (!file.exists()) return@withContext null
            cipher.decrypt(file.readBytes(), key, MEDIA_AAD).getOrNull()
        }

    /** Raw sealed bytes (for sending — the receiver shares the key, so re-use the seal). */
    suspend fun readSealed(path: String): ByteArray? = withContext(ioDispatcher) {
        val file = File(path)
        if (file.exists()) file.readBytes() else null
    }

    suspend fun delete(path: String) = withContext(ioDispatcher) {
        runCatching { File(path).delete() }
        Unit
    }

    /** Delete every encrypted media file (full wipe). */
    suspend fun wipeAll() = withContext(ioDispatcher) {
        runCatching { mediaDir.deleteRecursively() }
        Unit
    }

    companion object {
        // Media is sealed once with this AAD; both peers share the key so the same
        // ciphertext is stored on disk AND sent on the wire (no re-encryption).
        private val MEDIA_AAD = "aura-media-v1".toByteArray()
        const val MAX_MEDIA_BYTES = 50 * 1024 * 1024  // 50 MB per spec
    }
}
