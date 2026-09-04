package dev.mahlernim.gasselfmeter

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PredictionErrorSummaryTest {
    private val date = LocalDate.of(2026, 9, 4)
    private val until = dayStart(date) + 43_200_000L
    private val day = 86_400_000L
    private fun check(daysAgo: Int, reading: Double = 102.0, predicted: Double? = 100.0) =
        Observation(until - daysAgo * day, reading, "manual", predicted)
    private fun summary(vararg observations: Observation) =
        PredictionErrors.summarize(AppData(observations = observations.toList()), until)

    @Test fun computesAbsoluteDiscrepanciesAndSelectedDateSpanFromStoredPredictions() {
        val result = summary(check(3, 98.0), check(2, 104.0), check(0, 100.0))!!
        assertEquals(2.0, result.meanAbsoluteError, 0.000001)
        assertEquals(4.0, result.maxAbsoluteError, 0.000001)
        assertEquals(3, result.sampleCount)
        assertEquals(date.minusDays(3), result.firstCheckDate)
        assertEquals(date, result.lastCheckDate)
    }

    @Test fun needsThreeComparableDistinctDays() {
        assertNull(summary())
        assertNull(summary(check(1)))
        assertNull(summary(check(1), check(2), check(3, predicted = null)))
        assertNull(summary(check(1), check(1).copy(time = until - day + 1000), check(2)))
    }

    @Test fun isolatesCurrentMeterAndDoesNotUseBillingAnchors() {
        val observations = (0..2).map { check(it) }
        val data = AppData(profile = Profile(meter = "replacement"), observations = observations)
        assertNull(PredictionErrors.summarize(data, until))
        val mixed = AppData(observations = observations + check(3, 999.0).copy(meter = "old"))
        assertEquals(2.0, PredictionErrors.summarize(mixed, until)!!.meanAbsoluteError, 0.000001)
    }

    @Test fun windowIncludesExactBoundariesButExcludesFutureAndOutdatedChecks() {
        val result = summary(check(90), check(1), check(0),
            check(90, 999.0).copy(time = until - 90 * day - 1),
            check(0, 999.0).copy(time = until + 1))!!
        assertEquals(3, result.sampleCount)
        assertEquals(date.minusDays(90), result.firstCheckDate)
        assertEquals(2.0, result.meanAbsoluteError, 0.000001)
    }

    @Test fun rejectsMissingNegativeAndNonfiniteValuesButAcceptsZero() {
        val invalid = listOf(
            check(3, predicted = null), check(4, -1.0), check(5, predicted = -1.0),
            check(6, Double.NaN), check(7, predicted = Double.NaN),
            check(8, Double.POSITIVE_INFINITY), check(9, predicted = Double.POSITIVE_INFINITY),
            check(10, Double.NEGATIVE_INFINITY), check(11, predicted = Double.NEGATIVE_INFINITY),
        )
        val data = AppData(observations = invalid + listOf(check(0, 0.0, 0.0), check(1), check(2)))
        val result = PredictionErrors.summarize(data, until)!!
        assertEquals(3, result.sampleCount)
        assertEquals(4.0 / 3.0, result.meanAbsoluteError, 0.000001)
    }

    @Test fun duplicateAndSameDayChecksAreOrderIndependent() {
        val first = check(1, 106.0).copy(time = dayStart(date.minusDays(1)))
        val observations = listOf(check(0), check(2), first, first, check(1, 999.0))
        val data = AppData(observations = observations)
        val result = PredictionErrors.summarize(data, until)!!
        assertEquals(3, result.sampleCount)
        assertEquals(10.0 / 3.0, result.meanAbsoluteError, 0.000001)
        assertEquals(result, PredictionErrors.summarize(data.copy(observations = observations.reversed()), until))
    }

    @Test fun conflictingTimestampIsExcludedRatherThanChosenByListOrder() {
        val observations = listOf(check(0), check(1), check(2), check(3), check(3, 999.0))
        val data = AppData(observations = observations)
        val result = PredictionErrors.summarize(data, until)!!
        assertEquals(3, result.sampleCount)
        assertEquals(date.minusDays(2), result.firstCheckDate)
        assertEquals(result, PredictionErrors.summarize(data.copy(observations = observations.reversed()), until))
    }

    @Test fun takesLatestTwelveDistinctDays() {
        val observations = (0..20).map { check(it, 100.0 + it) }
        val result = PredictionErrors.summarize(AppData(observations = observations), until)!!
        assertEquals(12, result.sampleCount)
        assertEquals(5.5, result.meanAbsoluteError, 0.000001)
        assertEquals(11.0, result.maxAbsoluteError, 0.000001)
        assertEquals(date.minusDays(11), result.firstCheckDate)
    }

    @Test fun finiteExtremeImportedValuesDoNotOverflowMean() {
        val result = summary(check(0, Double.MAX_VALUE, 0.0),
            check(1, Double.MAX_VALUE, 0.0), check(2, Double.MAX_VALUE, 0.0))!!
        assertEquals(Double.MAX_VALUE, result.meanAbsoluteError, 0.0)
        assertTrue(result.meanAbsoluteError.isFinite())
    }
}
