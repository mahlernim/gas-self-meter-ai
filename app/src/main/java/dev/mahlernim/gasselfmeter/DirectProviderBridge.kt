package dev.mahlernim.gasselfmeter

import android.content.Context
import java.time.LocalDate
import kotlin.concurrent.withLock

/** Keeps a direct-provider session alive while the user chooses one of its contracts. */
data class DirectLogin(val client: DirectProviderClient, val contracts: List<DirectContract>) : AutoCloseable {
    override fun close() = client.close()
}

/** Shared account bridge for suppliers which operate their own password portal. */
object DirectProviderBridge {
    internal var clientFactory: (String, Credentials) -> DirectProviderClient = { providerId, credentials ->
        when (providerId) {
            "daesung", "daesungclean" -> DaesungProviderClient(providerId, credentials)
            "haeyang" -> HaeyangProviderClient(credentials)
            else -> error("지원하지 않는 직접 연결 공급사예요.")
        }
    }

    fun login(providerId: String, credentials: Credentials): DirectLogin {
        require(Providers.get(providerId).direct) { "지원하지 않는 직접 연결 공급사예요." }
        val client = clientFactory(providerId, credentials)
        return try {
            DirectLogin(client, client.login().also { check(it.isNotEmpty()) { "연결된 계약이 없어요. 공급사 홈페이지에서 사용 계약을 확인해 주세요." } })
        } catch (e: Exception) {
            client.close()
            throw e
        }
    }

    fun snapshot(login: DirectLogin, contract: DirectContract): DirectSnapshot {
        require(login.contracts.any { it.id == contract.id }) { "로그인한 계정의 계약을 다시 선택해 주세요." }
        return login.client.read(contract).also { check(it.contract.id == contract.id) { "선택한 계약과 조회 결과가 달라요. 다시 연결해 주세요." } }
    }

    internal fun meterKey(providerId: String, contract: DirectContract, meterId: String? = contract.meterId): String =
        DirectIdentity.meter(providerId, contract.id, meterId)

    internal fun target(providerId: String, snapshot: DirectSnapshot): SelfReadTarget? {
        val source = snapshot.target ?: return null
        require(source.start <= source.end) { "검침 가능 기간을 확인하지 못했어요." }
        require(source.cycle.isNotBlank() && source.cycle.length <= 80) { "검침 주기 정보를 확인하지 못했어요." }
        val meter = meterKey(providerId, snapshot.contract, snapshot.contract.meterId)
        require(source.contract.bp == snapshot.contract.id && source.contract.ca == providerId && source.installation == meter) {
            "조회한 계약 또는 계량기 정보가 달라요. 다시 연결해 주세요."
        }
        return source.copy(
            contract = Contract(snapshot.contract.id, providerId, snapshot.contract.label),
            installation = meter,
        )
    }

    internal fun periods(providerId: String, snapshot: DirectSnapshot, now: Long): List<UsagePeriod> = snapshot.bills.mapNotNull { bill ->
        runCatching {
            val start = bill.start
            val end = bill.end
            val previous = bill.previous
            val current = bill.current
            val meterId = bill.meterId
            if (start == null || end == null || previous == null || current == null || meterId == null || LocalDate.parse(end) > dateOf(now)) null
            else UsagePeriod(start, end, current - previous, meterKey(providerId, snapshot.contract, meterId), previous, current,
                billMonth = bill.month.replace("-", ""), amount = bill.amount).also { it.validate() }
        }.getOrNull()
    }.sortedBy { it.start }.also(Estimator::validatePeriods)

    fun merge(data: AppData, snapshot: DirectSnapshot, providerId: String, credentials: Credentials?, now: Long = System.currentTimeMillis()): AppData {
        require(Providers.get(providerId).direct) { "지원하지 않는 직접 연결 공급사예요." }
        val contractKey = DirectIdentity.contract(providerId, snapshot.contract.id)
        require(data.profile.contract.isBlank() ||
            (data.profile.providerId == providerId && data.profile.contract == contractKey)) {
            "다른 계약이에요. 현재 기록을 내보낸 후 데이터를 초기화해 주세요."
        }
        require(data.profile.customerNumber.isBlank() || data.profile.customerNumber == snapshot.contract.id) {
            "저장된 고객번호가 달라요. 계약을 다시 확인해 주세요."
        }
        val incoming = periods(providerId, snapshot, now)
        val combined = (data.periods.filter { old -> incoming.none { it.first <= old.last && old.first <= it.last } } + incoming).sortedBy { it.start }
        Estimator.validatePeriods(combined)
        val currentTarget = target(providerId, snapshot)
        val reconciled = applyReconciliation(data, reconciledRecord(data, currentTarget))
        val directBills = (reconciled.directBills + snapshot.bills).associateBy { it.month }.values.sortedBy { it.month }.takeLast(120)
        val meter = meterKey(providerId, snapshot.contract)
        // A refresh of the same connection must not erase consent. A new connection, replacement
        // meter, changed credentials or portable backup still needs a fresh opt-in.
        val keepAutomatic = data.submissionSettings.automatic && data.ready && credentials != null &&
            data.profile.providerId == providerId && data.profile.contract == contractKey &&
            data.profile.meter == meter && data.credentials == credentials &&
            data.gasappConnection == null && data.energyTalkConnection == null
        return reconciled.copy(
            profile = reconciled.profile.copy(providerId = providerId, contract = contractKey, customerNumber = snapshot.contract.id,
                meter = meter, plannedDate = currentTarget?.end, syncTime = now),
            periods = combined, credentials = credentials, ready = true, cachedSelfRead = currentTarget, directBills = directBills,
            submissionSettings = reconciled.submissionSettings.copy(automatic = keepAutomatic),
            gasappConnection = null, cachedGasappTarget = null, gasappBills = emptyList(), gasappMeterChangeObservedAt = null,
            samchullyBills = emptyList(), energyTalkConnection = null, energyTalkBills = emptyList(),
        )
    }

    internal fun reconciledRecord(data: AppData, current: SelfReadTarget?): SubmissionRecord? {
        val target = current ?: return null
        val record = data.submissions.lastOrNull { it.status in setOf("pending", "uncertain") && it.cycle == target.cycle } ?: return null
        return record.takeIf {
            target.submitted && target.submittedValue == it.value && target.contract.bp == data.profile.customerNumber &&
                target.contract.ca == data.profile.providerId && target.installation == data.profile.meter
        }
    }

    internal fun applyReconciliation(data: AppData, record: SubmissionRecord?): AppData = if (record == null) data else data.copy(
        submissions = data.submissions.map { old ->
            if (old == record && old.status in setOf("pending", "uncertain")) old.copy(status = "confirmed", detail = "공급사에서 제출 완료를 확인했어요.") else old
        },
    )

    fun refresh(context: Context, force: Boolean = false): AppData = SubmissionGate.lock.withLock {
        val store = SecureStore(context)
        val initial = store.read()
        val providerId = initial.profile.providerId
        if (!Providers.get(providerId).direct || !initial.ready) return@withLock initial
        if (!force && initial.profile.syncTime?.let { System.currentTimeMillis() - it < 86_400_000L } == true) return@withLock initial
        val credentials = initial.credentials ?: error("공급사 로그인 정보를 다시 입력해 주세요.")
        login(providerId, credentials).use { login ->
            val contract = login.contracts.singleOrNull { DirectIdentity.contract(providerId, it.id) == initial.profile.contract }
                ?: error("저장한 공급사 계약을 찾지 못했어요. 다시 연결해 주세요.")
            val snapshot = snapshot(login, contract)
            store.update { latest -> if (BackgroundState.sameAccount(latest, initial)) merge(latest, snapshot, providerId, credentials) else latest }
        }
    }

    fun checkStatus(context: Context): AppData = refresh(context, force = true)

    /** Saves a pending record before the one permitted mutation, then reconciles by a fresh read. */
    fun submit(context: Context, value: Double? = null, automatic: Boolean = false, cancelled: () -> Boolean = { false }): AppData = SubmissionGate.lock.withLock {
        val store = SecureStore(context)
        val initial = store.read()
        val providerId = initial.profile.providerId
        require(Providers.get(providerId).direct) { "공급사 연결 정보를 확인해 주세요." }
        check(!cancelled() && !initial.profile.reconnectRequired) { "공급사에 다시 연결한 뒤 확인해 주세요." }
        if (automatic) require(initial.submissionSettings.automatic && Providers.get(providerId).automaticSubmission) { "자동 제출이 꺼져 있어요." }
        val credentials = initial.credentials ?: error("공급사 로그인 정보를 다시 입력해 주세요.")
        login(providerId, credentials).use { login ->
            val contract = login.contracts.singleOrNull { DirectIdentity.contract(providerId, it.id) == initial.profile.contract }
                ?: error("저장한 공급사 계약을 찾지 못했어요. 다시 연결해 주세요.")
            val freshSnapshot = snapshot(login, contract)
            val current = store.update { latest -> if (BackgroundState.sameAccount(latest, initial)) merge(latest, freshSnapshot, providerId, credentials) else latest }
            check(BackgroundState.sameAccount(current, initial)) { "확인한 계정이나 계량기가 바뀌었어요. 다시 확인해 주세요." }
            val currentTarget = current.cachedSelfRead
            val decision = DirectSubmissionPolicy.decide(current, currentTarget, automatic = automatic)
            require(decision.allowed && decision.value != null) { decision.reason }
            val selected = decision.value
            require(value == null || value == selected) { "확인 후 제출값이 달라졌어요. 화면에서 다시 확인해 주세요." }
            val target = currentTarget ?: error("현재 자가검침 대상을 찾지 못했어요.")
            val record = SubmissionRecord(target.cycle, target.start, target.end, selected, System.currentTimeMillis(), "pending", "공급사 확인 대기")
            store.update { latest ->
                val fresh = DirectSubmissionPolicy.decide(latest, target, automatic = automatic)
                check(!cancelled() && BackgroundState.sameAccount(latest, current) && fresh.allowed && fresh.value == selected) { "제출 조건이 변경됐어요. 다시 확인해 주세요." }
                latest.copy(submissions = (latest.submissions.filterNot { it.cycle == target.cycle } + record).takeLast(100))
            }
            // Consent, record ownership and policy are checked once more at the network boundary.
            if (cancelled() || !DirectSubmissionPolicy.canSendPending(store.read(), current, target, record, automatic)) {
                return@withLock store.update { latest -> BackgroundState.finish(latest, current,
                    record.copy(status = "rejected", detail = "제출 조건이 변경되어 전송하지 않았어요.")) }
            }
            val result = try {
                login.client.submit(freshSnapshot.contract, target, selected)
                val refreshed = snapshot(login, freshSnapshot.contract)
                val actual = target(providerId, refreshed)
                val confirmed = actual != null && actual.submitted && actual.submittedValue == selected &&
                    actual.cycle == target.cycle && actual.start == target.start && actual.end == target.end &&
                    actual.contract == target.contract && actual.installation == target.installation
                Triple(if (confirmed) "confirmed" else "uncertain", actual, if (confirmed) "공급사에서 제출 완료를 확인했어요." else "전송 결과를 확인 중이에요. 공급사에서 제출 결과를 다시 확인해 주세요.")
            } catch (_: Exception) {
                Triple("uncertain", null, "전송 결과를 확인 중이에요. 공급사에서 제출 결과를 다시 확인해 주세요.")
            }
            store.update { latest ->
                if (!BackgroundState.sameAccount(latest, current)) latest else {
                    val withRead = result.second?.let { actual -> merge(latest, freshSnapshot.copy(target = actual), providerId, credentials) } ?: latest
                    BackgroundState.finish(withRead, current, record.copy(status = result.first, detail = result.third))
                }
            }
        }
    }
}
