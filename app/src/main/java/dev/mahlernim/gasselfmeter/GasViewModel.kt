package dev.mahlernim.gasselfmeter

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.concurrent.withLock

class GasViewModel(app: Application) : AndroidViewModel(app) {
    // Context.filesDir can itself touch disk, so construct the store on its first IO call too.
    private val store by lazy { SecureStore(app) }
    var data by mutableStateOf(AppData()); private set
    var message by mutableStateOf<String?>(null)
    var loginError by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableStateOf(""); private set
    var progressCurrent by mutableIntStateOf(0); private set
    var progressTotal by mutableIntStateOf(0); private set
    var storageError by mutableStateOf(false); private set
    var contracts by mutableStateOf<List<Contract>>(emptyList()); private set
    var selfReadTarget by mutableStateOf<SelfReadTarget?>(null); private set
    private var pendingClient: SkensClient? = null
    private var pendingSamchully: SamchullyLogin? = null
    private var pendingProvider: Provider? = null
    private var pendingCredentials: Credentials? = null
    private var remember = true
    init {
        busy = true
        setProgress(0, 1, "저장한 기록을 여는 중")
        viewModelScope.launch {
            try {
                publish(withContext(Dispatchers.IO) { store.read() })
                scheduleStoredData()
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { storageError = true; message = "저장 데이터를 열지 못했어요. 원본은 보존되어 있어요. 백업을 가져오거나 데이터를 초기화할 수 있어요." }
            finally { busy = false }
            if (!storageError) refreshIfDue()
        }
    }
    private fun publish(next: AppData) { data = next; selfReadTarget = next.cachedSelfRead }
    private suspend fun save(next: AppData) {
        check(!storageError) { "저장 파일을 먼저 복구하거나 초기화해 주세요." }
        val before = data
        val saved = withContext(Dispatchers.IO) { store.update { latest ->
            check(latest.profile.providerId == before.profile.providerId && latest.profile.contract == before.profile.contract) { "계정 정보가 변경되었어요. 현재 연결을 다시 확인해 주세요." }
            if (next.observations != before.observations) check(latest.profile.meter == before.profile.meter) { "계량기 정보가 변경되었어요. 현재 계량기를 다시 확인해 주세요." }
            next.copy(
            observations = if (next.observations == before.observations) latest.observations else next.observations,
            credentials = if (next.credentials == before.credentials) latest.credentials else next.credentials,
            gasappConnection = if (next.gasappConnection == before.gasappConnection) latest.gasappConnection else next.gasappConnection,
            submissionSettings = if (next.submissionSettings == before.submissionSettings) latest.submissionSettings else next.submissionSettings,
            profile = next.profile.copy(
                meter = if (next.profile.meter == before.profile.meter) latest.profile.meter else next.profile.meter,
                plannedDate = if (next.profile.plannedDate == before.profile.plannedDate) latest.profile.plannedDate else next.profile.plannedDate,
                syncTime = if (next.profile.syncTime == before.profile.syncTime) latest.profile.syncTime else next.profile.syncTime,
                customerNumber = if (next.profile.customerNumber == before.profile.customerNumber) latest.profile.customerNumber else next.profile.customerNumber),
            periods = if (next.periods == before.periods) latest.periods else next.periods,
            submissions = if (next.submissions == before.submissions) latest.submissions else next.submissions,
            cachedSelfRead = if (next.cachedSelfRead == before.cachedSelfRead) latest.cachedSelfRead else next.cachedSelfRead,
            gasappBills = if (next.gasappBills == before.gasappBills) latest.gasappBills else next.gasappBills,
            samchullyBills = if (next.samchullyBills == before.samchullyBills) latest.samchullyBills else next.samchullyBills,
            cachedGasappTarget = if (next.cachedGasappTarget == before.cachedGasappTarget) latest.cachedGasappTarget else next.cachedGasappTarget,
            gasappMeterChangeObservedAt = if (next.gasappMeterChangeObservedAt == before.gasappMeterChangeObservedAt) latest.gasappMeterChangeObservedAt else next.gasappMeterChangeObservedAt)
        } }
        publish(saved)
    }
    /** Gate immediately on Main, do synchronous disk transactions on IO, publish only after commit. */
    fun attempt(onResult: (String?) -> Unit = {}, action: suspend () -> Unit) {
        if (busy) {
            val error = "진행 중인 작업이 끝난 뒤 다시 시도해 주세요."
            message = error; onResult(error); return
        }
        busy = true
        setProgress(0, 1, "기록을 처리하는 중")
        viewModelScope.launch {
            var error: String? = null
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = reportError(e); message = error }
            finally { busy = false }
            onResult(error)
        }
    }
    fun manual(provider: String) = attempt {
        save(data.copy(profile = data.profile.copy(providerId = provider), ready = true))
    }
    fun calibrate(reading: String, onResult: (String?) -> Unit = {}) = attempt(onResult) {
        save(Estimator.addObservation(data, number(reading)))
        scheduleReminder()
        message = "실제 확인값을 저장했어요. 화면과 다음 추정에 반영했어요."
    }
    fun addPeriod(start: String, end: String, usage: String, onResult: (String?) -> Unit = {}) = attempt(onResult) {
        val period = UsagePeriod(LocalDate.parse(start).toString(), LocalDate.parse(end).toString(), number(usage))
        val next = data.periods + period
        Estimator.validatePeriods(next)
        save(data.copy(periods = next.sortedBy { it.start }))
        message = "사용 이력을 저장했어요."
    }
    fun deletePeriod(period: UsagePeriod) = attempt { save(data.copy(periods = data.periods - period)) }
    fun deleteObservation(observation: Observation) = attempt { save(data.copy(observations = data.observations - observation)) }
    fun resetMeter(onResult: (String?) -> Unit = {}) = attempt(onResult) {
        save(data.copy(profile = data.profile.copy(meter = "manual-${UUID.randomUUID()}", plannedDate = null)))
        message = "새 계량기로 시작해요. 현재 숫자를 확인해 주세요."
    }
    fun changeProvider() = attempt {
        save(data.copy(ready = false, credentials = null, submissionSettings = SubmissionSettings(), cachedSelfRead = null, gasappConnection = null, cachedGasappTarget = null))
        scheduleSubmission()
        scheduleRefresh()
    }
    fun forgetCredentials() = attempt {
        save(data.copy(credentials = null, gasappConnection = null, submissionSettings = data.submissionSettings.copy(automatic = false)))
        scheduleSubmission()
        scheduleRefresh()
        message = "저장한 로그인 정보를 지웠고 자동 입력을 껐어요."
    }
    fun setReminder(enabled: Boolean, day: Int, hour: Int) = attempt {
        val profile = data.profile.copy(reminder = enabled, reminderDay = day, reminderHour = hour)
        save(data.copy(profile = profile))
        scheduleReminder()
    }
    fun setReminderRepeatCount(count: Int) = attempt {
        require(count in 0..6)
        save(data.copy(profile = data.profile.copy(reminderRepeatCount = count)))
        scheduleReminder()
    }
    fun setSubmissionSettings(settings: SubmissionSettings) = attempt {
        require(settings.recentDays in 1..30 && settings.reminderHour in 0..23 && settings.reminderMinute in 0..59)
        save(data.copy(submissionSettings = settings.copy(automatic = settings.automatic && Providers.get(data.profile.providerId).automaticSubmission)))
        scheduleSubmission()
    }
    fun login(providerId: String, username: String, password: String, rememberPassword: Boolean) {
        if (busy) return
        if (username.isBlank() || password.isBlank()) { loginError = "아이디와 비밀번호를 입력해 주세요."; message = loginError; return }
        if (Providers.get(providerId).experimentalReadOnly) {
            loginSamchully(username, password, rememberPassword)
            return
        }
        busy = true; loginError = null; setProgress(0, 5, "1단계 · 공급사에 안전하게 로그인하는 중"); remember = rememberPassword
        viewModelScope.launch {
            try {
                pendingClient?.close()
                pendingProvider = Providers.skens(providerId)
                pendingCredentials = Credentials(username.trim(), password)
                pendingClient = SkensClient(pendingProvider!!, pendingCredentials!!)
                val list = withContext(Dispatchers.IO) { SubmissionGate.lock.withLock { pendingClient!!.login() } }
                contracts = list
                setProgress(1, 5, "2단계 · 연결된 계약을 확인하는 중")
                if (list.size == 1) syncSelected(list.first())
            } catch (e: Exception) { loginError = reportError(e); message = loginError; clearPending() }
            finally { busy = false }
        }
    }
    fun selectContract(contract: Contract) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { syncSelected(contract) } catch (e: Exception) { loginError = reportError(e); message = loginError; clearPending() }
            finally { busy = false }
        }
    }
    private suspend fun syncSelected(contract: Contract) {
        pendingSamchully?.let { login ->
            setProgress(2, 4, "삼천리 청구 이력을 조회하는 중")
            val selected = login.contracts.singleOrNull { it.customerNo == contract.ca }
                ?: error("선택한 삼천리 계약을 찾지 못했어요.")
            val snapshot = withContext(Dispatchers.IO) {
                SubmissionGate.lock.withLock { SamchullyBridge.snapshot(login, selected) }
            }
            save(SamchullyBridge.merge(data, snapshot, if (remember) pendingCredentials else null))
            scheduleSubmission()
            scheduleRefresh()
            message = "삼천리 청구 이력 ${snapshot.bills.size}개월을 가져왔어요. " + snapshot.warning
            clearPending()
            return
        }
        setProgress(2, 5, "3단계 · 계량기와 검침 기간을 확인하는 중")
        val knownMonths = data.periods.mapNotNull { it.billMonth.takeIf(String::isNotBlank) }.toSet()
        val result = withContext(Dispatchers.IO) { SubmissionGate.lock.withLock { pendingClient!!.history(contract, knownMonths) { state ->
            viewModelScope.launch { setProgress(state.completed, state.total, "4단계 · ${state.text}") }
        } } }
        setProgress(5, 5, "5단계 · 가져온 기록을 안전하게 저장하는 중")
        val provider = pendingProvider ?: error("연결할 공급사를 다시 선택해 주세요.")
        val contractKey = SkensClient.contractKey(provider, contract)
        check(data.profile.contract.isBlank() || data.profile.contract == contractKey) { "다른 계약이에요. 현재 기록을 내보낸 후 데이터를 초기화해 주세요." }
        val newMonths = result.periods.map { it.billMonth }.toSet()
        val keep = data.periods.filter { old -> old.billMonth !in newMonths && result.periods.none { it.first <= old.last && old.first <= it.last } }
        val periods = (keep + result.periods).sortedBy { it.start }
        Estimator.validatePeriods(periods)
        save(data.copy(profile = data.profile.copy(providerId = provider.id, meter = result.meter, contract = contractKey, customerNumber = contract.ca, plannedDate = result.planned, syncTime = System.currentTimeMillis()),
            periods = periods, credentials = if (remember) pendingCredentials else null, ready = true, cachedSelfRead = result.selfRead, gasappConnection = null, cachedGasappTarget = null))
        selfReadTarget = result.selfRead
        scheduleSubmission()
        scheduleRefresh()
        val changed = data.observations.isNotEmpty() && data.observations.last().meter != result.meter
        message = result.warning ?: if (changed) "계량기 정보가 달라졌어요. 현재 숫자를 다시 확인해 주세요." else "${newMonths.size}개월의 청구 이력을 가져왔어요."
        clearPending()
    }
    fun connectGasapp(session: GasappSession, account: GasappAccount) = gasappAction("청구 이력과 검침 정보를 가져오는 중") {
        GasappBridge.connect(getApplication(), session, account)
    }
    private fun gasappAction(label: String, action: () -> AppData) {
        if (busy) return
        busy = true; setProgress(0, 1, label)
        viewModelScope.launch {
            try {
                data = withContext(Dispatchers.IO) { action() }
                selfReadTarget = data.cachedSelfRead
                scheduleRefresh()
                scheduleSubmission()
                val recent = data.submissions.lastOrNull { System.currentTimeMillis() - it.attemptedAt < 60_000 }
                if (recent?.status == "confirmed") getApplication<Application>().getSystemService(android.app.NotificationManager::class.java).cancel(3)
                message = recent?.detail ?: "공급사 정보를 확인했어요."
            } catch (e: Exception) { message = reportError(e) }
            finally { busy = false }
        }
    }
    fun checkSubmissionStatus() {
        if (busy) return
        if (Providers.get(data.profile.providerId).experimentalReadOnly) { message = "삼천리는 실험적 조회 전용이에요. 검침 제출은 공급사 홈페이지를 이용해 주세요."; return }
        if (data.gasappConnection != null) { gasappAction("검침 기간과 제출 상태를 확인하는 중") { GasappBridge.checkStatus(getApplication()) }; return }
        val credentials = data.credentials ?: run { message = "자동 입력을 사용하려면 설정에서 로그인 정보를 암호화해 저장해 주세요."; return }
        busy = true; setProgress(0, 3, "검침 기간을 확인하려고 로그인하는 중")
        viewModelScope.launch {
            try {
                selfReadTarget = withContext(Dispatchers.IO) { SubmissionGate.lock.withLock {
                    val provider = Providers.skens(data.profile.providerId)
                    SkensClient(provider, credentials).use { client ->
                        val contract = client.login().find { SkensClient.contractKey(provider, it) == data.profile.contract }
                            ?: error("저장된 계약을 찾지 못했어요. 공급사를 다시 연결해 주세요.")
                        viewModelScope.launch { setProgress(1, 3, "계약과 계량기를 확인하는 중") }
                        client.selfReadTarget(contract)
                    }
                } }
                save(data.copy(cachedSelfRead = selfReadTarget))
                setProgress(3, 3, "검침 기간 확인을 마쳤어요")
            } catch (e: Exception) { message = reportError(e) }
            finally { busy = false }
        }
    }
    fun submitReading(value: Double) {
        if (busy) return
        if (Providers.get(data.profile.providerId).experimentalReadOnly) { message = "삼천리는 조회 전용이며 검침값을 전송하지 않아요."; return }
        if (data.gasappConnection != null) {
            gasappAction("제출 직전 공급사 상태를 다시 확인하는 중") { GasappBridge.submit(getApplication(), value, automatic = false) }
            return
        }
        if (data.credentials == null) { message = "검침값을 입력하려면 로그인 정보를 이 기기에 저장해 주세요."; return }
        val reviewed = data
        busy = true; setProgress(0, 4, "제출 직전 공급사 상태를 다시 확인하는 중")
        viewModelScope.launch {
            var record: SubmissionRecord? = null
            try {
                withContext(Dispatchers.IO) {
                    SubmissionGate.lock.lock()
                    try {
                        // Do not suspend while holding the thread-owned lock.
                        val current = store.read()
                        check(BackgroundState.sameAccount(current, reviewed)) { "확인한 계정이나 계량기가 바뀌었어요. 다시 확인해 주세요." }
                        val credentials = current.credentials ?: error("저장된 로그인 정보가 없어요.")
                        val provider = Providers.skens(current.profile.providerId)
                        SkensClient(provider, credentials).use { client ->
                            val contract = client.login().find { SkensClient.contractKey(provider, it) == current.profile.contract }
                                ?: error("저장된 계약을 찾지 못했어요. 공급사를 다시 연결해 주세요.")
                            val target = client.selfReadTarget(contract)
                            viewModelScope.launch { selfReadTarget = target; setProgress(1, 4, "입력 기간과 기존 제출 여부를 확인했어요") }
                            val decision = SubmissionPolicy.decide(current, target, System.currentTimeMillis(), automatic = false)
                            require(decision.allowed && decision.value != null) { decision.reason }
                            require(kotlin.math.abs(decision.value - value) < .001) { "확인 후 제출값이 달라졌어요. 화면에서 다시 확인해 주세요." }
                            record = SubmissionRecord(target.cycle, target.start, target.end, value, System.currentTimeMillis(), "pending", "공급사 확인 대기")
                            store.update { latest ->
                                check(BackgroundState.sameAccount(latest, current)) { "계정 정보가 변경되었어요." }
                                val latestDecision = SubmissionPolicy.decide(latest, target, System.currentTimeMillis(), automatic = false)
                                check(latestDecision.allowed && latestDecision.value == decision.value) { latestDecision.reason }
                                latest.copy(submissions = (latest.submissions.filterNot { it.cycle == target.cycle } + record!!).takeLast(100))
                            }
                            viewModelScope.launch { setProgress(2, 4, "검침값을 한 번만 전송하는 중") }
                            val outcome = client.submitReading(target, value)
                            val status = when { !outcome.accepted -> "rejected"; outcome.confirmed -> "confirmed"; else -> "uncertain" }
                            val detail = when (status) {
                                "confirmed" -> "공급사에서 입력 완료를 확인했어요."
                                "rejected" -> "공급사가 입력을 받지 않았어요."
                                else -> "응답은 성공이지만 재조회 확인이 필요해요. 자동 재전송하지 않습니다."
                            }
                            store.update { BackgroundState.finish(it, current, record!!.copy(status = status, detail = detail)) }
                        }
                    } finally { SubmissionGate.lock.unlock() }
                }
                publish(withContext(Dispatchers.IO) { store.read() })
                if (data.submissions.lastOrNull()?.status == "confirmed") getApplication<Application>().getSystemService(android.app.NotificationManager::class.java).cancel(3)
                setProgress(4, 4, "검침값 입력 결과를 확인했어요")
                message = data.submissions.lastOrNull()?.detail ?: "검침값 입력을 마쳤어요."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                try {
                    publish(withContext(Dispatchers.IO) {
                        record?.let { attempt ->
                            store.update { BackgroundState.finish(it, reviewed, attempt.copy(status = "uncertain", detail = "전송 결과를 확정하지 못했어요. 자동 재전송하지 않습니다.")) }
                        } ?: store.read()
                    })
                } catch (recovery: Exception) {
                    if (recovery is CancellationException) throw recovery
                    storageError = true
                    message = "전송 결과와 저장 상태를 확인하지 못했어요. 재전송하지 말고 공급사 홈페이지에서 확인해 주세요.\n" + reportError(recovery, "submit")
                    return@launch
                }
                message = record?.let { "전송 결과를 확정하지 못했어요. 공급사 홈페이지에서 확인해 주세요." } ?: reportError(e)
            } finally { busy = false }
        }
    }
    private fun setProgress(current: Int, total: Int, text: String) {
        progressCurrent = current; progressTotal = total; progress = text
    }
    fun onForeground() {
        if (busy || storageError) return
        attempt(onResult = { error -> if (error == null) refreshIfDue() }) {
            publish(withContext(Dispatchers.IO) { store.read() })
            scheduleStoredData()
        }
    }
    private suspend fun scheduleStoredData() {
        scheduleRefresh()
        scheduleSubmission()
        scheduleReminder()
    }
    private suspend fun scheduleReminder(profile: Profile = data.profile) = withContext(Dispatchers.IO) { Reminders.schedule(getApplication(), profile) }
    private suspend fun scheduleSubmission(snapshot: AppData = data) = withContext(Dispatchers.IO) { SubmissionScheduler.schedule(getApplication(), snapshot) }
    private suspend fun scheduleRefresh(snapshot: AppData = data) = withContext(Dispatchers.IO) { ProviderRefresh.schedule(getApplication(), snapshot) }
    private fun refreshIfDue() {
        if ((data.credentials != null || data.gasappConnection != null) &&
            (data.profile.syncTime == null || System.currentTimeMillis() - data.profile.syncTime!! >= 86_400_000L)) refresh(false)
    }
    fun refresh() = refresh(true)
    private fun refresh(force: Boolean) {
        if (busy) return
        if (data.credentials == null && data.gasappConnection == null) { message = "로그인 정보를 저장하지 않았어요. 설정에서 다시 연결해 주세요."; return }
        busy = true; setProgress(0, 1, "공급사 정보를 갱신하는 중")
        viewModelScope.launch {
            try {
                data = withContext(Dispatchers.IO) { ProviderRefresh.refresh(getApplication(), force) }
                selfReadTarget = data.cachedSelfRead
                if (force) message = "공급사 정보를 갱신했어요."
            } catch (e: Exception) {
                val detail = reportError(e, "refresh")
                if (force) message = detail
            }
            finally { busy = false }
        }
    }
    fun cancelLogin() { if (!busy) clearPending() }
    private fun loginSamchully(username: String, password: String, rememberPassword: Boolean) {
        clearPending()
        pendingProvider = Providers.get("samchully")
        pendingCredentials = Credentials(username.trim(), password)
        remember = rememberPassword
        busy = true
        loginError = null
        setProgress(0, 4, "삼천리에 로그인하고 계약을 확인하는 중")
        viewModelScope.launch {
            try {
                pendingSamchully = withContext(Dispatchers.IO) {
                    SubmissionGate.lock.withLock { SamchullyBridge.login(pendingCredentials!!) }
                }
                contracts = pendingSamchully!!.contracts.map { Contract("", it.customerNo, it.label) }
                setProgress(1, 4, "사용 계약을 선택해 주세요")
                if (contracts.size == 1) syncSelected(contracts.single())
            } catch (e: Exception) {
                loginError = reportError(e, "login")
                message = loginError
                clearPending()
            } finally { busy = false }
        }
    }
    private suspend fun reportError(e: Exception, stage: String = "sync"): String {
        val provider = pendingProvider?.id ?: data.profile.providerId
        return readableError(e) + "\n" + withContext(Dispatchers.IO) { Diagnostics.record(getApplication(), provider, stage, e) }
    }
    private fun clearPending() { pendingClient?.close(); pendingClient = null; pendingSamchully = null; pendingProvider = null; pendingCredentials = null; contracts = emptyList() }
    fun restore(raw: String, onResult: (String?) -> Unit = {}) = attempt(onResult) {
        restoreData(withContext(Dispatchers.IO) { DataCodec.decode(raw) })
    }
    fun restore(restored: AppData, onResult: (String?) -> Unit = {}) = attempt(onResult) { restoreData(restored) }
    private suspend fun restoreData(restored: AppData) {
        // Serialize replacement with provider mutations without suspending inside the thread-owned lock.
        val next = restored.copy(ready = true)
        withContext(Dispatchers.IO) { SubmissionGate.lock.withLock { store.write(next) } }
        storageError = false; publish(next)
        scheduleStoredData()
        message = "기록을 복원했어요. 로그인 정보와 알림 설정은 다시 연결해 주세요."
    }
    fun erase() = attempt {
        clearPending()
        withContext(Dispatchers.IO) { SubmissionGate.lock.withLock { store.erase(); Diagnostics.clear(getApplication()) } }
        publish(AppData()); storageError = false
        scheduleStoredData()
        message = "기기에 저장된 데이터를 모두 지웠어요."
    }
    fun loadDemo() = attempt {
        check(!data.ready && data.periods.isEmpty() && data.observations.isEmpty())
        val now = today(); val months = (1L..15L).map { YearMonth.from(now).minusMonths(it) }.sorted()
        var reading = 1800.0
        val periods = months.map { month ->
            val usage = listOf(160.0, 145.0, 110.0, 65.0, 28.0, 15.0, 11.0, 12.0, 18.0, 45.0, 85.0, 135.0)[month.monthValue - 1]
            val before = reading; reading += usage
            UsagePeriod(month.atDay(1).toString(), month.atEndOfMonth().toString(), usage, "demo", before, reading, month.toString().replace("-", ""), usage * 1050 + 990, 1050.0, 990.0)
        }
        val time = System.currentTimeMillis() - 7L * 86_400_000
        val base = AppData(Profile("busan", "demo", customerNumber = "0000000000", plannedDate = now.plusDays(7).toString()), periods, ready = true)
        val past = Estimator.estimate(base, time).reading
        val observations = if (past != null) listOf(Observation(time, past, "demo")) else emptyList()
        save(base.copy(observations = observations))
        message = "가상 데이터로 둘러보는 중이에요. 설정에서 지우고 내 기록으로 시작할 수 있어요."
    }
    override fun onCleared() { clearPending() }
}
fun readableError(e: Exception): String = when (e) {
    is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException -> "인터넷 연결을 확인하고 잠시 후 다시 시도해 주세요."
    is javax.net.ssl.SSLException -> "공급사와 안전하게 연결하지 못했어요. 기기의 날짜와 네트워크를 확인해 주세요."
    is IllegalArgumentException, is IllegalStateException -> e.message?.takeIf { it.any { ch -> ch in '가'..'힣' } } ?: "입력값이나 데이터 형식을 확인해 주세요."
    else -> "데이터를 처리하지 못했어요. 다시 시도하거나 직접 입력을 이용해 주세요."
}
