package dev.mahlernim.gasselfmeter

import java.time.Duration
import java.time.Instant

/** Initial scheduling only. WorkManager still controls execution and subsequent periodic runs. */
internal object DailyWorkTiming {
    fun initialDelayMillis(time: Long, hour: Int, minute: Int = 0, catchUpIfLate: Boolean = false): Long {
        val now = Instant.ofEpochMilli(time).atZone(Korea)
        var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (next <= now) {
            // An automatic-submission worker must get a chance to recheck the deadline today
            // when the user first enables it after the usual hour. This does not authorize a
            // submission: the worker still checks the supplier, consent and current period.
            if (catchUpIfLate) return 0L
            next = next.plusDays(1)
        }
        return Duration.between(now, next).toMillis()
    }
}
