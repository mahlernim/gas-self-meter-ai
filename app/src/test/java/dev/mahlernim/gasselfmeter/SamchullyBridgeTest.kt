package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class SamchullyBridgeTest {
    private val contract = SamchullyContract("0012345", "합성 계약", null, null, null, "new-meter")
    private val now = dayStart(LocalDate.of(2026, 9, 4))
    private fun bill(month: String = "202608", meter: String? = "new-meter") =
        SamchullyBill(month, "2026-07-10", "2026-08-09", 100.0, 110.0, 9.8, 12000.0, meter)

    @Test fun queriesExactlyTwentyFourMonthsIncludingCurrentMonth() {
        val window = SamchullyBridge.queryWindow(LocalDate.of(2026, 9, 4))
        assertEquals(YearMonth.of(2024, 10), window.first)
        assertEquals(YearMonth.of(2026, 9), window.second)
    }

    @Test fun missingDatesOrMeterKeepBillsWithoutInventingUsageOrAnchor() {
        val b = bill().copy(start = null, end = null, previousReading = null, currentReading = null)
        val snapshot = SamchullyBridge.assemble(contract, listOf(b), now)
        val data = SamchullyBridge.merge(AppData(), snapshot, null, now)
        assertTrue(data.periods.isEmpty())
        assertEquals(9.8, data.samchullyBills.single().reportedUsage!!, .0001)
        assertNull(Estimator.estimate(data, now).reading)
        assertTrue(snapshot.warning.contains("제외"))
        assertTrue(SamchullyBridge.assemble(contract, listOf(bill(meter = null)), now).periods.isEmpty())
    }

    @Test fun keepsReportedConsumptionSeparateFromUncorrectedMeterDifference() {
        val snapshot = SamchullyBridge.assemble(contract, listOf(bill()), now)
        val data = SamchullyBridge.merge(AppData(submissionSettings = SubmissionSettings(
            enabled = true, automatic = true, reminder = true, reminderHour = 18, reminderMinute = 20,
        )), snapshot, Credentials("id", "secret"), now)
        assertEquals(10.0, data.periods.single().usage, .0001)
        assertEquals(9.8, data.samchullyBills.single().reportedUsage!!, .0001)
        assertEquals("samchully", data.profile.providerId)
        assertEquals(contract.key, data.profile.contract)
        assertEquals(contract.customerNo, data.profile.customerNumber)
        assertFalse(data.samchullyBills.single().meterId!!.contains("new-meter"))
        assertFalse(data.submissionSettings.automatic)
        assertTrue(data.submissionSettings.enabled)
        assertTrue(data.submissionSettings.reminder)
        assertEquals(18, data.submissionSettings.reminderHour)
        assertEquals(20, data.submissionSettings.reminderMinute)
        assertTrue(data.gasappBills.isEmpty())
    }

    @Test fun meterReplacementDoesNotAnchorNewMeterToOldCumulativeReading() {
        val snapshot = SamchullyBridge.assemble(contract, listOf(bill(meter = "old-meter")), now)
        val data = SamchullyBridge.merge(AppData(), snapshot, null, now)
        assertNotEquals(data.profile.meter, data.periods.single().meter)
        assertNull(Estimator.estimate(data, now).anchorTime)
        assertTrue(snapshot.warning.contains("다른 계량기"))
    }

    @Test fun reconnectPreservesObservationsAndRejectsAnotherAccount() {
        val snapshot = SamchullyBridge.assemble(contract, listOf(bill()), now)
        val first = SamchullyBridge.merge(AppData(), snapshot, null, now)
        val observed = first.copy(observations = listOf(Observation(now, 111.0, first.profile.meter)))
        assertEquals(observed.observations, SamchullyBridge.merge(observed, snapshot, null, now).observations)
        assertThrows(IllegalArgumentException::class.java) {
            SamchullyBridge.merge(observed.copy(profile = observed.profile.copy(contract = "another")), snapshot, null, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SamchullyBridge.merge(observed.copy(profile = observed.profile.copy(providerId = "busan")), snapshot, null, now)
        }
    }

    @Test fun currentTargetCarriesOnlyTheSelectedContractAndConfirmedState() {
        val target = SamchullyBridge.selfReadTarget(contract, SamchullySelfReadState(
            "2026-09-01", "2026-09-30", "target-1", 120.0, false, null, null,
        ))!!
        assertEquals(contract.customerNo, target.contract.bp)
        assertTrue(target.eligible)
        assertFalse(target.submitted)
        assertEquals(120.0, target.previousValue!!, .0001)
        assertFalse(target.cycle.contains("target-1"))
        assertNull(SamchullyBridge.selfReadTarget(contract, SamchullySelfReadState(
            "2026-09-01", "2026-09-30", null, 120.0, false, null, null,
        )))
    }

    @Test fun submissionPolicyRequiresARecordedCheckAndTheActivePeriod() {
        val target = SamchullyBridge.selfReadTarget(contract, SamchullySelfReadState(
            "2026-09-01", "2026-09-30", "target-1", 120.0, false, null, null,
        ))!!
        val base = AppData(profile = Profile(providerId = "samchully", contract = contract.key,
            customerNumber = contract.customerNo, meter = SamchullyBridge.meterKey(contract, contract.meterId)), ready = true)
        val now = dayStart(LocalDate.of(2026, 9, 4))
        assertFalse(SamchullySubmissionPolicy.decide(base, target, now, false).allowed)
        val data = base.copy(observations = listOf(
            Observation(dayStart(LocalDate.of(2026, 8, 29)), 120.0, base.profile.meter),
            Observation(now, 121.0, base.profile.meter),
        ))
        assertTrue(SamchullySubmissionPolicy.decide(data, target, now, false).allowed)
        val otherTarget = target.copy(contract = Contract("0099999999", "samchully", "다른 계약"))
        assertFalse(SamchullySubmissionPolicy.decide(data, otherTarget, now, false).allowed)
        assertFalse(SamchullySubmissionPolicy.decide(data.copy(profile = data.profile.copy(meter = "reset-meter")), target, now, false).allowed)
        assertFalse(SamchullySubmissionPolicy.decide(data.copy(profile = data.profile.copy(contract = "other-contract")), target, now, false).allowed)
        assertFalse(SamchullySubmissionPolicy.decide(data, target, now, true).allowed)
        assertFalse(SamchullySubmissionPolicy.decide(data, target, dayStart(LocalDate.of(2026, 10, 1)), false).allowed)
    }

    @Test fun reconciliationConfirmsOnlyTheSameCycleMeterContractAndReading() {
        val target = SamchullyBridge.selfReadTarget(contract, SamchullySelfReadState(
            "2026-09-01", "2026-09-30", "target-1", 120.0, true, 125.0, "2026-09-04",
        ))!!
        val record = SubmissionRecord(target.cycle, target.start, target.end, 125.0, now, "uncertain", "확인 대기")
        val data = AppData(profile = Profile(providerId = "samchully", contract = contract.key,
            customerNumber = contract.customerNo, meter = target.installation), submissions = listOf(record))
        assertEquals(record, SamchullyBridge.reconciledRecord(data, target))
        assertEquals("confirmed", SamchullyBridge.applyReconciliation(data, record).submissions.single().status)
        assertNull(SamchullyBridge.reconciledRecord(data, target.copy(submittedValue = 126.0)))
        assertNull(SamchullyBridge.reconciledRecord(data.copy(profile = data.profile.copy(meter = "reset-meter")), target))
        assertNull(SamchullyBridge.reconciledRecord(data.copy(profile = data.profile.copy(contract = "other-contract")), target))
        assertNull(SamchullyBridge.reconciledRecord(data, target.copy(cycle = "other-cycle")))
    }
}
