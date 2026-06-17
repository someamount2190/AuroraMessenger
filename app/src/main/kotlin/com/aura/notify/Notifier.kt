package com.aura.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aura.MainActivity
import com.aura.R
import com.aura.call.CallActionReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * System notifications for events that arrive while the app is backgrounded.
 *
 *  - Messages: a single, privacy-preserving "New message received" alert. No
 *    sender, no preview — the body never leaves the device in the tray.
 *  - Calls: a full-screen-intent alert that surfaces the app over whatever is in
 *    the foreground (another app, the launcher, the lock screen) so an incoming
 *    call interrupts the way a phone call should.
 *
 * Both are suppressed while the app itself is foreground, where the in-app cues
 * already handle them (see [AppForegroundTracker]).
 */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foreground: AppForegroundTracker
) {
    init { createChannels() }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when a new message arrives"
            }
        )
        // Calls channel: high importance for the full-screen ring, but silent + no
        // vibration of its own — the Ringer owns the looping ringtone and vibration,
        // so the channel must not double them up. A fresh id ("calls_v2") because an
        // already-created channel's sound/vibration can't be changed afterwards.
        mgr.createNotificationChannel(
            NotificationChannel(CH_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts for incoming calls"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun launchIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Privacy-preserving inbound-message alert: generic text only, no preview. */
    fun notifyNewMessage() {
        if (foreground.isForeground) return
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Aurora")
            .setContentText("New message received")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        safeNotify(ID_MESSAGE, n)
    }

    /**
     * Full-screen incoming-call alert with Answer / Decline actions, surfacing over
     * whatever is in the foreground (including the lock screen) the way a phone call
     * should. Answer launches the call UI and accepts; Decline ends the call via a
     * broadcast without opening the app. Deliberately generic — no contact name, so
     * nothing identifying shows on the lock screen or in the tray.
     */
    fun notifyIncomingCall() {
        if (foreground.isForeground) return
        if (!canPost()) return
        val answer = answerIntent()
        val decline = declineIntent()
        val builder = NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(answer, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // CallStyle renders the native Answer/Decline call UI (lock screen included).
            val caller = androidx.core.app.Person.Builder().setName("Aurora call").build()
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
        } else {
            builder.setContentTitle("Incoming Aurora call")
                .setContentText("Tap to answer")
                .setContentIntent(answer)
                .addAction(R.drawable.ic_notification, "Decline", decline)
                .addAction(R.drawable.ic_notification, "Answer", answer)
        }
        safeNotify(ID_CALL, builder.build())
    }

    /** Activity intent that opens the call UI and answers (carries ACTION_ANSWER_CALL). */
    private fun answerIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_ANSWER_CALL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Broadcast intent that declines without opening the app. */
    private fun declineIntent(): PendingIntent {
        val intent = Intent(context, com.aura.call.CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DECLINE
        }
        return PendingIntent.getBroadcast(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancelCall() {
        NotificationManagerCompat.from(context).cancel(ID_CALL)
    }

    /**
     * Ongoing "call in progress" notification shown for the duration of a connected
     * call. Tapping it returns to the call; the End action hangs up via a broadcast
     * without opening the app. Unlike the other alerts this is NOT suppressed while the
     * app is foreground — it's a persistent control the user expects in the shade while
     * the call runs minimized. Reuses ID_CALL so it replaces the incoming-ring alert.
     */
    fun notifyOngoingCall() {
        if (!canPost()) return
        val end = endIntent()
        // NOTE: deliberately a plain notification, NOT CallStyle.forOngoingCall.
        // A CallStyle *ongoing* notification is "disqualified" by the system unless it
        // is tied to a running foreground service; posting one anyway makes
        // NotificationManagerService throw. Because this is posted from a WebRTC
        // observer callback (native signaling thread, via JNI), that exception would
        // abort the whole process. A plain ongoing notification carries the same
        // "tap to return" + End controls without the foreground-service requirement.
        val builder = NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openCallIntent())
            .setContentTitle("Aurora call in progress")
            .setContentText("Tap to return to the call")
            .addAction(R.drawable.ic_notification, "End", end)
        safeNotify(ID_CALL, builder.build())
    }

    /**
     * Post a notification without ever throwing. The system can reject a notification
     * (disqualifying features, rate limits, revoked permission); some of ours are
     * posted from a WebRTC observer callback running on the native signaling thread,
     * where an escaping Java exception aborts the whole process via JNI. Swallow it.
     */
    private fun safeNotify(id: Int, n: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    /** Activity intent that opens the app and restores (expands) the minimized call. */
    private fun openCallIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CALL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 4, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Broadcast intent that ends an in-progress call without opening the app. */
    private fun endIntent(): PendingIntent {
        val intent = Intent(context, com.aura.call.CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_END
        }
        return PendingIntent.getBroadcast(
            context, 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** A new pairing request arrived — generic alert prompting the user to open the app. */
    fun notifyContactRequest() {
        if (foreground.isForeground) return
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Aurora")
            .setContentText("New contact request")
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        safeNotify(ID_CONTACT_REQ, n)
    }

    fun cancelContactRequest() {
        NotificationManagerCompat.from(context).cancel(ID_CONTACT_REQ)
    }

    /**
     * A peer removed us as a contact (the conversation has been deleted on both ends).
     * Generic and name-free like the other alerts, so nothing identifying shows on the
     * lock screen or in the tray; the in-app toast names the contact instead.
     */
    fun notifyContactRemoved() {
        if (foreground.isForeground) return
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Aurora")
            .setContentText("A contact removed you")
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        safeNotify(ID_CONTACT_REMOVED, n)
    }

    companion object {
        /** Intent action carried by the notification's Answer button (handled in MainActivity). */
        const val ACTION_ANSWER_CALL = "com.aura.action.ANSWER_CALL"
        /** Tapping the ongoing-call notification restores (expands) the minimized call. */
        const val ACTION_OPEN_CALL = "com.aura.action.OPEN_CALL"
        private const val CH_MESSAGES = "messages"
        // "calls_v2": the original "calls" channel shipped with a default sound that
        // can't be changed after creation; this one is silent (Ringer owns the audio).
        private const val CH_CALLS = "calls_v2"
        private const val ID_MESSAGE = 1001
        private const val ID_CALL = 1002
        private const val ID_CONTACT_REQ = 1003
        private const val ID_CONTACT_REMOVED = 1004
    }
}
