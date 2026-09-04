package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class GasappBackgroundTest {
    private val account = GasappAccount("1", "customer", "contract", "test", "")
    private val connection = GasappConnection(GasappSession("token", "member", "device"), account)
    private val data = AppData(profile = Profile(providerId = "seoul", contract = account.key, meter = "meter"), gasappConnection = connection, ready = true)
    private val time = dayStart(LocalDate.of(2026, 9, 4)) + 12 * 3_600_000L
    private fun target(submitted: Boolean = false) = GasappTarget(account, true, true, "2026-09-02", "2026-09-04", "meter", 100.0, 5, false, false, submitted, if (submitted) 110.0 else null)

    @Test fun supplierSubmissionStopsReminderAndDeadlineUsesSupplierPeriod() {
        assertNull(GasappBackground.reminderText(data, target(true), time))
        assertEquals(ReminderPolicy.DEADLINE, GasappBackground.reminderText(data, target(), time))
        assertEquals("자가검침 입력 기간이에요. 마감까지 2일 남았어요.", GasappBackground.reminderText(data, target(), time - 2 * 86_400_000L))
        assertNull(GasappBackground.reminderText(data, target(), time + 86_400_000L))
    }
    @Test fun uncertainAndConfirmedRecordsNeverAskForDuplicateSubmission() {
        val target = target()
        val uncertain = SubmissionRecord(target.cycle, target.start!!, target.end!!, 110.0, time, "uncertain", "")
        assertEquals(ReminderPolicy.UNCERTAIN, GasappBackground.reminderText(data.copy(submissions = listOf(uncertain)), target, time))
        assertNull(GasappBackground.reminderText(data.copy(submissions = listOf(uncertain.copy(status = "confirmed"))), target, time))
    }
    @Test fun identityIsStableAcrossStorageDecodeButRejectsRevokedSessions() {
        val decodedEquivalent = data.copy(gasappConnection = GasappConnection(GasappSession("token", "member", "device"), account))
        assertTrue(BackgroundState.sameAccount(decodedEquivalent, data))
        assertFalse(BackgroundState.sameAccount(data.copy(gasappConnection = null), data))
        assertFalse(BackgroundState.sameAccount(data.copy(gasappConnection = GasappConnection(GasappSession("new", "member", "device"), account)), data))
    }
}

