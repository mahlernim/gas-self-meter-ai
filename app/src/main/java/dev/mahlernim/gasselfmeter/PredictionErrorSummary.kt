package dev.mahlernim.gasselfmeter

import java.time.LocalDate
import kotlin.math.abs

/** Descriptive discrepancies from saved checks, not a future error bound or confidence interval. */
data class PredictionErrorSummary(
    val meanAbsoluteError: Double,
    val maxAbsoluteError: Double,
    val sampleCount: Int,
    val firstCheckDate: LocalDate,
    val lastCheckDate: LocalDate,
)

object PredictionErrors {
    private const val WINDOW_MILLIS = 90L * 86_400_000L
    private const val MAX_SAMPLES = 12
    private const val MIN_SAMPLES = 3

    /**
     * Compare actual readings against their stored pre-check predictions for the current meter.
     * The window includes both endpoints of the trailing 90 elapsed days through [until].
     * Exact duplicates count once. Conflicting records at one timestamp are excluded because
     * their order cannot establish which is correct. After validating the values, keep only the
     * earliest comparable check per Korean calendar day to avoid overweighting repeated checks
     * made after that day's estimate has already been updated. Use the latest 12 such days.
     * Fewer than three comparable days returns null. No estimator or submission rule is changed.
     */
    fun summarize(data: AppData, until: Long = System.currentTimeMillis()): PredictionErrorSummary? {
        val since = if (until < Long.MIN_VALUE + WINDOW_MILLIS) Long.MIN_VALUE else until - WINDOW_MILLIS
        val samples = data.observations.asSequence()
            .filter { it.meter == data.profile.meter && it.time in since..until }
            .distinct()
            .groupBy { it.time }
            .values.mapNotNull { it.singleOrNull() }
            .filter { it.reading.isFinite() && it.reading >= 0.0 &&
                it.predicted?.let { predicted -> predicted.isFinite() && predicted >= 0.0 } == true }
            .sortedBy { it.time }
            .distinctBy { dateOf(it.time) }
            .takeLast(MAX_SAMPLES)
        if (samples.size < MIN_SAMPLES) return null
        val errors = samples.map { abs(it.reading - it.predicted!!) }
        // Divide before summing so even finite, very large imported values cannot overflow a sum.
        val mean = errors.foldIndexed(0.0) { index, mean, error -> mean + (error - mean) / (index + 1) }
        return PredictionErrorSummary(
            meanAbsoluteError = mean,
            maxAbsoluteError = errors.maxOrNull()!!,
            sampleCount = samples.size,
            firstCheckDate = dateOf(samples.first().time),
            lastCheckDate = dateOf(samples.last().time),
        )
    }
}
