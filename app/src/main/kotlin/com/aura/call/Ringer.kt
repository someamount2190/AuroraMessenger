package com.aura.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Plays the system ringtone (looping) and vibrates while an incoming call rings,
 * honouring the phone's ringer mode — silent rings nothing, vibrate buzzes only.
 * Started when an offer arrives, stopped the instant the call is answered,
 * declined, connected, or torn down. Owns call audio so the call notification
 * channel itself stays silent (no double sound).
 */
class Ringer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var ringtone: Ringtone? = null

    @Synchronized
    fun start() {
        stop()  // never stack two ringers
        val mode = context.getSystemService(AudioManager::class.java)?.ringerMode
            ?: AudioManager.RINGER_MODE_NORMAL
        if (mode != AudioManager.RINGER_MODE_SILENT) startVibration()
        if (mode == AudioManager.RINGER_MODE_NORMAL) startTone()
    }

    @Synchronized
    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator()?.cancel() }
    }

    private fun startTone() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        val rt = RingtoneManager.getRingtone(context, uri) ?: return
        rt.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) rt.isLooping = true
        ringtone = rt
        runCatching { rt.play() }
    }

    private fun startVibration() {
        val vib = vibrator() ?: return
        // wait, buzz, gap — repeated from index 0 until cancelled.
        val pattern = longArrayOf(0, 800, 1000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION") vib.vibrate(pattern, 0)
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }
}
