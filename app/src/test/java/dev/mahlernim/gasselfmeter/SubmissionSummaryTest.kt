package dev.mahlernim.gasselfmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubmissionSummaryTest {
    private val provider = Providers.skens("busan")
    private val contract = Contract("synthetic-bp", "synthetic-ca")
    private val serial = "synthetic-meter"
    private val target = SelfReadTarget(
        "2026-09-01:2026-09-05:${SkensClient.opaque(serial)}", "2026-09-01", "2026-09-05",
        true, false, null, 27.0, contract, serial, "", "", "", "",
    )
    private fun data(record: SubmissionRecord) = AppData(
        profile = Profile("busan", SkensClient.opaque(serial), SkensClient.contractKey(provider, contract)),
        submissions = listOf(record), ready = true,
    )
    private fun record(status: String = "confirmed", source: String? = "provider_response") = SubmissionRecord(
        target.cycle, target.start, target.end, 41.0, 1L, status, "synthetic", source,
    )

    @Test fun currentProviderResponseShowsCompletedValueWhenPortalIsStillUnsubmitted() {
        assertEquals(41.0, requireNotNull(currentSkensConfirmation(data(record()), target)).value, 0.0)
    }

    @Test fun pendingUncertainOrDifferentTargetCannotShowCompletedValue() {
        assertNull(currentSkensConfirmation(data(record("pending")), target))
        assertNull(currentSkensConfirmation(data(record("uncertain")), target))
        assertNull(currentSkensConfirmation(data(record().copy(cycle = "other-cycle")), target))
    }
}
