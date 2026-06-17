package com.aura.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    // While a call is live we add the microphone (and camera, for video) FGS type so the
    // OS keeps mic/camera and call-grade priority alive after the Activity is gone. Kept
    // as fields so a START_STICKY restart re-asserts the correct type.
    private var callActive = false
    private var callVideo = false

    override fun onCreate() {
        super.onCreate()
        goForeground()
        appWiring.ensureStarted()   // idempotent: receivers + check-in + parked-wake loop + TCP
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A call-state command toggles the mic/camera FGS type; any other start just
        // re-asserts foreground + wiring (e.g. START_STICKY recreation).
        if (intent?.action == ACTION_CALL_STATE) {
            callActive = intent.getBooleanExtra(EXTRA_CALL_ACTIVE, false)
            callVideo = intent.getBooleanExtra(EXTRA_VIDEO, false)
        }
        goForeground()
        appWiring.ensureStarted()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        // Typed overload is available since API 23; minSdk is 29, so always use it so the
        // service's effective FGS type tracks whether a call is live.
        startForeground(NOTIF_ID, buildNotification(), foregroundTypes())
    }

    /** specialUse always; microphone (+camera for video) added while a call is live. */
    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (callActive) {
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (callVideo && hasPermission(Manifest.permission.CAMERA)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        return types
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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
        private const val ACTION_CALL_STATE = "com.aura.service.action.CALL_STATE"
        private const val EXTRA_CALL_ACTIVE = "call_active"
        private const val EXTRA_VIDEO = "video"

        /** Start the wake service (foreground-safe; call from an Activity context). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java))
        }

        /**
         * Tell the wake service a call started/ended so it can add or drop the
         * microphone/camera FGS type. Must be called while the app is foreground when
         * activating (a call always starts/answers from the foreground), so adding the
         * mic type is permitted; deactivating just re-asserts the already-running service.
         */
        fun setCallActive(context: Context, active: Boolean, video: Boolean) {
            val intent = Intent(context, WakeService::class.java).apply {
                action = ACTION_CALL_STATE
                putExtra(EXTRA_CALL_ACTIVE, active)
                putExtra(EXTRA_VIDEO, video)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
