package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

/** The chart showed months up to the one before today, hiding a bill issued in the current month. */
class HistoryRangeTest {
    private val current = YearMonth.of(2026, 9)

    @Test fun trailingRangeEndsWithTheCurrentMonth() {
        val summary = HistorySummary.through(AppData(), current)
        assertEquals(24, summary.size)
        assertEquals(current, summary.last().month)
        assertEquals(current.minusMonths(23), summary.first().month)
    }

    @Test fun currentMonthKeepsItsSupplierBill() {
        val data = AppData(
            profile = Profile(providerId = "seoul"),
            gasappBills = listOf(GasappBill("2026-09", 12.0, 14_000.0, null, null)))

        val month = HistorySummary.through(data, current).last()

        assertEquals(current, month.month)
        assertEquals(12.0, month.usage!!, .0001)
        assertEquals(14_000.0, month.billedAmount!!, .0001)
    }

    @Test fun aPartiallyCoveredCurrentMonthStillHasNoInventedUsage() {
        // Fewer than fourteen covered days yields no period-based figure, and no bill fills it in.
        val data = AppData(periods = listOf(UsagePeriod("2026-09-01", "2026-09-05", 5.0)))

        val month = HistorySummary.through(data, current).last()

        assertEquals(current, month.month)
        assertNull(month.usage)
        assertNull(month.billedAmount)
    }

    @Test fun theExclusiveHelperIsUnchangedForItsOtherCallers() {
        assertEquals(current.minusMonths(1), HistorySummary.months(emptyList(), current, 1).single().month)
    }
}
