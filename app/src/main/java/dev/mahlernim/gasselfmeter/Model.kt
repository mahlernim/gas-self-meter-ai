package dev.mahlernim.gasselfmeter

import java.time.*
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.*

val Korea: ZoneId = ZoneId.of("Asia/Seoul")
fun today(): LocalDate = LocalDate.now(Korea)
fun dayStart(date: LocalDate): Long = date.atStartOfDay(Korea).toInstant().toEpochMilli()
fun dateOf(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(Korea).toLocalDate()
fun number(value: String): Double = value.trim().replace(",", "").toDoubleOrNull()
    ?.takeIf { it.isFinite() && it >= 0 && it <= 99_999_999 } ?: error("0 이상 99,999,999 이하의 숫자를 입력해 주세요.")

data class UsagePeriod(
    val start: String, val end: String, val usage: Double,
    val meter: String = "manual", val previous: Double? = null, val current: Double? = null,
    val billMonth: String = "", val amount: Double? = null,
    val unitCost: Double? = null, val baseCost: Double? = null,
) {
    val first: LocalDate get() = LocalDate.parse(start)
    val last: LocalDate get() = LocalDate.parse(end)
    val days: Long get() = ChronoUnit.DAYS.between(first, last) + 1
    fun validate() {
        require(days in 1..370 && first.year >= 2000 && last <= today()) { "사용 기간을 확인해 주세요. 미래 기간은 입력할 수 없어요." }
        require(usage.isFinite() && usage in 0.0..9_999_999.0) { "사용량을 확인해 주세요." }
        listOfNotNull(previous, current, amount, unitCost, baseCost).forEach { require(it.isFinite() && it in 0.0..99_999_999.0) }
        if (previous != null && current != null) require(current >= previous && abs(current - previous - usage) < .011) { "지침과 사용량이 일치하지 않아요." }
    }
}
data class Observation(val time: Long, val reading: Double, val meter: String, val predicted: Double? = null)
data class Profile(
    val providerId: String = "busan", val meter: String = "manual", val contract: String = "",
    val plannedDate: String? = null, val syncTime: Long? = null, val reminder: Boolean = false,
    val reminderDay: Int = 7, val reminderHour: Int = 19,
)
data class Credentials(val username: String, val password: String)
data class SubmissionSettings(
    val enabled: Boolean = false,
    val automatic: Boolean = false,
    val requireRecentCheck: Boolean = true,
    val recentDays: Int = 7,
)
data class SubmissionRecord(
    val cycle: String,
    val periodStart: String,
    val periodEnd: String,
    val value: Double,
    val attemptedAt: Long,
    val status: String,
    val detail: String,
)
data class AppData(
    val profile: Profile = Profile(), val periods: List<UsagePeriod> = emptyList(),
    val observations: List<Observation> = emptyList(), val credentials: Credentials? = null,
    val submissionSettings: SubmissionSettings = SubmissionSettings(),
    val submissions: List<SubmissionRecord> = emptyList(),
    val ready: Boolean = false,
)
data class Estimate(val reading: Double?, val daily: Double?, val source: String, val ageDays: Long?, val anchorTime: Long?)
data class SubmissionDecision(val allowed: Boolean, val value: Double?, val reason: String)
object SubmissionGate { val lock = ReentrantLock() }

object SubmissionPolicy {
    fun decide(data: AppData, target: SelfReadTarget?, time: Long = System.currentTimeMillis(), automatic: Boolean): SubmissionDecision {
        val settings = data.submissionSettings
        if (!settings.enabled) return SubmissionDecision(false, null, "검침값 입력 기능이 꺼져 있어요.")
        if (automatic && !settings.automatic) return SubmissionDecision(false, null, "마지막 날 자동 입력이 꺼져 있어요.")
        if (!Providers.get(data.profile.providerId).skens || data.profile.meter == "demo") return SubmissionDecision(false, null, "자동 연결한 SK E&S 공급사 계정에서만 사용할 수 있어요.")
        if (automatic && !Providers.get(data.profile.providerId).automaticSubmission) return SubmissionDecision(false, null, "이 공급사는 직접 확인 입력만 지원해요.")
        if (automatic && data.credentials == null) return SubmissionDecision(false, null, "자동 입력에 필요한 로그인 정보가 저장되어 있지 않아요.")
        if (target == null) return SubmissionDecision(false, null, "먼저 공급사에서 검침 기간을 확인해 주세요.")
        if (SkensClient.contractKey(Providers.get(data.profile.providerId), target.contract) != data.profile.contract) {
            return SubmissionDecision(false, null, "조회한 계약이 저장된 계약과 달라요. 공급사를 다시 연결해 주세요.")
        }
        if (target.serial.isBlank() || SkensClient.opaque(target.serial) != data.profile.meter) {
            return SubmissionDecision(false, null, "계량기가 변경되었어요. 공급사를 새로고침하고 실제 숫자를 다시 확인해 주세요.")
        }
        if (!target.eligible) return SubmissionDecision(false, null, "이 계약은 자가검침 대상이 아니에요.")
        if (target.submitted) return SubmissionDecision(false, target.submittedValue, "이번 검침값은 이미 입력되어 있어요.")
        val date = dateOf(time)
        val start = LocalDate.parse(target.start)
        val end = LocalDate.parse(target.end)
        if (date !in start..end) return SubmissionDecision(false, null, "입력 가능 기간은 ${target.start}부터 ${target.end}까지예요.")
        if (automatic && date != end) return SubmissionDecision(false, null, "자동 입력은 검침 기간 마지막 날에 실행해요.")
        val prior = data.submissions.lastOrNull { it.cycle == target.cycle }
        if (prior?.status in setOf("pending", "confirmed", "uncertain")) {
            return SubmissionDecision(false, prior?.value, if (prior?.status == "confirmed") "이번 검침값 입력을 완료했어요." else "이전 전송 결과를 먼저 공급사에서 확인해 주세요.")
        }
        val latest = data.observations.lastOrNull { it.meter == data.profile.meter }
            ?: return SubmissionDecision(false, null, "실제 계량기 숫자를 먼저 확인해 주세요.")
        val age = ((time - latest.time) / 86_400_000).coerceAtLeast(0)
        if (settings.requireRecentCheck && age > settings.recentDays) {
            return SubmissionDecision(false, null, "마지막 실측 확인이 ${age}일 전이에요. ${settings.recentDays}일 이내에 다시 확인해 주세요.")
        }
        val estimated = Estimator.estimate(data, time).reading
            ?: return SubmissionDecision(false, null, "제출할 현재 누적 지침을 계산할 수 없어요.")
        val value = kotlin.math.round(estimated * 10.0) / 10.0
        if (target.previousValue != null && value < target.previousValue) return SubmissionDecision(false, value, "계산한 값이 공급사의 이전 검침값보다 작아 입력하지 않아요.")
        val reason = if (automatic) "오늘은 검침 기간 마지막 날이며, 마지막 실측 확인이 ${age}일 전이에요." else "검침 기간과 기존 입력 여부를 확인했어요."
        return SubmissionDecision(true, value, reason)
    }
}

/** Local adaptive estimate. No external AI service, invented readings or fitted accuracy claims. */
object Estimator {
    private data class Anchor(val time: Long, val reading: Double)
    private fun anchors(data: AppData, until: Long): List<Anchor> = (
        data.observations.filter { it.meter == data.profile.meter && it.time <= until }.map { Anchor(it.time, it.reading) } +
        data.periods.filter { it.meter == data.profile.meter && it.current != null }
            .map { Anchor(dayStart(it.last.plusDays(1)), it.current!!) }.filter { it.time <= until }
    ).distinctBy { it.time }.sortedBy { it.time }

    fun monthlyRate(periods: List<UsagePeriod>, month: YearMonth): Double? {
        // Imported segments must not overlap. A month needs at least 14 covered days.
        var sum = 0.0
        var covered = 0L
        periods.forEach { p ->
            val start = maxOf(p.first, month.atDay(1))
            val end = minOf(p.last, month.atEndOfMonth())
            if (start <= end) {
                val days = ChronoUnit.DAYS.between(start, end) + 1
                covered += days
                sum += p.usage / p.days * days
            }
        }
        return if (covered >= minOf(14, month.lengthOfMonth())) sum / covered else null
    }

    fun seasonalRate(periods: List<UsagePeriod>, date: LocalDate): Double? {
        val lastYear = date.minusYears(1)
        val month = YearMonth.from(lastYear)
        val center = month.atDay(15)
        val rate = monthlyRate(periods, month) ?: return null
        val neighborMonth = if (lastYear < center) month.minusMonths(1) else month.plusMonths(1)
        val neighbor = monthlyRate(periods, neighborMonth) ?: return rate
        val distance = abs(ChronoUnit.DAYS.between(center, neighborMonth.atDay(15))).toDouble()
        val weight = abs(ChronoUnit.DAYS.between(center, lastYear)) / distance
        return rate * (1 - weight) + neighbor * weight
    }

    private fun integrate(start: Long, end: Long, rate: (LocalDate) -> Double?): Double? {
        if (end < start || end - start > 370L * 86_400_000) return null
        var cursor = start
        var total = 0.0
        while (cursor < end) {
            val date = dateOf(cursor)
            val next = minOf(end, dayStart(date.plusDays(1)))
            val daily = rate(date) ?: return null
            total += daily * (next - cursor) / 86_400_000.0
            cursor = next
        }
        return total
    }

    fun estimate(data: AppData, time: Long = System.currentTimeMillis(), evidenceUntil: Long = time): Estimate {
        val anchors = anchors(data, minOf(time, evidenceUntil))
        val anchor = anchors.lastOrNull() ?: return Estimate(null, null, "첫 계량기 확인이 필요해요", null, null)
        val age = ((time - anchor.time) / 86_400_000).coerceAtLeast(0)
        if (age > 60) return Estimate(null, null, "확인한 지 60일이 지났어요", age, anchor.time)
        val physical = data.observations.filter { it.meter == data.profile.meter && it.time <= evidenceUntil && it.time <= time }.sortedBy { it.time }
        val last = physical.lastOrNull()
        val first = if (last == null) null else physical.lastOrNull { it.time <= last.time - 86_400_000 && it.time >= last.time - 28L * 86_400_000 }
        val span = if (first != null && last != null) (last.time - first.time) / 86_400_000.0 else 0.0
        val recent = if (span > 0 && last!!.reading >= first!!.reading) (last.reading - first.reading) / span else null
        val daysSince = if (last != null) (time - last.time) / 86_400_000.0 else Double.POSITIVE_INFINITY
        val priorInterval = if (first != null && last != null) integrate(first.time, last.time) { seasonalRate(data.periods, it) } else null
        val weight = (span / (span + 7.0)) * (1.0 - daysSince / 28.0).coerceIn(0.0, 1.0)
        val ratio = if (priorInterval != null && priorInterval > .01 && recent != null) ((last!!.reading - first!!.reading) / priorInterval).coerceIn(0.0, 5.0) else null
        fun rate(date: LocalDate): Double? {
            val seasonal = seasonalRate(data.periods, date)
            // Decay on each integrated date, not on the forecast's end date. Otherwise
            // revising the entire elapsed interval can make a cumulative meter run backward.
            val dateAge = if (last != null) ((dayStart(date) - last.time) / 86_400_000.0).coerceAtLeast(0.0) else Double.POSITIVE_INFINITY
            val dateWeight = (span / (span + 7.0)) * (1.0 - dateAge / 28.0).coerceIn(0.0, 1.0)
            if (seasonal != null) {
                if (ratio != null) return seasonal * (1.0 + dateWeight * (ratio - 1.0))
                if (recent != null && dateAge <= 14) return seasonal * (1 - dateWeight) + recent * dateWeight
                return seasonal
            }
            return recent?.takeIf { daysSince <= 14 }
        }
        val increment = integrate(anchor.time, time, ::rate)
        val daily = rate(dateOf(time))
        val source = when {
            increment == null -> "작년 이력 또는 두 번의 실측이 필요해요"
            daily == null -> "확인한 계량기 숫자"
            seasonalRate(data.periods, dateOf(time)) == null -> "최근 실측 기준 · 계절 정보 없음"
            recent != null && weight > 0 -> "작년 계절 흐름 + 최근 실측 보정"
            else -> "작년 계절 흐름 기준"
        }
        return Estimate(increment?.let { anchor.reading + it }, daily, source, age, anchor.time)
    }

    fun addObservation(data: AppData, reading: Double, time: Long = System.currentTimeMillis()): AppData {
        require(reading.isFinite() && reading in 0.0..99_999_999.0)
        // A correction within ten minutes replaces that check and preserves its original forecast.
        val previous = data.observations.lastOrNull()?.takeIf { it.meter == data.profile.meter && time - it.time in 0..600_000 }
        val kept = if (previous != null) data.observations.dropLast(1) else data.observations
        val anchor = anchors(data.copy(observations = kept), time).lastOrNull()
        require(anchor == null || reading >= anchor.reading) { "이전 확인값보다 작아요. 계량기를 교체했다면 설정에서 새 계량기로 시작해 주세요." }
        val prior = estimate(data, time).reading
        return data.copy(observations = kept + Observation(time, reading, data.profile.meter, previous?.predicted ?: prior))
    }

    fun validatePeriods(periods: List<UsagePeriod>) {
        require(periods.size <= 600) { "사용 이력은 600개까지 가져올 수 있어요." }
        periods.forEach { it.validate() }
        val sorted = periods.sortedBy { it.start }
        sorted.zipWithNext().forEach { (a, b) -> require(a.last < b.first) { "사용 기간이 겹쳐요. 기존 이력의 날짜를 확인해 주세요." } }
    }
}

data class MonthlyHistory(
    val month: YearMonth,
    val usage: Double?,
    val billedAmount: Double?,
)

object HistorySummary {
    fun months(periods: List<UsagePeriod>, latest: YearMonth, count: Int = 24): List<MonthlyHistory> {
        require(count in 1..120)
        return (count downTo 1).map { offset ->
            val month = latest.minusMonths(offset.toLong())
            val usage = Estimator.monthlyRate(periods, month)?.times(month.lengthOfMonth())
            val billMonth = "%04d%02d".format(Locale.ROOT, month.year, month.monthValue)
            val exactAmounts = periods.asSequence()
                .filter { it.billMonth == billMonth }
                .mapNotNull { it.amount }
                .distinct()
                .toList()
            MonthlyHistory(month, usage, exactAmounts.singleOrNull())
        }
    }
}
