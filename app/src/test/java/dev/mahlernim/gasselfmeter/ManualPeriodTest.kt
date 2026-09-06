package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test

/** Only rows the user typed in are editable; supplier rows are rewritten by the next sync. */
class ManualPeriodTest {
    private val typed = UsagePeriod("2025-01-01", "2025-01-31", 31.0)
    private val imported = UsagePeriod("2025-02-01", "2025-02-28", 28.0, "opaque-meter-key",
        previous = 100.0, current = 128.0, billMonth = "202502", amount = 20_000.0)

    @Test fun theSourceIsToldApartByTheMeterKey() {
        assertTrue(typed.manual)
        assertFalse(imported.manual)
        // A restored backup keeps the distinction.
        val restored = DataCodec.decode(DataCodec.encode(AppData(periods = listOf(typed, imported))))
        assertEquals(listOf(true, false), restored.periods.map { it.manual })
    }

    @Test fun anEditThatWouldOverlapAnotherPeriodIsRejected() {
        val edited = typed.copy(start = "2025-02-10", end = "2025-02-20", usage = 10.0)
        val next = (listOf(typed, imported) - typed + edited).sortedBy { it.start }

        val failure = runCatching { Estimator.validatePeriods(next) }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("겹쳐요"))
    }

    @Test fun anEditThatMovesWithinFreeSpaceIsAccepted() {
        val edited = typed.copy(start = "2024-12-01", end = "2024-12-31", usage = 40.0)

        val next = (listOf(typed, imported) - typed + edited).sortedBy { it.start }

        Estimator.validatePeriods(next)
        assertEquals(listOf("2024-12-01", "2025-02-01"), next.map { it.start })
        assertTrue(next.first().manual)
    }
}
