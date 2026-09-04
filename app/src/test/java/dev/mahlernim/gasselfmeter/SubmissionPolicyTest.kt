package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SubmissionPolicyTest {
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
