package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.DateTimeException
import java.time.Instant

class DailyWorkTimingTest {
    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    @Test fun enablingAutomaticSubmissionAfterTenDoesNotDeferFirstCheckUntilTomorrow() {
        // 2026-09-08 15:00 KST. Previously the first run was delayed until 09-09 10:00.
        val now = at("2026-09-08T06:00:00Z")
        assertEquals(19 * 3_600_000L, DailyWorkTiming.initialDelayMillis(now, 10))
        assertEquals(0L, DailyWorkTiming.initialDelayMillis(now, 10, catchUpIfLate = true))
    }

    @Test fun earlyEnablementStillWaitsUntilTen() {
        val now = at("2026-09-08T00:30:00Z") // 09:30 KST
        assertEquals(30 * 60_000L, DailyWorkTiming.initialDelayMillis(now, 10, catchUpIfLate = true))
        assertEquals(30 * 60_000L, DailyWorkTiming.initialDelayMillis(now, 10))
    }

    @Test fun exactHourCanRunTodayRatherThanWaitingAFullDay() {
        val now = at("2026-09-08T01:00:00Z")
        assertEquals(0L, DailyWorkTiming.initialDelayMillis(now, 10, catchUpIfLate = true))
        // Ordinary reminders retain their existing next-occurrence behavior.
        assertEquals(86_400_000L, DailyWorkTiming.initialDelayMillis(now, 10))
    }

    @Test fun midnightBoundaryUsesKoreaRatherThanUtc() {
        val beforeMidnight = at("2026-09-08T14:59:59.999Z")
        val midnight = at("2026-09-08T15:00:00Z")
        assertEquals(0L, DailyWorkTiming.initialDelayMillis(beforeMidnight, 10, catchUpIfLate = true))
        assertEquals(10 * 3_600_000L, DailyWorkTiming.initialDelayMillis(midnight, 10, catchUpIfLate = true))
    }

    @Test fun minutePrecisionAndDefaultReminderBehaviorArePreserved() {
        val now = at("2026-09-08T00:29:59.500Z")
        assertEquals(500L, DailyWorkTiming.initialDelayMillis(now, 9, 30))
        assertEquals(500L, DailyWorkTiming.initialDelayMillis(now, 9, 30, true))
        assertEquals(86_399_500L, DailyWorkTiming.initialDelayMillis(now + 1000L, 9, 30))
        assertEquals(0L, DailyWorkTiming.initialDelayMillis(now + 1000L, 9, 30, true))
    }

    @Test(expected = DateTimeException::class)
    fun invalidHourIsNotSilentlyClamped() {
        DailyWorkTiming.initialDelayMillis(at("2026-09-08T00:00:00Z"), 24, catchUpIfLate = true)
    }

    @Test(expected = DateTimeException::class)
    fun invalidMinuteIsNotSilentlyClamped() {
        DailyWorkTiming.initialDelayMillis(at("2026-09-08T00:00:00Z"), 10, 60, true)
    }
}
