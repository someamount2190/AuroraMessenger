package com.aura.media

import android.media.MediaDataSource

/**
 * An in-memory [MediaDataSource] over an already-decrypted media blob, so in-app
 * video playback and thumbnail extraction never write plaintext to disk — the bytes
 * stay in RAM and are released with the owning player/retriever. The conversation
 * window is FLAG_SECURE, so the rendered frames are screenshot-blocked too.
 *
 * Requires API 23+ ([MediaPlayer.setDataSource]/[MediaMetadataRetriever.setDataSource]
 * with a [MediaDataSource]); the app's minSdk is 29, so this is always available.
 */
class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {

    override fun getSize(): Long = data.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val count = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun close() { /* nothing to release; the array is GC'd with this instance */ }
}
