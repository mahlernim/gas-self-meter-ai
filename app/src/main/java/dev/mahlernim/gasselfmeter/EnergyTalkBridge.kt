package dev.mahlernim.gasselfmeter

import android.content.Context
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlinx.coroutines.runBlocking

/** Integrates an official EnergyTalk session without accepting a password in the app. */
object EnergyTalkBridge {
    private val providers = mapOf(
        "cncity" to "cncity", "kne" to "knenergy", "ktrm" to "kiturami",
        "miraense" to "seohae", "srb" to "seorabeol", "gse" to "gse",
        "cwjgas" to "chambit", "ccbgas" to "chambit", "cydgas" to "chambit",
        "cdhgas" to "chambit", "cscgas" to "chambit",
    )

    fun providerId(tenant: String): String = requireNotNull(providers[tenant]) { "지원하지 않는 EnergyTalk 공급사예요." }
    fun tenantFor(providerId: String): String? = providers.entries.singleOrNull { it.value == providerId }?.key
    fun tenantsFor(providerId: String): Set<String> = providers.filterValues { it == providerId }.keys

    fun connect(context: Context, connection: EnergyTalkConnection, snapshot: EnergyTalkSnapshot): AppData = locked {
        require(snapshot.clientId == connection.tenant) { "선택한 공급사와 로그인한 공급사가 달라요." }
        val store = SecureStore(context)
        val initial = store.read()
        store.update { latest ->
            check(sameConnection(initial, latest)) { "연결 계정이 변경됐어요. 다시 확인해 주세요." }
            merge(latest, connection, snapshot)
        }
    }

    fun refresh(context: Context, force: Boolean = false): AppData = locked {
        val store = SecureStore(context)
        val initial = store.read()
        val connection = initial.energyTalkConnection ?: return@locked initial
        if (!initial.ready || (!force && initial.profile.syncTime?.let { System.currentTimeMillis() - it < 86_400_000L } == true)) return@locked initial
        val snapshot = runBlocking { EnergyTalkReadClient().verifyAndRead(connection.session, connection.tenant) }
        store.update { latest -> if (sameConnection(initial, latest)) merge(latest, connection, snapshot) else latest }
    }

    fun checkStatus(context: Context): AppData = refresh(context, force = true)

    /** Persists pending before the sole provider mutation, then reads status again for reconciliation. */
    fun submit(context: Context, value: Double? = null, cancelled: () -> Boolean = { false }): AppData = locked {
        val store = SecureStore(context)
        val initial = store.read()
        val connection = initial.energyTalkConnection ?: error("EnergyTalk에 다시 연결해 주세요.")
        val client = EnergyTalkReadClient()
        val snapshot = runBlocking { client.verifyAndRead(connection.session, connection.tenant) }
        val target = target(connection, snapshot)
        val selected = EnergyTalkSubmissionPolicy.decide(initial, target, automatic = false).let { decision ->
            require(decision.allowed && decision.value != null) { decision.reason }
            require(value == null || value == decision.value) { "확인 후 제출값이 달라졌어요. 화면에서 다시 확인해 주세요." }
            decision.value
        }
        val check = runBlocking { client.checkReading(connection.session, connection.tenant, selected) }
        require(check.allowed) { check.message ?: "이 검침값은 입력할 수 없어요." }
        val record = SubmissionRecord(target.cycle, target.start, target.end, selected, System.currentTimeMillis(), "pending", "공급사 확인 대기")
        store.update { latest ->
            check(sameConnection(initial, latest)) { "연결 계정이 변경됐어요. 다시 확인해 주세요." }
            val fresh = EnergyTalkSubmissionPolicy.decide(latest, target, automatic = false)
            check(!cancelled() && fresh.allowed && fresh.value == selected) { "제출 조건이 변경됐어요. 다시 확인해 주세요." }
            latest.copy(cachedSelfRead = target, submissions = (latest.submissions.filterNot { it.cycle == record.cycle } + record).takeLast(100))
        }
        val beforePost = store.read()
        if (cancelled() || !sameConnection(initial, beforePost) || beforePost.submissions.lastOrNull { it.cycle == record.cycle } != record) {
            return@locked store.update { latest -> latest.copy(submissions = latest.submissions.map { old ->
                if (old == record) record.copy(status = "rejected", detail = "제출 조건이 변경되어 전송하지 않았어요.") else old
            }) }
        }
        try {
            runBlocking { client.submitReading(connection.session, connection.tenant, selected) }
            val refreshed = runBlocking { client.verifyAndRead(connection.session, connection.tenant) }
            val actual = target(connection, refreshed)
            val confirmed = EnergyTalkSubmissionPolicy.confirms(target, actual, selected)
            store.update { latest ->
                if (!sameConnection(initial, latest)) latest else merge(latest, connection, refreshed).copy(
                    cachedSelfRead = actual.copy(submitted = confirmed || actual.submitted),
                    submissions = latest.submissions.map { old -> if (old == record) record.copy(
                        status = if (confirmed) "confirmed" else "uncertain",
                        detail = if (confirmed) "공급사에서 제출 완료를 확인했어요." else "검침 기간 확인을 눌러 제출 결과를 확인해 주세요.",
                    ) else old },
                )
            }
        } catch (_: Exception) {
            store.update { latest -> latest.copy(submissions = latest.submissions.map { old ->
                if (old == record) record.copy(status = "uncertain", detail = "검침 기간 확인을 눌러 제출 결과를 확인해 주세요.") else old
            }) }
        }
    }

    fun merge(data: AppData, connection: EnergyTalkConnection, snapshot: EnergyTalkSnapshot, now: Long = System.currentTimeMillis()): AppData {
        require(snapshot.clientId == connection.tenant) { "선택한 공급사와 로그인한 공급사가 달라요." }
        val providerId = providerId(connection.tenant)
        require(data.profile.contract.isBlank() || data.profile.contract == contractKey(connection, snapshot.address)) {
            "다른 계약이에요. 현재 기록을 내보낸 후 데이터를 초기화해 주세요."
        }
        val target = target(connection, snapshot, now)
        val bills = snapshot.usage.map { usage -> EnergyTalkBill(usage.month, usage.usage, usage.amount, unit(usage.usage)) }
        val priorTarget = data.cachedSelfRead
        val records = data.submissions.map { record ->
            if (record.status in setOf("pending", "uncertain") && priorTarget != null &&
                record.cycle == target.cycle && EnergyTalkSubmissionPolicy.confirms(priorTarget, target, record.value))
                record.copy(status = "confirmed", detail = "공급사에서 제출 완료를 확인했어요.") else record
        }
        val confirmed = records.any { it.cycle == target.cycle && it.status == "confirmed" && it.value == target.submittedValue }
        return data.copy(
            profile = data.profile.copy(providerId = providerId, meter = target.serial,
                contract = contractKey(connection, snapshot.address), plannedDate = target.end, syncTime = now),
            ready = true, credentials = null, cachedSelfRead = target.copy(submitted = target.submitted || confirmed), submissions = records,
            energyTalkConnection = connection, energyTalkBills = if (bills.isEmpty() && snapshot.unavailable.isNotEmpty()) data.energyTalkBills else bills,
            gasappConnection = null, cachedGasappTarget = null, gasappBills = emptyList(),
            gasappMeterChangeObservedAt = null, samchullyBills = emptyList(),
            directBills = emptyList(),
            submissionSettings = data.submissionSettings.copy(automatic = false),
        )
    }

    fun sameConnection(before: AppData, after: AppData): Boolean =
        before.profile.contract == after.profile.contract && before.energyTalkConnection == after.energyTalkConnection &&
            (before.energyTalkConnection == null || BackgroundState.sameAccount(after, before))

    fun target(connection: EnergyTalkConnection, snapshot: EnergyTalkSnapshot, now: Long = System.currentTimeMillis()): SelfReadTarget {
        val month = YearMonth.from(dateOf(now))
        val meter = SkensClient.opaque("energytalk:${connection.tenant}:${snapshot.address}")
        val state = snapshot.meter
        return SelfReadTarget(
            cycle = SkensClient.opaque("energytalk:${connection.tenant}:${snapshot.address}:$month"),
            start = month.atDay(1).toString(), end = month.atEndOfMonth().toString(),
            eligible = state?.eligible == true, submitted = state?.submitted == true,
            submittedValue = state?.recent?.toDoubleOrNull(), previousValue = state?.previous?.toDoubleOrNull(),
            contract = Contract("energytalk:${connection.tenant}", SkensClient.opaque("energytalk:${snapshot.address}"), snapshot.address),
            serial = meter, address = snapshot.address, planned = month.atEndOfMonth().toString(), vLdo = "", installation = "",
        )
    }

    private fun contractKey(connection: EnergyTalkConnection, address: String) = SkensClient.opaque("energytalk:${connection.tenant}:$address")
    private fun unit(usage: String): String? = usage.trim().replace(Regex("^[0-9,.]+\\s*"), "").takeIf { it.isNotBlank() }
    private fun <T> locked(block: () -> T): T {
        SubmissionGate.lock.lock()
        return try { block() } finally { SubmissionGate.lock.unlock() }
    }
}

object EnergyTalkSubmissionPolicy {
    fun decide(data: AppData, target: SelfReadTarget?, time: Long = System.currentTimeMillis(), automatic: Boolean): SubmissionDecision {
        fun deny(reason: String) = SubmissionDecision(false, null, reason)
        val connection = data.energyTalkConnection ?: return deny("EnergyTalk에 다시 연결해 주세요.")
        if (automatic) return deny("EnergyTalk 자가검침은 직접 확인 후 제출해 주세요.")
        if (target == null) return deny("검침 기간을 먼저 확인해 주세요.")
        if (data.profile.providerId != EnergyTalkBridge.providerId(connection.tenant) ||
            data.profile.contract != SkensClient.opaque("energytalk:${connection.tenant}:${target.address}")) return deny("계약 정보가 달라요. 다시 연결해 주세요.")
        if (target.serial != data.profile.meter || target.serial != SkensClient.opaque("energytalk:${connection.tenant}:${target.address}")) return deny("계량기 정보를 다시 확인해 주세요.")
        if (!target.eligible) return deny("지금은 자가검침 제출 대상이 아니에요.")
        if (target.submitted) return deny("이번 검침값은 이미 제출했어요.")
        val date = dateOf(time)
        if (date !in LocalDate.parse(target.start)..LocalDate.parse(target.end)) return deny("자가검침 입력 기간이 아니에요.")
        val prior = data.submissions.lastOrNull { it.cycle == target.cycle }
        if (prior?.status in setOf("pending", "uncertain", "confirmed")) return deny("이전 전송 결과를 공급사에서 확인해 주세요.")
        val observed = data.observations.lastOrNull { it.meter == data.profile.meter && it.time <= time }
            ?: return deny("실제 계량기 숫자를 먼저 확인해 주세요.")
        val value = kotlin.math.floor((if (dateOf(observed.time) == date) observed.reading else Estimator.estimate(data, time).reading)
            ?: return deny("오늘 확인한 계량기 숫자 또는 사용량 추정이 필요해요."))
        if (target.previousValue == null || !value.isFinite() || value !in 0.0..99_999_999.0 || value < target.previousValue) return deny("이전 지침과 제출값을 확인해 주세요.")
        return SubmissionDecision(true, value, "검침 기간과 기존 제출 여부를 확인했어요.")
    }

    fun confirms(expected: SelfReadTarget, actual: SelfReadTarget, value: Double): Boolean =
        (actual.submitted || actual.submittedValue != expected.submittedValue) &&
            actual.cycle == expected.cycle && actual.serial == expected.serial && actual.contract == expected.contract &&
            actual.submittedValue?.let { abs(it - value) < .001 } == true
}
