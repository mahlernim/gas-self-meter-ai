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
        val data = SamchullyBridge.merge(AppData(), snapshot, Credentials("id", "secret"), now)
        assertEquals(10.0, data.periods.single().usage, .0001)
        assertEquals(9.8, data.samchullyBills.single().reportedUsage!!, .0001)
        assertEquals("samchully", data.profile.providerId)
        assertEquals(contract.key, data.profile.contract)
        assertEquals(contract.customerNo, data.profile.customerNumber)
        assertFalse(data.samchullyBills.single().meterId!!.contains("new-meter"))
        assertFalse(data.submissionSettings.enabled)
        assertFalse(data.submissionSettings.automatic)
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
}
