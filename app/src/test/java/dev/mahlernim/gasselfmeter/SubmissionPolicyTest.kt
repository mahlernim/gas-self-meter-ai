package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SubmissionPolicyTest {
    @Test fun firstPhysicalReadingCanBeSubmittedTodayWithoutForecastHistory() {
        val initial = data().copy(periods = emptyList(), observations = listOf(Observation(time - 1000, 107.2, data().profile.meter)))
        val decision = SubmissionPolicy.decide(initial, target, time, automatic = false)
        assertTrue(decision.allowed)
        assertEquals(107.2, decision.value!!, .001)
        assertFalse(SubmissionPolicy.decide(initial, target, time, automatic = true).allowed)
        assertFalse(SubmissionPolicy.decide(initial, target.copy(end = date.plusDays(1).toString()), time + 86_400_000, automatic = false).allowed)
    }
    @Test fun persistedPendingAndUncertainRecordsBlockBothModesAfterReload() {
        for (status in listOf("pending", "uncertain", "confirmed")) {
            val record = SubmissionRecord("cycle", target.start, target.end, 107.0, time - 1000, status, "synthetic")
            val restored = DataCodec.decode(DataCodec.encode(data(records = listOf(record)), true), true)
            assertTrue(restored.submissionSettings.enabled)
            for (automatic in listOf(false, true)) {
                assertFalse(SubmissionPolicy.decide(restored, target, time, automatic).allowed)
            }
        }
    }

    @Test fun rejectedAttemptNeedsManualReviewInsteadOfAutomaticRetry() {
        val record = SubmissionRecord("cycle", target.start, target.end, 107.0, time - 1000, "rejected", "synthetic")
        assertFalse(SubmissionPolicy.decide(data(records = listOf(record)), target, time, true).allowed)
        assertTrue(SubmissionPolicy.decide(data(records = listOf(record)), target, time, false).allowed)
    }

    @Test fun deadlineUsesKoreanMidnightAndRejectsClockRollback() {
        val start = dayStart(date)
        assertFalse(SubmissionPolicy.decide(data(), target, start - 1, true).allowed)
        assertTrue(SubmissionPolicy.decide(data(), target, start, true).allowed)
        assertTrue(SubmissionPolicy.decide(data(), target, start + 86_400_000 - 1, true).allowed)
        assertFalse(SubmissionPolicy.decide(data(), target, start + 86_400_000, true).allowed)
        assertFalse(SubmissionPolicy.decide(data(-1), target, time, true).allowed)
    }


    private val date = LocalDate.of(2026, 9, 4)
    private val time = dayStart(date) + 10 * 3_600_000L
    private val target = SelfReadTarget(
        cycle = "cycle", start = date.minusDays(2).toString(), end = date.toString(),
        eligible = true, submitted = false, submittedValue = null, previousValue = 90.0,
        contract = Contract("1", "2", "synthetic"), serial = "meter", address = "",
        planned = "20260904", vLdo = "", installation = ""
    )
    private fun data(lastCheckDaysAgo: Long = 1, records: List<SubmissionRecord> = emptyList()) = AppData(
        profile = Profile("busan", SkensClient.opaque("meter"), SkensClient.contractKey(Providers.skens("busan"), target.contract)),
        observations = listOf(
            Observation(time - 8 * 86_400_000L, 100.0, SkensClient.opaque("meter")),
            Observation(time - lastCheckDaysAgo * 86_400_000L, 107.0, SkensClient.opaque("meter"))
        ),
        credentials = Credentials("user", "secret"),
        submissionSettings = SubmissionSettings(enabled = true, automatic = true, requireRecentCheck = true, recentDays = 7),
        submissions = records,
        ready = true
    )

    @Test fun manualSubmissionDoesNotRequireRemovedFeatureToggle() {
        val original = data()
        assertTrue(SubmissionPolicy.decide(original.copy(submissionSettings = original.submissionSettings.copy(enabled = false)), target, time, automatic = false).allowed)
    }
    @Test fun newReminderPreferencesRoundTripAndLegacyDefaultsMigrate() {
        val original = data().copy(profile = data().profile.copy(reminderRepeatCount = 6, customerNumber = "123"),
            submissionSettings = data().submissionSettings.copy(reminder = true, reminderHour = 21, reminderMinute = 35), cachedSelfRead = target)
        val restored = DataCodec.decode(DataCodec.encode(original, true), true)
        assertEquals(original, restored)
        val backup = DataCodec.decode(DataCodec.encode(original))
        assertNull(backup.cachedSelfRead)
        assertFalse(backup.submissionSettings.reminder)
        val legacy = org.json.JSONObject(DataCodec.encode(data(), true)).apply {
            put("schema", 2)
            getJSONObject("profile").remove("reminderRepeatCount")
            getJSONObject("submissionSettings").remove("reminder")
        }
        assertEquals(3, DataCodec.decode(legacy.toString(), true).profile.reminderRepeatCount)
    }

    @Test fun automaticSubmissionRequiresLastDayAndRecentPhysicalCheck() {
        val allowed = SubmissionPolicy.decide(data(), target, time, automatic = true)
        assertTrue(allowed.allowed)
        assertNotNull(allowed.value)
        val beforeLastDay = SubmissionPolicy.decide(data(), target.copy(end = date.plusDays(1).toString()), time, automatic = true)
        assertFalse(beforeLastDay.allowed)
        val stale = SubmissionPolicy.decide(data(8), target, time, automatic = true)
        assertFalse(stale.allowed)
        assertTrue(stale.reason.contains("8일 전"))
    }

    @Test fun uncertainAttemptIsNeverAutomaticallyRepeated() {
        val record = SubmissionRecord("cycle", target.start, target.end, 107.0, time - 1_000, "uncertain", "확인 필요")
        val decision = SubmissionPolicy.decide(data(records = listOf(record)), target, time, automatic = true)
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("확인"))
    }

    @Test fun submissionSupportsConfiguredSkensProvidersOnly() {
        val koone = data().copy(profile = data().profile.copy(providerId = "koone",
            contract = SkensClient.contractKey(Providers.skens("koone"), target.contract)))
        assertTrue(SubmissionPolicy.decide(koone, target, time, automatic = false).allowed)
        assertFalse(SubmissionPolicy.decide(koone, target, time, automatic = true).allowed)
        val seoul = data().copy(profile = data().profile.copy(providerId = "seoul"))
        assertFalse(SubmissionPolicy.decide(seoul, target, time, automatic = false).allowed)
    }

    @Test fun portableBackupKeepsAuditHistoryButDisablesAutomaticSubmission() {
        val record = SubmissionRecord("cycle", target.start, target.end, 107.0, time, "confirmed", "완료")
        val original = data(records = listOf(record))
        val imported = DataCodec.decode(DataCodec.encode(original))
        assertEquals(listOf(record), imported.submissions)
        assertFalse(imported.submissionSettings.enabled)
        assertNull(imported.credentials)
    }

    @Test fun changedContractOrMeterBlocksManualAndAutomaticSubmission() {
        val changedTargets = listOf(
            target.copy(contract = target.contract.copy(ca = "3")),
            target.copy(contract = target.contract.copy(bp = "3")),
            target.copy(serial = "replacement"),
            target.copy(serial = "")
        )
        for (automatic in listOf(false, true)) {
            assertTrue(SubmissionPolicy.decide(data(), target, time, automatic).allowed)
            changedTargets.forEach { changed ->
                assertFalse(SubmissionPolicy.decide(data(), changed, time, automatic).allowed)
            }
        }
    }

    @Test fun confirmationRequiresSameContractMeterPeriodAndExactReading() {
        val confirmed = target.copy(submitted = true, submittedValue = 108.0)
        assertTrue(SkensClient.confirmsSubmission(target, confirmed, 108.0))
        val unconfirmed = listOf(
            confirmed.copy(submitted = false),
            confirmed.copy(submittedValue = null),
            confirmed.copy(submittedValue = 109.0),
            confirmed.copy(submittedValue = Double.NaN),
            confirmed.copy(contract = target.contract.copy(ca = "3")),
            confirmed.copy(contract = target.contract.copy(bp = "3")),
            confirmed.copy(serial = "replacement"),
            confirmed.copy(cycle = "next-cycle"),
            confirmed.copy(start = date.minusDays(1).toString()),
            confirmed.copy(end = date.plusDays(1).toString()),
            confirmed.copy(planned = "20261004"),
            confirmed.copy(installation = "replacement")
        )
        unconfirmed.forEach { assertFalse(SkensClient.confirmsSubmission(target, it, 108.0)) }
    }
}
