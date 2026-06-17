package com.aura.call

/**
 * Pure call-outcome logic, extracted from [CallController] so it carries no WebRTC,
 * coroutine, or Android dependency and can be unit-tested in isolation.
 *
 * Given how a call ended, it derives the status string stored in
 * `MessageEntity.callStatus` and the human-readable label shown in the chat.
 */
object CallLog {

    /**
     * Outcome status for a finished call.
     * @param isCaller     we placed the call (outgoing)
     * @param connected    media actually connected at some point (answered)
     * @param declinedByMe we tapped Decline on an incoming call
     * @param acceptedByMe we tapped Accept but media never connected
     */
    fun status(
        isCaller: Boolean,
        connected: Boolean,
        declinedByMe: Boolean,
        acceptedByMe: Boolean
    ): String = when {
        connected    -> "answered"
        isCaller     -> "no_answer"
        declinedByMe -> "declined"
        acceptedByMe -> "ended"      // we answered, but media never connected
        else         -> "missed"
    }

    /** Human-readable chat label for a call-log row. */
    fun label(isCaller: Boolean, status: String, durationMs: Long?): String = when (status) {
        "answered"  -> (if (isCaller) "Outgoing call" else "Incoming call") +
                       (durationMs?.let { " · ${formatDuration(it)}" } ?: "")
        "missed"    -> "Missed call"
        "declined"  -> "Declined call"
        "no_answer" -> "No answer"
        "ended"     -> "Call ended"
        else        -> "Call"
    }

    /** mm:ss (minutes uncapped) from a millisecond duration. */
    fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }
}
