package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

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
    @Test fun historySummaryReturns24MonthsAndOnlyUnambiguousExactBillAmount() {
        val periods = listOf(
            UsagePeriod("2025-01-01", "2025-01-31", 31.0, billMonth = "202501", amount = 18_810.0),
            UsagePeriod("2025-02-01", "2025-02-14", 14.0, billMonth = "202502", amount = 10_000.0),
            UsagePeriod("2025-02-15", "2025-02-28", 28.0, billMonth = "202502", amount = 11_000.0),
        )

        val summary = HistorySummary.months(periods, YearMonth.of(2025, 3))

        assertEquals(24, summary.size)
        assertEquals(YearMonth.of(2023, 3), summary.first().month)
        assertEquals(31.0, summary.single { it.month == YearMonth.of(2025, 1) }.usage!!, .00001)
        assertEquals(18_810.0, summary.single { it.month == YearMonth.of(2025, 1) }.billedAmount!!, .00001)
        assertNull(summary.single { it.month == YearMonth.of(2025, 2) }.billedAmount)
    }
    @Test fun medianPairwiseSlopeIgnoresASingleMisreadCheck() {
        val day = 86_400_000L
        // A true two cubic metres a day, with the third check mistyped as 170.
        val points = listOf(
            Observation(time - 21 * day, 100.0, "manual"),
            Observation(time - 14 * day, 114.0, "manual"),
            Observation(time - 7 * day, 170.0, "manual"),
            Observation(time, 142.0, "manual"),
        )

        assertEquals(2.0, Estimator.robustDailyRate(points)!!, .00001)
        assertNull(Estimator.robustDailyRate(points.take(1)))
    }
    @Test fun misreadCheckNoLongerLeavesTheForecastWithoutARate() {
        val day = 86_400_000L
        val data = AppData(observations = listOf(
            Observation(time - 21 * day, 100.0, "manual"),
            Observation(time - 14 * day, 114.0, "manual"),
            Observation(time - 7 * day, 170.0, "manual"),
            Observation(time, 142.0, "manual"),
        ))

        // The trailing pair alone falls backward and used to yield no rate at all.
        val result = Estimator.estimate(data, time + day)

        assertEquals(2.0, result.daily!!, .00001)
        assertEquals(144.0, result.reading!!, .00001)
    }
    @Test fun storedForecastErrorBecomesABiasAndStaysBounded() {
        val day = 86_400_000L
        val data = AppData(observations = listOf(
            Observation(time - 40 * day, 100.0, "manual"),
            Observation(time, 120.0, "manual", predicted = 110.0),
        ))

        // Forecast ten, actual twenty, so the ratio is two and the clamp keeps it there.
        assertEquals(2.0, Estimator.calibrationBias(data)!!, .00001)
        assertNull(Estimator.calibrationBias(AppData(observations = listOf(Observation(time, 120.0, "manual")))))
    }
    @Test fun learnedBiasCorrectsTheSeasonalOnlyPathAndKeepsTheMeterRising() {
        val day = 86_400_000L
        val data = AppData(periods = history(), observations = listOf(
            Observation(time - 40 * day, 100.0, "manual"),
            Observation(time, 260.0, "manual", predicted = 180.0),
        ))

        // Only one check sits inside the trailing window, so there is no in-window ratio and the
        // learned bias of two doubles the two cubic metre seasonal rate.
        val result = Estimator.estimate(data, time + day)

        assertEquals(4.0, result.daily!!, .00001)
        assertEquals(264.0, result.reading!!, .00001)
        val readings = (0L..20L).map { Estimator.estimate(data, time + it * day, time).reading!! }
        readings.zipWithNext().forEach { (a, b) -> assertTrue("Corrected rate must stay non-negative", b >= a) }
    }
    @Test fun recentSlopeFadesOutWithoutSteppingAtTheFourteenDayHorizon() {
        val day = 86_400_000L
        // A seasonal rate this small keeps the prior interval under the ratio threshold, which is
        // what routes the estimate through the blended branch this test covers.
        val data = AppData(periods = history(.005), observations = listOf(
            Observation(time - day, 100.0, "manual"), Observation(time, 100.5, "manual")))

        val daily = (12L..16L).map { Estimator.estimate(data, time + it * day).daily!! }

        assertEquals(.005, daily[2], .0000001)
        assertEquals("The blend must reach the seasonal rate continuously", daily[2], daily[3], .0000001)
        daily.zipWithNext().forEach { (a, b) -> assertTrue("Daily rate must not step", abs(a - b) < .005) }
    }
    @Test fun decayingCalibrationCannotMakeCumulativeMeterRunBackward() {
        val data = AppData(periods = history(), observations = listOf(
            Observation(time - 28 * 86_400_000L, 100.0, "manual"), Observation(time, 380.0, "manual")))
        val readings = (0L..40L).map { Estimator.estimate(data, time + it * 86_400_000, time).reading!! }
        readings.zipWithNext().forEach { (a, b) -> assertTrue("Cumulative projection must increase with positive usage", b > a) }
    }
}
