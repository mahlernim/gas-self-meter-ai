package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test

class BackgroundStateTest {
    private val pending = SubmissionRecord("cycle", "2026-09-01", "2026-09-04", 100.0, 1000L, "pending", "")
    private val account = AppData(profile = Profile(contract = "contract", meter = "meter"),
        credentials = Credentials("user", "password"), submissions = listOf(pending), ready = true)
    private val confirmed = pending.copy(status = "confirmed")

    @Test fun resultsCannotResurrectErasedOrReconnectedAccountState() {
        assertEquals(AppData(), BackgroundState.finish(AppData(), account, confirmed))
        val different = account.copy(profile = account.profile.copy(contract = "other"))
        assertEquals(different, BackgroundState.finish(different, account, confirmed))
        val reconnected = account.copy(submissions = emptyList())
        assertEquals(reconnected, BackgroundState.finish(reconnected, account, confirmed))
    }
    @Test fun resultsOnlyCompleteTheirOwnPendingAttemptAndPreserveConcurrentChanges() {
        val newer = account.copy(submissions = listOf(pending.copy(attemptedAt = 2000L)))
        assertEquals(newer, BackgroundState.finish(newer, account, confirmed))
        val changed = account.copy(profile = account.profile.copy(reminder = true), observations = listOf(Observation(5000L, 110.0, "meter")))
        val result = BackgroundState.finish(changed, account, confirmed)
        assertEquals(listOf(confirmed), result.submissions)
        assertEquals(changed.profile, result.profile)
        assertEquals(changed.observations, result.observations)
    }
    @Test fun meterResetOrCredentialRevocationInvalidatesInflightResults() {
        assertFalse(BackgroundState.sameAccount(account.copy(profile = account.profile.copy(meter = "new-meter")), account))
        assertFalse(BackgroundState.sameAccount(account.copy(credentials = null), account))
    }
}
