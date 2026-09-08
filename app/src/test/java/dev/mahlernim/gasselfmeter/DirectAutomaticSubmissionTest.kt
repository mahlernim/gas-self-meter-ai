package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DirectAutomaticSubmissionTest {
    private val ids = listOf("daesung", "daesungclean", "haeyang")
    private val day = LocalDate.of(2026, 9, 8)
    private val time = dayStart(day) + 10 * 3_600_000L
    private val contract = DirectContract("test-contract", "합성 계약", "test-meter")
    private val credentials = Credentials("test-user", "test-password")
    private fun target(id: String) = SelfReadTarget("$id-test-cycle", "2026-09-01", day.toString(), true, false, null, 90.0,
        Contract(contract.id, id, contract.label), "test-order", "", "", "", DirectIdentity.meter(id, contract.id, contract.meterId))
    private fun data(id: String): AppData {
        val target = target(id)
        return AppData(profile = Profile(id, target.installation, DirectIdentity.contract(id, contract.id), customerNumber = contract.id),
            credentials = credentials, ready = true, cachedSelfRead = target,
            observations = listOf(Observation(time - 8 * 86_400_000L, 100.0, target.installation), Observation(time - 86_400_000L, 107.0, target.installation)),
            submissionSettings = SubmissionSettings(automatic = true))
    }
    private fun allowed(data: AppData, target: SelfReadTarget?, at: Long = time) = DirectSubmissionPolicy.decide(data, target, at, true).allowed

    @Test fun allThreeProvidersSupportExplicitOptInOnly() {
        assertFalse(SubmissionSettings().automatic)
        ids.forEach { id ->
            val data = data(id)
            assertTrue(id, Providers.get(id).automaticSubmission)
            assertTrue(id, allowed(data, target(id)))
            assertFalse(id, allowed(data.copy(submissionSettings = SubmissionSettings()), target(id)))
            assertFalse(id, allowed(data.copy(credentials = null), target(id)))
            assertFalse(id, allowed(data.copy(ready = false), target(id)))
            assertFalse(id, allowed(data.copy(profile = data.profile.copy(reconnectRequired = true)), target(id)))
        }
    }

    @Test fun deadlineAndFuturePhysicalCheckUseKoreanCalendar() {
        ids.forEach { id ->
            val data = data(id)
            val target = target(id)
            assertFalse(id, allowed(data, target, dayStart(day) - 1))
            assertTrue(id, allowed(data, target, dayStart(day)))
            assertTrue(id, allowed(data, target, dayStart(day.plusDays(1)) - 1))
            assertFalse(id, allowed(data, target, dayStart(day.plusDays(1))))
            val future = data.copy(observations = data.observations + Observation(time + 1, 108.0, data.profile.meter))
            assertFalse(id, allowed(future, target))
        }
    }

    @Test fun missingOrChangedRuntimeTargetCannotBeSubmitted() {
        ids.forEach { id ->
            val data = data(id)
            val target = target(id)
            assertFalse(id, allowed(data, null))
            listOf(target.copy(eligible = false), target.copy(submitted = true), target.copy(previousValue = null),
                target.copy(previousValue = Double.NaN), target.copy(previousValue = -1.0), target.copy(previousValue = 999.0),
                target.copy(installation = "replacement"), target.copy(contract = target.contract.copy(bp = "other")),
                target.copy(contract = target.contract.copy(ca = "other"))).forEach { assertFalse(id, allowed(data, it)) }
            assertFalse(id, allowed(data.copy(observations = emptyList()), target))
        }
    }

    @Test fun recentCheckLimitIsEnforcedAndCanOnlyBeRelaxedByTheSavedSetting() {
        ids.forEach { id ->
            val original = data(id)
            val stale = original.copy(observations = listOf(
                Observation(time - 15 * 86_400_000L, 93.0, original.profile.meter),
                Observation(time - 8 * 86_400_000L, 100.0, original.profile.meter)))
            assertFalse(id, allowed(stale, target(id)))
            assertTrue(id, allowed(stale.copy(submissionSettings = stale.submissionSettings.copy(requireRecentCheck = false)), target(id)))
        }
    }

    @Test fun everyPreviousAttemptBlocksAutomaticReplayAfterCodecReload() {
        ids.forEach { id -> listOf("pending", "uncertain", "confirmed", "rejected").forEach { status ->
            val target = target(id)
            val record = SubmissionRecord(target.cycle, target.start, target.end, 108.0, time - 1000, status, "synthetic")
            val restored = DataCodec.decode(DataCodec.encode(data(id).copy(submissions = listOf(record)), true), true)
            assertFalse("$id/$status", allowed(restored, target))
        } }
    }

    @Test fun sameAccountRefreshPreservesOptInButChangedMeterOrCredentialsDoNot() {
        ids.forEach { id ->
            val original = data(id)
            val snapshot = DirectSnapshot(contract, emptyList(), target(id))
            assertTrue(id, DirectProviderBridge.merge(original, snapshot, id, credentials, time).submissionSettings.automatic)
            assertFalse(id, DirectProviderBridge.merge(original.copy(submissionSettings = SubmissionSettings()), snapshot, id, credentials, time).submissionSettings.automatic)
            assertFalse(id, DirectProviderBridge.merge(original, snapshot, id, Credentials("new-user", "new-password"), time).submissionSettings.automatic)
            val replacement = contract.copy(meterId = "replacement")
            val changed = snapshot.copy(contract = replacement, target = target(id).copy(installation = DirectIdentity.meter(id, replacement.id, replacement.meterId)))
            assertFalse(id, DirectProviderBridge.merge(original, changed, id, credentials, time).submissionSettings.automatic)
            val portable = DataCodec.decode(DataCodec.encode(original))
            assertFalse(id, portable.submissionSettings.automatic)
            assertNull(portable.credentials)
        }
    }

    @Test fun networkBoundaryChecksConsentRecordOwnershipAndChangedReadingAgain() {
        ids.forEach { id ->
            val original = data(id)
            val target = target(id)
            val value = DirectSubmissionPolicy.decide(original, target, time, true).value!!
            val record = SubmissionRecord(target.cycle, target.start, target.end, value, time, "pending", "synthetic")
            val pending = original.copy(submissions = listOf(record))
            fun check(latest: AppData) = DirectSubmissionPolicy.canSendPending(latest, original, target, record, true, time)
            assertTrue(id, check(pending))
            assertFalse(id, check(original))
            assertFalse(id, check(pending.copy(submissionSettings = SubmissionSettings())))
            assertFalse(id, check(pending.copy(profile = pending.profile.copy(reconnectRequired = true))))
            assertFalse(id, check(pending.copy(credentials = null)))
            assertFalse(id, check(pending.copy(submissions = listOf(record.copy(attemptedAt = time + 1)))))
            assertFalse(id, check(pending.copy(submissions = listOf(record, record))))
            assertFalse(id, check(pending.copy(observations = pending.observations + Observation(time, 120.0, pending.profile.meter))))
        }
    }

    @Test fun manualSameDayFirstCheckStillWorksWithoutForecastHistory() {
        ids.forEach { id ->
            val original = data(id)
            val single = original.copy(observations = listOf(Observation(time, 110.9, original.profile.meter)), submissionSettings = SubmissionSettings())
            assertEquals(110.0, DirectSubmissionPolicy.decide(single, target(id), time, false).value!!, .0001)
            assertFalse(id, allowed(single, target(id)))
        }
    }
}
