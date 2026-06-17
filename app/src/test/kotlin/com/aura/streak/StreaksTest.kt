package com.aura.streak

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class StreaksTest {

    private val utc = ZoneId.of("UTC")
    private val now = 1_700_000_000_000L          // fixed reference instant
    private val day = 86_400_000L

    private fun streak(vararg tsMs: Long) = Streaks.compute(tsMs.toList(), now, utc)

    @Test fun emptyList_isZero() = assertEquals(0, streak())

    @Test fun singleMessageToday_isOne() = assertEquals(1, streak(now))

    @Test fun threeConsecutiveDaysEndingToday_isThree() =
        assertEquals(3, streak(now, now - day, now - 2 * day))

    @Test fun aliveWhenLastActivityWasYesterday() = assertEquals(1, streak(now - day))

    @Test fun brokenWhenTwoMidnightsPassed() = assertEquals(0, streak(now - 2 * day))

    @Test fun gapBreaksTheStreak() =
        assertEquals(1, streak(now, now - 2 * day))   // today counts, -1d missing → 1

    @Test fun multipleMessagesSameDay_countOnce() =
        assertEquals(1, streak(now, now - 1000, now - 2000))

    @Test fun longRunIsCounted() {
        val ts = (0 until 10).map { now - it * day }.toLongArray()
        assertEquals(10, streak(*ts))
    }
}
