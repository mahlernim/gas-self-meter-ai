package dev.mahlernim.gasselfmeter

import java.time.LocalDate
import kotlin.math.floor

/** Same one-shot policy for manual checks and explicitly enabled last-day background submissions. */
object DirectSubmissionPolicy {
    fun decide(data: AppData, target: SelfReadTarget?, time: Long = System.currentTimeMillis(), automatic: Boolean = false): SubmissionDecision {
        fun deny(reason: String) = SubmissionDecision(false, null, reason)
        val provider = Providers.get(data.profile.providerId)
        val settings = data.submissionSettings
        if (!provider.direct || data.profile.reconnectRequired) return deny("공급사 연결 정보를 확인해 주세요.")
        // Match the existing client's range before saving pending. A local validation failure
        // must not unnecessarily become an uncertain network attempt.
        val maximumReading = if (provider.id == "haeyang") 99_999_999.0 else 99_999.0
        if (automatic && (!data.ready || !settings.automatic || !provider.automaticSubmission)) return deny("마지막 날 자동 제출이 꺼져 있어요.")
        if (automatic && data.credentials == null) return deny("자동 제출에 필요한 로그인 정보를 저장해 주세요.")
        if (data.profile.meter == DirectIdentity.meter(data.profile.providerId, data.profile.customerNumber, null))
            return deny("공급사에서 계량기 정보를 확인하지 못했어요. 실제 숫자를 직접 기록해 주세요.")
        if (target == null || target.contract.bp != data.profile.customerNumber || target.contract.ca != data.profile.providerId ||
            DirectIdentity.contract(data.profile.providerId, target.contract.bp) != data.profile.contract || target.installation != data.profile.meter)
            return deny("계약 또는 계량기 정보가 달라요. 다시 조회해 주세요.")
        if (!target.eligible || target.submitted) return deny("이번 검침 대상 상태를 공급사에서 다시 확인해 주세요.")
        if (target.previousValue == null || !target.previousValue.isFinite() || target.previousValue !in 0.0..maximumReading)
            return deny("공급사의 이전 검침값을 확인하지 못했어요.")
        val date = dateOf(time)
        val start = LocalDate.parse(target.start)
        val end = LocalDate.parse(target.end)
        if (date !in start..end) return deny("자가검침 입력 기간이 아니에요.")
        if (automatic && date != end) return deny("자동 제출은 검침 기간 마지막 날에 실행해요.")
        val attempts = data.submissions.filter { it.cycle == target.cycle }
        if ((automatic && attempts.isNotEmpty()) || attempts.any { it.status in setOf("pending", "uncertain", "confirmed") })
            return deny("이전 전송 결과를 먼저 확인해 주세요.")
        val observed = data.observations.filter { it.meter == data.profile.meter }.maxByOrNull { it.time }
            ?: return deny("실제 계량기 숫자를 먼저 확인해 주세요.")
        if (observed.time > time) return deny("실측 확인 시각이 현재보다 미래예요. 기기 시간을 확인해 주세요.")
        val age = (time - observed.time) / 86_400_000L
        if (automatic && settings.requireRecentCheck && age > settings.recentDays)
            return deny("마지막 실측 확인이 ${age}일 전이에요. ${settings.recentDays}일 이내에 다시 확인해 주세요.")
        val reading = floor((if (!automatic && dateOf(observed.time) == date) observed.reading else Estimator.estimate(data, time).reading)
            ?: return deny("제출할 지침을 계산할 수 없어요."))
        if (!reading.isFinite() || reading !in 0.0..maximumReading || reading < target.previousValue)
            return deny("이전 지침과 제출값을 확인해 주세요.")
        return SubmissionDecision(true, reading, if (automatic) "오늘은 검침 기간 마지막 날이며, 마지막 실측 확인이 ${age}일 전이에요."
            else "검침 기간과 기존 제출 여부를 확인했어요.")
    }

    /** Exempt only this exact durable pending record while repeating policy checks before sending. */
    internal fun canSendPending(latest: AppData, expected: AppData, target: SelfReadTarget, record: SubmissionRecord,
        automatic: Boolean, time: Long = System.currentTimeMillis()): Boolean {
        if (record.status != "pending" || record.cycle != target.cycle || record.periodStart != target.start || record.periodEnd != target.end ||
            !BackgroundState.sameAccount(latest, expected) || latest.submissions.count { it == record } != 1) return false
        val decision = decide(latest.copy(submissions = latest.submissions.filterNot { it == record }), target, time, automatic)
        return decision.allowed && decision.value == record.value
    }
}
