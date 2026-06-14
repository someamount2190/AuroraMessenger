package com.aura.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records short voice messages to AAC/MP4. The raw recording lands in cache and
 * is read out by the caller (which encrypts it before it ever leaves the app);
 * the cache file is deleted right after.
 */
@Singleton
class VoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        if (recorder != null) return false
        return try {
            val dir = File(context.cacheDir, "voice").apply { mkdirs() }
            val file = File(dir, "rec-${System.currentTimeMillis()}.m4a")
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(64_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outputFile = file
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            cleanup()
            false
        }
    }

    /** Stop and return (recorded bytes, durationMs), or null if too short / failed. */
    fun stop(): Pair<ByteArray, Long>? {
        val rec = recorder ?: return null
        val file = outputFile
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        val result = try {
            rec.stop()
            rec.release()
            if (file != null && file.exists() && durationMs >= MIN_DURATION_MS) {
                file.readBytes() to durationMs
            } else null
        } catch (e: Exception) {
            null
        }
        recorder = null
        file?.delete()
        outputFile = null
        return result
    }

    fun cancel() {
        try { recorder?.stop(); recorder?.release() } catch (e: Exception) {}
        cleanup()
    }

    private fun cleanup() {
        recorder = null
        outputFile?.delete()
        outputFile = null
    }

    companion object {
        const val MIN_DURATION_MS = 500L
    }
}
