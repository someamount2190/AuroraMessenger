package com.aura.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aura.AppWiring
import com.aura.MainActivity
import com.aura.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The app's only always-on footprint. A low-priority foreground service that keeps
 * the process alive so [SyncEngine]'s parked-wake loop survives the Activity being
 * destroyed (BACK to launcher, screen off). Its single job is to hold one parked
 * long-poll to the rendezvous server and stay listening on the direct-message TCP
 * port — so a peer's contentless "tap" can rouse us, after which the real message
 * or call flows directly peer-to-peer. The server never holds content; this service
 * never does network work of its own beyond delegating to [SyncEngine].
 */
@AndroidEntryPoint
class WakeService : Service() {

    @Inject lateinit var appWiring: AppWiring

    override fun onCreate() {
        super.onCreate()
        goForeground()
        appWiring.ensureStarted()   // idempotent: receivers + check-in + parked-wake loop + TCP
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground + wiring in case the system recreated us (START_STICKY).
        goForeground()
        appWiring.ensureStarted()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val n = buildNotification()
        // Android 14+ requires the typed overload; the manifest declares specialUse.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_BACKGROUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Aurora")
            .setContentText("Staying reachable for messages and calls")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CH_BACKGROUND) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CH_BACKGROUND, "Background connection", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps Aurora reachable to receive messages and calls"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CH_BACKGROUND = "background"
        private const val NOTIF_ID = 1100

        /** Start the wake service (foreground-safe; call from an Activity context). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java))
        }
    }
}
