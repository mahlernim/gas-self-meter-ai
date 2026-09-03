package dev.mahlernim.gasselfmeter

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class GasViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecureStore(app)
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
    private var pendingProvider: Provider? = null
    private var pendingCredentials: Credentials? = null
    private var remember = true
    init {
        try { data = store.read() } catch (_: Exception) { storageError = true; message = "저장 데이터를 열지 못했어요. 원본은 보존되어 있어요. 백업을 가져오거나 데이터를 초기화할 수 있어요." }
    }
    private fun save(next: AppData) {
        check(!storageError) { "저장 파일을 먼저 복구하거나 초기화해 주세요." }
        store.write(next)
        data = next
    }
    fun attempt(action: () -> Unit) { try { action() } catch (e: Exception) { message = readableError(e) } }
    fun manual(provider: String) = attempt {
        save(data.copy(profile = data.profile.copy(providerId = provider), ready = true))
    }
    fun calibrate(reading: String): Boolean {
        return try {
            check(!busy) { "조회가 끝난 뒤 다시 시도해 주세요." }
            save(Estimator.addObservation(data, number(reading)))
            message = "실제 확인값을 저장했어요. 화면과 다음 추정에 반영했어요."
            true
        } catch (e: Exception) {
            message = readableError(e)
            false
        }
    }
    fun addPeriod(start: String, end: String, usage: String) = attempt {
        check(!busy) { "조회가 끝난 뒤 다시 시도해 주세요." }
        val period = UsagePeriod(LocalDate.parse(start).toString(), LocalDate.parse(end).toString(), number(usage))
        val next = data.periods + period
        Estimator.validatePeriods(next)
        save(data.copy(periods = next.sortedBy { it.start }))
        message = "사용 이력을 저장했어요."
    }
    fun deletePeriod(period: UsagePeriod) = attempt { check(!busy); save(data.copy(periods = data.periods - period)) }
    fun deleteObservation(observation: Observation) = attempt { check(!busy); save(data.copy(observations = data.observations - observation)) }
    fun resetMeter() = attempt {
        check(!busy) { "조회가 끝난 뒤 다시 시도해 주세요." }
        save(data.copy(profile = data.profile.copy(meter = "manual-${UUID.randomUUID()}", plannedDate = null)))
        message = "새 계량기로 시작해요. 현재 숫자를 확인해 주세요."
    }
    fun changeProvider() = attempt {
        save(data.copy(ready = false, credentials = null, submissionSettings = SubmissionSettings()))
        SubmissionScheduler.schedule(getApplication(), data)
    }
    fun forgetCredentials() = attempt {
        save(data.copy(credentials = null, submissionSettings = data.submissionSettings.copy(automatic = false)))
        SubmissionScheduler.schedule(getApplication(), data)
        message = "저장한 로그인 정보를 지웠고 자동 입력을 껐어요."
    }
    fun setReminder(enabled: Boolean, day: Int, hour: Int) = attempt {
        val profile = data.profile.copy(reminder = enabled, reminderDay = day, reminderHour = hour)
        save(data.copy(profile = profile))
        Reminders.schedule(getApplication(), profile)
    }
    fun setSubmissionSettings(enabled: Boolean, automatic: Boolean, requireRecent: Boolean, recentDays: Int) = attempt {
        require(recentDays in 1..30)
        val settings = SubmissionSettings(enabled, enabled && automatic && Providers.get(data.profile.providerId).automaticSubmission, requireRecent, recentDays)
        save(data.copy(submissionSettings = settings))
        SubmissionScheduler.schedule(getApplication(), data)
    }
    fun login(providerId: String, username: String, password: String, rememberPassword: Boolean) {
        if (busy) return
        if (username.isBlank() || password.isBlank()) { message = "아이디와 비밀번호를 입력해 주세요."; return }
        busy = true; loginError = null; setProgress(0, 5, "1단계 · 공급사에 안전하게 로그인하는 중"); remember = rememberPassword
        viewModelScope.launch {
            try {
                pendingClient?.close()
                pendingProvider = Providers.skens(providerId)
                pendingCredentials = Credentials(username.trim(), password)
                pendingClient = SkensClient(pendingProvider!!, pendingCredentials!!)
                val list = withContext(Dispatchers.IO) { pendingClient!!.login() }
                contracts = list
                setProgress(1, 5, "2단계 · 연결된 계약을 확인하는 중")
                if (list.size == 1) syncSelected(list.first())
            } catch (e: Exception) { loginError = readableError(e); message = loginError; clearPending() }
            finally { busy = false }
        }
    }
    fun selectContract(contract: Contract) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { syncSelected(contract) } catch (e: Exception) { loginError = readableError(e); message = loginError; clearPending() }
            finally { busy = false }
        }
    }
    private suspend fun syncSelected(contract: Contract) {
        setProgress(2, 5, "3단계 · 계량기와 검침 기간을 확인하는 중")
        val knownMonths = data.periods.mapNotNull { it.billMonth.takeIf(String::isNotBlank) }.toSet()
        val result = withContext(Dispatchers.IO) { pendingClient!!.history(contract, knownMonths) { state ->
            viewModelScope.launch { setProgress(state.completed, state.total, "4단계 · ${state.text}") }
        } }
        setProgress(5, 5, "5단계 · 가져온 기록을 안전하게 저장하는 중")
        val provider = pendingProvider ?: error("연결할 공급사를 다시 선택해 주세요.")
        val contractKey = SkensClient.contractKey(provider, contract)
        check(data.profile.contract.isBlank() || data.profile.contract == contractKey) { "다른 계약이에요. 현재 기록을 내보낸 후 데이터를 초기화해 주세요." }
        val newMonths = result.periods.map { it.billMonth }.toSet()
        val keep = data.periods.filter { old -> old.billMonth !in newMonths && result.periods.none { it.first <= old.last && old.first <= it.last } }
        val periods = (keep + result.periods).sortedBy { it.start }
        Estimator.validatePeriods(periods)
        save(data.copy(profile = data.profile.copy(providerId = provider.id, meter = result.meter, contract = contractKey, plannedDate = result.planned, syncTime = System.currentTimeMillis()),
            periods = periods, credentials = if (remember) pendingCredentials else null, ready = true))
        selfReadTarget = result.selfRead
        SubmissionScheduler.schedule(getApplication(), data)
        val changed = data.observations.isNotEmpty() && data.observations.last().meter != result.meter
        message = result.warning ?: if (changed) "계량기 정보가 달라졌어요. 현재 숫자를 다시 확인해 주세요." else "${newMonths.size}개월의 청구 이력을 가져왔어요."
        clearPending()
    }
    fun checkSubmissionStatus() {
        if (busy) return
        val credentials = data.credentials ?: run { message = "자동 입력을 사용하려면 설정에서 로그인 정보를 암호화해 저장해 주세요."; return }
        busy = true; setProgress(0, 3, "검침 기간을 확인하려고 로그인하는 중")
        viewModelScope.launch {
            try {
                selfReadTarget = withContext(Dispatchers.IO) {
                    val provider = Providers.skens(data.profile.providerId)
                    SkensClient(provider, credentials).use { client ->
                        val contract = client.login().find { SkensClient.contractKey(provider, it) == data.profile.contract }
                            ?: error("저장된 계약을 찾지 못했어요. 공급사를 다시 연결해 주세요.")
                        viewModelScope.launch { setProgress(1, 3, "계약과 계량기를 확인하는 중") }
                        client.selfReadTarget(contract)
                    }
                }
                setProgress(3, 3, "검침 기간 확인을 마쳤어요")
            } catch (e: Exception) { message = readableError(e) }
            finally { busy = false }
        }
    }
    fun submitReading(value: Double) {
        if (busy) return
        val credentials = data.credentials ?: run { message = "검침값을 입력하려면 로그인 정보를 이 기기에 저장해 주세요."; return }
        busy = true; setProgress(0, 4, "제출 직전 공급사 상태를 다시 확인하는 중")
        viewModelScope.launch {
            var record: SubmissionRecord? = null
            try {
                withContext(Dispatchers.IO) {
                    SubmissionGate.lock.lock()
                    try {
                        val provider = Providers.skens(data.profile.providerId)
                        SkensClient(provider, credentials).use { client ->
                            val contract = client.login().find { SkensClient.contractKey(provider, it) == data.profile.contract }
                                ?: error("저장된 계약을 찾지 못했어요. 공급사를 다시 연결해 주세요.")
                            val target = client.selfReadTarget(contract)
                            viewModelScope.launch { selfReadTarget = target; setProgress(1, 4, "입력 기간과 기존 제출 여부를 확인했어요") }
                            val decision = SubmissionPolicy.decide(data, target, System.currentTimeMillis(), automatic = false)
                            require(decision.allowed && decision.value != null) { decision.reason }
                            require(kotlin.math.abs(decision.value - value) < .001) { "확인 후 제출값이 달라졌어요. 화면에서 다시 확인해 주세요." }
                            record = SubmissionRecord(target.cycle, target.start, target.end, value, System.currentTimeMillis(), "pending", "공급사 확인 대기")
                            withContext(Dispatchers.Main) { save(data.copy(submissions = (data.submissions.filterNot { it.cycle == target.cycle } + record!!).takeLast(100))) }
                            viewModelScope.launch { setProgress(2, 4, "검침값을 한 번만 전송하는 중") }
                            val outcome = client.submitReading(target, value)
                            val status = when { !outcome.accepted -> "rejected"; outcome.confirmed -> "confirmed"; else -> "uncertain" }
                            val detail = when (status) {
                                "confirmed" -> "공급사에서 입력 완료를 확인했어요."
                                "rejected" -> "공급사가 입력을 받지 않았어요."
                                else -> "응답은 성공이지만 재조회 확인이 필요해요. 자동 재전송하지 않습니다."
                            }
                            withContext(Dispatchers.Main) { replaceSubmission(record!!.copy(status = status, detail = detail)) }
                        }
                    } finally { SubmissionGate.lock.unlock() }
                }
                setProgress(4, 4, "검침값 입력 결과를 확인했어요")
                message = data.submissions.lastOrNull()?.detail ?: "검침값 입력을 마쳤어요."
            } catch (e: Exception) {
                record?.let { replaceSubmission(it.copy(status = "uncertain", detail = "전송 결과를 확정하지 못했어요. 자동 재전송하지 않습니다.")) }
                message = record?.let { "전송 결과를 확정하지 못했어요. 공급사 홈페이지에서 확인해 주세요." } ?: readableError(e)
            } finally { busy = false }
        }
    }
    private fun replaceSubmission(record: SubmissionRecord) {
        save(data.copy(submissions = (data.submissions.filterNot { it.cycle == record.cycle } + record).takeLast(100)))
    }
    private fun setProgress(current: Int, total: Int, text: String) {
        progressCurrent = current; progressTotal = total; progress = text
    }
    fun refresh() {
        data.credentials?.let { login(data.profile.providerId, it.username, it.password, true) } ?: run { message = "로그인 정보를 저장하지 않았어요. 설정에서 다시 연결해 주세요." }
    }
    fun cancelLogin() { if (!busy) clearPending() }
    private fun clearPending() { pendingClient?.close(); pendingClient = null; pendingProvider = null; pendingCredentials = null; contracts = emptyList() }
    fun restore(raw: String) = attempt {
        check(!busy) { "조회가 끝난 뒤 다시 시도해 주세요." }
        val restored = DataCodec.decode(raw)
        store.write(restored.copy(ready = true))
        storageError = false; data = restored.copy(ready = true)
        Reminders.schedule(getApplication(), data.profile)
        SubmissionScheduler.schedule(getApplication(), data)
        message = "기록을 복원했어요. 로그인 정보와 알림 설정은 다시 연결해 주세요."
    }
    fun erase() = attempt {
        check(!busy) { "조회가 끝난 뒤 다시 시도해 주세요." }
        clearPending(); Reminders.schedule(getApplication(), Profile()); SubmissionScheduler.schedule(getApplication(), AppData()); store.erase(); data = AppData(); storageError = false
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
        val base = AppData(Profile("busan", "demo"), periods, ready = true)
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
