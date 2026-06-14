package com.aura.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles the "Decline" action on the incoming-call notification (including from the
 * lock screen). Answering goes through an Activity intent instead, because accepting
 * needs to bring the call UI to the foreground; declining does not.
 */
@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {

    @Inject lateinit var callManager: CallManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DECLINE) callManager.declineCall()
    }

    companion object {
        const val ACTION_DECLINE = "com.aura.action.DECLINE_CALL"
    }
}
