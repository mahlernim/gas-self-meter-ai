package dev.mahlernim.gasselfmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkensReconciliationTest {
    private val provider = Providers.get("busan")
    private val contract = Contract("synthetic-bp", "synthetic-ca")
    private val serial = "synthetic-meter"
    private val cycle = "2026-09-01:2026-09-05:${SkensClient.opaque(serial)}"

    private fun target(
        submittedValue: Double? = 41.2,
        serial: String = this.serial,
        cycle: String = this.cycle,
        start: String = "2026-09-01",
        end: String = "2026-09-05",
        planned: String = "2026-09-05",
        installation: String = "",
    ) = SelfReadTarget(
        cycle, start, end, true, submittedValue != null, submittedValue, 40.0,
        contract, serial, "", planned, "", installation,
    )

    private fun record(value: Double = 41.2, status: String = "uncertain") = SubmissionRecord(
        cycle, "2026-09-01", "2026-09-05", value, 1L, status, "공급사 확인 대기",
    )

    private fun data(vararg records: SubmissionRecord) = AppData(
        profile = Profile("busan", SkensClient.opaque(serial), SkensClient.contractKey(provider, contract)),
        submissions = records.toList(), ready = true, cachedSelfRead = target(submittedValue = null),
    )

    @Test fun exactHistoricalDecimalConfirmsWithoutIntegerCoercion() {
        val reconciled = SkensReconciliation.apply(data(record()), target())

        assertEquals("confirmed", reconciled.submissions.single().status)
        assertEquals(41.2, reconciled.submissions.single().value, 0.0)
        assertEquals("readback", reconciled.submissions.single().confirmationSource)
    }

    @Test fun flooredPortalValueCannotConfirmHistoricalDecimal() {
        val oldUncertain = record().copy(detail = "공급사 응답으로 41 m³ 제출을 완료했어요.")
        val reconciled = SkensReconciliation.apply(data(oldUncertain), target(41.0))

        assertEquals("uncertain", reconciled.submissions.single().status)
        assertEquals("공급사 응답으로 41 m³ 제출을 완료했어요.", reconciled.submissions.single().detail)
        assertNull(reconciled.submissions.single().confirmationSource)
    }

    @Test fun matchingLaterReadbackUpgradesAProviderResponse() {
        val accepted = record(status = "confirmed").copy(confirmationSource = "provider_response")
        val reconciled = SkensReconciliation.apply(data(accepted), target())

        assertEquals("confirmed", reconciled.submissions.single().status)
        assertEquals("readback", reconciled.submissions.single().confirmationSource)
        assertEquals("공급사에서 제출 완료를 확인했어요.", reconciled.submissions.single().detail)
    }

    @Test fun changedMeterOrPeriodDoesNotConfirm() {
        assertNull(SkensReconciliation.reconciledRecord(data(record()), target(serial = "other-meter")))
        assertNull(SkensReconciliation.reconciledRecord(data(record()), target(cycle = "other-cycle")))
        assertNull(SkensReconciliation.reconciledRecord(data(record()), target(end = "2026-09-06")))
        assertNull(SkensReconciliation.reconciledRecord(data(record()), target(planned = "2026-09-06")))
        assertNull(SkensReconciliation.reconciledRecord(data(record()), target(installation = "other-installation")))
        assertNull(SkensReconciliation.reconciledRecord(data(record()).copy(cachedSelfRead = null), target()))
    }

    @Test fun reconciliationKeepsUnrelatedAndAlreadyFinalizedRecords() {
        val unrelated = record(50.0, "pending").copy(cycle = "other-cycle")
        val finalized = record(41.2, "rejected").copy(attemptedAt = 2L)
        val reconciled = SkensReconciliation.apply(data(unrelated, record(), finalized), target())

        assertEquals("pending", reconciled.submissions[0].status)
        assertEquals("confirmed", reconciled.submissions[1].status)
        assertEquals("rejected", reconciled.submissions[2].status)
    }
}
