package com.aura.streak

import java.time.Instant
import java.time.ZoneId

/**
 * Conversation streaks, computed entirely on-device from message timestamps —
 * nothing about who talks to whom or how often ever leaves the phone.
 *
 * A day "counts" when the conversation had at least one message that calendar day
 * (local time). The streak is the run of consecutive counting days ending today,
 * or yesterday (so a streak stays alive through the current day until midnight).
 * It breaks once two midnights pass with no messages.
 */
object Streaks {
    /** Only surface the flame once a streak is meaningfully established. */
    const val MIN_DISPLAY_DAYS = 3

    fun compute(
        timestampsMs: List<Long>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Int {
        if (timestampsMs.isEmpty()) return 0
        val days = HashSet<Long>(timestampsMs.size)
        for (ts in timestampsMs) {
            days.add(Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().toEpochDay())
        }
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay()
        // The streak is "alive" only if there was activity today or yesterday.
        val anchor = when {
            days.contains(today)     -> today
            days.contains(today - 1) -> today - 1
            else                     -> return 0
        }
        var streak = 0
        var day = anchor
        while (days.contains(day)) { streak++; day-- }
        return streak
    }
}
