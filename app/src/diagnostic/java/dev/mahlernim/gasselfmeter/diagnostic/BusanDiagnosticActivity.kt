package dev.mahlernim.gasselfmeter.diagnostic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.mahlernim.gasselfmeter.BusanTraceEvent
import dev.mahlernim.gasselfmeter.BuildConfig
import dev.mahlernim.gasselfmeter.Contract
import dev.mahlernim.gasselfmeter.Credentials
import dev.mahlernim.gasselfmeter.ProviderFailure
import dev.mahlernim.gasselfmeter.Providers
import dev.mahlernim.gasselfmeter.SelfReadTarget
import dev.mahlernim.gasselfmeter.SkensClient
import dev.mahlernim.gasselfmeter.SubmissionReading
import dev.mahlernim.gasselfmeter.today
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** Separate launcher for an intentional, tap-driven, read-only Busan portal inspection. */
class BusanDiagnosticActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var portal: SkensClient? = null
    private lateinit var marker: AttemptMarker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        marker = AttemptMarker(this)
        setContent { MaterialTheme { DiagnosticScreen() } }
    }

    override fun onDestroy() {
        portal?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun newClient(credentials: Credentials): SkensClient {
        portal?.close()
        return SkensClient(Providers.skens("busan"), credentials, observer = { event -> trace(event) }).also { portal = it }
    }

    private fun trace(event: BusanTraceEvent) {
        val outcome = when {
            event.httpCode in setOf(401, 403) -> "authentication"
            event.httpCode != null && event.httpCode !in 200..299 -> "http"
            event.serverResultCode == "N" -> "validation"
            else -> "ok"
        }
        DiagnosticTrace.record(applicationContext, event, outcome)
    }

    private fun sameTarget(expected: SelfReadTarget, current: SelfReadTarget): Boolean =
        expected.contract.bp == current.contract.bp && expected.contract.ca == current.contract.ca &&
            expected.serial == current.serial && expected.cycle == current.cycle &&
            expected.start == current.start && expected.end == current.end &&
            expected.planned == current.planned && expected.installation == current.installation

    private fun validateLiveTarget(target: SelfReadTarget, value: Double) {
        require(target.eligible && !target.submitted) { "공급사 상태가 제출 가능한 미제출 대상이 아닙니다." }
        require(target.previousValue != null && value >= target.previousValue) { "이전 지침보다 작은 검침값은 제출할 수 없습니다." }
        require(today() in java.time.LocalDate.parse(target.start)..java.time.LocalDate.parse(target.end)) { "현재는 제출 기간이 아닙니다." }
        SubmissionReading.wire(value)
    }

    @Suppress("UnusedReceiverParameter")
    @androidx.compose.runtime.Composable
    private fun DiagnosticScreen() {
        var username by androidx.compose.runtime.remember { mutableStateOf("") }
        var password by androidx.compose.runtime.remember { mutableStateOf("") }
        var contracts by androidx.compose.runtime.remember { mutableStateOf<List<Contract>>(emptyList()) }
        var selected by androidx.compose.runtime.remember { mutableStateOf<Contract?>(null) }
        var target by androidx.compose.runtime.remember { mutableStateOf<SelfReadTarget?>(null) }
        var busy by androidx.compose.runtime.remember { mutableStateOf(false) }
        var status by androidx.compose.runtime.remember { mutableStateOf("로그인 후 연결된 계약과 검침 대상을 조회합니다.") }
        var showReport by androidx.compose.runtime.remember { mutableStateOf(false) }
        var readingText by androidx.compose.runtime.remember { mutableStateOf("") }
        var submissionCandidate by androidx.compose.runtime.remember { mutableStateOf<Double?>(null) }

        fun reportText(): String = target?.let {
            listOf(
                "부산도시가스 검침 진단",
                "기간 ${it.start} ~ ${it.end}",
                "제출 상태 ${if (it.submitted) "제출됨" else "미제출"}",
                "제출값 ${it.submittedValue?.display() ?: "없음"}",
                "이전 지침 ${it.previousValue?.display() ?: "확인 불가"}"
            ).joinToString("\n")
        } ?: "부산도시가스 검침 진단\n아직 조회한 대상이 없습니다."

        fun read(contract: Contract) {
            selected = contract
            target = null
            busy = true
            status = "검침 대상을 조회하는 중입니다."
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { portal?.selfReadTarget(contract) ?: error("세션이 종료됐어요. 다시 로그인해 주세요.") } }
                busy = false
                result.onSuccess {
                    target = it
                    status = "조회했습니다. 제출 전에 값과 대상 상태를 확인해 주세요."
                }.onFailure { status = errorText(it, "meter") }
            }
        }

        fun openSubmitConfirmation() {
            val current = target ?: return
            val value = readingText.toDoubleOrNull()
            val error = runCatching { require(value != null); validateLiveTarget(current, value) }.exceptionOrNull()
            if (error != null) status = "정수 검침값, 이전 지침, 제출 기간과 미제출 상태를 확인해 주세요."
            else submissionCandidate = value
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("부산도시가스 검침 진단", style = MaterialTheme.typography.headlineSmall)
            Text("조회는 직접 탭할 때만 실행됩니다. 로그인 정보는 기기에 저장하거나 진단 기록에 남기지 않습니다.")
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("아이디") }, singleLine = true, enabled = !busy)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("비밀번호") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            Button(enabled = !busy && username.isNotBlank() && password.isNotBlank(), onClick = {
                busy = true
                target = null
                selected = null
                contracts = emptyList()
                status = "로그인과 계약 목록을 확인하는 중입니다."
                val credentials = Credentials(username, password)
                password = ""
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        newClient(credentials).login()
                    } }
                    busy = false
                    result.onSuccess {
                        contracts = it
                        status = if (it.size == 1) "연결된 계약을 확인했습니다." else "조회할 계약을 선택해 주세요."
                        if (it.size == 1) read(it.single())
                    }.onFailure {
                        portal?.close()
                        portal = null
                        status = errorText(it, "login")
                    }
                }
            }) { Text(if (busy) "처리 중" else "조회") }

            if (contracts.size > 1) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("계약 선택")
                    contracts.forEach { contract ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected == contract, { read(contract) }, enabled = !busy)
                            Text(contract.label)
                        }
                    }
                }
            }
            target?.let { value -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("검침 대상", style = MaterialTheme.typography.titleMedium)
                    Text("계약 ${value.contract.label}")
                    Text("기간 ${value.start} ~ ${value.end}")
                    Text("제출 상태 ${if (value.submitted) "제출됨" else "미제출"}")
                    Text("제출값 ${value.submittedValue?.display() ?: "없음"}")
                    Text("이전 지침 ${value.previousValue?.display() ?: "확인 불가"}")
                }
            } }
            if (selected != null) Button(enabled = !busy, onClick = { read(selected!!) }) { Text("같은 세션으로 새로고침") }
            if (BuildConfig.BUSAN_DIAGNOSTIC_WRITE_ENABLED && target != null) {
                OutlinedTextField(readingText, { if (it.length <= 8 && it.all { ch -> ch in '0'..'9' }) readingText = it }, Modifier.fillMaxWidth(), label = { Text("제출할 정수 검침값") }, singleLine = true, enabled = !busy && !marker.exists())
                Button(enabled = !busy && !marker.exists() && readingText.isNotBlank(), onClick = ::openSubmitConfirmation) { Text("검침값 제출") }
            }
            Text(status)
            Button(enabled = !busy, onClick = { showReport = true }) { Text("진단내역 복사 또는 공유") }
            if (marker.exists()) Text("이 설치에서 제출 시도 기록이 있어 다시 전송할 수 없습니다. 공식 결과를 확인해 주세요.")
        }

        if (showReport) AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text("진단내역") }, text = { Text(reportText() + "\n\n" + DiagnosticTrace.report(applicationContext)) },
            confirmButton = { TextButton(onClick = {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("부산도시가스 검침 진단", reportText() + "\n\n" + DiagnosticTrace.report(applicationContext)))
                showReport = false
            }) { Text("복사") } },
            dismissButton = { TextButton(onClick = {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, reportText() + "\n\n" + DiagnosticTrace.report(applicationContext)) }, "진단내역 공유"))
                showReport = false
            }) { Text("공유") } }
        )

        submissionCandidate?.let { value -> AlertDialog(
            onDismissRequest = { submissionCandidate = null },
            title = { Text("검침값 제출 확인") },
            text = { Text(target?.let { "계약 ${it.contract.label}\n기간 ${it.start} ~ ${it.end}\n제출값 ${SubmissionReading.wire(value)}\n이 동작은 한 번만 전송됩니다." } ?: "검침 대상을 다시 조회해 주세요.") },
            confirmButton = { TextButton(onClick = {
                val expected = target
                submissionCandidate = null
                if (expected == null || busy || marker.exists()) return@TextButton
                busy = true
                status = "공급사 상태를 다시 확인하는 중입니다."
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        val client = portal ?: error("세션이 종료됐어요. 다시 로그인해 주세요.")
                        val live = client.selfReadTarget(expected.contract)
                        require(sameTarget(expected, live)) { "조회한 대상과 현재 공급사 대상이 달라졌습니다." }
                        validateLiveTarget(live, value)
                        val wire = SubmissionReading.wire(value)
                        check(DiagnosticTrace.recordSubmitStart(applicationContext, wire.toInt())) { "제출 전 진단 기록을 저장하지 못했습니다." }
                        check(marker.markBeforeDispatch(wire.toInt())) { "제출 시도 기록을 저장하지 못했습니다." }
                        live to client.submitReading(live, value)
                    } }
                    busy = false
                    result.onSuccess { (live, outcome) ->
                        when {
                            outcome.confirmed -> {
                                target = live.copy(submitted = true, submittedValue = value)
                                status = "등록 확인 완료. 정수 검침값 ${SubmissionReading.wire(value)}이 등록되었습니다."
                            }
                            outcome.accepted -> status = "공급사 성공 응답으로 ${SubmissionReading.wire(value)} m³ 제출을 완료했습니다. 재조회 화면에는 아직 반영되지 않았습니다."
                            else -> status = "공급사가 등록을 확인하지 않았습니다. 다시 전송하지 않고 공식 결과를 확인해 주세요."
                        }
                    }.onFailure { status = errorText(it, "submit") + " 다시 전송하지 않고 공식 결과를 확인해 주세요." }
                }
            }) { Text("한 번 전송") } },
            dismissButton = { TextButton(onClick = { submissionCandidate = null }) { Text("취소") } }
        ) }
    }

    private fun Double.display(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

    private fun errorText(error: Throwable, fallbackStage: String): String {
        val failure = error as? ProviderFailure
        val category = failure?.category ?: when (error) {
            is SocketTimeoutException -> "timeout"
            is SSLException -> "tls"
            is IOException -> "network"
            is IllegalArgumentException, is IllegalStateException -> "validation"
            else -> "unknown"
        }
        DiagnosticTrace.recordFailure(applicationContext, failure?.stage ?: fallbackStage, category, failure?.httpCode)
        return when (category) {
            "authentication" -> "로그인 정보를 다시 확인해 주세요."
            "network" -> "네트워크 연결을 확인한 뒤 다시 조회해 주세요."
            "timeout" -> "응답 시간이 초과됐어요. 잠시 후 다시 조회해 주세요."
            "tls" -> "보안 연결을 확인하지 못했어요."
            else -> "조회 결과를 확인하지 못했어요. 진단내역에서 오류 분류를 확인할 수 있습니다."
        }
    }
}
