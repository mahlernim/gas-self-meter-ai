package dev.mahlernim.gasselfmeter

import java.time.LocalDate
import java.time.YearMonth

data class SamchullyLogin(val session: SamchullySession, val contracts: List<SamchullyContract>)
data class SamchullySnapshot(
    val contract: SamchullyContract,
    val bills: List<SamchullyBill>,
    val periods: List<UsagePeriod>,
    val meter: String,
    val warning: String,
)

/** Experimental reads only. A successful history import never enables submission. */
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
            assemble(contract, client.bills(login.session, contract.customerNo, from, to), now)
        }
    }

    internal fun queryWindow(date: LocalDate): Pair<YearMonth, YearMonth> =
        YearMonth.from(date).let { it.minusMonths(23) to it }

    internal fun meterKey(contract: SamchullyContract, meterId: String?): String =
        SkensClient.opaque("samchully:${contract.customerNo}:meter:${meterId ?: "unverified"}")

    internal fun assemble(contract: SamchullyContract, bills: List<SamchullyBill>, now: Long): SamchullySnapshot {
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
            add("삼천리 실험적 조회 전용 연결이에요. 검침 제출은 지원하지 않아요.")
            if (bills.isEmpty()) add("조회 기간에 청구 이력이 없어요.")
            if (periods.size < bills.size) add("날짜·누적 지침·계량기 정보가 부족한 청구는 추정 계산에서 제외했어요.")
            if (contract.meterId == null) add("현재 계량기 번호를 확인하지 못했어요. 실제 숫자를 직접 기록해 주세요.")
            if (periods.any { it.meter != meter }) add("다른 계량기의 누적 지침은 현재 지침에 연결하지 않아요.")
        }.joinToString(" ")
        return SamchullySnapshot(contract, bills, periods, meter, warning)
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
            periods = periods, credentials = credentials, ready = true,
            samchullyBills = (data.samchullyBills + snapshot.bills.map { bill ->
                bill.copy(meterId = bill.meterId?.let { meterKey(snapshot.contract, it) })
            }).associateBy { it.billMonth }.values.sortedBy { it.billMonth }.takeLast(120),
            submissionSettings = data.submissionSettings.copy(enabled = false, automatic = false, reminder = false),
            cachedSelfRead = null, gasappConnection = null, cachedGasappTarget = null,
            gasappBills = emptyList(), gasappMeterChangeObservedAt = null,
        )
    }
}
