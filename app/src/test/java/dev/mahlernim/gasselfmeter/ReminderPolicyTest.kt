package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReminderPolicyTest {
    private val date = LocalDate.of(2026, 9, 4)
    private val time = dayStart(date) + 19 * 3_600_000L
    private val target = SelfReadTarget("cycle", "2026-09-02", "2026-09-04", true, false, null, null,
        Contract("1", "2"), "meter", "", "20260904", "", "")
    private val data = AppData(profile = Profile(meter = "meter", reminder = true, reminderDay = 5, reminderHour = 19), ready = true)

    @Test fun calibrationRepeatsOnlyConfiguredDaysAndStopsAfterPhysicalCheck() {
        assertTrue(ReminderPolicy.calibrationDue(data, time))
        assertTrue(ReminderPolicy.calibrationDue(data, time + 3 * 86_400_000L))
        assertFalse(ReminderPolicy.calibrationDue(data, time + 4 * 86_400_000L))
        assertFalse(ReminderPolicy.calibrationDue(data.copy(profile = data.profile.copy(reminderRepeatCount = 0)), time + 86_400_000L))
        assertFalse(ReminderPolicy.calibrationDue(data.copy(observations = listOf(Observation(time, 100.0, "meter"))), time + 86_400_000L))
        assertTrue(ReminderPolicy.calibrationDue(data.copy(observations = listOf(Observation(time, 100.0, "old-meter"))), time + 86_400_000L))
    }
    @Test fun deadlineAndPeriodAreDerivedFromSupplierDates() {
        assertEquals(ReminderPolicy.DEADLINE, ReminderPolicy.submissionText(data, target, time))
        assertEquals("자가검침 입력 기간이에요. 마감까지 2일 남았어요.", ReminderPolicy.submissionText(data, target, time - 2 * 86_400_000L))
        assertNull(ReminderPolicy.submissionText(data, target, time + 86_400_000L))
        assertNull(ReminderPolicy.submissionText(data, target.copy(eligible = false), time))
        assertNull(ReminderPolicy.submissionText(data, target.copy(submitted = true), time))
    }
    @Test fun uncertainResultsAskForSupplierVerificationInsteadOfAnotherSubmission() {
        val record = SubmissionRecord("cycle", target.start, target.end, 100.0, time, "uncertain", "")
        assertEquals(ReminderPolicy.UNCERTAIN, ReminderPolicy.submissionText(data.copy(submissions = listOf(record)), target, time))
        assertNull(ReminderPolicy.submissionText(data.copy(submissions = listOf(record.copy(status = "confirmed"))), target, time))
    }
}
