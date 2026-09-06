package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DirectProviderBridgeTest {
    private val contract = DirectContract("contract-42", "합성 계약", "meter-9")
    private val now = dayStart(LocalDate.of(2026, 9, 6))

    private fun sourceTarget(submitted: Boolean = false, value: Double? = null) = SelfReadTarget(
        cycle = "direct-cycle-202609", start = "2026-09-01", end = "2026-09-10", eligible = true,
        submitted = submitted, submittedValue = value, previousValue = 120.0,
        contract = Contract(contract.id, "daesung", contract.label), serial = "order-9", address = "", planned = "", vLdo = "", installation = DirectIdentity.meter("daesung", contract.id, contract.meterId),
    )

    @Test fun loginRetainsTheSameClientForSelectedContractAndClosesIt() {
        val original = DirectProviderBridge.clientFactory
        val fake = FakeClient(contract)
        DirectProviderBridge.clientFactory = { _, _ -> fake }
        try {
            DirectProviderBridge.login("daesung", Credentials("tester", "secret")).use { login ->
                assertEquals(contract, login.contracts.single())
                assertEquals(contract, DirectProviderBridge.snapshot(login, contract).contract)
            }
            assertTrue(fake.closed)
        } finally {
            DirectProviderBridge.clientFactory = original
        }
    }

    @Test fun mergeKeepsBillSummaryButUsesOnlyExplicitCumulativeMeterDelta() {
        val incomplete = DirectBill("2026-08", usage = 9.8, amount = 12000.0)
        val complete = DirectBill("2026-07", usage = 7.0, amount = 11000.0, start = "2026-06-01", end = "2026-06-30", previous = 90.0, current = 100.0, meterId = "meter-9")
        val merged = DirectProviderBridge.merge(AppData(), DirectSnapshot(contract, listOf(incomplete, complete), sourceTarget()), "daesung", null, now)
        assertEquals(2, merged.directBills.size)
        assertEquals(10.0, merged.periods.single().usage, .0001)
        assertEquals("202607", merged.periods.single().billMonth)
        assertEquals(DirectIdentity.contract("daesung", contract.id), merged.profile.contract)
        assertFalse(merged.submissionSettings.automatic)
    }

    @Test fun policyPrefersSameDayObservationAndRejectsMissingMeterOrReplay() {
        val snapshot = DirectSnapshot(contract, emptyList(), sourceTarget())
        val target = DirectProviderBridge.target("daesung", snapshot)!!
        val data = AppData(profile = Profile(providerId = "daesung", contract = DirectIdentity.contract("daesung", contract.id),
            customerNumber = contract.id, meter = target.installation), observations = listOf(Observation(now, 126.9, target.installation)))
        assertEquals(126.0, DirectSubmissionPolicy.decide(data, target, now).value!!, .0001)
        assertFalse(DirectSubmissionPolicy.decide(data, target, now, automatic = true).allowed)
        assertFalse(DirectSubmissionPolicy.decide(data.copy(profile = data.profile.copy(meter = DirectIdentity.meter("daesung", contract.id, null))), target, now).allowed)
        val replay = data.copy(submissions = listOf(SubmissionRecord(target.cycle, target.start, target.end, 126.0, now, "uncertain", "확인 중")))
        assertFalse(DirectSubmissionPolicy.decide(replay, target, now).allowed)
    }

    @Test fun reconciliationRequiresTheSameCycleContractMeterAndReading() {
        val target = DirectProviderBridge.target("daesung", DirectSnapshot(contract, emptyList(), sourceTarget(true, 126.0)))!!
        val record = SubmissionRecord(target.cycle, target.start, target.end, 126.0, now, "uncertain", "확인 중")
        val data = AppData(profile = Profile(providerId = "daesung", contract = DirectIdentity.contract("daesung", contract.id),
            customerNumber = contract.id, meter = target.installation), submissions = listOf(record))
        assertEquals(record, DirectProviderBridge.reconciledRecord(data, target))
        assertEquals("confirmed", DirectProviderBridge.applyReconciliation(data, record).submissions.single().status)
        assertNull(DirectProviderBridge.reconciledRecord(data, target.copy(submittedValue = 127.0)))
        assertNull(DirectProviderBridge.reconciledRecord(data.copy(profile = data.profile.copy(meter = "other")), target))
    }

    @Test fun directBillSummariesSurviveEncryptedBackupCodecRoundTrip() {
        val bills = listOf(DirectBill("2026-08", usage = 12.5, amount = 14300.0, start = "2026-07-01", end = "2026-07-31", previous = 50.0, current = 62.5, meterId = "meter-9"))
        val restored = DataCodec.decode(DataCodec.encode(AppData(directBills = bills), includeCredentials = true), allowCredentials = true)
        assertEquals(bills, restored.directBills)
    }

    private class FakeClient(private val contract: DirectContract) : DirectProviderClient {
        var closed = false
        override fun login() = listOf(contract)
        override fun read(contract: DirectContract) = DirectSnapshot(contract, emptyList(), null)
        override fun submit(contract: DirectContract, target: SelfReadTarget, value: Double) = Unit
        override fun close() { closed = true }
    }
}
