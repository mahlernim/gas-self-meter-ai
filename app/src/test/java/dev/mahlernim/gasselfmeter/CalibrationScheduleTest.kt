package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/**
 * The calibration check used to run on a 24-hour periodic request, which could land before the
 * chosen hour. The policy declined, and the next window was another day away.
 */
class CalibrationScheduleTest {
    // 2026-09-04 is a Friday, matching reminderDay 5.
    private val friday = LocalDate.of(2026, 9, 4)
    private val profile = Profile(reminder = true, reminderDay = 5, reminderHour = 19)
    private fun at(date: LocalDate, hour: Int) = dayStart(date) + hour * 3_600_000L
    private fun next(profile: Profile, time: Long) = ReminderPolicy.nextCalibrationRun(profile, time)

    @Test fun anEarlyRunAimsAtTheChosenHourTheSameDay() {
        // The periodic schedule waited a full day here and skipped the reminder entirely.
        assertEquals(at(friday, 19), next(profile, at(friday, 9)))
        assertEquals(at(friday, 19), next(profile, at(friday, 18)))
    }

    @Test fun onceTheHourHasPassedTheNextRepeatDayIsTargeted() {
        assertEquals(at(friday.plusDays(1), 19), next(profile, at(friday, 19)))
        assertEquals(at(friday.plusDays(1), 19), next(profile, at(friday, 20)))
        assertEquals(at(friday.plusDays(3), 19), next(profile, at(friday.plusDays(2), 20)))
    }

    @Test fun theRepeatWindowEndsAndTheCycleResumesNextWeek() {
        // Default repeat count 3 covers Friday through Monday, then waits for the next Friday.
        assertEquals(at(friday.plusDays(7), 19), next(profile, at(friday.plusDays(3), 20)))
        assertEquals(at(friday.plusDays(7), 19), next(profile, at(friday.plusDays(5), 9)))
    }

    @Test fun withoutRepeatsOnlyTheChosenWeekdayIsTargeted() {
        val once = profile.copy(reminderRepeatCount = 0)
        assertEquals(at(friday, 19), next(once, at(friday, 9)))
        assertEquals(at(friday.plusDays(7), 19), next(once, at(friday, 19)))
        assertEquals(at(friday.plusDays(7), 19), next(once, at(friday.plusDays(1), 9)))
    }

    @Test fun aRunDelayedPastMidnightLandsOnThatDaysOccurrence() {
        // Woken at 03:00 on the Saturday, the policy is not due yet, and the next run is that
        // evening rather than a day later.
        val delayed = at(friday.plusDays(1), 3)
        assertFalse(ReminderPolicy.calibrationDue(AppData(profile = profile, ready = true), delayed))
        assertEquals(at(friday.plusDays(1), 19), next(profile, delayed))
    }

    @Test fun midnightAndEndOfDayHoursStayOnTheirOwnDates() {
        val midnight = profile.copy(reminderHour = 0)
        assertEquals(at(friday, 0), next(midnight, at(friday, 0) - 1))
        assertEquals(at(friday.plusDays(1), 0), next(midnight, at(friday, 0)))
        val late = profile.copy(reminderHour = 23)
        assertEquals(at(friday, 23), next(late, at(friday, 22)))
    }

    @Test fun everyTargetIsAnOccurrenceTheUserAskedFor() {
        var time = at(friday, 0)
        repeat(40) {
            val run = next(profile, time)
            assertTrue(run > time)
            val date = dateOf(run)
            assertEquals(at(date, 19), run)
            assertTrue((date.dayOfWeek.value - profile.reminderDay + 7) % 7 <= profile.reminderRepeatCount)
            time = run
        }
    }
}
