package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class EstimatorTest {
    private val date = today().minusDays(2)
    private val time = dayStart(date)
    private fun history(rate: Double = 2.0): List<UsagePeriod> = (-2L..2L).map {
        val m = YearMonth.from(date).minusYears(1).plusMonths(it)
        UsagePeriod(m.atDay(1).toString(), m.atEndOfMonth().toString(), rate * m.lengthOfMonth())
    }
    @Test fun seasonalEstimateIntegratesPartialDaysFromPhysicalAnchor() {
        val data = AppData(periods = history(), observations = listOf(Observation(time - 7 * 86_400_000L, 100.0, "manual")))
        val result = Estimator.estimate(data, time + 43_200_000)
        assertEquals(115.0, result.reading!!, .00001)
        assertEquals(2.0, result.daily!!, .00001)
    }
    @Test fun recentPhysicalRateNudgesSeasonalPriorWithoutReplacingIt() {
        val data = AppData(periods = history(), observations = listOf(
            Observation(time - 7 * 86_400_000L, 100.0, "manual"), Observation(time, 128.0, "manual")))
        val result = Estimator.estimate(data, time)
        assertEquals(3.0, result.daily!!, .00001)
        assertEquals(128.0, result.reading!!, .00001)
    }
    @Test fun noHistoryNeedsTwoPhysicalReadingsAndExpiresAfterFourteenDays() {
        val data = AppData(observations = listOf(Observation(time - 7 * 86_400_000L, 100.0, "manual"), Observation(time, 107.0, "manual")))
        assertEquals(108.0, Estimator.estimate(data, time + 86_400_000).reading!!, .0001)
        assertNull(Estimator.estimate(data, time + 15 * 86_400_000L).reading)
        assertNull(Estimator.estimate(data.copy(observations = data.observations.take(1)), time).reading)
    }
    @Test fun missingAnchorAndMeterReplacementDoNotInventZero() {
        val data = AppData(periods = history())
        assertNull(Estimator.estimate(data, time).reading)
        val replaced = data.copy(profile = Profile(meter = "new"), observations = listOf(Observation(time - 86_400_000, 100.0, "old")))
        assertNull(Estimator.estimate(replaced, time).reading)
    }
    @Test fun duplicateQuickCorrectionReplacesObservationAndPreservesOriginalForecast() {
        val initial = AppData(periods = history(), observations = listOf(Observation(time - 7 * 86_400_000L, 100.0, "manual")))
        val first = Estimator.addObservation(initial, 114.1, time)
        val corrected = Estimator.addObservation(first, 114.0, time + 30_000)
        assertEquals(2, corrected.observations.size)
        assertEquals(114.0, corrected.observations.last().predicted!!, .00001)
        assertThrows(IllegalArgumentException::class.java) { Estimator.addObservation(initial, 99.9, time) }
    }
    @Test fun zeroUseIsEvidenceAndLeapMonthUsesItsActualDays() {
        val feb = UsagePeriod("2024-02-01", "2024-02-29", 29.0)
        assertEquals(1.0, Estimator.monthlyRate(listOf(feb), YearMonth.of(2024, 2))!!, .00001)
        val data = AppData(observations = listOf(Observation(time - 7 * 86_400_000L, 100.0, "manual"), Observation(time, 100.0, "manual")))
        assertEquals(100.0, Estimator.estimate(data, time + 86_400_000).reading!!, .00001)
    }
    @Test fun overlappingPeriodsAndInvalidNumbersAreRejected() {
        val rows = listOf(UsagePeriod("2025-01-01", "2025-01-31", 31.0), UsagePeriod("2025-01-31", "2025-02-28", 29.0))
        assertThrows(IllegalArgumentException::class.java) { Estimator.validatePeriods(rows) }
        assertThrows(IllegalStateException::class.java) { number("NaN") }
        assertThrows(IllegalStateException::class.java) { number("-0.1") }
    }
    @Test fun decayingCalibrationCannotMakeCumulativeMeterRunBackward() {
        val data = AppData(periods = history(), observations = listOf(
            Observation(time - 28 * 86_400_000L, 100.0, "manual"), Observation(time, 380.0, "manual")))
        val readings = (0L..40L).map { Estimator.estimate(data, time + it * 86_400_000, time).reading!! }
        readings.zipWithNext().forEach { (a, b) -> assertTrue("Cumulative projection must increase with positive usage", b > a) }
    }
}
