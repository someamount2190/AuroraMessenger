package com.aura.call

import kotlin.test.Test
import kotlin.test.assertEquals

class CallLogTest {

    // ── status() — outcome derivation ────────────────────────────────────────
    @Test fun connected_isAnswered_regardlessOfOtherFlags() {
        assertEquals("answered", CallLog.status(isCaller = true, connected = true, declinedByMe = true, acceptedByMe = true))
        assertEquals("answered", CallLog.status(isCaller = false, connected = true, declinedByMe = false, acceptedByMe = false))
    }

    @Test fun callerNotConnected_isNoAnswer() =
        assertEquals("no_answer", CallLog.status(isCaller = true, connected = false, declinedByMe = false, acceptedByMe = false))

    @Test fun calleeDeclined_isDeclined() =
        assertEquals("declined", CallLog.status(isCaller = false, connected = false, declinedByMe = true, acceptedByMe = false))

    @Test fun calleeAcceptedButNeverConnected_isEnded() =
        assertEquals("ended", CallLog.status(isCaller = false, connected = false, declinedByMe = false, acceptedByMe = true))

    @Test fun calleeNoInteraction_isMissed() =
        assertEquals("missed", CallLog.status(isCaller = false, connected = false, declinedByMe = false, acceptedByMe = false))

    // ── label() ──────────────────────────────────────────────────────────────
    @Test fun answeredLabel_includesDirectionAndDuration() {
        assertEquals("Outgoing call · 1:05", CallLog.label(isCaller = true, status = "answered", durationMs = 65_000))
        assertEquals("Incoming call · 0:30", CallLog.label(isCaller = false, status = "answered", durationMs = 30_000))
    }

    @Test fun answeredLabel_withoutDuration_hasNoSuffix() =
        assertEquals("Outgoing call", CallLog.label(isCaller = true, status = "answered", durationMs = null))

    @Test fun otherStatuses_mapToFixedLabels() {
        assertEquals("Missed call", CallLog.label(false, "missed", null))
        assertEquals("Declined call", CallLog.label(false, "declined", null))
        assertEquals("No answer", CallLog.label(true, "no_answer", null))
        assertEquals("Call ended", CallLog.label(false, "ended", null))
        assertEquals("Call", CallLog.label(false, "weird", null))
    }

    // ── formatDuration() ─────────────────────────────────────────────────────
    @Test fun formatDuration_padsSeconds_andUncapsMinutes() {
        assertEquals("0:00", CallLog.formatDuration(0))
        assertEquals("0:05", CallLog.formatDuration(5_000))
        assertEquals("1:05", CallLog.formatDuration(65_000))
        assertEquals("61:01", CallLog.formatDuration(3_661_000))
    }
}
