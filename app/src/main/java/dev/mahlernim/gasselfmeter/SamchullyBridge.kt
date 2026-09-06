package dev.mahlernim.gasselfmeter

import android.content.Context
import java.time.LocalDate
import java.time.YearMonth
import kotlin.concurrent.withLock
import kotlin.math.floor

data class SamchullyLogin(val session: SamchullySession, val contracts: List<SamchullyContract>)
data class SamchullySnapshot(
    val contract: SamchullyContract,
    val bills: List<SamchullyBill>,
    val periods: List<UsagePeriod>,
    val meter: String,
    val selfRead: SelfReadTarget?,
    val warning: String,
)

/** Account bridge for Samchully billing history and self-reading. */
object SamchullyBridge {
    fun login(credentials: Credentials): SamchullyLogin =
        SamchullyReadClient(Providers.get("samchully"), credentials).use { client ->
            val session = client.login()
            SamchullyLogin(session, client.contracts(session, client.user(session)))
        }

    fun snapshot(login: SamchullyLogin, contract: SamchullyContract, now: Long = System.currentTimeMillis()): SamchullySnapshot {
        require(login.contracts.any { it == contract }) { "로그인한 계정의 계약을 다시 선택해 주세요." }
        val (from, to) = queryWindow(dateOf(now))
        // Credentials are used only by login. History requests use the selected session token.
        return SamchullyReadClient(Providers.get("samchully")).use { client ->
            val bills = client.bills(login.session, contract.customerNo, from, to)
            val state = client.selfReadState(login.session, contract)
            assemble(contract, bills, now, state)
        }
    }

    internal fun queryWindow(date: LocalDate): Pair<YearMonth, YearMonth> =
        YearMonth.from(date).let { it.minusMonths(23) to it }

    internal fun meterKey(contract: SamchullyContract, meterId: String?): String =
        SkensClient.opaque("samchully:${contract.customerNo}:meter:${meterId ?: "unverified"}")

    internal fun assemble(contract: SamchullyContract, bills: List<SamchullyBill>, now: Long, state: SamchullySelfReadState? = null): SamchullySnapshot {
        val meter = meterKey(contract, contract.meterId)
        val periods = bills.mapNotNull { bill ->
            val start = bill.start ?: return@mapNotNull null
            val end = bill.end ?: return@mapNotNull null
            val previous = bill.previousReading ?: return@mapNotNull null
            val current = bill.currentReading ?: return@mapNotNull null
            // Reported consumption may be corrected or expressed in another unit. Only an
            // explicit cumulative-meter difference is used as an estimator quantity.
            val identity = bill.meterId ?: return@mapNotNull null
            if (LocalDate.parse(end) > dateOf(now)) return@mapNotNull null
            UsagePeriod(start, end, current - previous, meterKey(contract, identity), previous, current,
                billMonth = bill.billMonth, amount = bill.amount).also { it.validate() }
        }.sortedBy { it.start }
        Estimator.validatePeriods(periods)
        val warning = buildList {
            if (bills.isEmpty()) add("조회 기간에 청구 이력이 없어요.")
            if (periods.size < bills.size) add("날짜·누적 지침·계량기 정보가 부족한 청구는 추정 계산에서 제외했어요.")
            if (contract.meterId == null) add("현재 계량기 번호를 확인하지 못했어요. 실제 숫자를 직접 기록해 주세요.")
            if (periods.any { it.meter != meter }) add("다른 계량기의 누적 지침은 현재 지침에 연결하지 않아요.")
        }.joinToString(" ")
        return SamchullySnapshot(contract, bills, periods, meter, selfReadTarget(contract, state), warning)
    }

    fun merge(data: AppData, snapshot: SamchullySnapshot, credentials: Credentials?, now: Long = System.currentTimeMillis()): AppData {
        require(data.profile.contract.isBlank() ||
            (data.profile.providerId == "samchully" && data.profile.contract == snapshot.contract.key)) {
            "다른 계약이에요. 현재 기록을 내보낸 후 데이터를 초기화해 주세요."
        }
        require(data.profile.customerNumber.isBlank() || data.profile.contract.isBlank() ||
            data.profile.customerNumber == snapshot.contract.customerNo) { "저장된 고객번호가 달라요. 계약을 다시 확인해 주세요." }
        val incoming = snapshot.periods
        val periods = (data.periods.filter { old -> incoming.none { it.first <= old.last && old.first <= it.last } } + incoming)
            .sortedBy { it.start }
        Estimator.validatePeriods(periods)
        return data.copy(
            profile = data.profile.copy(providerId = "samchully", contract = snapshot.contract.key,
                customerNumber = snapshot.contract.customerNo, meter = snapshot.meter, plannedDate = null, syncTime = now),
            periods = periods, credentials = credentials, ready = true, cachedSelfRead = snapshot.selfRead,
            samchullyBills = (data.samchullyBills + snapshot.bills.map { bill ->
                bill.copy(meterId = bill.meterId?.let { meterKey(snapshot.contract, it) })
            }).associateBy { it.billMonth }.values.sortedBy { it.billMonth }.takeLast(120),
            submissionSettings = data.submissionSettings.copy(automatic = false),
            gasappConnection = null, cachedGasappTarget = null,
            gasappBills = emptyList(), gasappMeterChangeObservedAt = null,
            energyTalkConnection = null, energyTalkBills = emptyList(),
            directBills = emptyList(),
        )
    }

    internal fun selfReadTarget(contract: SamchullyContract, state: SamchullySelfReadState?): SelfReadTarget? {
        val source = state ?: return null
        val start = source.start ?: return null
        val end = source.end ?: return null
        val targetId = source.targetId ?: return null
        require(LocalDate.parse(start) <= LocalDate.parse(end)) { "삼천리 검침 기간을 확인하지 못했어요." }
        return SelfReadTarget(
            cycle = "$start:$end:${SkensClient.opaque("samchully:${contract.customerNo}:$targetId")}",
            start = start, end = end, eligible = source.submitted == false, submitted = source.submitted == true,
            submittedValue = source.submittedReading, previousValue = source.previousReading,
            contract = Contract(contract.customerNo, "samchully", contract.label), serial = targetId,
            address = "", planned = "", vLdo = "", installation = meterKey(contract, contract.meterId),
        )
    }

    internal fun reconciledRecord(data: AppData, target: SelfReadTarget?): SubmissionRecord? {
        val current = target ?: return null
        val record = data.submissions.lastOrNull { it.status in setOf("pending", "uncertain") && it.cycle == current.cycle } ?: return null
        return record.takeIf {
            current.submitted && current.submittedValue == it.value && current.contract.bp == data.profile.customerNumber &&
                SkensClient.opaque("samchully:${current.contract.bp}") == data.profile.contract && current.installation == data.profile.meter
        }
    }

    internal fun applyReconciliation(data: AppData, record: SubmissionRecord?): AppData {
        if (record == null) return data
        return data.copy(submissions = data.submissions.map { current ->
            if (current == record && current.status in setOf("pending", "uncertain"))
                current.copy(status = "confirmed", detail = "공급사에서 제출 완료를 확인했어요.") else current
        })
    }

    fun checkStatus(context: Context): AppData = SubmissionGate.lock.withLock {
        val store = SecureStore(context)
        val initial = store.read()
        val credentials = initial.credentials ?: error("삼천리 로그인 정보를 다시 입력해 주세요.")
        require(initial.profile.providerId == "samchully") { "삼천리 연결 정보를 확인해 주세요." }
        val login = login(credentials)
        val contract = login.contracts.singleOrNull { it.key == initial.profile.contract }
            ?: error("저장한 삼천리 계약을 찾지 못했어요. 다시 연결해 주세요.")
        val state = SamchullyReadClient(Providers.get("samchully")).use { it.selfReadState(login.session, contract) }
        val target = selfReadTarget(contract, state)
        store.update { latest ->
            if (BackgroundState.sameAccount(latest, initial))
                applyReconciliation(latest, reconciledRecord(latest, target)).copy(cachedSelfRead = target)
            else latest
        }
    }

    fun submit(context: Context, value: Double? = null, automatic: Boolean = false, cancelled: () -> Boolean = { false }): AppData = SubmissionGate.lock.withLock {
        val store = SecureStore(context)
        val initial = checkStatus(context)
        val target = initial.cachedSelfRead ?: error("현재 자가검침 대상을 찾지 못했어요.")
        val decision = SamchullySubmissionPolicy.decide(initial, target, automatic = automatic)
        require(decision.allowed && decision.value != null) { decision.reason }
        val selected = decision.value
        require(value == null || value == selected) { "확인 후 제출값이 달라졌어요. 화면에서 다시 확인해 주세요." }
        val record = SubmissionRecord(target.cycle, target.start, target.end, selected, System.currentTimeMillis(), "pending", "공급사 확인 대기")
        store.update { latest ->
            val fresh = SamchullySubmissionPolicy.decide(latest, target, automatic = automatic)
            check(!cancelled() && BackgroundState.sameAccount(latest, initial) && fresh.allowed && fresh.value == selected) { "제출 조건이 변경됐어요. 다시 확인해 주세요." }
            latest.copy(submissions = (latest.submissions.filterNot { it.cycle == target.cycle } + record).takeLast(100))
        }
        if (cancelled()) return@withLock store.update { latest -> BackgroundState.finish(latest, initial, record.copy(status = "rejected", detail = "제출 조건이 변경되어 전송하지 않았어요.")) }
        val credentials = initial.credentials ?: error("삼천리 로그인 정보를 다시 입력해 주세요.")
        val outcome = try {
            SamchullyReadClient(Providers.get("samchully"), credentials).use { client ->
                val login = client.login()
                val contract = client.contracts(login, client.user(login)).singleOrNull { it.key == initial.profile.contract }
                    ?: error("저장한 삼천리 계약을 찾지 못했어요. 다시 연결해 주세요.")
                client.validateAndSubmit(contract, target.serial, selected)
                val refreshed = selfReadTarget(contract, client.selfReadState(login, contract))
                SubmissionOutcome(true, refreshed?.submitted == true && refreshed.submittedValue == selected &&
                    refreshed.cycle == target.cycle && refreshed.contract.bp == target.contract.bp &&
                    SkensClient.opaque("samchully:${refreshed.contract.bp}") == initial.profile.contract &&
                    refreshed.installation == initial.profile.meter, uncertain = false) to refreshed
            }
        } catch (_: Exception) { SubmissionOutcome(false, false, uncertain = true) to target }
        val detail = when (outcome.first.status) {
            "confirmed" -> "공급사에서 제출 완료를 확인했어요."
            "uncertain" -> "전송 결과를 확인 중이에요. 공급사에서 제출 결과를 다시 확인해 주세요."
            else -> "공급사가 제출을 받지 않았어요. 공급사에서 확인해 주세요."
        }
        store.update { latest ->
            val finished = BackgroundState.finish(latest, initial, record.copy(status = outcome.first.status, detail = detail))
            if (finished === latest) latest else finished.copy(cachedSelfRead = outcome.second)
        }
    }
}

object SamchullySubmissionPolicy {
    fun decide(data: AppData, target: SelfReadTarget?, time: Long = System.currentTimeMillis(), automatic: Boolean): SubmissionDecision {
        fun deny(reason: String) = SubmissionDecision(false, null, reason)
        if (data.profile.providerId != "samchully") return deny("삼천리 연결 정보를 확인해 주세요.")
        if (automatic) return deny("삼천리 자가검침은 화면에서 확인 후 직접 제출해 주세요.")
        if (target == null || target.contract.bp != data.profile.customerNumber ||
            SkensClient.opaque("samchully:${target.contract.bp}") != data.profile.contract || target.installation != data.profile.meter)
            return deny("계약 또는 계량기 정보가 달라요. 다시 조회해 주세요.")
        if (!target.eligible || target.submitted) return deny("이번 검침 대상 상태를 공급사에서 다시 확인해 주세요.")
        if (target.previousValue == null) return deny("공급사의 이전 검침값을 확인하지 못했어요.")
        val date = dateOf(time)
        if (date !in LocalDate.parse(target.start)..LocalDate.parse(target.end)) return deny("자가검침 입력 기간이 아니에요.")
        if (data.submissions.any { it.cycle == target.cycle && it.status in setOf("pending", "uncertain", "confirmed") }) return deny("이전 전송 결과를 먼저 확인해 주세요.")
        val observation = data.observations.lastOrNull { it.meter == data.profile.meter && it.time <= time }
            ?: return deny("실제 계량기 숫자를 먼저 확인해 주세요.")
        val sameDayCheck = data.observations.lastOrNull { it.meter == data.profile.meter &&
            it.time <= time && dateOf(it.time) == date }
        val reading = floor(sameDayCheck?.reading ?: (Estimator.estimate(data, time).reading
            ?: return deny("제출할 지침을 계산할 수 없어요.")))
        if (!reading.isFinite() || reading < target.previousValue || reading > 99_999_999) return deny("이전 지침과 제출값을 확인해 주세요.")
        return SubmissionDecision(true, reading, "검침 기간과 기존 제출 여부를 확인했어요.")
    }
}
