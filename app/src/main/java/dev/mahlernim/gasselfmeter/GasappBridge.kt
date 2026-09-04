package dev.mahlernim.gasselfmeter

import android.content.Context
import java.time.LocalDate
import kotlin.concurrent.withLock
import kotlin.math.floor

/** Provider reads and writes remain separate. Every write is preceded by a persisted pending record. */
object GasappBridge {
    fun connect(context: Context, session: GasappSession, account: GasappAccount): AppData = SubmissionGate.lock.withLock {
        val store = SecureStore(context)
        val initial = store.read()
        val connection = GasappConnection(session, account)
        GasappApi(session.deviceId).use { api ->
            val snapshot = api.snapshot(session, account)
            store.update { latest ->
                check(latest.profile.contract == initial.profile.contract) { "연결 계정이 변경됐어요. 다시 확인해 주세요." }
                merge(latest, connection, snapshot).copy(submissionSettings = latest.submissionSettings.copy(automatic = false))
            }
        }
    }

    fun checkStatus(context: Context): AppData = check(context)

    fun merge(data: AppData, connection: GasappConnection, snapshot: GasappSnapshot, now: Long = System.currentTimeMillis()): AppData {
        require(connection.account.key == snapshot.account.key && snapshot.target.account.key == connection.account.key)
        val providerId = GasappApi.companyProviders[connection.account.company] ?: error("지원하지 않는 공급사예요.")
        require(Providers.get(providerId).gasapp) { "지원하지 않는 공급사예요." }
        require(data.profile.contract.isBlank() || data.profile.contract == connection.account.key) {
            "다른 계약이에요. 현재 기록을 내보낸 후 데이터를 초기화해 주세요."
        }
        val target = snapshot.target
        val meter = target.meter ?: data.profile.meter
        // Only provider readings with an explicit, matching meter identity become estimator anchors.
        // Imported readings never become physical checks used by the recent-calibration safeguard.
        val readings = snapshot.readings.filter { r ->
            r.meter != null && gasappHash("meter:${connection.account.company}:${r.meter}") == meter &&
                LocalDate.parse(r.date) <= dateOf(now)
        }.distinctBy { it.date }.sortedBy { it.date }
        val intervals = readings.zipWithNext().mapNotNull { (a, b) ->
            val start = LocalDate.parse(a.date)
            val end = LocalDate.parse(b.date).minusDays(1)
            if (end < start || b.value < a.value || java.time.temporal.ChronoUnit.DAYS.between(start, end) >= 370) null
            else UsagePeriod(start.toString(), end.toString(), b.value - a.value, meter, a.value, b.value)
        }
        val datedBills = snapshot.bills.mapNotNull { b ->
            if (b.start == null || b.end == null || b.usage == null || LocalDate.parse(b.end) > dateOf(now)) null
            else UsagePeriod(b.start, b.end, b.usage, meter, billMonth = b.month.replace("-", ""), amount = b.amount)
        }.filter { bill -> intervals.none { it.first <= bill.last && bill.first <= it.last } }
        val incoming = intervals + datedBills
        val periods = (data.periods.filter { old -> incoming.none { it.first <= old.last && old.first <= it.last } } + incoming).sortedBy { it.start }
        Estimator.validatePeriods(periods)
        val bills = (data.gasappBills + snapshot.bills).associateBy { it.month }.values.sortedBy { it.month }.takeLast(600)
        return data.copy(profile = data.profile.copy(providerId = providerId, contract = connection.account.key,
            customerNumber = connection.account.customer.ifBlank { connection.account.contract }, meter = meter,
            plannedDate = target.end, syncTime = now), periods = periods, ready = true, credentials = null,
            cachedSelfRead = null, gasappConnection = connection, cachedGasappTarget = target, gasappBills = bills, gasappMeterChangeObservedAt = replacementObservedAt(data, target, now))
    }

    fun replacementObservedAt(data: AppData, target: GasappTarget, now: Long): Long? {
        val old = data.cachedGasappTarget
        val changedMeter = target.meter != null && target.meter != data.profile.meter
        val newlyFlagged = target.meterChanged && (old?.meterChanged != true || old.meter != target.meter)
        return if (changedMeter || newlyFlagged) now else data.gasappMeterChangeObservedAt
    }

    private fun reconcileRecord(api: GasappApi, initial: AppData): SubmissionRecord? {
        val expected = initial.cachedGasappTarget ?: return null
        val record = initial.submissions.lastOrNull { it.cycle == expected.cycle && it.status in setOf("pending", "uncertain") } ?: return null
        val connection = initial.gasappConnection ?: return null
        if (expected.account.key != connection.account.key || expected.meter != initial.profile.meter) return null
        val result = api.reconcile(connection.session, expected, record.value)
        return record.takeIf { result.status == GasappSubmitStatus.CONFIRMED }
    }

    fun applyReconciliation(latest: AppData, initial: AppData, record: SubmissionRecord?): AppData {
        if (record == null || !sameAccount(initial, latest)) return latest
        val current = latest.submissions.lastOrNull { it.cycle == record.cycle }
        if (current != record || current.status !in setOf("pending", "uncertain")) return latest
        return latest.copy(submissions = latest.submissions.map { if (it == current) it.copy(status = "confirmed", detail = "공급사에서 제출 완료를 확인했어요.") else it })
    }

    fun refresh(context: Context, force: Boolean = false): AppData = SubmissionGate.lock.withLock {
        val store = SecureStore(context)
        val initial = store.read()
        val connection = initial.gasappConnection ?: return@withLock initial
        if (!initial.ready || (!force && initial.profile.syncTime?.let { System.currentTimeMillis() - it < 86_400_000L } == true)) return@withLock initial
        GasappApi(connection.session.deviceId).use { api ->
            val snapshot = api.snapshot(connection.session, connection.account)
            val confirmed = reconcileRecord(api, initial)
            store.update { latest -> if (sameAccount(initial, latest)) merge(applyReconciliation(latest, initial, confirmed), connection, snapshot) else latest }.also {
                if (it.cachedGasappTarget?.submitted == true) context.getSystemService(android.app.NotificationManager::class.java).cancel(3)
            }
        }
    }

    fun check(context: Context): AppData = SubmissionGate.lock.withLock {
        val store = SecureStore(context)
        val initial = store.read()
        val connection = initial.gasappConnection ?: error("가스앱에 다시 연결해 주세요.")
        GasappApi(connection.session.deviceId).use { api ->
            val target = api.target(connection.session, connection.account)
            val confirmed = reconcileRecord(api, initial)
            store.update { latest -> if (sameAccount(initial, latest)) applyReconciliation(latest, initial, confirmed).copy(
                cachedGasappTarget = target, gasappMeterChangeObservedAt = replacementObservedAt(latest, target, System.currentTimeMillis())) else latest }.also {
                if (it.cachedGasappTarget?.submitted == true) context.getSystemService(android.app.NotificationManager::class.java).cancel(3)
            }
        }
    }

    fun submit(context: Context, value: Double? = null, automatic: Boolean, cancelled: () -> Boolean = { false }): AppData = SubmissionGate.lock.withLock {
        val store = SecureStore(context)
        val initial = store.read()
        val connection = initial.gasappConnection ?: error("가스앱에 다시 연결해 주세요.")
        GasappApi(connection.session.deviceId).use { api ->
            val target = api.target(connection.session, connection.account)
            val current = store.update { latest ->
                check(sameAccount(initial, latest)) { "연결 계정이 변경됐어요. 다시 확인해 주세요." }
                latest.copy(cachedGasappTarget = target, gasappMeterChangeObservedAt = replacementObservedAt(latest, target, System.currentTimeMillis()))
            }
            val decision = GasappSubmissionPolicy.decide(current, target, automatic = automatic)
            require(decision.allowed && decision.value != null) { decision.reason }
            val selected = decision.value
            require(value == null || value == selected) { "확인 후 제출값이 달라졌어요. 화면에서 다시 확인해 주세요." }
            val record = SubmissionRecord(target.cycle, target.start!!, target.end!!, selected, System.currentTimeMillis(), "pending", "공급사 확인 대기")
            store.update { latest ->
                check(sameAccount(initial, latest)) { "연결 계정이 변경됐어요. 다시 확인해 주세요." }
                val freshDecision = GasappSubmissionPolicy.decide(latest, target, automatic = automatic)
                check(!cancelled() && freshDecision.allowed && freshDecision.value == selected) { "제출 조건이 변경됐어요. 다시 확인해 주세요." }
                latest.copy(submissions = (latest.submissions.filterNot { it.cycle == record.cycle } + record).takeLast(100))
            }
            val beforePost = store.read()
            val pending = beforePost.submissions.lastOrNull { it.cycle == record.cycle }
            val beforeDecision = GasappSubmissionPolicy.decide(beforePost.copy(submissions = beforePost.submissions.filterNot { it.cycle == record.cycle }), target, automatic = automatic)
            if (cancelled() || !sameAccount(initial, beforePost) || pending != record || !beforeDecision.allowed || beforeDecision.value != selected) {
                return@withLock store.update { latest -> BackgroundState.finish(latest, initial,
                    record.copy(status = "rejected", detail = "제출 조건이 변경되어 전송하지 않았어요.")) }
            }
            val outcome = try { api.submit(connection.session, target, selected) }
                catch (_: Exception) { GasappSubmitResult(GasappSubmitStatus.UNCERTAIN, null) }
            val status = outcome.status.name.lowercase()
            val detail = when (outcome.status) {
                GasappSubmitStatus.CONFIRMED -> "공급사에서 제출 완료를 확인했어요."
                GasappSubmitStatus.REJECTED -> "공급사가 제출을 받지 않았어요. 공급사에서 확인해 주세요."
                GasappSubmitStatus.UNCERTAIN -> "전송 결과가 불확실해요. 공급사에서 결과를 확인해 주세요."
            }
            store.update { latest ->
                val finished = BackgroundState.finish(latest, initial, record.copy(status = status, detail = detail))
                if (finished === latest) latest else finished.copy(cachedGasappTarget = outcome.target ?: target)
            }
        }
    }

    fun sameAccount(a: AppData, b: AppData): Boolean = BackgroundState.sameAccount(b, a)
}

object GasappSubmissionPolicy {
    fun decide(data: AppData, target: GasappTarget?, time: Long = System.currentTimeMillis(), automatic: Boolean): SubmissionDecision {
        fun deny(reason: String) = SubmissionDecision(false, null, reason)
        val connection = data.gasappConnection ?: return deny("가스앱에 다시 연결해 주세요.")
        if (!Providers.get(data.profile.providerId).gasapp) return deny("가스앱 연결 정보를 확인해 주세요.")
        if (automatic && !data.submissionSettings.automatic) return deny("자가검침 자동제출이 꺼져 있어요.")
        if (target == null) return deny("검침 기간을 먼저 확인해 주세요.")
        if (target.account.key != connection.account.key || data.profile.contract != target.account.key) return deny("계약 정보가 달라요. 다시 연결해 주세요.")
        if (target.meter == null || target.meter != data.profile.meter) return deny("계량기 정보를 갱신하고 실제 숫자를 다시 확인해 주세요.")
        if (!target.registered) return deny("자가검침 서비스 신청이 필요해요.")
        if (target.needsChannelChange) return deny("가스앱 검침으로 변경이 필요해요.")
        if (target.submitted) return deny("이번 검침값은 이미 제출했어요.")
        if (!target.eligible || target.start == null || target.end == null) return deny("지금은 자가검침 제출 대상이 아니에요.")
        val date = dateOf(time)
        if (date !in LocalDate.parse(target.start)..LocalDate.parse(target.end)) return deny("자가검침 입력 기간이 아니에요.")
        if (automatic && date != LocalDate.parse(target.end)) return deny("자동제출은 검침 기간 마지막 날에 실행해요.")
        val prior = data.submissions.lastOrNull { it.cycle == target.cycle }
        if (prior?.status in setOf("pending", "uncertain", "confirmed")) return deny("이전 전송 결과를 공급사에서 확인해 주세요.")
        val observation = data.observations.lastOrNull { it.meter == data.profile.meter && it.time <= time }
            ?: return deny("실제 계량기 숫자를 먼저 확인해 주세요.")
        if (target.meterChanged && (data.gasappMeterChangeObservedAt == null || observation.time <= data.gasappMeterChangeObservedAt))
            return deny("교체된 계량기의 실제 숫자를 다시 확인해 주세요.")
        val age = ((time - observation.time) / 86_400_000L).coerceAtLeast(0)
        if (automatic && data.submissionSettings.requireRecentCheck && age > data.submissionSettings.recentDays) return deny("보정한 지 오래됐어요. 실제 숫자를 다시 확인해 주세요.")
        val estimate = Estimator.estimate(data, time).reading ?: return deny("제출할 지침을 계산할 수 없어요.")
        val value = floor(estimate)
        if (target.previous == null || !value.isFinite() || value < target.previous || value > 99_999_999) return deny("이전 지침과 제출값을 확인해 주세요.")
        if (target.digits != null && value.toLong().toString().length > target.digits) return deny("계량기 자릿수를 확인해 주세요.")
        return SubmissionDecision(true, value, "검침 기간과 기존 제출 여부를 확인했어요.")
    }
}
