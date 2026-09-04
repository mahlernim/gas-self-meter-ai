package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class GasappIntegrationTest {
    private val account = GasappAccount("1", "customer", "contract", "집", "N")
    private val session = GasappSession("synthetic-token", "synthetic-member", "synthetic-device")
    private val meter = gasappHash("meter:1:meter")
    private val target = GasappTarget(account, true, true, "2026-09-01", "2026-09-07", meter, 100.0, 5, false, false, false, null)
    private val connection = GasappConnection(session, account)
    private fun data() = AppData(profile = Profile("seoul", meter, account.key), ready = true,
        gasappConnection = connection, cachedGasappTarget = target)

    @Test fun portableBackupCannotRestoreGasappSessionOrCachedTarget() {
        val data = data().copy(gasappBills = listOf(GasappBill("2026-08", 12.0, 14000.0, null, null)))
        val portable = DataCodec.encode(data)
        assertFalse(portable.contains(session.token))
        assertFalse(portable.contains("gasappConnection"))
        assertNull(DataCodec.decode(DataCodec.encode(data, true)).gasappConnection)
        assertNull(DataCodec.decode(DataCodec.encode(data, true)).cachedGasappTarget)
        val restored = DataCodec.decode(DataCodec.encode(data, true), true)
        assertEquals(session.token, restored.gasappConnection!!.session.token)
        assertEquals(target, restored.cachedGasappTarget)
        assertEquals(data.gasappBills, DataCodec.decode(portable).gasappBills)
    }

    @Test fun undatedBillsRemainMonthlyEvidenceAndNeverBecomeInventedPeriods() {
        val bill = GasappBill("2026-08", 12.0, 14000.0, null, null)
        val merged = GasappBridge.merge(data(), connection, GasappSnapshot(account, listOf(bill), emptyList(), target))
        assertTrue(merged.periods.isEmpty())
        assertTrue(merged.observations.isEmpty())
        val summary = HistorySummary.months(merged, YearMonth.of(2026, 9)).last()
        assertEquals(12.0, summary.usage!!, .0001)
        assertEquals(14000.0, summary.billedAmount!!, .0001)
    }

    @Test fun importedReadingsDoNotQualifyAsPhysicalCalibrationForAutomaticSubmission() {
        val readings = listOf(GasappReading("1", "2026-08-01", 80.0, "meter"), GasappReading("2", "2026-09-01", 100.0, "meter"))
        val merged = GasappBridge.merge(data(), connection, GasappSnapshot(account, emptyList(), readings, target), dayStart(LocalDate.of(2026, 9, 7)))
        assertEquals(1, merged.periods.size)
        assertTrue(merged.observations.isEmpty())
        assertFalse(GasappSubmissionPolicy.decide(merged.copy(submissionSettings = SubmissionSettings(automatic = true)), target,
            dayStart(LocalDate.of(2026, 9, 7)), automatic = true).allowed)
    }

    @Test fun meterChangeAndUncertainPriorAttemptBlockSubmission() {
        val time = dayStart(LocalDate.of(2026, 9, 7))
        val calibrated = data().copy(observations = listOf(Observation(time - 86_400_000, 111.9, meter), Observation(time, 113.8, meter)))
        val allowed = GasappSubmissionPolicy.decide(calibrated, target, time, automatic = false)
        assertTrue(allowed.reason, allowed.allowed)
        assertEquals(113.0, allowed.value!!, .0001)
        assertFalse(GasappSubmissionPolicy.decide(calibrated, target.copy(meterChanged = true), time, automatic = false).allowed)
        val pending = SubmissionRecord(target.cycle, target.start!!, target.end!!, 113.0, time, "uncertain", "")
        assertFalse(GasappSubmissionPolicy.decide(calibrated.copy(submissions = listOf(pending)), target, time, automatic = false).allowed)
    }

    @Test fun replacementRequiresCalibrationAfterFirstObservedChangeAndDoesNotResetOnRefresh() {
        val time = dayStart(LocalDate.of(2026, 9, 7))
        val changed = target.copy(meterChanged = true)
        val base = data().copy(cachedGasappTarget = changed, gasappMeterChangeObservedAt = time - 1000,
            observations = listOf(Observation(time - 86_400_000, 111.0, meter), Observation(time, 113.0, meter)))
        assertEquals(time - 1000, GasappBridge.replacementObservedAt(base, changed, time + 1000))
        assertTrue(GasappSubmissionPolicy.decide(base, changed, time, automatic = false).allowed)
        assertFalse(GasappSubmissionPolicy.decide(base.copy(gasappMeterChangeObservedAt = time), changed, time, automatic = false).allowed)
    }

    @Test fun reconciliationCannotResurrectRemovedOrReplacedAttempt() {
        val record = SubmissionRecord(target.cycle, target.start!!, target.end!!, 113.0, 1234L, "uncertain", "")
        val initial = data().copy(submissions = listOf(record))
        assertEquals("confirmed", GasappBridge.applyReconciliation(initial, initial, record).submissions.single().status)
        val removed = initial.copy(submissions = emptyList())
        assertTrue(GasappBridge.applyReconciliation(removed, initial, record).submissions.isEmpty())
        val reset = AppData()
        assertEquals(reset, GasappBridge.applyReconciliation(reset, initial, record))
    }
}
