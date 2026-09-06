package dev.mahlernim.gasselfmeter

import java.time.LocalDate
import java.time.temporal.ChronoUnit

object ReminderPolicy {
    const val DEADLINE = "오늘이 자가검침 마감일이에요. 오늘 안에 꼭! 제출해 주세요."
    const val UNCERTAIN = "전송 결과가 불확실해요. 공급사에서 제출 결과를 확인해 주세요."
    fun calibrationDue(data: AppData, time: Long): Boolean {
        if (!data.ready || !data.profile.reminder) return false
        val now = java.time.Instant.ofEpochMilli(time).atZone(Korea)
        val elapsed = (now.dayOfWeek.value - data.profile.reminderDay + 7) % 7
        if (elapsed > data.profile.reminderRepeatCount || now.hour < data.profile.reminderHour) return false
        val cycleStart = now.toLocalDate().minusDays(elapsed.toLong())
        return data.observations.none { it.meter == data.profile.meter && it.time >= dayStart(cycleStart) }
    }

    /**
     * The next moment the calibration check is worth running, in Korean local time.
     *
     * A reminder cycle offers the chosen weekday plus the next [Profile.reminderRepeatCount] days,
     * each at the chosen hour. A 24-hour periodic request could land anywhere in its window, and a
     * run before the chosen hour produced nothing and then waited another whole day, so the check
     * now aims at these occurrences instead. Late runs are covered by the repeat days, which keep
     * their own dates rather than collapsing into one reminder a week.
     */
    fun nextCalibrationRun(profile: Profile, time: Long): Long {
        var date = java.time.Instant.ofEpochMilli(time).atZone(Korea).toLocalDate()
        // A full week plus a margin always contains the chosen weekday, whatever the repeat count.
        repeat(9) {
            val at = date.atTime(profile.reminderHour, 0).atZone(Korea).toInstant().toEpochMilli()
            val offset = (date.dayOfWeek.value - profile.reminderDay + 7) % 7
            if (at > time && offset <= profile.reminderRepeatCount) return at
            date = date.plusDays(1)
        }
        return time + 7 * 86_400_000L
    }
    fun submissionText(data: AppData, target: SelfReadTarget?, time: Long, failed: Boolean = false): String? {
        target ?: return null
        if (!target.eligible || target.submitted) return null
        val date = dateOf(time)
        if (date !in LocalDate.parse(target.start)..LocalDate.parse(target.end)) return null
        val record = data.submissions.lastOrNull { it.cycle == target.cycle }
        if (record?.status == "confirmed") return null
        if (record?.status in setOf("pending", "uncertain")) return UNCERTAIN
        val days = ChronoUnit.DAYS.between(date, LocalDate.parse(target.end))
        if (days == 0L && (failed || !SubmissionPolicy.decide(data, target, time, automatic = true).allowed)) return DEADLINE
        return "자가검침 입력 기간이에요. 마감까지 ${days}일 남았어요."
    }
}
